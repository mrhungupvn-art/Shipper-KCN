package com.com11h.app

import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.TextView
import kotlin.math.abs

/**
 * Quản lý video "📰 Tin Tức" TRONG APP, chỉ trên Trang chủ.
 *
 * Ý tưởng: chỉ giữ ĐÚNG MỘT WebView phát video trong suốt vòng đời, rồi
 * "chuyển nhà" nó (removeView ở nơi cũ -> addView ở nơi mới) giữa 2 trạng thái,
 * cả hai đều diễn ra NGAY TRÊN Trang chủ:
 *
 *   1) INLINE — nằm ngay trong khung "📰 Tin Tức", hiển thị bình thường như
 *      một module trên trang, khi khung video còn trong tầm nhìn.
 *
 *   2) BUBBLE — bong bóng nhỏ, kéo-thả tự do, "docking" đè lên góc Trang chủ
 *      khi khách đã cuộn khung video ra khỏi tầm nhìn (kiểu mini-player) —
 *      xem HomeActivity.checkNewsDockState().
 *
 * Khi khách bấm sang màn hình KHÁC trong app (Thực đơn, Giỏ hàng, Đơn hàng,
 * Tài khoản...) sẽ KHÔNG còn bong bóng nổi đè lên màn hình đó nữa — video chỉ
 * tự tạm dừng sau một khoảng ngắn (đỡ tốn pin/dữ liệu) và tự phát tiếp ngay
 * khi khách quay lại Trang chủ.
 */
object FloatingVideoManager {

    private const val SITE_URL = "https://com11h.com"

    private val handler = Handler(Looper.getMainLooper())
    private var orphanCheck: Runnable? = null

    // ---- Video đang được quản lý (tồn tại độc lập với vòng đời của mọi Activity) ----
    private var webView: WebView? = null
    private var embedUrl: String? = null

    // ---- Nơi WebView đang thực sự "cắm" tại thời điểm hiện tại ----
    private var inlineContainer: FrameLayout? = null
    private var bubbleRoot: FrameLayout? = null // lớp phủ toàn màn hình chứa bong bóng, add vào decor của 1 activity

    /** HomeActivity gán callback này để tự ẩn khối "📰 Tin Tức" khi video bị đóng hẳn (bấm ✕ trên bong bóng). */
    var onClosed: (() -> Unit)? = null

    fun isActive(): Boolean = webView != null && embedUrl != null
    fun currentEmbedUrl(): String? = embedUrl

    // =========================================================================
    // KHỞI TẠO / HUỶ VIDEO
    // =========================================================================

