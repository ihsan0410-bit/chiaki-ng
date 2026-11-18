// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL
//
// PSN Account ID'yi Sony'nin kendi giriş sayfasıyla otomatik alır
// (chiaki-ng masaüstü sürümündeki yöntemin aynısı). Şifre yalnızca Sony'nin
// sayfasına girilir; uygulama sadece dönüşteki tek kullanımlık kodu alıp
// hesap numarasını öğrenir. Erişim anahtarı saklanmaz.
//
// İki yol var:
//  1) Telefonla: QR kodu okut, telefondan giriş yap, açılan sayfanın adresini
//     kopyalayıp buraya yapıştır (TV kumandasıyla şifre yazmaya gerek kalmaz).
//  2) TV'de: Sony sayfasını TV'nin içinde aç.

package com.metallic.chiaki.regist

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
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

		private const val DESKTOP_UA =
			"Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"
	}

	private lateinit var root: FrameLayout
	private lateinit var phonePanel: View
	private lateinit var pasteField: EditText
	private lateinit var phoneStatus: TextView
	private var webView: WebView? = null
	private var webStatus: TextView? = null
	@Volatile private var handled = false

	override fun onCreate(savedInstanceState: Bundle?)
	{
		super.onCreate(savedInstanceState)
		CookieManager.getInstance().removeAllCookies(null)
		root = FrameLayout(this).apply { setBackgroundColor(Color.parseColor("#101418")) }
		phonePanel = buildPhonePanel()
		root.addView(phonePanel, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
		setContentView(root)
	}

	// ---------- 1) Telefonla giriş ----------

	private fun buildPhonePanel(): View
	{
		val qrSize = (resources.displayMetrics.heightPixels * 0.62f).toInt()
		val qr = ImageView(this).apply {
			setImageBitmap(makeQr(LOGIN_URL, 900))
			setBackgroundColor(Color.WHITE)
			setPadding(16, 16, 16, 16)
		}

		fun label(text: String, size: Float, color: Int = Color.WHITE) = TextView(this).apply {
			setText(text); textSize = size; setTextColor(color); setPadding(0, 8, 0, 8)
		}

		phoneStatus = label("", 15f, Color.YELLOW)

		pasteField = EditText(this).apply {
			hint = "Adresi buraya yapıştır (…redirect?code=…)"
			setHintTextColor(Color.GRAY)
			setTextColor(Color.WHITE)
			textSize = 15f
			inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
			setSingleLine(true)
		}

		val okButton = Button(this).apply {
			text = "Tamam"
			setOnClickListener { submitPasted() }
		}
		val tvButton = Button(this).apply {
			text = "Telefon yerine TV'de giriş yap"
			setOnClickListener { openWebLogin() }
		}

		val right = LinearLayout(this).apply {
			orientation = LinearLayout.VERTICAL
			setPadding(40, 0, 0, 0)
			addView(label("PSN ile giriş (telefonla)", 24f))
			addView(label(
				"1. Telefonunun kamerasıyla soldaki QR kodu okut.\n" +
				"2. Açılan Sony sayfasında PSN hesabınla giriş yap.\n" +
				"3. Giriş bitince boş/hata gibi görünen bir sayfa açılır, bu normal. " +
				"O sayfanın ADRESİNİ kopyala (adres çubuğuna dokun → kopyala).\n" +
				"4. Telefondaki Google TV uygulamasının klavyesiyle adresi aşağıdaki kutuya yapıştır ve Tamam'a bas.\n\n" +
				"Kod birkaç dakika geçerli, giriş yaptıktan hemen sonra yapıştır.", 16f))
			addView(pasteField, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
			addView(okButton)
			addView(phoneStatus)
			addView(tvButton)
		}

		val row = LinearLayout(this).apply {
			orientation = LinearLayout.HORIZONTAL
			setPadding(48, 40, 48, 40)
			gravity = Gravity.CENTER_VERTICAL
			addView(qr, LinearLayout.LayoutParams(qrSize, qrSize))
			addView(right, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
		}
		pasteField.requestFocus()
		return ScrollView(this).apply { addView(row) }
	}

	private fun makeQr(text: String, size: Int): Bitmap
	{
		val hints = mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.L, EncodeHintType.MARGIN to 1)
		val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, hints)
		val pixels = IntArray(matrix.width * matrix.height)
		for(y in 0 until matrix.height)
			for(x in 0 until matrix.width)
				pixels[y * matrix.width + x] = if(matrix.get(x, y)) Color.BLACK else Color.WHITE
		return Bitmap.createBitmap(pixels, matrix.width, matrix.height, Bitmap.Config.ARGB_8888)
	}

	private fun submitPasted()
	{
		val text = pasteField.text.toString().trim()
		val code = Regex("[?&]code=([^&\\s#]+)").find(text)?.groupValues?.get(1)
			?: text.takeIf { it.isNotEmpty() && !it.contains("/") && !it.contains(" ") }
		if(code.isNullOrEmpty())
		{
			phoneStatus.text = "Adreste kod bulunamadı. Sayfanın tam adresini (code= içeren) yapıştır."
			return
		}
		phoneStatus.text = "Kod alındı, hesap numarası alınıyor…"
		exchange(code) { msg -> phoneStatus.text = msg }
	}

	// ---------- 2) TV'de giriş ----------

	@SuppressLint("SetJavaScriptEnabled")
	private fun openWebLogin()
	{
		val wv = WebView(this)
		webView = wv
		wv.settings.javaScriptEnabled = true
		wv.settings.domStorageEnabled = true
		wv.settings.userAgentString = DESKTOP_UA
		wv.settings.loadWithOverviewMode = true
		wv.settings.useWideViewPort = true
		CookieManager.getInstance().setAcceptCookie(true)
		CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)

		val status = TextView(this).apply {
			setBackgroundColor(Color.parseColor("#CC000000"))
			setTextColor(Color.WHITE)
			textSize = 16f
			setPadding(24, 16, 24, 16)
			text = "Sony sayfası yükleniyor… (geri tuşu: telefonla giriş ekranı)"
		}
		webStatus = status

		wv.webViewClient = object: WebViewClient()
		{
			override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean
				= checkRedirect(request.url.toString())

			@Deprecated("Deprecated in Java")
			override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean
				= checkRedirect(url)

			override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?)
			{
				if(!checkRedirect(url))
					status.text = "Yükleniyor: ${Uri.parse(url).host}"
			}

			override fun onPageFinished(view: WebView, url: String)
			{
				if(!handled)
					status.text = "Sony hesabınla giriş yap. Şifren sadece Sony'nin sayfasına gider. (Boş kalırsa geri tuşuyla telefonla giriş yap.)"
			}

			override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError)
			{
				if(request.isForMainFrame)
					status.text = "Sayfa hatası: ${error.description} (geri tuşuyla telefonla giriş yap)"
			}

			override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, errorResponse: WebResourceResponse)
			{
				if(request.isForMainFrame)
					status.text = "Sony sayfası hata verdi: HTTP ${errorResponse.statusCode} (geri tuşuyla telefonla giriş yap)"
			}
		}

		phonePanel.visibility = View.GONE
		root.addView(wv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
		root.addView(status, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
		wv.requestFocus()
		wv.loadUrl(LOGIN_URL)
	}

	private fun closeWebLogin()
	{
		webView?.let { root.removeView(it); it.destroy() }
		webStatus?.let { root.removeView(it) }
		webView = null
		webStatus = null
		phonePanel.visibility = View.VISIBLE
		pasteField.requestFocus()
	}

	@Deprecated("Deprecated in Java")
	override fun onBackPressed()
	{
		val wv = webView
		if(wv != null)
		{
			if(wv.canGoBack()) wv.goBack() else closeWebLogin()
			return
		}
		@Suppress("DEPRECATION")
		super.onBackPressed()
	}

	private fun checkRedirect(url: String): Boolean
	{
		if(!url.startsWith(REDIRECT))
			return false
		if(handled)
			return true
		val uri = Uri.parse(url)
		val code = uri.getQueryParameter("code")
		if(code.isNullOrEmpty())
		{
			webStatus?.text = "Giriş tamamlanamadı: ${uri.getQueryParameter("error_description") ?: uri.getQueryParameter("error") ?: "kod yok"}"
			return true
		}
		webView?.stopLoading()
		webStatus?.text = "Giriş başarılı, hesap numarası alınıyor…"
		exchange(code) { msg -> webStatus?.text = msg }
		return true
	}

	// ---------- Ortak: kodu hesap numarasına çevir ----------

	private fun exchange(code: String, onError: (String) -> Unit)
	{
		if(handled)
			return
		handled = true
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
				handled = false
				runOnUiThread { onError("Hesap numarası alınamadı (${e.message}). Kodun süresi dolmuş olabilir, tekrar giriş yapıp yeni adresi yapıştır.") }
			}
		}
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

	override fun onDestroy()
	{
		CookieManager.getInstance().removeAllCookies(null)
		webView?.destroy()
		super.onDestroy()
	}
}
