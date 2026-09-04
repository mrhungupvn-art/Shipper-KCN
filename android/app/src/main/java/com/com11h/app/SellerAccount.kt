package com.com11h.app

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Tương đương AccountSync nhưng dành riêng cho SELLER (tài khoản phát live),
 * lưu token ở SharedPreferences RIÊNG ("com11h_seller") để không đụng vào
 * phiên đăng nhập khách hàng ("com11h_secure") — 1 máy có thể vừa là khách
 * vừa là seller mà không bị lẫn token.
 */
class SellerAccount(context: Context) {
    companion object {
        private const val BASE_URL = "https://com11h.com/api/index.php"
        private const val PREFS = "com11h_seller"
        private const val TOKEN = "token"
    }

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun token(): String? = prefs.getString(TOKEN, null)
    fun isLoggedIn(): Boolean = !token().isNullOrBlank()
    fun saveToken(value: String) = prefs.edit().putString(TOKEN, value).apply()
    fun logout() = prefs.edit().remove(TOKEN).apply()

    fun request(
        action: String,
        method: String = "GET",
        body: String? = null,
        query: Map<String, String> = emptyMap()
    ): JSONObject {
        val qs = StringBuilder("action=").append(URLEncoder.encode(action, "UTF-8"))
        query.forEach { (k, v) -> qs.append('&').append(URLEncoder.encode(k, "UTF-8")).append('=').append(URLEncoder.encode(v, "UTF-8")) }

        val c = (URL("$BASE_URL?$qs").openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 12000
            readTimeout = 15000
            useCaches = false
            setRequestProperty("Accept", "application/json")
            token()?.let { setRequestProperty("Authorization", "Bearer $it") }
        }
        return try {
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: "{}"
            try { JSONObject(text) } catch (_: Exception) {
                JSONObject().put("ok", false).put("message", "Máy chủ trả về dữ liệu không hợp lệ (HTTP $code).")
            }
        } finally { c.disconnect() }
    }
}
