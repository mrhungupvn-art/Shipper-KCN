package com.com11h.app

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.widget.*
import org.json.JSONObject
import java.util.concurrent.Executors

/**
 * Màn hình duy nhất cho seller: vì app KHÔNG tự dựng server truyền video
 * (xem trao đổi trước), seller tự bật live trên app YouTube/Facebook có sẵn
 * trên máy, quay lại đây dán link để tạo phiên, ghim sản phẩm rồi bấm
 * "Bắt đầu bán". Toàn bộ ghim sản phẩm được server kiểm tra đúng ngành hàng
 * của Shop mình — sai 2 lần sẽ tự bị khoá (xem live_add_product trong API).
 */
class SellerLiveActivity : SessionActivity() {
    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var account: SellerAccount
    private val primary = Color.rgb(56, 142, 60)
    private val danger = Color.rgb(200, 40, 40)
    private var liveId: Int = -1
    private lateinit var statusText: TextView
    private lateinit var productsBox: LinearLayout
    private lateinit var startBtn: Button
    private lateinit var endBtn: Button

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        account = SellerAccount(this)
        if (!account.isLoggedIn()) {
            startActivity(Intent(this, SellerLoginActivity::class.java)); finish(); return
        }
        buildView()
    }

    private fun buildView() {
        val scroll = ScrollView(this)
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(20), dp(20), dp(40)) }
        scroll.addView(content)

        content.addView(TextView(this).apply {
            text = "Phát live bán hàng"; textSize = 22f; setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, dp(6))
        })
        content.addView(TextView(this).apply {
            text = "Bước 1: mở app YouTube hoặc Facebook, bật live (chế độ Không công khai), copy link, dán vào đây."
            setTextColor(Color.rgb(107, 107, 107)); textSize = 13f; setPadding(0, 0, 0, dp(16))
        })

        val title = EditText(this).apply { hint = "Tiêu đề live (VD: Cơm trưa hôm nay)" }
        val videoUrl = EditText(this).apply { hint = "Dán link YouTube/Facebook Live vào đây" }
        val createBtn = Button(this).apply { text = "Tạo phiên live"; setBackgroundColor(primary); setTextColor(Color.WHITE) }
        content.addView(title, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
        content.addView(videoUrl, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
        content.addView(createBtn, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(16) })

        statusText = TextView(this).apply { setTextColor(danger); setPadding(0, 0, 0, dp(12)) }
        content.addView(statusText)

        startBtn = Button(this).apply { text = "▶ Bắt đầu live"; isEnabled = false; setBackgroundColor(Color.rgb(30, 150, 90)); setTextColor(Color.WHITE) }
        endBtn = Button(this).apply { text = "■ Kết thúc live"; isEnabled = false; setBackgroundColor(danger); setTextColor(Color.WHITE) }
        content.addView(startBtn, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        content.addView(endBtn, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(20) })

        content.addView(TextView(this).apply {
            text = "Ghim sản phẩm (chỉ món đúng ngành hàng của Shop bạn mới ghim được)"
            textSize = 15f; setTypeface(null, android.graphics.Typeface.BOLD); setPadding(0, 0, 0, dp(8))
        })
        val addFoodId = EditText(this).apply { hint = "Nhập ID món ăn (xem trong Thực đơn trên web/app)"; inputType = android.text.InputType.TYPE_CLASS_NUMBER }
        val addBtn = Button(this).apply { text = "Ghim món" }
        content.addView(addFoodId, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        content.addView(addBtn, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })

        productsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(productsBox)

        setContentView(scroll)

        createBtn.setOnClickListener {
            val t = title.text.toString().trim()
            val u = videoUrl.text.toString().trim()
            if (t.isBlank() || u.isBlank()) { statusText.text = "Vui lòng nhập tiêu đề và link video."; return@setOnClickListener }
            val platform = when {
                u.contains("youtube.com") || u.contains("youtu.be") -> "youtube"
                u.contains("facebook.com") || u.contains("fb.watch") -> "facebook"
                u.contains("tiktok.com") -> "tiktok"
                else -> "other"
            }
            createBtn.isEnabled = false
            executor.execute {
                val body = JSONObject().put("title", t).put("platform", platform).put("video_url", u).toString()
                val r = try { account.request("live_create", "POST", body) } catch (_: Exception) { null }
                runOnUiThread {
                    createBtn.isEnabled = true
                    if (r != null && r.optBoolean("ok")) {
                        liveId = r.optJSONObject("data")?.optInt("live_id") ?: -1
                        statusText.setTextColor(Color.rgb(30, 150, 90))
                        statusText.text = "Đã tạo phiên #$liveId — ghim sản phẩm rồi bấm Bắt đầu live."
                        startBtn.isEnabled = true
                        title.isEnabled = false; videoUrl.isEnabled = false; createBtn.isEnabled = false
                    } else {
                        statusText.setTextColor(danger)
                        statusText.text = r?.optString("message") ?: "Không tạo được phiên live. Kiểm tra kết nối mạng."
                    }
                }
            }
        }

        addBtn.setOnClickListener {
            if (liveId <= 0) { statusText.text = "Hãy tạo phiên live trước."; return@setOnClickListener }
            val foodId = addFoodId.text.toString().trim().toIntOrNull()
            if (foodId == null) { statusText.text = "ID món ăn không hợp lệ."; return@setOnClickListener }
            executor.execute {
                val body = JSONObject().put("live_id", liveId).put("food_id", foodId).toString()
                val r = try { account.request("live_add_product", "POST", body) } catch (_: Exception) { null }
                runOnUiThread {
                    if (r != null && r.optBoolean("ok")) {
                        statusText.setTextColor(Color.rgb(30, 150, 90))
                        statusText.text = "Đã ghim món #$foodId."
                        addFoodId.text.clear()
                        productsBox.addView(pinnedRow(foodId))
                    } else {
                        // Server có thể trả 403 kèm cảnh báo "vi phạm lần X/2" hoặc đã khoá live —
                        // hiển thị nguyên văn để seller biết chính xác đang ở mức nào.
                        statusText.setTextColor(danger)
                        statusText.text = r?.optString("message") ?: "Không ghim được món này."
                        if (statusText.text.contains("KHOÁ")) {
                            startBtn.isEnabled = false; endBtn.isEnabled = false; addBtn.isEnabled = false
                        }
                    }
                }
            }
        }

        startBtn.setOnClickListener {
            executor.execute {
                val body = JSONObject().put("live_id", liveId).toString()
                val r = try { account.request("live_start", "POST", body) } catch (_: Exception) { null }
                runOnUiThread {
                    if (r != null && r.optBoolean("ok")) {
                        statusText.setTextColor(Color.rgb(30, 150, 90))
                        statusText.text = "🔴 Đang LIVE — khách đã có thể xem và đặt hàng."
                        startBtn.isEnabled = false; endBtn.isEnabled = true
                    } else {
                        statusText.text = r?.optString("message") ?: "Không bắt đầu được live."
                    }
                }
            }
        }

        endBtn.setOnClickListener {
            executor.execute {
                val body = JSONObject().put("live_id", liveId).toString()
                val r = try { account.request("live_end", "POST", body) } catch (_: Exception) { null }
                runOnUiThread {
                    statusText.setTextColor(Color.rgb(107, 107, 107))
                    statusText.text = "Đã kết thúc live. Cảm ơn bạn!"
                    endBtn.isEnabled = false
                }
            }
        }
    }

    private fun pinnedRow(foodId: Int): TextView = TextView(this).apply {
        text = "• Món #$foodId đã ghim"
        setPadding(0, dp(4), 0, dp(4))
    }
}
