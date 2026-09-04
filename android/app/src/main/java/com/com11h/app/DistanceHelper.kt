package com.com11h.app

import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Tính khoảng cách giao hàng khi backend (order_preview trên
 * com11h.com/api/index.php) CHƯA trả về distance_km — dùng làm phương án dự
 * phòng ngay trên app, không phụ thuộc server.
 *
 * Cách làm: quy đổi địa chỉ quán và địa chỉ khách sang toạ độ (geocode) bằng
 * dịch vụ miễn phí Nominatim (OpenStreetMap), rồi tính khoảng cách đường
 * chim bay (Haversine). Nếu server đã tự tính distance_km riêng (chính xác
 * hơn, theo đường đi thực tế) thì MainActivity luôn ưu tiên dùng giá trị đó
 * và bỏ qua lớp này.
 *
 * Lưu ý: đây là khoảng cách đường chim bay (ước lượng), không phải khoảng
 * cách đi xe thực tế — chỉ dùng để chặn sơ bộ các địa chỉ quá xa quán.
 */
object DistanceHelper {
    // Địa chỉ quán — điểm gốc để tính khoảng cách giao hàng.
    const val STORE_ADDRESS = "Ngô Gia Tự, Phường Trung An, Mỹ Tho, Tỉnh Đồng Tháp 84118, Việt Nam"
    const val MAX_DELIVERY_KM = 15.0

    // Cache toạ độ quán sau lần geocode đầu tiên (địa chỉ quán cố định).
    @Volatile private var storeCoords: Pair<Double, Double>? = null

    private fun geocode(address: String): Pair<Double, Double>? {
        return try {
            val q = URLEncoder.encode(address, "UTF-8")
            val url = URL("https://nominatim.openstreetmap.org/search?format=json&limit=1&countrycodes=vn&q=$q")
            val c = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 10000
                readTimeout = 10000
                // Nominatim yêu cầu User-Agent hợp lệ cho mỗi request.
                setRequestProperty("User-Agent", "Com11hApp/1.0 (delivery-distance-check)")
            }
            val code = c.responseCode
            val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }
            c.disconnect()
            val arr = JSONArray(text ?: "[]")
            if (arr.length() == 0) return null
            val o = arr.getJSONObject(0)
            Pair(o.getString("lat").toDouble(), o.getString("lon").toDouble())
        } catch (_: Exception) { null }
    }

    private fun haversineKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        return r * 2 * atan2(sqrt(a), sqrt(1 - a))
    }

    /**
     * Trả về khoảng cách (km) từ quán tới địa chỉ khách, hoặc null nếu
     * không geocode được (địa chỉ quá mơ hồ, sai định dạng, hoặc lỗi mạng).
     * PHẢI gọi trên luồng nền (executor), không gọi trên main thread vì có
     * request mạng.
     */
    fun distanceFromStoreKm(customerAddress: String): Double? {
        val store = storeCoords ?: geocode(STORE_ADDRESS)?.also { storeCoords = it } ?: return null
        val customer = geocode(customerAddress) ?: return null
        return haversineKm(store.first, store.second, customer.first, customer.second)
    }
}
