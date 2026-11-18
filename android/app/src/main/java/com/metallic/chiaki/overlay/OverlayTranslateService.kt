// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL
//
// HDMI üstü çeviri modu (4K HDR korunur):
// Oyun PS5'in HDMI girişinden normal oynanır. Bu servis arka planda PS5'e
// Remote Play ile bağlanır, görüntüyü SADECE altyazıyı okumak için kullanır
// (ekranda göstermez, sesini kapatır) ve Türkçe altyazıyı her şeyin üstünde
// duran şeffaf bir pencerede gösterir.

package com.metallic.chiaki.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.ImageFormat
import android.graphics.PixelFormat
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.content.IntentCompat
import androidx.lifecycle.Observer
import com.metallic.chiaki.R
import com.metallic.chiaki.common.LogManager
import com.metallic.chiaki.common.Preferences
import com.metallic.chiaki.lib.ChiakiAudio
import com.metallic.chiaki.lib.ConnectInfo
import com.metallic.chiaki.session.*
import com.metallic.chiaki.stream.SubtitleTranslator

class OverlayTranslateService : Service()
{
	companion object
	{
		private const val TAG = "OverlayTranslate"
		const val EXTRA_CONNECT_INFO = "connect_info"
		const val ACTION_STOP = "com.metallic.chiaki.overlay.STOP"
		private const val CHANNEL_ID = "overlay_translate"
		private const val NOTIFICATION_ID = 4242

		@Volatile var isRunning = false
			private set

		fun canDrawOverlays(context: Context) =
			Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(context)

		fun start(context: Context, connectInfo: ConnectInfo)
		{
			val intent = Intent(context, OverlayTranslateService::class.java)
				.putExtra(EXTRA_CONNECT_INFO, connectInfo)
			if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
				context.startForegroundService(intent)
			else
				context.startService(intent)
		}

		fun stop(context: Context)
		{
			context.startService(Intent(context, OverlayTranslateService::class.java).setAction(ACTION_STOP))
		}
	}

	private val mainHandler = Handler(Looper.getMainLooper())
	private var windowManager: WindowManager? = null
	private var overlayRoot: View? = null
	private var session: StreamSession? = null
	private var translator: SubtitleTranslator? = null
	private var statusView: TextView? = null
	private var imageReader: ImageReader? = null
	private var videoW = 1280
	private var videoH = 720
	private var stateObserver: Observer<StreamState>? = null

	override fun onBind(intent: Intent?): IBinder? = null

	override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int
	{
		startAsForeground()

		if(intent?.action == ACTION_STOP)
		{
			stopSelf()
			return START_NOT_STICKY
		}

		val connectInfo = intent?.let { IntentCompat.getParcelableExtra(it, EXTRA_CONNECT_INFO, ConnectInfo::class.java) }
		if(connectInfo == null)
		{
			stopSelf()
			return START_NOT_STICKY
		}

		if(session != null)
			teardown()

		try
		{
			setup(connectInfo)
			isRunning = true
		}
		catch(e: Exception)
		{
			Log.e(TAG, "Kurulum başarısız", e)
			teardown()
			stopSelf()
		}
		return START_NOT_STICKY
	}

	private fun startAsForeground()
	{
		val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
		if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
			nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Türkçe altyazı", NotificationManager.IMPORTANCE_LOW))
		val builder = if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
			Notification.Builder(this, CHANNEL_ID)
		else
			@Suppress("DEPRECATION") Notification.Builder(this)
		val notification = builder
			.setContentTitle("Türkçe altyazı çalışıyor")
			.setContentText("Durdurmak için uygulamayı açın")
			.setSmallIcon(R.mipmap.ic_launcher)
			.setOngoing(true)
			.build()
		if(Build.VERSION.SDK_INT >= 34)
			startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
		else
			startForeground(NOTIFICATION_ID, notification)
	}

