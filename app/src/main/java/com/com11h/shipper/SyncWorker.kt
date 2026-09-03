package com.com11h.shipper

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import android.app.NotificationChannel
import android.app.NotificationManager

class SyncWorker(appContext: Context, params: WorkerParameters): CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val session = SecureSession(applicationContext)
        val token = session.token() ?: return Result.success()
        val kcn = session.kcnId() ?: return Result.success()
        return try {
            val api = Api(BuildConfig.API_BASE_URL, kcn, token)
            val json = api.call("shipper_available_orders")
            val orders = json.optJSONObject("data")?.optJSONArray("orders")
            val ids = buildList {
                if (orders != null) for (i in 0 until orders.length()) {
                    add(orders.optJSONObject(i)?.optInt("order_id", 0)?.toString() ?: "0")
                }
            }.filter { it != "0" }.sorted().joinToString(",")
            val prefs = applicationContext.getSharedPreferences("sync", Context.MODE_PRIVATE)
            val previous = prefs.getString("available_ids", null)
            if (previous != null && ids != previous) {
                val old = previous.split(',').filter { it.isNotBlank() }.toSet()
                val current = ids.split(',').filter { it.isNotBlank() }.toSet()
                val added = current.count { it !in old }
                if (added > 0) notifyNewOrders(added)
            }
            prefs.edit().putString("available_ids", ids).apply()
            Result.success()
        } catch (_: UnauthorizedException) { Result.success() }
        catch (_: Exception) { Result.retry() }
    }
    private fun notifyNewOrders(delta: Int) {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("orders", "Đơn mới", NotificationManager.IMPORTANCE_HIGH))
        val notification = NotificationCompat.Builder(applicationContext, "orders")
            .setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle("Có đơn mới")
            .setContentText("Có $delta đơn mới trong pool KCN").setPriority(NotificationCompat.PRIORITY_HIGH).setAutoCancel(true).build()
        NotificationManagerCompat.from(applicationContext).notify(1101, notification)
    }
}
