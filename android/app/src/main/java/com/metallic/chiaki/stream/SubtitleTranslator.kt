// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL
//
// Türkçe altyazı katmanı: Remote Play görüntüsünün alt kısmındaki İngilizce
// altyazıyı cihaz üzerinde (internetsiz) okur, Türkçeye çevirir ve orijinal
// altyazının üstüne yazar. Görüntü kaydedilmez, hiçbir yere gönderilmez.

package com.metallic.chiaki.stream

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
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
	private val surfaceView: SurfaceView,
	private val subtitleView: TextView,
	private val statusView: TextView
)
{
	companion object
	{
		private const val TAG = "SubtitleTranslator"

		/** Ekranın taranacak bölgesi (oran olarak). GTA altyazıları alt ortada çıkar. */
		private const val REGION_LEFT = 0.12f
		private const val REGION_RIGHT = 0.88f
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

	private var bitmap: Bitmap? = null
	private var lastSource = ""
	private var emptyFrames = 0
	private var translationGeneration = 0

	var enabled = true
		set(value)
		{
			field = value
			if(!value)
				mainHandler.post { subtitleView.visibility = View.GONE }
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

		showStatus("Türkçe çeviri modeli hazırlanıyor… (ilk seferde ~30 MB indirilir)", 0)
		translator.downloadModelIfNeeded(DownloadConditions.Builder().build())
			.addOnSuccessListener {
				modelReady = true
				showStatus("Türkçe altyazı hazır ✓", 2500)
			}
			.addOnFailureListener {
				Log.e(TAG, "Model indirilemedi", it)
				showStatus("Çeviri modeli indirilemedi. İnterneti kontrol edip yeniden bağlanın.", 8000)
			}

		workerHandler?.post(captureLoop)
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
		recognizer.close()
		translator.close()
	}

	private val captureLoop: Runnable = object: Runnable
	{
		override fun run()
		{
			if(!running)
				return
			if(enabled && modelReady && !busy)
				capture()
			workerHandler?.postDelayed(this, INTERVAL_MS)
		}
	}

	private fun capture()
	{
		if(Build.VERSION.SDK_INT < Build.VERSION_CODES.O)
			return
		val holder = surfaceView.holder
		val surface = holder.surface
		if(surface == null || !surface.isValid)
			return
		val frame = holder.surfaceFrame
		val w = frame.width()
		val h = frame.height()
		if(w <= 0 || h <= 0)
			return

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
			PixelCopy.request(surfaceView, src, bmp, { result ->
				if(result == PixelCopy.SUCCESS)
					recognize(bmp)
				else
					busy = false
			}, workerHandler!!)
		}
		catch(e: Exception)
		{
			Log.w(TAG, "PixelCopy başarısız", e)
			busy = false
		}
	}

	private fun recognize(bmp: Bitmap)
	{
		val image = try
		{
			// Kopya al: OCR çalışırken bir sonraki kare aynı bitmap'e yazılmasın.
			InputImage.fromBitmap(bmp.copy(Bitmap.Config.ARGB_8888, false), 0)
		}
		catch(e: Exception)
		{
			busy = false
			return
		}
		recognizer.process(image)
			.addOnSuccessListener { onTextRecognized(extractSubtitle(it)) }
			.addOnFailureListener {
				Log.w(TAG, "OCR hatası", it)
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
				letters >= 3 && letters >= line.text.length * 0.6f
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
