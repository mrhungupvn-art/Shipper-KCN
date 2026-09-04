package com.com11h.app

import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Toast
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Web bridge used when shopping moves from the native shell to com11h.com.
 * If the customer is authenticated in the app, it first asks the API for a
 * short-lived one-time SSO URL. The SSO endpoint must create the normal PHP
 * customer session and redirect to the requested web page.
 *
 * If there is no app token, the web page opens normally so guests can still
 * browse the menu and log in on the website.
 */
class WebActivity : SessionActivity() {
    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var webView: WebView
    private lateinit var progressBar: ProgressBar

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildView()
        openWeb(intent.getStringExtra("url") ?: "https://com11h.com/menu.php")
    }

    override fun onDestroy() {
        webView.stopLoading()
        webView.destroy()
        executor.shutdownNow()
        super.onDestroy()
    }

    private fun buildView() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
        }
        progressBar = ProgressBar(this).apply { isIndeterminate = true }
        root.addView(progressBar, LinearLayout.LayoutParams(-1, 4))
        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            settings.cacheMode = WebSettings.LOAD_DEFAULT
            settings.setSupportZoom(false)
            settings.builtInZoomControls = false
            settings.displayZoomControls = false
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    progressBar.visibility = View.GONE
                }
            }
            webChromeClient = WebChromeClient()
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, false)
        root.addView(webView, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }

    private fun openWeb(targetUrl: String) {
        val parsed = try { URL(targetUrl) } catch (_: Exception) { null }
        val host = parsed?.host?.lowercase()
        if (parsed == null || parsed.protocol.lowercase() != "https" ||
            host !in setOf("com11h.com", "www.com11h.com")) {
            Toast.makeText(this, "Địa chỉ mở trong ứng dụng không hợp lệ.", Toast.LENGTH_SHORT).show()
            webView.loadUrl("https://com11h.com/")
            return
        }

        // AccountSync lưu token theo từng KCN (token_<kcn_id>), vì vậy không
        // đọc cứng key "token"; nếu không SSO sẽ hỏng sau khi chọn KCN.
        val token = AccountSync(this).token()?.trim()
        if (token.isNullOrBlank()) {
            webView.loadUrl(targetUrl)
            return
        }

        progressBar.visibility = View.VISIBLE
        executor.execute {
            try {
                val conn = (URL("https://com11h.com/api/index.php?action=create_web_sso").openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 12000
                    readTimeout = 15000
                    doOutput = true
                    setRequestProperty("Accept", "application/json")
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    setRequestProperty("Authorization", "Bearer $token")
                }
                val body = JSONObject().put("next", targetUrl).toString()
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val text = stream?.bufferedReader()?.use { it.readText() } ?: "{}"
                val json = JSONObject(text)
                val ssoUrl = json.optJSONObject("data")?.optString("url", "")?.trim().orEmpty()
                conn.disconnect()

                runOnUiThread {
                    progressBar.visibility = View.GONE
                    if (code in 200..299 && json.optBoolean("ok", false) && ssoUrl.isNotBlank()) {
                        webView.loadUrl(ssoUrl)
                    } else {
                        Toast.makeText(this, "Chưa kết nối SSO website. Đang mở thực đơn.", Toast.LENGTH_SHORT).show()
                        webView.loadUrl(targetUrl)
                    }
                }
            } catch (_: Exception) {
                runOnUiThread {
                    progressBar.visibility = View.GONE
                    Toast.makeText(this, "Không kết nối được SSO. Đang mở thực đơn.", Toast.LENGTH_SHORT).show()
                    webView.loadUrl(targetUrl)
                }
            }
        }
    }
}