	private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics).toInt()

	private fun setup(connectInfo: ConnectInfo)
	{
		val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
		windowManager = wm

		val root = FrameLayout(this)

		// Remote Play görüntüsü buraya çözülür. Ekranda 2x2 piksel (görünmez),
		// ama tampon gerçek video çözünürlüğünde, böylece altyazı net okunur.
		val surfaceView = SurfaceView(this)
		videoW = connectInfo.videoProfile.width
		videoH = connectInfo.videoProfile.height
		surfaceView.holder.setFixedSize(videoW, videoH)
		root.addView(surfaceView, FrameLayout.LayoutParams(2, 2, Gravity.TOP or Gravity.START))

		val subtitle = TextView(this).apply {
			gravity = Gravity.CENTER
			setPadding(dp(16f), dp(8f), dp(16f), dp(8f))
			setBackgroundColor(Color.parseColor("#E6000000"))
			setTextColor(Color.WHITE)
			textSize = 24f
			setTypeface(typeface, android.graphics.Typeface.BOLD)
			setShadowLayer(4f, 0f, 0f, Color.BLACK)
			minHeight = dp(84f)
			visibility = View.GONE
		}
		root.addView(subtitle, FrameLayout.LayoutParams(
			FrameLayout.LayoutParams.MATCH_PARENT,
			FrameLayout.LayoutParams.WRAP_CONTENT,
			Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
				val side = (resources.displayMetrics.widthPixels * 0.115f).toInt()
				leftMargin = side
				rightMargin = side
				bottomMargin = dp(16f)
			})

		val status = TextView(this).apply {
			setPadding(dp(10f), dp(10f), dp(10f), dp(10f))
			setBackgroundColor(Color.parseColor("#CC000000"))
			setTextColor(Color.WHITE)
			textSize = 16f
			visibility = View.GONE
		}
		root.addView(status, FrameLayout.LayoutParams(
			FrameLayout.LayoutParams.WRAP_CONTENT,
			FrameLayout.LayoutParams.WRAP_CONTENT,
			Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(24f) })
		statusView = status

		// Tanılama satırı (sol üst, küçük): kare okuma / OCR durumu
		val debug = TextView(this).apply {
			setPadding(dp(6f), dp(4f), dp(6f), dp(4f))
			setBackgroundColor(Color.parseColor("#99000000"))
			setTextColor(Color.parseColor("#FFB0FFB0"))
			textSize = 11f
		}
		root.addView(debug, FrameLayout.LayoutParams(
			FrameLayout.LayoutParams.WRAP_CONTENT,
			FrameLayout.LayoutParams.WRAP_CONTENT,
			Gravity.TOP or Gravity.START).apply { topMargin = dp(4f); leftMargin = dp(4f) })

		@Suppress("DEPRECATION")
		val type = if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
			WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
		else
			WindowManager.LayoutParams.TYPE_PHONE
		val params = WindowManager.LayoutParams(
			WindowManager.LayoutParams.MATCH_PARENT,
			WindowManager.LayoutParams.MATCH_PARENT,
			type,
			WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
				WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
				WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
				WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
			PixelFormat.TRANSLUCENT)
		params.gravity = Gravity.TOP or Gravity.START
		if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
			params.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES

		// Remote Play sesi kapalı: oyunun sesi HDMI'dan geliyor.
		ChiakiAudio.setMuted(true)

		val preferences = Preferences(this)
		val input = StreamInput(this, preferences) // hiçbir tuş gönderilmez; kumanda PS5'e bağlı kalır
		val streamSession = StreamSession(connectInfo, LogManager(this), preferences.logVerbose, input)
		streamSession.attachToSurfaceView(surfaceView)
		session = streamSession

		wm.addView(root, params)
		overlayRoot = root

		val subtitleTranslator = SubtitleTranslator(surfaceView, subtitle, status, debug)
		translator = subtitleTranslator

		val observer = Observer<StreamState> { state -> onStateChanged(state) }
		stateObserver = observer
		streamSession.state.observeForever(observer)

		showStatus("PS5'e bağlanılıyor… Şimdi TV'de PS5'in HDMI girişine geçebilirsin.")
		streamSession.resume()
		subtitleTranslator.start()
	}

	private fun onStateChanged(state: StreamState)
	{
		when(state)
		{
			is StreamStateConnected -> {
				showStatus("PS5'e bağlandı ✓ Türkçe altyazı açık.", 4000)
				translator?.resetStats()
				mainHandler.postDelayed(checkCapture, 8000)
			}
			is StreamStateQuit -> {
				showStatus("PS5 bağlantısı koptu: ${state.reason}" + (state.reasonString?.let { "\n$it" } ?: ""), 0)
				mainHandler.postDelayed({ stopSelf() }, 8000)
			}
			is StreamStateCreateError -> {
				showStatus("Bağlantı başlatılamadı: ${state.error.errorCode}", 0)
				mainHandler.postDelayed({ stopSelf() }, 8000)
			}
			is StreamStateLoginPinRequest -> {
				showStatus("PS5 giriş PIN'i istiyor. PIN'i PS5 ayarlarından kapatıp tekrar dene.", 0)
				mainHandler.postDelayed({ stopSelf() }, 10000)
			}
			else -> {}
		}
	}

	/** PixelCopy bu TV'de görüntüyü okuyamıyorsa ImageReader yöntemine geç. */
	private val checkCapture = Runnable {
		val t = translator ?: return@Runnable
		if(imageReader == null && t.captureLooksBroken())
			switchToImageReader()
	}

	private fun switchToImageReader()
	{
		try
		{
			val reader = ImageReader.newInstance(videoW, videoH, ImageFormat.YUV_420_888, 3)
			imageReader = reader
			translator?.imageReader = reader
			translator?.resetStats()
			session?.attachToSurface(reader.surface)
			showStatus("Görüntü okuma yöntemi değiştirildi (yedek yöntem).", 4000)
		}
		catch(e: Exception)
		{
			Log.e(TAG, "ImageReader'a geçilemedi", e)
			showStatus("Yedek okuma yöntemi başlatılamadı: ${e.message}", 0)
		}
	}

	private val hideStatus = Runnable { statusView?.visibility = View.GONE }

	private fun showStatus(text: String, hideAfterMs: Long = 0)
	{
		val v = statusView ?: return
		mainHandler.removeCallbacks(hideStatus)
		v.text = text
		v.visibility = View.VISIBLE
		if(hideAfterMs > 0)
			mainHandler.postDelayed(hideStatus, hideAfterMs)
	}

	private fun teardown()
	{
		mainHandler.removeCallbacksAndMessages(null)
		translator?.close()
		translator = null
		stateObserver?.let { session?.state?.removeObserver(it) }
		stateObserver = null
		try { session?.shutdown() } catch(e: Exception) { Log.w(TAG, "shutdown", e) }
		session = null
		overlayRoot?.let { try { windowManager?.removeView(it) } catch(_: Exception) { } }
		overlayRoot = null
		statusView = null
		try { imageReader?.close() } catch(_: Exception) { }
		imageReader = null
		try { ChiakiAudio.setMuted(false) } catch(_: Throwable) { }
	}

	override fun onDestroy()
	{
		teardown()
		isRunning = false
		super.onDestroy()
	}
}
