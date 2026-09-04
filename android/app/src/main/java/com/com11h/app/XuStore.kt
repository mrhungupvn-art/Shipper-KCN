package com.com11h.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Bản thử nghiệm XU. Khi API xu_balance/xu_watch đã có trên server, MainActivity ưu tiên server. */
object XuStore {
    private const val PREFS = "com11h_xu_test"
    private const val BALANCE = "balance"
    private const val DAY = "day"
    private const val DAY_XU = "day_xu"
    private const val HOUR = "hour"
    private const val HOUR_XU = "hour_xu"
    private const val WATCHED = "watched"
    private const val BONUS_10 = "bonus10"

    data class State(val balance: Int, val dayXu: Int, val hourXu: Int, val watched: Int)

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun dayKey() = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
    private fun hourKey() = java.text.SimpleDateFormat("yyyy-MM-dd-HH", java.util.Locale.US).format(java.util.Date())

    private fun normalize(c: Context) {
        val p = prefs(c)
        val d = dayKey(); val h = hourKey()
        if (p.getString(DAY, "") != d) p.edit().putString(DAY, d).putInt(DAY_XU, 0).putInt(BALANCE, 0).putInt(BONUS_10, 0).apply()
        if (p.getString(HOUR, "") != h) p.edit().putString(HOUR, h).putInt(HOUR_XU, 0).apply()
    }

    fun state(c: Context): State { normalize(c); val p = prefs(c); return State(p.getInt(BALANCE,0), p.getInt(DAY_XU,0), p.getInt(HOUR_XU,0), JSONArray(p.getString(WATCHED,"[]") ?: "[]").length()) }

    fun watched(c: Context, productId: Int): Boolean {
        normalize(c); val a = JSONArray(prefs(c).getString(WATCHED,"[]") ?: "[]")
        for (i in 0 until a.length()) if (a.optInt(i) == productId) return true
        return false
    }

    /** Local demo only: 10 XU after 30s, then +100 XU at 10 unique products. */
    fun reward(c: Context, productId: Int): Int {
        normalize(c); val p = prefs(c)
        if (watched(c, productId)) return 0
        val day = p.getInt(DAY_XU, 0); val hour = p.getInt(HOUR_XU, 0)
        if (day >= 2000 || hour >= 200) return 0
        val a = JSONArray(p.getString(WATCHED,"[]") ?: "[]"); a.put(productId)
        var add = minOf(10, 200 - hour, 2000 - day)
        var watchedCount = a.length()
        var bonus = 0
        val alreadyBonuses = p.getInt(BONUS_10, 0)
        if (watchedCount >= 10 && watchedCount / 10 > alreadyBonuses) {
            bonus = minOf(100, 200 - hour - add, 2000 - day - add)
            if (bonus > 0) p.edit().putInt(BONUS_10, alreadyBonuses + 1).apply()
        }
        val total = add + bonus
        p.edit().putString(WATCHED, a.toString()).putInt(BALANCE, p.getInt(BALANCE,0)+total)
            .putInt(DAY_XU, day+total).putInt(HOUR_XU, hour+total).apply()
        return total
    }

    fun redeem(c: Context, xu: Int): Boolean {
        normalize(c); val p = prefs(c); val b = p.getInt(BALANCE,0); if (xu <= 0 || b < xu) return false
        p.edit().putInt(BALANCE, b-xu).apply(); return true
    }
}
