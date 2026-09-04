package com.com11h.app

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast

/**
 * Activity nền tảng dùng chung cho toàn bộ màn hình trong app.
 *
 * Nếu khách đã đăng nhập nhưng không có thao tác trong hơn 5 phút:
 * - Tự động xoá phiên đăng nhập.
 * - Đưa khách về Trang chủ.
 * - Hiển thị thông báo yêu cầu đăng nhập lại.
 *
 * Mỗi lần khách chạm/bấm trong app, onUserInteraction() sẽ cập nhật
 * thời điểm hoạt động cuối cùng.
 */
abstract class SessionActivity : Activity() {
    private lateinit var sessionAccount: AccountSync
    private val sessionHandler = Handler(Looper.getMainLooper())
    private var sessionWatchdog: Runnable? = null

    companion object {
        /** Kiểm tra phiên mỗi 15 giây để tự đăng xuất ngay khi đủ 5 phút. */
        private const val WATCHDOG_INTERVAL_MS = 15_000L
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sessionAccount = AccountSync(this)
    }

    override fun onResume() {
        super.onResume()

        // Kiểm tra trước khi touch() để việc mở lại app sau hơn 5 phút
        // không vô tình gia hạn phiên đã hết hạn.
        if (checkSessionExpiry()) return

        sessionAccount.touch()
        startSessionWatchdog()

        // Video "📰 Tin Tức" nổi (nếu đang phát): nếu màn hình này KHÔNG phải
        // Trang chủ, tự "đón" bong bóng nổi sang đè lên màn hình này — xem
        // FloatingVideoManager để biết vì sao KHÔNG dùng Picture-in-Picture
        // của hệ điều hành.
        FloatingVideoManager.onActivityResumed(this)
    }

    override fun onPause() {
        stopSessionWatchdog()
        // Gỡ bong bóng nổi khỏi màn hình này trước khi rời đi, để nó có thể
        // "chuyển nhà" sang màn hình kế tiếp (hoặc tạm dừng nếu khách rời hẳn app).
        FloatingVideoManager.onActivityPaused(this)
        super.onPause()
    }

    private fun checkSessionExpiry(): Boolean {
        if (!(sessionAccount.isLoggedIn() && sessionAccount.isSessionExpired())) {
            return false
        }

        sessionAccount.logout()

        Toast.makeText(
            this,
            "Phiên đăng nhập đã hết hạn do không hoạt động quá 5 phút. Vui lòng đăng nhập lại.",
            Toast.LENGTH_LONG
        ).show()

        if (this !is HomeActivity) {
            startActivity(
                Intent(this, HomeActivity::class.java)
                    .addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_CLEAR_TASK
                    )
            )
            finish()
        } else {
            onSessionExpired()
        }

        return true
    }

    /** Cho Activity con cập nhật giao diện sau khi tự động đăng xuất. */
    protected open fun onSessionExpired() {}

    /** Theo dõi phiên khi app đang mở và khách không thao tác. */
    private fun startSessionWatchdog() {
        stopSessionWatchdog()

        val runnable = object : Runnable {
            override fun run() {
                if (isFinishing || isDestroyed) return

                if (!checkSessionExpiry()) {
                    sessionHandler.postDelayed(this, WATCHDOG_INTERVAL_MS)
                }
            }
        }

        sessionWatchdog = runnable
        sessionHandler.postDelayed(runnable, WATCHDOG_INTERVAL_MS)
    }

    private fun stopSessionWatchdog() {
        sessionWatchdog?.let {
            sessionHandler.removeCallbacks(it)
        }
        sessionWatchdog = null
    }

    /**
     * Mỗi lần khách chạm/bấm bất kỳ đâu trong Activity, kể cả WebView,
     * thời gian chờ 5 phút sẽ được tính lại từ đầu.
     */
    override fun onUserInteraction() {
        super.onUserInteraction()
        sessionAccount.touch()
    }
}
