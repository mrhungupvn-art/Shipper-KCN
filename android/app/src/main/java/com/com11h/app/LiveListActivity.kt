package com.com11h.app

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.widget.*
import java.util.concurrent.Executors

/** Danh sách phiên live đang phát (public — không cần đăng nhập để xem danh sách). */
class LiveListActivity : SessionActivity() {
    private val executor = Executors.newSingleThreadExecutor()
    private val primary = Color.rgb(56, 142, 60)
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val account = AccountSync(this)

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.WHITE) }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14)); setBackgroundColor(primary)
        }
        header.addView(TextView(this).apply { text = "←"; textSize = 20f; setTextColor(Color.WHITE); setPadding(0, 0, dp(12), 0); setOnClickListener { finish() } })
        header.addView(TextView(this).apply { text = "🔴 Đang livestream"; textSize = 18f; setTypeface(null, Typeface.BOLD); setTextColor(Color.WHITE) })
        root.addView(header, LinearLayout.LayoutParams(-1, -2))

        val scroll = ScrollView(this)
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(16), dp(16), dp(16)) }
        scroll.addView(list)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)

        val loading = TextView(this).apply { text = "Đang tải danh sách live..."; gravity = Gravity.CENTER; setPadding(0, dp(30), 0, 0) }
        list.addView(loading)

        executor.execute {
            val r = try { account.request("live_list") } catch (_: Exception) { null }
            runOnUiThread {
                list.removeAllViews()
                val items = r?.optJSONArray("data")
                if (r == null || !r.optBoolean("ok") || items == null || items.length() == 0) {
                    list.addView(TextView(this).apply {
                        text = "Hiện chưa có Shop nào đang live. Quay lại sau nhé!"
                        gravity = Gravity.CENTER; setTextColor(Color.rgb(107, 107, 107)); setPadding(0, dp(30), 0, 0)
                    })
                    return@runOnUiThread
                }
                for (i in 0 until items.length()) {
                    val o = items.getJSONObject(i)
                    list.addView(liveCard(
                        id = o.optInt("id"),
                        title = o.optString("title"),
                        shop = o.optString("shop_name"),
                        viewers = o.optInt("viewer_count"),
                        cover = o.optString("cover_image")
                    ), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
                }
            }
        }
    }

    private fun liveCard(id: Int, title: String, shop: String, viewers: Int, cover: String): LinearLayout {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply { setColor(Color.rgb(250, 250, 250)); cornerRadius = dp(14).toFloat() }
            setPadding(dp(14), dp(14), dp(14), dp(14))
            setOnClickListener {
                startActivity(Intent(this@LiveListActivity, LiveWatchActivity::class.java).putExtra("live_id", id))
            }
        }
        val img = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; background = GradientDrawable().apply { setColor(Color.rgb(230, 230, 230)); cornerRadius = dp(10).toFloat() } }
        card.addView(img, LinearLayout.LayoutParams(-1, dp(160)).apply { bottomMargin = dp(10) })
        if (cover.isNotBlank()) ImageLoader.load(img, cover)

        card.addView(TextView(this).apply { text = "🔴 LIVE"; setTextColor(Color.rgb(200, 40, 40)); setTypeface(null, Typeface.BOLD); textSize = 12f })
        card.addView(TextView(this).apply { text = title; textSize = 16f; setTypeface(null, Typeface.BOLD); setPadding(0, dp(4), 0, dp(2)) })
        card.addView(TextView(this).apply { text = "$shop · $viewers đang xem"; textSize = 13f; setTextColor(Color.rgb(107, 107, 107)) })
        return card
    }
}
