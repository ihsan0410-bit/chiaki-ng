// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL
//
// Türkçe altyazı katmanı: Remote Play görüntüsünün alt kısmındaki İngilizce
// altyazıyı cihaz üzerinde (internetsiz) okur, Türkçeye çevirir ve orijinal
// altyazının üstüne yazar. Görüntü kaydedilmez, hiçbir yere gönderilmez.
//
// Görüntü iki yoldan okunabilir:
//  - SurfaceView + PixelCopy (varsayılan)
//  - ImageReader (bazı TV'lerde PixelCopy çalışmazsa yedek yol; Y düzlemi = gri ton)

package com.metallic.chiaki.stream

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.util.LruCache
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.View
import android.widget.TextView
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlin.math.max
import kotlin.math.min

class SubtitleTranslator(
	private val surfaceView: SurfaceView?,
	private val subtitleView: TextView,
	private val statusView: TextView,
	private val debugView: TextView? = null
)
{
	companion object
	{
		private const val TAG = "SubtitleTranslator"

		/** Ekranın taranacak bölgesi (oran olarak). Altyazılar genelde alt ortada çıkar. */
		private const val REGION_LEFT = 0.10f
		private const val REGION_RIGHT = 0.90f
		private const val REGION_TOP = 0.70f
		private const val REGION_BOTTOM = 0.97f

		/** OCR için kullanılacak en fazla genişlik (piksel). */
		private const val MAX_CAPTURE_WIDTH = 1280

		private const val INTERVAL_MS = 300L
		/** Bu kadar tarama üst üste boş gelirse çeviri gizlenir (~1.2 sn). */
		private const val EMPTY_FRAMES_TO_HIDE = 4
		/** İki metin bu orandan fazla benziyorsa aynı altyazı kabul edilir. */
		private const val SAME_TEXT_SIMILARITY = 0.80f
	}

	private val mainHandler = Handler(Looper.getMainLooper())
	private var workerThread: HandlerThread? = null
	private var workerHandler: Handler? = null

	private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
	private val translator: Translator = Translation.getClient(
		TranslatorOptions.Builder()
			.setSourceLanguage(TranslateLanguage.ENGLISH)
			.setTargetLanguage(TranslateLanguage.TURKISH)
			.build())

	private val cache = LruCache<String, String>(300)

	@Volatile private var running = false
	@Volatile private var busy = false
	@Volatile private var modelReady = false
	@Volatile private var modelError: String? = null

	/** Ayarlanırsa görüntü PixelCopy yerine buradan okunur. */
	@Volatile var imageReader: ImageReader? = null
		private set
	private val imageLock = Object()
	private var latestImage: android.media.Image? = null
	private var drainThread: HandlerThread? = null
	@Volatile private var modelStartedAt = 0L

	/**
	 * Görüntüyü ImageReader'dan oku. Kod çözücü hiç beklemesin diye kareler ayrı
	 * bir iş parçacığında sürekli alınır; sadece en yenisi tutulur.
	 */
	fun attachImageReader(reader: ImageReader)
	{
		val t = HandlerThread("frame-drain").also { it.start() }
		drainThread = t
		reader.setOnImageAvailableListener({ r ->
			val img = try { r.acquireLatestImage() } catch(e: Exception) { null }
			if(img != null)
				synchronized(imageLock) {
					latestImage?.close()
					latestImage = img
				}
		}, Handler(t.looper))
		imageReader = reader
	}

	private fun takeLatestImage(): android.media.Image? = synchronized(imageLock) {
		val img = latestImage
		latestImage = null
		img
	}

	private var bitmap: Bitmap? = null
	private var lastSource = ""
	private var emptyFrames = 0
	private var translationGeneration = 0

	// ---- Tanılama sayaçları ----
	@Volatile var framesOk = 0
	@Volatile var framesFailed = 0
	@Volatile var framesBlack = 0
	@Volatile var framesNone = 0
	@Volatile private var lastFailCode = 0
	@Volatile private var lastOcr = ""
	@Volatile private var ocrRuns = 0
	@Volatile private var translations = 0
	private var lastDebugUpdate = 0L

	var enabled = true
		set(value)
		{
			field = value
			if(!value)
				mainHandler.post { subtitleView.visibility = View.GONE }
		}

	fun resetStats()
	{
		framesOk = 0; framesFailed = 0; framesBlack = 0; framesNone = 0
	}

	/** PixelCopy yolu bu cihazda çalışmıyor gibi mi? (hep hata ya da hep siyah) */
	fun captureLooksBroken(): Boolean
	{
		val total = framesOk + framesFailed
		if(total < 12)
			return framesNone >= 20
		return framesFailed > total * 0.8f || framesBlack >= framesOk
	}

	fun start()
	{
		if(running)
			return
		running = true
		busy = false
		lastSource = ""
		emptyFrames = 0
		if(Build.VERSION.SDK_INT < Build.VERSION_CODES.O)
		{
			showStatus("Türkçe altyazı bu cihazda desteklenmiyor (Android 8+ gerekli).", 6000)
			return
		}
		workerThread = HandlerThread("subtitle-capture").also { it.start() }
		workerHandler = Handler(workerThread!!.looper)

		if(!modelReady)
			downloadModel()

		workerHandler?.post(captureLoop)
	}

	private fun downloadModel()
	{
		modelStartedAt = SystemClock.elapsedRealtime()
		showStatus("Türkçe çeviri modeli indiriliyor… (ilk seferde ~30 MB, bir kere)", 0)
		translator.downloadModelIfNeeded(DownloadConditions.Builder().build())
			.addOnSuccessListener {
				modelReady = true
				modelError = null
				showStatus("Türkçe altyazı hazır ✓", 2500)
			}
			.addOnFailureListener {
				Log.e(TAG, "Model indirilemedi", it)
				modelError = it.message ?: it.javaClass.simpleName
				showStatus("Çeviri modeli indirilemedi: $modelError — 20 sn sonra tekrar denenecek", 10000)
				mainHandler.postDelayed({ if(running && !modelReady) downloadModel() }, 20000)
			}
	}

	fun stop()
	{
		running = false
		workerHandler?.removeCallbacksAndMessages(null)
		workerThread?.quitSafely()
		workerThread = null
		workerHandler = null
		mainHandler.post { subtitleView.visibility = View.GONE }
	}

	fun close()
	{
		stop()
		imageReader?.setOnImageAvailableListener(null, null)
		drainThread?.quitSafely()
		drainThread = null
		synchronized(imageLock) { latestImage?.close(); latestImage = null }
		recognizer.close()
		translator.close()
	}

	private val captureLoop: Runnable = object: Runnable
	{
		override fun run()
		{
			if(!running)
				return
			// Model hazır olmasa da kareleri okuyup OCR'ı çalıştır (tanılama için);
			// çeviri model hazır olunca yapılır.
			if(enabled && !busy)
				capture()
			updateDebug()
			workerHandler?.postDelayed(this, INTERVAL_MS)
		}
	}

	private fun capture()
	{
		val reader = imageReader
		if(reader != null)
		{
			captureFromReader(reader)
			return
		}
		if(Build.VERSION.SDK_INT < Build.VERSION_CODES.O)
			return
		val sv = surfaceView ?: return
		val holder = sv.holder
		val surface = holder.surface
		if(surface == null || !surface.isValid)
		{
			framesNone++
			return
		}
		val frame = holder.surfaceFrame
		val w = frame.width()
		val h = frame.height()
		if(w <= 0 || h <= 0)
		{
			framesNone++
			return
		}

		val src = Rect(
			(w * REGION_LEFT).toInt(),
			(h * REGION_TOP).toInt(),
			(w * REGION_RIGHT).toInt(),
			(h * REGION_BOTTOM).toInt())
		val scale = min(1f, MAX_CAPTURE_WIDTH.toFloat() / src.width())
		val bw = max(1, (src.width() * scale).toInt())
		val bh = max(1, (src.height() * scale).toInt())

		val bmp = bitmap.let {
			if(it == null || it.width != bw || it.height != bh)
			{
				it?.recycle()
				Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888).also { b -> bitmap = b }
			}
			else
				it
		}

		busy = true
		try
		{
			PixelCopy.request(sv, src, bmp, { result ->
				if(result == PixelCopy.SUCCESS)
				{
					framesOk++
					if(isBlack(bmp))
						framesBlack++
					recognize(bmp.copy(Bitmap.Config.ARGB_8888, false))
				}
				else
				{
					framesFailed++
					lastFailCode = result
					busy = false
				}
			}, workerHandler!!)
		}
		catch(e: Exception)
		{
			Log.w(TAG, "PixelCopy başarısız", e)
			framesFailed++
			lastFailCode = -1
			busy = false
		}
	}

	/** ImageReader'dan (YUV) son kareyi al, altyazı bölgesini gri tonlu bitmap'e çevir. */
	private fun captureFromReader(reader: ImageReader)
	{
		val image = takeLatestImage()
		if(image == null)
		{
			framesNone++
			return
		}
		busy = true
		val bmp: Bitmap
		try
		{
			val w = image.width
			val h = image.height
			val plane = image.planes[0]
			val buf = plane.buffer
			val rowStride = plane.rowStride
			val pixelStride = plane.pixelStride
			val x0 = (w * REGION_LEFT).toInt()
			val y0 = (h * REGION_TOP).toInt()
			val cw = (w * (REGION_RIGHT - REGION_LEFT)).toInt()
			val ch = (h * (REGION_BOTTOM - REGION_TOP)).toInt()
			val pixels = IntArray(cw * ch)
			var maxY = 0
			for(y in 0 until ch)
			{
				val rowBase = (y0 + y) * rowStride
				for(x in 0 until cw)
				{
					val v = buf.get(rowBase + (x0 + x) * pixelStride).toInt() and 0xFF
					if(v > maxY) maxY = v
					pixels[y * cw + x] = Color.rgb(v, v, v)
				}
			}
			bmp = Bitmap.createBitmap(pixels, cw, ch, Bitmap.Config.ARGB_8888)
			framesOk++
			if(maxY < 24)
				framesBlack++
		}
		catch(e: Exception)
		{
			Log.w(TAG, "ImageReader karesi okunamadı", e)
			framesFailed++
			lastFailCode = -2
			busy = false
			return
		}
		finally
		{
			image.close()
		}
		recognize(bmp)
	}

	private fun isBlack(bmp: Bitmap): Boolean
	{
		var maxL = 0
		val stepX = max(1, bmp.width / 24)
		val stepY = max(1, bmp.height / 8)
		var y = 0
		while(y < bmp.height)
		{
			var x = 0
			while(x < bmp.width)
			{
				val c = bmp.getPixel(x, y)
				val l = (Color.red(c) + Color.green(c) + Color.blue(c)) / 3
				if(l > maxL) maxL = l
				x += stepX
			}
			y += stepY
		}
		return maxL < 24
	}

	private fun recognize(bmp: Bitmap)
	{
		val image = try { InputImage.fromBitmap(bmp, 0) } catch(e: Exception) { busy = false; return }
		ocrRuns++
		recognizer.process(image)
			.addOnSuccessListener {
				val text = extractSubtitle(it)
				lastOcr = text
				onTextRecognized(text)
			}
			.addOnFailureListener {
				Log.w(TAG, "OCR hatası", it)
				lastOcr = "OCR hatası: ${it.message}"
				busy = false
			}
	}

	/** OCR sonucundan altyazı satırlarını seçer, HUD sayıları gibi gürültüyü atar. */
	private fun extractSubtitle(text: Text): String
	{
		val lines = text.textBlocks
			.flatMap { it.lines }
			.filter { line ->
				val letters = line.text.count { it.isLetter() }
				letters >= 3 && letters >= line.text.length * 0.5f
			}
			.sortedBy { it.boundingBox?.top ?: 0 }
		return lines.joinToString(" ") { it.text.trim() }
			.replace(Regex("\\s+"), " ")
			.trim()
	}

	private fun onTextRecognized(source: String)
	{
		if(source.length < 4)
		{
			busy = false
			emptyFrames++
			if(emptyFrames >= EMPTY_FRAMES_TO_HIDE)
			{
				lastSource = ""
				mainHandler.post { subtitleView.visibility = View.GONE }
			}
			return
		}
		emptyFrames = 0

		if(!modelReady)
		{
			busy = false
			return
		}

		if(similarity(normalize(source), normalize(lastSource)) >= SAME_TEXT_SIMILARITY)
		{
			busy = false
			return
		}
		lastSource = source

		val key = normalize(source)
		cache.get(key)?.let {
			show(it)
			busy = false
			return
		}

		val generation = ++translationGeneration
		translator.translate(source)
			.addOnSuccessListener { translated ->
				translations++
				cache.put(key, translated)
				if(generation == translationGeneration)
					show(translated)
				busy = false
			}
			.addOnFailureListener {
				Log.w(TAG, "Çeviri hatası", it)
				busy = false
			}
	}

	private fun show(text: String)
	{
		mainHandler.post {
			if(!enabled)
				return@post
			subtitleView.text = text
			subtitleView.visibility = View.VISIBLE
		}
	}

	private fun updateDebug()
	{
		val dv = debugView ?: return
		val now = SystemClock.elapsedRealtime()
		if(now - lastDebugUpdate < 1000)
			return
		lastDebugUpdate = now
		val mode = if(imageReader != null) "ImageReader" else "PixelCopy"
		val model = when {
			modelReady -> "hazır"
			modelError != null -> "HATA(${modelError?.take(40)})"
			else -> "iniyor ${(now - modelStartedAt) / 1000}sn"
		}
		val text = "[$mode] kare ok:$framesOk hata:$framesFailed(kod $lastFailCode) siyah:$framesBlack yok:$framesNone | " +
			"OCR:$ocrRuns çeviri:$translations model:$model\nokunan: ${lastOcr.take(80)}"
		mainHandler.post { dv.text = text }
	}

	private val hideStatusRunnable = Runnable { statusView.visibility = View.GONE }

	private fun showStatus(message: String, hideAfterMs: Long)
	{
		mainHandler.post {
			mainHandler.removeCallbacks(hideStatusRunnable)
			statusView.text = message
			statusView.visibility = View.VISIBLE
			if(hideAfterMs > 0)
				mainHandler.postDelayed(hideStatusRunnable, hideAfterMs)
		}
	}

	private fun normalize(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

	/** 0..1 arası benzerlik (Levenshtein). */
	private fun similarity(a: String, b: String): Float
	{
		if(a.isEmpty() && b.isEmpty())
			return 1f
		if(a.isEmpty() || b.isEmpty())
			return 0f
		val x = a.take(200)
		val y = b.take(200)
		var prev = IntArray(y.length + 1) { it }
		var cur = IntArray(y.length + 1)
		for(i in 1..x.length)
		{
			cur[0] = i
			for(j in 1..y.length)
			{
				val cost = if(x[i - 1] == y[j - 1]) 0 else 1
				cur[j] = min(min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost)
			}
			val t = prev; prev = cur; cur = t
		}
		return 1f - prev[y.length].toFloat() / max(x.length, y.length)
	}
}
