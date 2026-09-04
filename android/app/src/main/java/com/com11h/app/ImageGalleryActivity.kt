package com.com11h.app

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.widget.FrameLayout
import android.widget.TextView

/** Bộ xem ảnh sản phẩm: vuốt trái/phải để chuyển sang sản phẩm kế tiếp. */
class ImageGalleryActivity : SessionActivity() {
    private val images = mutableListOf<String>()
    private val titles = mutableListOf<String>()
    private var index = 0
    private lateinit var imageView: ZoomableImageView
    private lateinit var counter: TextView
    private lateinit var titleView: TextView
    private var downX = 0f
    private var downY = 0f

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        images.addAll(intent.getStringArrayListExtra("images") ?: arrayListOf())
        titles.addAll(intent.getStringArrayListExtra("titles") ?: arrayListOf())
        index = intent.getIntExtra("index", 0).coerceIn(0, (images.size - 1).coerceAtLeast(0))

        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        imageView = ZoomableImageView(this)
        root.addView(imageView, FrameLayout.LayoutParams(-1, -1))

        val hint = TextView(this).apply {
            text = "Vuốt trái/phải để xem sản phẩm khác"
            textSize = 12f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(7), dp(12), dp(7))
            setBackgroundColor(Color.argb(110, 0, 0, 0))
        }
        root.addView(hint, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(12) })

        counter = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setTypeface(null, Typeface.BOLD)
            setPadding(dp(12), dp(6), dp(12), dp(6))
            setBackgroundColor(Color.argb(130, 0, 0, 0))
        }
        root.addView(counter, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply { topMargin = dp(14); leftMargin = dp(14) })

        titleView = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 15f
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(dp(20), dp(14), dp(20), dp(14))
            setBackgroundColor(Color.argb(145, 0, 0, 0))
        }
        root.addView(titleView, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))

        root.addView(TextView(this).apply {
            text = "✕"
            textSize = 22f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setBackgroundColor(Color.argb(140, 0, 0, 0))
            setOnClickListener { finish() }
        }, FrameLayout.LayoutParams(dp(46), dp(46), Gravity.TOP or Gravity.END).apply { topMargin = dp(14); rightMargin = dp(14) })

        imageView.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x; downY = event.y
                    v.parent?.requestDisallowInterceptTouchEvent(true)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val dx = event.x - downX
                    val dy = event.y - downY
                    if (event.pointerCount <= 1 && kotlin.math.abs(dx) > dp(55) && kotlin.math.abs(dx) > kotlin.math.abs(dy) * 1.25f) {
                        if (dx < 0) next() else previous()
                    }
                    v.parent?.requestDisallowInterceptTouchEvent(false)
                }
            }
            false
        }

        setContentView(root)
        if (images.isEmpty()) {
            titleView.text = "Không có ảnh để hiển thị."
            counter.text = "0/0"
        } else {
            hint.postDelayed({ hint.animate().alpha(0f).setDuration(500).start() }, 2200)
            render()
        }
    }

    private fun render() {
        val url = images[index]
        imageView.resetZoom()
        ImageLoader.load(imageView, url)
        counter.text = "${index + 1}/${images.size}"
        titleView.text = titles.getOrNull(index).orEmpty()
        titleView.visibility = if (titleView.text.isBlank()) android.view.View.GONE else android.view.View.VISIBLE
    }

    private fun next() {
        if (index >= images.lastIndex) return
        index++
        render()
    }

    private fun previous() {
        if (index <= 0) return
        index--
        render()
    }
}
