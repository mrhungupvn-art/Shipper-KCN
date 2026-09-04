package com.com11h.app

import android.content.Context
import org.json.JSONArray

/** Lưu món yêu thích cục bộ, không cần API riêng. */
object FavoriteStore {
    private const val PREFS = "com11h_local"
    private const val KEY = "favorite_food_ids"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun ids(context: Context): MutableSet<Int> {
        val out = linkedSetOf<Int>()
        try {
            val a = JSONArray(prefs(context).getString(KEY, "[]"))
            for (i in 0 until a.length()) out.add(a.optInt(i))
        } catch (_: Exception) { }
        return out
    }

    fun contains(context: Context, id: Int): Boolean = ids(context).contains(id)

    fun toggle(context: Context, id: Int): Boolean {
        val set = ids(context)
        val added = if (set.contains(id)) { set.remove(id); false } else { set.add(id); true }
        val a = JSONArray()
        set.forEach { a.put(it) }
        prefs(context).edit().putString(KEY, a.toString()).apply()
        return added
    }

    /** Xóa danh sách món yêu thích khi khách chuyển sang một KCN khác. */
    fun clear(context: Context) {
        prefs(context).edit().remove(KEY).apply()
    }
}
