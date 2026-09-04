package com.com11h.app

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Lớp gọi API DUY NHẤT của app tới com11h.com/api/index.php.
 *
 * Food KCN: mọi request nghiệp vụ tự gửi X-KCN-ID của KCN khách đang chọn.
 * Riêng action kcn_list là request cấp hệ thống nên không gửi KCN-ID.
 */
class AccountSync(context: Context) {
    companion object {
        private const val BASE_URL = "https://com11h.com/api/index.php"
        private const val PREFS = "com11h_secure"
        private const val TOKEN = "token"
        private const val LAST_ACTIVE = "last_active"
        const val IDLE_TIMEOUT_MS = 5 * 60 * 1000L
    }

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun kcnId(): Int = KcnStore.id(appContext)
    private fun tokenKey(): String = if (kcnId() > 0) "${TOKEN}_${kcnId()}" else TOKEN
    private fun activeKey(): String = if (kcnId() > 0) "${LAST_ACTIVE}_${kcnId()}" else LAST_ACTIVE

    // Mỗi KCN là một tenant/database: lưu token riêng theo KCN để khách có thể
    // đăng nhập ở nhiều KCN trên cùng thiết bị mà không bị mất phiên KCN khác.
    fun token(): String? = prefs.getString(tokenKey(), null)
    fun isLoggedIn(): Boolean = !token().isNullOrBlank()

    fun saveToken(value: String) {
        prefs.edit()
            .putString(tokenKey(), value)
            .putLong(activeKey(), System.currentTimeMillis())
            .apply()
    }

    fun logout() = prefs.edit()
        .remove(tokenKey())
        .remove(activeKey())
        .apply()

    /**
     * Permanently request deletion of the currently authenticated customer account.
     * The server MUST delete/anonymize the account and associated personal data
     * according to the product retention policy before returning ok=true.
     */
    fun deleteAccount(): JSONObject = request(
        action = "delete_account",
        method = "POST",
        body = JSONObject().put("confirm", true).toString()
    )

    fun touch() {
        if (isLoggedIn()) prefs.edit().putLong(activeKey(), System.currentTimeMillis()).apply()
    }

    fun isSessionExpired(): Boolean {
        val last = prefs.getLong(activeKey(), 0L)
        if (last == 0L) return false
        return System.currentTimeMillis() - last > IDLE_TIMEOUT_MS
    }

    fun request(
        action: String,
        method: String = "GET",
        body: String? = null,
        headers: Map<String, String> = emptyMap(),
        query: Map<String, String> = emptyMap()
    ): JSONObject {
        val qs = StringBuilder("action=").append(URLEncoder.encode(action, "UTF-8"))
        query.forEach { (k, v) ->
            qs.append('&')
                .append(URLEncoder.encode(k, "UTF-8"))
                .append('=')
                .append(URLEncoder.encode(v, "UTF-8"))
        }

        val url = URL("$BASE_URL?$qs")
        val c = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 12000
            readTimeout = 15000
            useCaches = false
            setRequestProperty("Accept", "application/json")
            token()?.let { setRequestProperty("Authorization", "Bearer $it") }

            // KCN được chọn trên thiết bị là ngữ cảnh của toàn bộ API.
            // kcn_list là API trung tâm để lấy danh sách KCN nên không gửi header.
            if (action != "kcn_list") {
                val kcnId = KcnStore.id(appContext)
                if (kcnId > 0) setRequestProperty("X-KCN-ID", kcnId.toString())
            }

            headers.forEach { (k, v) -> setRequestProperty(k, v) }
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

            val json = try {
                JSONObject(text)
            } catch (_: Exception) {
                JSONObject()
                    .put("ok", false)
                    .put("message", "Máy chủ trả về dữ liệu không hợp lệ (HTTP $code).")
            }

            if (!json.has("http_code")) json.put("http_code", code)
            json
        } finally {
            c.disconnect()
        }
    }
}