    /** Tạo video mới để quản lý nổi. Gọi từ HomeActivity ngay sau khi tải xong 1 video hợp lệ từ server. */
    fun start(context: Context, embedUrl: String, title: String) {
        if (this.embedUrl == embedUrl && webView != null) return // đúng video đang phát rồi, không tạo lại
        stop()
        this.embedUrl = embedUrl
        webView = WebView(context.applicationContext).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.mediaPlaybackRequiresUserGesture = false
            settings.setSupportZoom(false)
            settings.builtInZoomControls = false
            settings.displayZoomControls = false
            // Referer/Origin hợp lệ để né lỗi YouTube 153 (xem newsVideoHtml()).
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            webViewClient = WebViewClient()
            webChromeClient = WebChromeClient()
            setBackgroundColor(Color.BLACK)
            contentDescription = title
            loadDataWithBaseURL("$SITE_URL/", newsVideoHtml(embedUrl), "text/html", "utf-8", null)
        }
    }

    /** Dừng hẳn video, huỷ WebView, gỡ mọi thứ khỏi màn hình đang hiển thị. Gọi khi khách bấm ✕ trên bong bóng. */
    fun stop() {
        cancelOrphanCheck()
        removeBubbleFromWindow()
        val wv = webView
        if (wv != null) {
            (wv.parent as? ViewGroup)?.removeView(wv)
            try { wv.destroy() } catch (_: Exception) { }
        }
        webView = null
        embedUrl = null
        inlineContainer = null
        onClosed?.invoke()
    }

    private fun newsVideoHtml(embedUrl: String): String {
        val safeUrl = embedUrl.replace("\"", "&quot;")
        return """
            <!DOCTYPE html>
            <html>
            <head>
              <meta charset="utf-8">
              <meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1, user-scalable=no">
              <style>
                html, body { margin:0; padding:0; background:#000; overflow:hidden; }
                iframe { position:absolute; top:0; left:0; width:100%; height:100%; border:0; }
              </style>
            </head>
            <body>
              <iframe src="$safeUrl"
                referrerpolicy="strict-origin-when-cross-origin"
                allow="autoplay; encrypted-media; picture-in-picture"
                allowfullscreen></iframe>
            </body>
            </html>
        """.trimIndent()
    }

    // =========================================================================
    // CHẾ ĐỘ INLINE — video nằm trong khung "📰 Tin Tức" của Trang chủ
    // =========================================================================

    /** HomeActivity gọi khi khung video đang trong tầm nhìn (onResume, hoặc khi cuộn video trở lại màn hình). */
    fun attachInline(container: FrameLayout) {
        val wv = webView ?: return
        cancelOrphanCheck()
        removeBubbleFromWindow()
        val parent = wv.parent as? ViewGroup
        if (parent !== container) {
            parent?.removeView(wv)
            container.addView(wv, FrameLayout.LayoutParams(-1, -1))
        }
        inlineContainer = container
        try { wv.onResume() } catch (_: Exception) { }
    }

    /** HomeActivity gọi khi khung video ra khỏi tầm nhìn hoặc khi rời màn hình (onPause). */
    fun detachInline() {
        val wv = webView
        val c = inlineContainer
        if (wv != null && c != null && wv.parent === c) c.removeView(wv)
        inlineContainer = null
        scheduleOrphanCheck()
    }

    // =========================================================================
    // CHẾ ĐỘ BONG BÓNG NỔI — đè lên màn hình bất kỳ, kéo-thả tự do
    // =========================================================================

    /**
     * SessionActivity gọi ở onResume của MỌI Activity trong app.
     *
     * Bong bóng nổi CHỈ còn xuất hiện khi khách cuộn khung video ra khỏi tầm
     * nhìn ngay trên Trang chủ (xem HomeActivity.checkNewsDockState()) — khi
     * khách bấm sang màn hình khác (Thực đơn, Giỏ hàng, Đơn hàng, Tài khoản…)
     * KHÔNG còn bong bóng đè lên màn hình đó nữa. Video sẽ tự tạm dừng sau
     * một khoảng ngắn (xem scheduleOrphanCheck ở onActivityPaused) và tự phát
     * tiếp ngay khi khách quay lại Trang chủ.
     */
    fun onActivityResumed(activity: Activity) {
        if (activity is HomeActivity) cancelOrphanCheck()
    }

    /** SessionActivity gọi ở onPause của MỌI Activity trong app. */
    fun onActivityPaused(activity: Activity) {
        removeBubbleFromWindow()
        scheduleOrphanCheck()
    }

    /** Hiển thị bong bóng nổi đè lên [activity] hiện tại. HomeActivity cũng có thể gọi khi video bị cuộn ra khỏi màn hình. */
    fun showBubble(activity: Activity) {
        val wv = webView ?: return
        val decor = activity.window?.decorView as? ViewGroup ?: return
        if (bubbleRoot != null && bubbleRoot?.parent === decor) return // đã nổi đúng nơi rồi
        cancelOrphanCheck()
        removeBubbleFromWindow()
        (wv.parent as? ViewGroup)?.removeView(wv)
        inlineContainer = null

        val dm = activity.resources.displayMetrics
        fun dp(v: Int) = (v * dm.density).toInt()
        val bubbleW = dp(168)
        val bubbleH = dp(96)
        val margin = dp(14)

        val root = FrameLayout(activity)

        val bubble = FrameLayout(activity).apply {
            background = GradientDrawable().apply { setColor(Color.BLACK); cornerRadius = dp(14).toFloat() }
            clipToOutline = true
            elevation = dp(10).toFloat()
        }
        bubble.addView(wv, FrameLayout.LayoutParams(-1, -1))

        // Dải mờ phía trên bong bóng, nút ✕ đóng video nằm trên dải này.
        val titleBar = BubbleTitleBar(activity)
        bubble.addView(titleBar, FrameLayout.LayoutParams(-1, dp(22), Gravity.TOP))

        val closeBtn = TextView(activity).apply {
            text = "✕"
            textSize = 13f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.argb(215, 30, 30, 30)) }
            setOnClickListener { stop() }
        }
        bubble.addView(closeBtn, FrameLayout.LayoutParams(dp(22), dp(22), Gravity.TOP or Gravity.END).apply {
            topMargin = dp(4); rightMargin = dp(4)
        })

        // Lớp phủ trong suốt để bắt toàn bộ chạm cho việc kéo-thả (không cho lọt vào WebView bên dưới).
        val dragCatcher = View(activity)
        bubble.addView(dragCatcher, FrameLayout.LayoutParams(-1, -1))
        // closeBtn cần nằm TRÊN dragCatcher để ✕ luôn bấm được dù đang chồng lớp bắt-chạm.
        bubble.bringChildToFront(closeBtn)

        val lp = FrameLayout.LayoutParams(bubbleW, bubbleH, Gravity.TOP or Gravity.START).apply {
            leftMargin = dm.widthPixels - bubbleW - margin
            topMargin = dm.heightPixels / 4
        }
        root.addView(bubble, lp)
        attachDrag(activity, dragCatcher, bubble, lp, dm)

        decor.addView(root, FrameLayout.LayoutParams(-1, -1))
        bubbleRoot = root
        try { wv.onResume() } catch (_: Exception) { }
    }

    private fun attachDrag(
        activity: Activity,
        touchSurface: View,
        bubble: FrameLayout,
        lp: FrameLayout.LayoutParams,
        dm: android.util.DisplayMetrics
    ) {
        var downRawX = 0f
        var downRawY = 0f
        var startLeft = 0
        var startTop = 0
        var moved = false
        touchSurface.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = ev.rawX; downRawY = ev.rawY
                    startLeft = lp.leftMargin; startTop = lp.topMargin
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.rawX - downRawX
                    val dy = ev.rawY - downRawY
                    if (abs(dx) > 8 || abs(dy) > 8) moved = true
                    val maxLeft = (dm.widthPixels - bubble.width).coerceAtLeast(0)
                    val maxTop = (dm.heightPixels - bubble.height).coerceAtLeast(0)
                    lp.leftMargin = (startLeft + dx).toInt().coerceIn(0, maxLeft)
                    lp.topMargin = (startTop + dy).toInt().coerceIn(0, maxTop)
                    bubble.layoutParams = lp
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (!moved) {
                        // Bấm (không kéo) vào thân bong bóng -> mở lại video full trên Trang chủ,
                        // hoặc cuộn về khung video nếu đang đứng sẵn ở Trang chủ (xem openOrScrollHome()).
                        openOrScrollHome(activity)
                    } else {
                        snapToEdge(bubble, lp, dm)
                    }
                    true
                }
                else -> false
            }
        }
    }

    /** Hút bong bóng dính sát cạnh trái/phải gần nhất khi buông tay, giống bong bóng chat của Messenger. */
    private fun snapToEdge(bubble: FrameLayout, lp: FrameLayout.LayoutParams, dm: android.util.DisplayMetrics) {
        val target = if (lp.leftMargin + bubble.width / 2 < dm.widthPixels / 2) 0 else dm.widthPixels - bubble.width
        val animator = ValueAnimator.ofInt(lp.leftMargin, target)
        animator.duration = 180
        animator.addUpdateListener { a -> lp.leftMargin = a.animatedValue as Int; bubble.layoutParams = lp }
        animator.start()
    }

    /** Bấm vào bong bóng: nếu đang ở màn khác thì mở lại Trang chủ; nếu đang đứng sẵn ở Trang chủ (bong bóng docking do cuộn) thì nhờ Trang chủ tự cuộn về khung video. */
    private fun openOrScrollHome(activity: Activity) {
        if (activity is HomeActivity) {
            activity.scrollToNewsSection()
        } else {
            activity.startActivity(
                Intent(activity, HomeActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            )
        }
    }

    private fun removeBubbleFromWindow() {
        val root = bubbleRoot ?: return
        (root.parent as? ViewGroup)?.removeView(root)
        bubbleRoot = null
    }

    // =========================================================================
    // Tự tạm dừng video khi không còn Activity nào trong app giữ nó (khách đã
    // rời hẳn app), tự phát lại ngay khi có Activity kế tiếp resume và "đón".
    // =========================================================================
    private fun scheduleOrphanCheck() {
        cancelOrphanCheck()
        val r = Runnable {
            val wv = webView ?: return@Runnable
            if (wv.parent == null) { try { wv.onPause() } catch (_: Exception) { } }
        }
        orphanCheck = r
        handler.postDelayed(r, 500)
    }

    private fun cancelOrphanCheck() {
        orphanCheck?.let { handler.removeCallbacks(it) }
        orphanCheck = null
    }
}

/** Dải mờ mỏng phía trên bong bóng — tách riêng 1 View đơn giản để dễ style, không cần layout XML. */
private class BubbleTitleBar(context: Context) : View(context) {
    init { setBackgroundColor(Color.argb(90, 0, 0, 0)) }
}
