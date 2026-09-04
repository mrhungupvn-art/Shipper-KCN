package com.com11h.app

import android.content.Context
import org.json.JSONArray

/**
 * Đọc nhanh tổng số lượng món trong giỏ hàng cục bộ (lưu ở SharedPreferences
 * "com11h_local", key "cart" — cùng nơi MainActivity lưu giỏ hàng) để hiển
 * thị số lượng (badge đỏ) trên icon 🛒 Giỏ hàng ở thanh điều hướng.
 *
 * Dùng ở HomeActivity (nơi không trực tiếp thao tác giỏ hàng, chỉ cần đọc lại
 * mỗi khi quay về trang chủ). MainActivity giữ giỏ hàng trong bộ nhớ (biến
 * `cart`) nên tự tính tổng trực tiếp, không cần qua lớp này.
 */
object CartStore {
    fun totalQty(context: Context): Int {
        val p = context.getSharedPreferences("com11h_local", Context.MODE_PRIVATE)
        return try {
            val arr = JSONArray(p.getString("cart", "[]"))
            var total = 0
            for (i in 0 until arr.length()) total += arr.getJSONObject(i).optInt("qty")
            total
        } catch (_: Exception) { 0 }
    }
}
