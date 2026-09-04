package com.com11h.app

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors

/**
 * Xem 1 phiên live: video nhúng (YouTube/Facebook — kỹ thuật referrer giống
 * hệt HomeActivity.newsVideoHtml() để né lỗi YouTube 153), danh sách sản
 * phẩm ghim (bấm "Đặt hàng" -> thêm vào GIỎ HÀNG CÓ SẴN của app, tận dụng
 * lại toàn bộ luồng thanh toán đã chạy ổn định, không viết luồng đặt hàng
 * riêng), và chat 2 chiều dạng polling mỗi 4 giây.
 */
class LiveWatchActivity : SessionActivity() {
    private val executor = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private var chatSinceId = 0
    private var chatPollRunnable: Runnable? = null
    private var webView: WebView? = null
    private lateinit var chatBox: LinearLayout
    private lateinit var chatScroll: ScrollView
    private val primary = Color.rgb(56, 142, 60)
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object { private const val SITE_URL = "https://com11h.com" }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val liveId = intent.getIntExtra("live_id", -1)
        if (liveId <= 0) { finish(); return }
        buildView(liveId)
        loadDetail(liveId)
        startChatPolling(liveId)
    }

    override fun onDestroy() {
        chatPollRunnable?.let { handler.removeCallbacks(it) }
        try { webView?.destroy() } catch (_: Exception) {}
        executor.shutdownNow()
        super.onDestroy()
    }

    private fun buildView(liveId: Int) {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.WHITE) }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12)); setBackgroundColor(primary)
        }
        header.addView(TextView(this).apply { text = "←"; textSize = 20f; setTextColor(Color.WHITE); setPadding(0, 0, dp(12), 0); setOnClickListener { finish() } })
        header.addView(TextView(this).apply { text = "🔴 Live bán hàng"; textSize = 16f; setTypeface(null, Typeface.BOLD); setTextColor(Color.WHITE) })
        root.addView(header, LinearLayout.LayoutParams(-1, -2))

        val scroll = ScrollView(this)
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)

        // AspectRatioFrameLayout đã có sẵn trong HomeActivity.kt (cùng package) — tái
        // dùng nguyên lớp, không viết lại, để khung video luôn đúng tỉ lệ 16:9.
        val videoBox = AspectRatioFrameLayout(this, 16f, 9f).apply { background = GradientDrawable().apply { setColor(Color.BLACK) } }
        content.addView(videoBox, LinearLayout.LayoutParams(-1, -2))
        this.videoBoxRef = videoBox

        this.productsBoxRef = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(14), dp(16), dp(14)) }
        content.addView(productsBoxRef)

        content.addView(TextView(this).apply { text = "💬 Chat trực tiếp"; textSize = 15f; setTypeface(null, Typeface.BOLD); setPadding(dp(16), dp(6), dp(16), dp(8)) })
        chatScroll = ScrollView(this)
        chatBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), 0, dp(16), dp(10)) }
        chatScroll.addView(chatBox)
        content.addView(chatScroll, LinearLayout.LayoutParams(-1, dp(220)))

        val chatRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(16), dp(8), dp(16), dp(16)); gravity = Gravity.CENTER_VERTICAL }
        val chatInput = EditText(this).apply { hint = "Nhập tin nhắn..." }
        val sendBtn = Button(this).apply { text = "Gửi"; setBackgroundColor(primary); setTextColor(Color.WHITE) }
        chatRow.addView(chatInput, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = dp(8) })
        chatRow.addView(sendBtn, LinearLayout.LayoutParams(-2, -2))
        content.addView(chatRow)

        sendBtn.setOnClickListener {
            val msg = chatInput.text.toString().trim()
            if (msg.isBlank()) return@setOnClickListener
            val account = AccountSync(this)
            if (!account.isLoggedIn()) { Toast.makeText(this, "Vui lòng đăng nhập để chat.", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
            chatInput.setText("")
            executor.execute {
                val body = JSONObject().put("live_id", liveId).put("message", msg).toString()
                try { account.request("live_send_message", "POST", body) } catch (_: Exception) {}
            }
        }
    }

    private lateinit var videoBoxRef: FrameLayout
    private lateinit var productsBoxRef: LinearLayout

    private fun embedHtml(embedUrl: String): String {
        val safeUrl = embedUrl.replace("\"", "&quot;")
        return """
            <!DOCTYPE html><html><head><meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1, user-scalable=no">
            <style>html,body{margin:0;padding:0;background:#000;overflow:hidden}iframe{position:absolute;top:0;left:0;width:100%;height:100%;border:0}</style>
            </head><body>
              <iframe src="$safeUrl" referrerpolicy="strict-origin-when-cross-origin"
                allow="autoplay; encrypted-media; picture-in-picture" allowfullscreen></iframe>
            </body></html>
        """.trimIndent()
    }

    /** Chuyển link seller dán (watch/live thường) thành link nhúng được (embed). */
    private fun toEmbedUrl(platform: String, url: String): String = when (platform) {
        "youtube" -> {
            val id = Regex("(?:v=|youtu\\.be/|live/)([A-Za-z0-9_-]{6,})").find(url)?.groupValues?.get(1)
            if (id != null) "https://www.youtube.com/embed/$id?autoplay=1&playsinline=1" else url
        }
        "facebook" -> "https://www.facebook.com/plugins/video.php?href=" + java.net.URLEncoder.encode(url, "UTF-8") + "&show_text=false&autoplay=true"
        else -> url
    }

    private fun loadDetail(liveId: Int) {
        executor.execute {
            val account = AccountSync(this)
            val r = try { account.request("live_detail", query = mapOf("live_id" to liveId.toString())) } catch (_: Exception) { null }
            runOnUiThread {
                val data = r?.optJSONObject("data")
                val live = data?.optJSONObject("live")
                if (r == null || !r.optBoolean("ok") || live == null) {
                    Toast.makeText(this, "Không tải được phiên live.", Toast.LENGTH_SHORT).show(); return@runOnUiThread
                }
                val platform = live.optString("platform", "other")
                val videoUrl = live.optString("video_url")
                try {
                    val wv = WebView(this).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.mediaPlaybackRequiresUserGesture = false
                        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                        settings.setSupportZoom(false)
                        isVerticalScrollBarEnabled = false; isHorizontalScrollBarEnabled = false
                        overScrollMode = View.OVER_SCROLL_NEVER
                        webViewClient = WebViewClient(); webChromeClient = WebChromeClient()
                        setBackgroundColor(Color.BLACK)
                        loadDataWithBaseURL("$SITE_URL/", embedHtml(toEmbedUrl(platform, videoUrl)), "text/html", "utf-8", null)
                    }
                    videoBoxRef.removeAllViews()
                    videoBoxRef.addView(wv, FrameLayout.LayoutParams(-1, -1))
                    webView = wv
                } catch (_: Exception) { /* máy không dựng được WebView -> chỉ ẩn video, vẫn xem được sản phẩm/chat */ }

                val products = data.optJSONArray("products") ?: JSONArray()
                productsBoxRef.removeAllViews()
                if (products.length() == 0) {
                    productsBoxRef.addView(TextView(this).apply { text = "Chưa có sản phẩm nào được ghim."; setTextColor(Color.rgb(107, 107, 107)) })
                }
                for (i in 0 until products.length()) {
                    val p = products.getJSONObject(i)
                    productsBoxRef.addView(productRow(p), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
                }
            }
        }
    }

    private fun productRow(p: JSONObject): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply { setColor(Color.rgb(250, 250, 250)); cornerRadius = dp(12).toFloat() }
            setPadding(dp(10), dp(10), dp(10), dp(10))
        }
        val img = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; background = GradientDrawable().apply { setColor(Color.rgb(230, 230, 230)); cornerRadius = dp(8).toFloat() } }
        row.addView(img, LinearLayout.LayoutParams(dp(56), dp(56)).apply { rightMargin = dp(10) })
        val image = p.optString("image")
        if (image.isNotBlank()) ImageLoader.load(img, image)

        val info = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        info.addView(TextView(this).apply { text = p.optString("name"); setTypeface(null, Typeface.BOLD); textSize = 14f })
        info.addView(TextView(this).apply { text = String.format("%,d", p.optInt("price_at_live")).replace(',', '.') + "đ"; setTextColor(primary); textSize = 13f })
        row.addView(info, LinearLayout.LayoutParams(0, -2, 1f))

        val addBtn = Button(this).apply { text = "Đặt hàng"; setBackgroundColor(primary); setTextColor(Color.WHITE); textSize = 12f }
        addBtn.setOnClickListener { addToCart(p.optInt("food_id")) }
        row.addView(addBtn, LinearLayout.LayoutParams(-2, -2))
        return row
    }

    /**
     * Thêm 1 món vào giỏ hàng cục bộ CHUNG với toàn app (SharedPreferences
     * "com11h_local"/"cart", cùng định dạng MainActivity đang dùng), rồi mở
     * thẳng màn Giỏ hàng để khách bấm đặt — tái dùng nguyên luồng thanh toán
     * đã kiểm chứng, không viết API đặt hàng riêng cho live.
     */
    private fun addToCart(foodId: Int) {
        if (foodId <= 0) return
        val p = getSharedPreferences("com11h_local", MODE_PRIVATE)
        val arr = try { JSONArray(p.getString("cart", "[]")) } catch (_: Exception) { JSONArray() }
        var found = false
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            if (o.optInt("id") == foodId) { o.put("qty", o.optInt("qty") + 1); found = true; break }
        }
        if (!found) arr.put(JSONObject().put("id", foodId).put("qty", 1))
        p.edit().putString("cart", arr.toString()).apply()
        Toast.makeText(this, "Đã thêm vào giỏ hàng.", Toast.LENGTH_SHORT).show()
        startActivity(Intent(this, MainActivity::class.java).putExtra("screen", "cart"))
    }

    private fun startChatPolling(liveId: Int) {
        val runnable = object : Runnable {
            override fun run() {
                executor.execute {
                    val account = AccountSync(this@LiveWatchActivity)
                    val r = try {
                        account.request("live_get_messages", query = mapOf("live_id" to liveId.toString(), "since_id" to chatSinceId.toString()))
                    } catch (_: Exception) { null }
                    val items = r?.optJSONArray("data")
                    if (r != null && r.optBoolean("ok") && items != null && items.length() > 0) {
                        runOnUiThread {
                            for (i in 0 until items.length()) {
                                val m = items.getJSONObject(i)
                                chatSinceId = m.optInt("id")
                                chatBox.addView(TextView(this@LiveWatchActivity).apply {
                                    text = "${m.optString("sender_name")}: ${m.optString("message")}"
                                    textSize = 13f; setPadding(0, dp(2), 0, dp(2))
                                })
                            }
                            chatScroll.post { chatScroll.fullScroll(View.FOCUS_DOWN) }
                        }
                    }
                }
                handler.postDelayed(this, 4000)
            }
        }
        chatPollRunnable = runnable
        handler.postDelayed(runnable, 500)
    }
}
