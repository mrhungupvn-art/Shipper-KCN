package com.com11h.app

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import android.widget.TextView
import java.util.concurrent.Executors
import kotlin.math.abs

/**
 * Xem ảnh (banner / món ăn) PHÓNG TO ngay trong app, và VUỐT SANG TRÁI/PHẢI để
 * chuyển qua ảnh khác trong CÙNG danh sách (banner khác, hoặc món ăn khác) —
 * đúng món/đúng tiêu đề luôn đi theo đúng ảnh đang xem, không lệch nhau.
 *
 * Khi đang phóng to (chụm 2 ngón tay) hoặc đang kéo ảnh đã phóng to, vuốt sẽ
 * dùng để DI CHUYỂN trong ảnh như trước — chỉ khi ảnh đang ở kích thước gốc
 * (chưa zoom) thì vuốt ngang mới được hiểu là "chuyển ảnh" (xem ghi chú trong
 * ZoomableImageView.onTouchEvent(): nó tự nhả quyền chặn chạm cho View cha
 * đúng lúc ảnh không còn zoom/kéo, nên logic vuốt-chuyển-ảnh ở dưới không bao
 * giờ tranh chấp với zoom/pan).
 *
 * Nhận vào qua Intent extras:
 *   - "images": ArrayList<String> — danh sách URL ảnh trong bộ này (bắt buộc để vuốt được)
 *   - "titles": ArrayList<String> — tiêu đề tương ứng từng ảnh (cùng độ dài với "images")
 *   - "bannerIds": ArrayList<Int>  — (tuỳ chọn) id banner tương ứng từng ảnh, để tự báo
 *     lượt xem (banner_click.php) mỗi khi vuốt sang 1 banner khác, giống hệt khi bấm vào nó
 *   - "index": Int — vị trí ảnh được bấm vào ban đầu
 *   - "image" / "title" (tương thích cũ): dùng khi chỉ có đúng 1 ảnh, không vuốt được
 */
class BannerViewActivity : SessionActivity() {
    companion object { private const val SITE_URL = "https://com11h.com" }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private val executor = Executors.newSingleThreadExecutor()

    private var images: List<String> = emptyList()
    private var titles: List<String> = emptyList()
    private var bannerIds: List<Int> = emptyList()
    private var index = 0
    private val pingedBannerIds = mutableSetOf<Int>()

