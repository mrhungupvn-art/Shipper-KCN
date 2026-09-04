package com.com11h.app

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.*
import java.util.concurrent.Executors

/**
 * Đăng nhập cho SELLER (người phát live) — tài khoản này do Admin tạo ở
 * trang admin/live_sellers.php, KHÁC với tài khoản khách hàng thường.
 */
class SellerLoginActivity : SessionActivity() {
    private val executor = Executors.newSingleThreadExecutor()
    private val primary = Color.rgb(56, 142, 60)
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val account = SellerAccount(this)
        if (account.isLoggedIn()) {
            startActivity(Intent(this, SellerLiveActivity::class.java)); finish(); return
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(28), dp(28), dp(28), dp(28))
            setBackgroundColor(Color.WHITE)
        }
        root.addView(TextView(this).apply {
            text = "Đăng nhập Seller"; textSize = 24f; setTextColor(Color.rgb(38, 38, 38))
            gravity = Gravity.CENTER; setPadding(0, 0, 0, dp(20))
        })
        val username = EditText(this).apply { hint = "Tên đăng nhập" }
        val password = EditText(this).apply { hint = "Mật khẩu"; inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD }
        val status = TextView(this).apply { setTextColor(Color.RED); setPadding(0, dp(8), 0, 0) }
        val btn = Button(this).apply {
            text = "Đăng nhập"; setBackgroundColor(primary); setTextColor(Color.WHITE)
        }

        root.addView(username, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
        root.addView(password, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(14) })
        root.addView(btn, LinearLayout.LayoutParams(-1, -2))
        root.addView(status, LinearLayout.LayoutParams(-1, -2))
        setContentView(root)

        btn.setOnClickListener {
            val u = username.text.toString().trim()
            val p = password.text.toString()
            if (u.isBlank() || p.isBlank()) { status.text = "Vui lòng nhập đầy đủ."; return@setOnClickListener }
            btn.isEnabled = false; status.text = ""
            executor.execute {
                val body = org.json.JSONObject().put("username", u).put("password", p).toString()
                val r = try { account.request("seller_login", "POST", body) } catch (_: Exception) { null }
                runOnUiThread {
                    btn.isEnabled = true
                    if (r != null && r.optBoolean("ok")) {
                        val token = r.optJSONObject("data")?.optString("token").orEmpty()
                        if (token.isNotBlank()) {
                            account.saveToken(token)
                            startActivity(Intent(this, SellerLiveActivity::class.java)); finish()
                            return@runOnUiThread
                        }
                    }
                    status.text = r?.optString("message") ?: "Đăng nhập thất bại. Kiểm tra kết nối mạng."
                }
            }
        }
    }
}
