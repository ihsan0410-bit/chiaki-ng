// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL
//
// Çökme raporlayıcı: uygulama çökerse hatayı kaydeder ve bir sonraki açılışta
// (ya da hemen, ayrı bir süreçte) ekranda gösterir. TV'de logcat'e erişim zor
// olduğu için hata metninin fotoğrafını çekip göndermek yeterli olsun diye var.

package com.metallic.chiaki.common

import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.app.ApplicationExitInfo
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import kotlin.system.exitProcess

object CrashReporter
{
	private const val CRASH_FILE = "last_crash.txt"
	private const val PREFS = "crash_reporter"
	private const val KEY_SEEN_EXIT_TS = "seen_exit_ts"

	fun isCrashProcess(): Boolean
	{
		val name = if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) Application.getProcessName() else null
		return name?.endsWith(":crash") == true
	}

	fun install(context: Context)
	{
		val appContext = context.applicationContext ?: context
		val previous = Thread.getDefaultUncaughtExceptionHandler()
		Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
			try
			{
				val sw = StringWriter()
				throwable.printStackTrace(PrintWriter(sw))
				val text = header() + "Thread: ${thread.name}\n\n" + sw.toString()
				File(appContext.filesDir, CRASH_FILE).writeText(text)
				val intent = Intent(appContext, CrashActivity::class.java)
					.putExtra(CrashActivity.EXTRA_TEXT, text)
					.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
				appContext.startActivity(intent)
			}
			catch(_: Throwable) { }
			try
			{
				Process.killProcess(Process.myPid())
				exitProcess(10)
			}
			catch(_: Throwable)
			{
				previous?.uncaughtException(thread, throwable)
			}
		}
	}

	/** Son çökmenin metnini döndürür (yoksa null) ve bir daha gösterilmemesi için işaretler. */
	fun takeLastCrash(context: Context): String?
	{
		val file = File(context.filesDir, CRASH_FILE)
		if(file.exists())
		{
			val text = file.readText()
			file.delete()
			markExitsSeen(context)
			return text
		}

		// Yerel (native) çökmeler Java tarafından yakalanamaz; Android 11+ sistemden okuyalım.
		if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
		{
			try
			{
				val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
				val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
				val seen = prefs.getLong(KEY_SEEN_EXIT_TS, 0L)
				val exit = am.getHistoricalProcessExitReasons(context.packageName, 0, 5)
					.filter { it.timestamp > seen }
					.firstOrNull { it.reason == ApplicationExitInfo.REASON_CRASH
							|| it.reason == ApplicationExitInfo.REASON_CRASH_NATIVE
							|| it.reason == ApplicationExitInfo.REASON_ANR
							|| it.reason == ApplicationExitInfo.REASON_INITIALIZATION_FAILURE }
				markExitsSeen(context)
				if(exit != null)
				{
					val sb = StringBuilder(header())
					sb.append("Süreç: ${exit.processName}\n")
					sb.append("Sebep: ${reasonName(exit.reason)} (${exit.reason})\n")
					sb.append("Açıklama: ${exit.description}\n\n")
					if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.S || exit.reason == ApplicationExitInfo.REASON_ANR)
					{
						try
						{
							exit.traceInputStream?.use { stream ->
								val bytes = stream.readBytes()
								sb.append(String(bytes.copyOf(minOf(bytes.size, 6000))))
							}
						}
						catch(_: Throwable) { }
					}
					return sb.toString()
				}
			}
			catch(_: Throwable) { }
		}
		return null
	}

	private fun markExitsSeen(context: Context)
	{
		context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
			.putLong(KEY_SEEN_EXIT_TS, System.currentTimeMillis()).apply()
	}

	private fun reasonName(reason: Int) = when(reason)
	{
		4 -> "CRASH (Java)"
		5 -> "CRASH_NATIVE"
		6 -> "ANR"
		7 -> "INITIALIZATION_FAILURE"
		else -> "OTHER"
	}

	private fun header() =
		"mirfanceviri hata raporu\n" +
		"Cihaz: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n" +
		"ABI: ${Build.SUPPORTED_ABIS.joinToString()}\n\n"

	/** Hata metnini gösteren basit ekran (tema veya kütüphane gerektirmez). */
	fun buildView(activity: Activity, text: String, onContinue: () -> Unit): ScrollView
	{
		val title = TextView(activity).apply {
			setText("Uygulama çöktü. Bu ekranın fotoğrafını çekip Claude'a gönder.")
			textSize = 20f
			setTextColor(Color.YELLOW)
			setPadding(0, 0, 0, 24)
		}
		val body = TextView(activity).apply {
			setText(text)
			textSize = 13f
			setTextColor(Color.WHITE)
			setTextIsSelectable(true)
		}
		val button = Button(activity).apply {
			setText("Tekrar dene")
			setOnClickListener { onContinue() }
		}
		val layout = LinearLayout(activity).apply {
			orientation = LinearLayout.VERTICAL
			setPadding(48, 32, 48, 32)
			setBackgroundColor(Color.BLACK)
			gravity = Gravity.START
			addView(title)
			addView(button)
			addView(body)
		}
		button.requestFocus()
		return ScrollView(activity).apply {
			setBackgroundColor(Color.BLACK)
			addView(layout)
		}
	}
}

/** Ayrı süreçte (":crash") çalışır; ana süreç ölse bile hatayı gösterebilir. */
class CrashActivity : Activity()
{
	companion object
	{
		const val EXTRA_TEXT = "crash_text"
	}

	override fun onCreate(savedInstanceState: Bundle?)
	{
		super.onCreate(savedInstanceState)
		val text = intent.getStringExtra(EXTRA_TEXT) ?: "Bilinmeyen hata"
		setContentView(CrashReporter.buildView(this, text) {
			startActivity(Intent(this, LaunchActivity::class.java)
				.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
			finish()
		})
	}
}

/** Başlangıç ekranı: önce önceki çökmeyi kontrol eder, yoksa ana ekrana geçer. */
class LaunchActivity : Activity()
{
	override fun onCreate(savedInstanceState: Bundle?)
	{
		super.onCreate(savedInstanceState)
		val crash = CrashReporter.takeLastCrash(this)
		if(crash != null)
		{
			setContentView(CrashReporter.buildView(this, crash) { openMain() })
			return
		}
		openMain()
	}

	private fun openMain()
	{
		startActivity(Intent(this, com.metallic.chiaki.main.MainActivity::class.java))
		finish()
	}
}

class ChiakiApplication : Application()
{
	override fun attachBaseContext(base: Context)
	{
		super.attachBaseContext(base)
		if(!CrashReporter.isCrashProcess())
			CrashReporter.install(base)
	}
}
