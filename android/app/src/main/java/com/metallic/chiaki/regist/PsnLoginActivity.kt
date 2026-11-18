// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL
//
// PSN Account ID'yi Sony'nin kendi giriş sayfasıyla otomatik alır
// (chiaki-ng masaüstü sürümündeki yöntemin aynısı). Şifre yalnızca Sony'nin
// sayfasına girilir; uygulama sadece dönüşteki tek kullanımlık kodu alıp
// hesap numarasını öğrenir. Erişim anahtarı saklanmaz.

package com.metallic.chiaki.regist

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import android.view.Gravity
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.TextView
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.concurrent.thread

class PsnLoginActivity : Activity()
{
	companion object
	{
		const val EXTRA_ACCOUNT_ID = "account_id"
		const val EXTRA_ERROR = "error"

		private const val CLIENT_ID = "ba495a24-818c-472b-b12d-ff231c1b5745"
		private const val CLIENT_SECRET = "mvaiZkRsAsI1IBkY"
		private const val REDIRECT = "https://remoteplay.dl.playstation.net/remoteplay/redirect"
		private const val SCOPE = "psn:clientapp referenceDataService:countryConfig.read pushNotification:webSocket.desktop.connect sessionManager:remotePlaySession.system.update"
		private const val TOKEN_URL = "https://auth.api.sonyentertainmentnetwork.com/2.0/oauth/token"
		private val LOGIN_URL =
			"https://auth.api.sonyentertainmentnetwork.com/2.0/oauth/authorize?service_entity=urn:service-entity:psn" +
			"&response_type=code&client_id=$CLIENT_ID&redirect_uri=$REDIRECT&scope=${Uri.encode(SCOPE)}" +
			"&request_locale=en_US&ui=pr&service_logo=ps&layout_type=popup&smcid=remoteplay&prompt=always&PlatformPrivacyWs1=minimal&"
	}

	private lateinit var webView: WebView
	private lateinit var statusView: TextView
	private var handled = false

	@SuppressLint("SetJavaScriptEnabled")
	override fun onCreate(savedInstanceState: Bundle?)
	{
		super.onCreate(savedInstanceState)

		// Önceki oturumlardan kalan Sony çerezlerini temizle
		CookieManager.getInstance().removeAllCookies(null)

		webView = WebView(this)
		webView.settings.javaScriptEnabled = true
		webView.settings.domStorageEnabled = true
		webView.webViewClient = object: WebViewClient()
		{
			override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean
				= checkRedirect(request.url.toString())

			@Deprecated("Deprecated in Java")
			override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean
				= checkRedirect(url)

			override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?)
			{
				checkRedirect(url)
			}
		}

		statusView = TextView(this).apply {
			setBackgroundColor(Color.parseColor("#CC000000"))
			setTextColor(Color.WHITE)
			textSize = 16f
			setPadding(24, 16, 24, 16)
			text = "Sony hesabınla giriş yap. Şifren sadece Sony'nin sayfasına gider."
		}

		val root = FrameLayout(this)
		root.addView(webView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
		root.addView(statusView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
		setContentView(root)

		webView.loadUrl(LOGIN_URL)
	}

	private fun checkRedirect(url: String): Boolean
	{
		if(!url.startsWith(REDIRECT))
			return false
		if(handled)
			return true
		handled = true
		val uri = Uri.parse(url)
		val code = uri.getQueryParameter("code")
		if(code.isNullOrEmpty())
		{
			finishWithError("Giriş tamamlanamadı: ${uri.getQueryParameter("error_description") ?: uri.getQueryParameter("error") ?: "kod yok"}")
			return true
		}
		statusView.text = "Giriş başarılı, hesap numarası alınıyor…"
		webView.stopLoading()
		thread {
			try
			{
				val accountId = fetchAccountId(code)
				runOnUiThread {
					setResult(RESULT_OK, Intent().putExtra(EXTRA_ACCOUNT_ID, accountId))
					finish()
				}
			}
			catch(e: Exception)
			{
				runOnUiThread { finishWithError("Hesap numarası alınamadı: ${e.message}") }
			}
		}
		return true
	}

	private fun basicAuth() = "Basic " + Base64.encodeToString("$CLIENT_ID:$CLIENT_SECRET".toByteArray(), Base64.NO_WRAP)

	private fun fetchAccountId(code: String): String
	{
		// 1) Kodu erişim anahtarıyla değiştir
		val body = "grant_type=authorization_code&code=${URLEncoder.encode(code, "UTF-8")}" +
			"&scope=${URLEncoder.encode(SCOPE, "UTF-8")}&redirect_uri=${URLEncoder.encode(REDIRECT, "UTF-8")}"
		val tokenConn = (URL(TOKEN_URL).openConnection() as HttpURLConnection).apply {
			requestMethod = "POST"
			doOutput = true
			setRequestProperty("Authorization", basicAuth())
			setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
			connectTimeout = 15000
			readTimeout = 15000
		}
		tokenConn.outputStream.use { it.write(body.toByteArray()) }
		if(tokenConn.responseCode !in 200..299)
			throw Exception("token HTTP ${tokenConn.responseCode}")
		val accessToken = JSONObject(tokenConn.inputStream.bufferedReader().readText()).getString("access_token")

		// 2) Anahtarın bilgisinden kullanıcı numarasını oku
		val infoConn = (URL("$TOKEN_URL/$accessToken").openConnection() as HttpURLConnection).apply {
			setRequestProperty("Authorization", basicAuth())
			connectTimeout = 15000
			readTimeout = 15000
		}
		if(infoConn.responseCode !in 200..299)
			throw Exception("bilgi HTTP ${infoConn.responseCode}")
		val userId = JSONObject(infoConn.inputStream.bufferedReader().readText()).getString("user_id").toULong()

		// 3) 8 bayt little-endian → base64 (Remote Play'in istediği biçim)
		val bytes = ByteArray(8) { i -> ((userId shr (8 * i)) and 0xFFu).toByte() }
		return Base64.encodeToString(bytes, Base64.NO_WRAP)
	}

	private fun finishWithError(message: String)
	{
		setResult(RESULT_CANCELED, Intent().putExtra(EXTRA_ERROR, message))
		finish()
	}

	override fun onDestroy()
	{
		CookieManager.getInstance().removeAllCookies(null)
		webView.destroy()
		super.onDestroy()
	}
}
