package com.com11h.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.widget.ImageView
import kotlin.math.abs

/**
 * ImageView hỗ trợ phóng to/thu nhỏ bằng cử chỉ chụm/mở 2 ngón tay, kéo ảnh
 * khi đã phóng to, và double-tap để zoom nhanh — viết tay bằng Matrix, không
 * phụ thuộc thư viện ngoài (đồng nhất với ImageLoader). Dùng để khách xem ảnh
 * banner trang chủ phóng to ngay trong app ở BannerViewActivity.
 */
class ZoomableImageView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : ImageView(context, attrs) {

    private val matrixValues = FloatArray(9)
    private val imgMatrix = Matrix()
    private var scale = 1f
    private val minScale = 1f
    private val maxScale = 5f

    private var lastX = 0f
    private var lastY = 0f
    private var isDragging = false

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val prev = scale
            scale = (scale * detector.scaleFactor).coerceIn(minScale, maxScale)
            val factor = scale / prev
            imgMatrix.postScale(factor, factor, detector.focusX, detector.focusY)
            fixTranslation()
            imageMatrix = imgMatrix
            return true
        }
    })

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (scale > minScale + 0.05f) {
                resetZoom()
            } else {
                scale = 2.5f
                imgMatrix.postScale(2.5f, 2.5f, e.x, e.y)
                fixTranslation()
                imageMatrix = imgMatrix
            }
            return true
        }
        override fun onDown(e: MotionEvent) = true
    })

    init { scaleType = ScaleType.MATRIX }

    /** Đưa ảnh về trạng thái vừa khung ban đầu (bỏ zoom). */
    fun resetZoom() {
        scale = 1f
        centerImage()
        imageMatrix = imgMatrix
    }

    private fun centerImage() {
        val d = drawable ?: return
        val vw = width.toFloat(); val vh = height.toFloat()
        val dw = d.intrinsicWidth.toFloat(); val dh = d.intrinsicHeight.toFloat()
        if (vw <= 0 || vh <= 0 || dw <= 0 || dh <= 0) return
        val s = minOf(vw / dw, vh / dh)
        imgMatrix.reset()
        imgMatrix.postScale(s, s)
        imgMatrix.postTranslate((vw - dw * s) / 2f, (vh - dh * s) / 2f)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        scale = 1f; centerImage(); imageMatrix = imgMatrix
    }

    override fun setImageBitmap(bm: Bitmap?) {
        super.setImageBitmap(bm)
        scale = 1f
        post { centerImage(); imageMatrix = imgMatrix }
    }

    private fun fixTranslation() {
        val d = drawable ?: return
        imgMatrix.getValues(matrixValues)
        val transX = matrixValues[Matrix.MTRANS_X]
        val transY = matrixValues[Matrix.MTRANS_Y]
        val curScale = matrixValues[Matrix.MSCALE_X]
        val dw = d.intrinsicWidth * curScale
        val dh = d.intrinsicHeight * curScale
        var fixX = 0f; var fixY = 0f
        when {
            dw <= width -> fixX = (width - dw) / 2f - transX
            transX > 0 -> fixX = -transX
            transX < width - dw -> fixX = (width - dw) - transX
        }
        when {
            dh <= height -> fixY = (height - dh) / 2f - transY
            transY > 0 -> fixY = -transY
            transY < height - dh -> fixY = (height - dh) - transY
        }
        imgMatrix.postTranslate(fixX, fixY)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> { lastX = event.x; lastY = event.y; isDragging = false }
            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount == 1 && scale > minScale + 0.01f) {
                    val dx = event.x - lastX; val dy = event.y - lastY
                    if (!isDragging && (abs(dx) > 6 || abs(dy) > 6)) isDragging = true
                    if (isDragging) {
                        imgMatrix.postTranslate(dx, dy)
                        fixTranslation()
                        imageMatrix = imgMatrix
                        lastX = event.x; lastY = event.y
                    }
                } else { lastX = event.x; lastY = event.y }
            }
        }
        parent?.requestDisallowInterceptTouchEvent(scale > minScale + 0.01f || isDragging)
        return true
    }
}
