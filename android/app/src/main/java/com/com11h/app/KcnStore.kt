package com.com11h.app

import android.content.Context
import org.json.JSONObject

/**
 * Lưu KCN khách đã chọn trên thiết bị.
 *
 * Khi đổi KCN, dữ liệu cục bộ thuộc KCN cũ phải được tách khỏi KCN mới:
 * - đăng xuất token của DB/KCN cũ;
 * - xóa giỏ hàng cũ;
 * - xóa món yêu thích cũ.
 * Dữ liệu XU/đơn hàng/khách hàng không được chuyển sang KCN mới vì chúng nằm
 * trên database riêng của từng KCN.
 */
object KcnStore {
    private const val PREFS = "com11h_local"
    private const val ID = "selected_kcn_id"
    private const val NAME = "selected_kcn_name"
    private const val SECURE_PREFS = "com11h_secure"

    fun id(context: Context): Int = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(ID, 0)
    fun name(context: Context): String = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(NAME, "") ?: ""

    fun save(context: Context, kcn: JSONObject) {
        val newId = kcn.optInt("id")
        val oldId = id(context)

        if (newId > 0 && oldId > 0 && oldId != newId) {
            // Đổi KCN = chuyển tenant. Không mang phiên đăng nhập, giỏ hàng
            // hoặc món yêu thích của tenant cũ sang tenant mới.
            // Token được AccountSync lưu theo token_<kcn_id>, vì vậy không xóa
            // phiên KCN cũ khi chuyển KCN. Mỗi KCN giữ tài khoản độc lập.
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString("cart", "[]")
                .apply()
            FavoriteStore.clear(context)
        }

        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(ID, newId)
            .putString(NAME, kcn.optString("name"))
            .apply()
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(ID)
            .remove(NAME)
            .putString("cart", "[]")
            .apply()
        context.getSharedPreferences(SECURE_PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove("token")
            .remove("last_active")
            .apply()
        FavoriteStore.clear(context)
    }
}