    private lateinit var imageView: ZoomableImageView
    private lateinit var loadingLabel: TextView
    private lateinit var titleLabel: TextView
    private lateinit var counterLabel: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        images = intent.getStringArrayListExtra("images")?.filterNotNull()?.filter { it.isNotBlank() }
            ?: intent.getStringExtra("image")?.takeIf { it.isNotBlank() }?.let { listOf(it) }
            ?: emptyList()
        titles = intent.getStringArrayListExtra("titles")?.filterNotNull()
            ?: intent.getStringExtra("title")?.let { listOf(it) }
            ?: emptyList()
        bannerIds = intent.getIntegerArrayListExtra("bannerIds")?.filterNotNull() ?: emptyList()
        index = intent.getIntExtra("index", 0).coerceIn(0, (images.size - 1).coerceAtLeast(0))

        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }

        val stage = SwipeStage(this)
        root.addView(stage, FrameLayout.LayoutParams(-1, -1))

        imageView = ZoomableImageView(this)
        stage.addView(imageView, FrameLayout.LayoutParams(-1, -1))

        loadingLabel = TextView(this).apply {
            text = "Đang tải ảnh..."
            setTextColor(Color.WHITE)
            textSize = 14f
            gravity = Gravity.CENTER
        }
        root.addView(loadingLabel, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))

        titleLabel = TextView(this).apply {
            textSize = 15f
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(dp(20), dp(14), dp(20), dp(14))
            setBackgroundColor(Color.argb(140, 0, 0, 0))
            visibility = View.GONE
        }
        root.addView(titleLabel, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))

        // Chấm chỉ vị trí "2/6" — chỉ hiện khi có từ 2 ảnh trở lên, để khách biết còn vuốt được nữa.
        counterLabel = TextView(this).apply {
            textSize = 12.5f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(5), dp(12), dp(5))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.argb(150, 0, 0, 0)); cornerRadius = dp(20).toFloat()
            }
        }
        root.addView(counterLabel, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(16) })

        if (images.size > 1) {
            root.addView(arrowButton("‹") { goPrev() }, FrameLayout.LayoutParams(dp(46), dp(46), Gravity.CENTER_VERTICAL or Gravity.START).apply { leftMargin = dp(8) })
            root.addView(arrowButton("›") { goNext() }, FrameLayout.LayoutParams(dp(46), dp(46), Gravity.CENTER_VERTICAL or Gravity.END).apply { rightMargin = dp(8) })
        }

        root.addView(TextView(this).apply {
            text = "✕"
            textSize = 22f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setBackgroundColor(Color.argb(140, 0, 0, 0))
            setOnClickListener { finish() }
        }, FrameLayout.LayoutParams(dp(46), dp(46), Gravity.TOP or Gravity.END).apply { topMargin = dp(14); rightMargin = dp(14) })

        setContentView(root)
        showCurrent(animateIn = false)
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }

    private fun arrowButton(symbol: String, onClick: () -> Unit) = TextView(this).apply {
        text = symbol
        textSize = 26f
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        background = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL
            setColor(Color.argb(110, 0, 0, 0))
        }
        setOnClickListener { onClick() }
    }

    private fun goNext() {
        if (index >= images.size - 1) return
        index++
        showCurrent(animateIn = true, fromRight = true)
    }

    private fun goPrev() {
        if (index <= 0) return
        index--
        showCurrent(animateIn = true, fromRight = false)
    }

    /** Hiện đúng ảnh + tiêu đề ở vị trí [index], có hiệu ứng trượt nhẹ khi chuyển ảnh. */
    private fun showCurrent(animateIn: Boolean, fromRight: Boolean = true) {
        if (images.isEmpty()) {
            loadingLabel.text = "Không có ảnh để hiển thị."
            return
        }
        val imageUrl = images.getOrNull(index) ?: return
        val title = titles.getOrNull(index) ?: ""

        titleLabel.text = title
        titleLabel.visibility = if (title.isNotBlank()) View.VISIBLE else View.GONE
        counterLabel.visibility = if (images.size > 1) View.VISIBLE else View.GONE
        counterLabel.text = "${index + 1}/${images.size}"

        loadingLabel.visibility = View.VISIBLE
        loadingLabel.text = "Đang tải ảnh..."
        imageView.resetZoom()

        val doLoad = {
            ImageLoader.load(imageView, imageUrl) { loadingLabel.visibility = View.GONE }
        }

        if (animateIn) {
            val outDx = if (fromRight) -dp(60).toFloat() else dp(60).toFloat()
            imageView.animate().translationX(outDx).alpha(0f).setDuration(110)
                .setListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        doLoad()
                        imageView.translationX = -outDx
                        imageView.animate().translationX(0f).alpha(1f).setDuration(150).setListener(null).start()
                    }
                }).start()
        } else {
            doLoad()
        }

        pingBannerClickIfNeeded()
    }

    /** Báo lượt xem banner (giống hệt lúc bấm vào banner) khi vuốt sang 1 banner khác lần đầu. */
    private fun pingBannerClickIfNeeded() {
        val id = bannerIds.getOrNull(index) ?: return
        if (id <= 0 || !pingedBannerIds.add(id)) return
        executor.execute {
            try {
                (java.net.URL("$SITE_URL/banner_click.php?id=$id").openConnection() as java.net.HttpURLConnection).apply {
                    connectTimeout = 8000; readTimeout = 8000; requestMethod = "GET"
                }.inputStream.close()
            } catch (_: Exception) { }
        }
    }

    /**
     * FrameLayout chứa ảnh, tự nhận biết vuốt ngang để CHUYỂN ẢNH — nhưng chỉ khi
     * ZoomableImageView bên trong đang KHÔNG zoom/kéo (nó tự gọi
     * requestDisallowInterceptTouchEvent(false) lúc đó, xem ghi chú đầu file).
     */
    private inner class SwipeStage(context: android.content.Context) : FrameLayout(context) {
        private var downX = 0f
        private var downY = 0f
        private var swiping = false
        private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

        override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = ev.x; downY = ev.y; swiping = false }
                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.x - downX; val dy = ev.y - downY
                    if (!swiping && abs(dx) > touchSlop * 2.2f && abs(dx) > abs(dy) * 1.4f) {
                        swiping = true
                        return true
                    }
                }
            }
            return false
        }

        override fun onTouchEvent(ev: MotionEvent): Boolean {
            when (ev.actionMasked) {
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val dx = ev.x - downX
                    if (swiping) {
                        if (dx <= -dp(60)) goNext() else if (dx >= dp(60)) goPrev()
                    }
                    swiping = false
                }
            }
            return true
        }
    }
}
