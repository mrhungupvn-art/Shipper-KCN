package com.com11h.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.widget.ImageView
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Bộ tải ảnh nhẹ, không phụ thuộc thư viện ngoài (Glide/Picasso), dùng để
 * hiển thị ảnh món ăn và mã QR thanh toán lấy trực tiếp từ URL trả về bởi
 * API (com11h.com). Có cache trong bộ nhớ để tránh tải lại nhiều lần.
 */
object ImageLoader {
    private val cache = LruCache<String, Bitmap>(40)
    private val executor = Executors.newFixedThreadPool(3)
    private val handler = Handler(Looper.getMainLooper())

    fun load(view: ImageView, url: String?, onLoaded: (() -> Unit)? = null) {
        view.tag = url
        if (url.isNullOrBlank()) { view.setImageDrawable(null); return }
        cache.get(url)?.let { view.setImageBitmap(it); onLoaded?.invoke(); return }
        view.setImageDrawable(null)
        executor.execute {
            try {
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 10000; readTimeout = 10000
                }
                val bmp = BitmapFactory.decodeStream(conn.inputStream)
                conn.disconnect()
                if (bmp != null) {
                    cache.put(url, bmp)
                    handler.post { if (view.tag == url) { view.setImageBitmap(bmp); onLoaded?.invoke() } }
                }
            } catch (_: Exception) { /* ảnh lỗi -> giữ nguyên trống, không làm crash app */ }
        }
    }
}
