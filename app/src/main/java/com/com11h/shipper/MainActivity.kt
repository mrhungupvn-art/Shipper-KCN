package com.com11h.shipper

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import androidx.work.*
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Màn hình duy nhất của app shipper:
 *   - Đăng nhập theo KCN ID + tài khoản shipper (bảng shipper_accounts).
 *   - "Đơn đang cầm": đơn đã nhận, chưa giao xong (shipper_my_orders).
 *       + Chưa bắt đầu giao -> nút "Bắt đầu giao" (shipper_start_delivery, sinh OTP
 *         hiển thị cho KHÁCH xem trên web, không hiện ở đây).
 *       + Đã bắt đầu giao -> ô nhập OTP khách đọc + nút xác nhận (shipper_confirm_otp).
 *   - "Đơn sẵn sàng để nhận": đơn chưa ai nhận trong KCN (shipper_available_orders)
 *       + nút "Nhận đơn" (shipper_claim_order).
 */
class MainActivity: AppCompatActivity() {
    private lateinit var api: Api
    private lateinit var session: SecureSession
    private lateinit var status: TextView
    private lateinit var codBadge: TextView
    private lateinit var myBox: LinearLayout
    private lateinit var box: LinearLayout
    private lateinit var loginPanel: LinearLayout
    private lateinit var appPanel: LinearLayout
    private lateinit var swipe: SwipeRefreshLayout
    private lateinit var kcnSpinner: Spinner
    private var kcnList: List<KcnItem> = emptyList()
    private var kcn = 1

    // Fast polling: khi app đang mở (foreground), hỏi API mỗi ~8s bằng endpoint nhẹ "shipper_ping" thay vì
    // chờ WorkManager (tối thiểu 15 phút/lần, giới hạn cứng của Android). Nếu nội dung ping đổi khác lần trước
    // -> có thay đổi (đơn mới/đổi trạng thái) -> gọi sync() để làm mới danh sách ngay trong vài giây.
    private val pingHandler = Handler(Looper.getMainLooper())
    private var pingRunnable: Runnable? = null
    private var lastPingSignature: String? = null
    private val PING_INTERVAL_MS = 8000L

    override fun onCreate(b: Bundle?) {
        super.onCreate(b); setContentView(com.com11h.shipper.R.layout.activity_main)
        session = SecureSession(this)
        status = findViewById(com.com11h.shipper.R.id.status)
        codBadge = findViewById(com.com11h.shipper.R.id.codBadge)
        myBox = findViewById(com.com11h.shipper.R.id.myOrdersBox)
        box = findViewById(com.com11h.shipper.R.id.ordersBox)
        loginPanel = findViewById(com.com11h.shipper.R.id.loginPanel)
        appPanel = findViewById(com.com11h.shipper.R.id.appPanel)
        swipe = findViewById(com.com11h.shipper.R.id.swipeRefresh)
        kcnSpinner = findViewById(com.com11h.shipper.R.id.kcnSpinner)
        loadKcnList()
        swipe.setOnRefreshListener { sync() }
        findViewById<Button>(com.com11h.shipper.R.id.loginBtn).setOnClickListener { login() }
        findViewById<Button>(com.com11h.shipper.R.id.logoutBtn).setOnClickListener { logout() }
        findViewById<Button>(com.com11h.shipper.R.id.refreshBtn).setOnClickListener { sync() }
        session.token()?.let { t ->
            kcn = session.kcnId() ?: 1
            api = Api(com.com11h.shipper.BuildConfig.API_BASE_URL, kcn, t)
            showApp("Đã đăng nhập: ${session.name().orEmpty()}")
            sync()
            startFastPolling()
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED && android.os.Build.VERSION.SDK_INT >= 33)
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 12)
        scheduleSync()
    }

    /** Tải danh sách KCN từ server (action=kcn_list, không cần đăng nhập) để đổ vào Spinner. */
    private fun loadKcnList() {
        thread {
            try {
                val list = Api.fetchKcnList(com.com11h.shipper.BuildConfig.API_BASE_URL)
                runOnUiThread {
                    kcnList = list
                    val adapter = ArrayAdapter(this, android.com.com11h.shipper.R.layout.simple_spinner_item, list)
                    adapter.setDropDownViewResource(android.com.com11h.shipper.R.layout.simple_spinner_dropdown_item)
                    kcnSpinner.adapter = adapter
                    session.kcnId()?.let { savedId ->
                        val idx = list.indexOfFirst { it.id == savedId }
                        if (idx >= 0) kcnSpinner.setSelection(idx)
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    if (loginPanel.visibility == LinearLayout.VISIBLE) {
                        status.text = "Không tải được danh sách KCN: ${e.message ?: "lỗi không xác định"}"
                    }
                }
            }
        }
    }

    private fun showApp(text: String) {
        loginPanel.visibility = LinearLayout.GONE
        appPanel.visibility = LinearLayout.VISIBLE
        status.text = text
        findViewById<TextView>(com.com11h.shipper.R.id.kcnBadge).text = "KCN #$kcn"
    }

    private fun login() {
        val selected = kcnSpinner.selectedItem as? KcnItem
        kcn = selected?.id ?: 0
        val u = findViewById<EditText>(com.com11h.shipper.R.id.username).text.toString().trim()
        val p = findViewById<EditText>(com.com11h.shipper.R.id.password).text.toString()
        if (kcn <= 0) { status.text = "Vui lòng chọn Khu công nghiệp"; return }
        if (u.isBlank() || p.isBlank()) { status.text = "Vui lòng nhập tài khoản và mật khẩu"; return }
        api = Api(com.com11h.shipper.BuildConfig.API_BASE_URL, kcn)
        status.text = "Đang đăng nhập..."
        thread {
            try {
                val j = api.call("shipper_login", JSONObject().put("username", u).put("password", p).put("device", "android"))
                val data = j.getJSONObject("data")
                val token = data.getString("token")
                val ship = data.optJSONObject("shipper")
                val name = ship?.optString("name", u) ?: u
                session.save(token, kcn, name)
                api.setToken(token)
                runOnUiThread { showApp("Đăng nhập thành công: $name"); sync(); startFastPolling() }
            } catch (e: Exception) {
                runOnUiThread { status.text = e.message ?: "Đăng nhập thất bại" }
            }
        }
    }

    private fun logout() {
        if (::api.isInitialized) thread { runCatching { api.call("shipper_logout", JSONObject()) } }
        stopFastPolling()
        session.clear()
        myBox.removeAllViews(); box.removeAllViews()
        loginPanel.visibility = LinearLayout.VISIBLE
        appPanel.visibility = LinearLayout.GONE
        status.text = "Đã đăng xuất"
    }

    /** Đồng bộ cả 2 danh sách (đang cầm + sẵn sàng nhận) trong 1 lần bấm "Đồng bộ". */
    private fun sync() {
        if (!::api.isInitialized) { swipe.isRefreshing = false; return }
        status.text = "Đang đồng bộ..."
        thread {
            try {
                val mine = api.call("shipper_my_orders")
                val mineData = mine.optJSONObject("data")
                val mineArr = mineData?.optJSONArray("orders")
                val codPending = mineData?.optInt("cod_pending_total", 0) ?: 0

                val avail = api.call("shipper_available_orders")
                val availArr = avail.optJSONObject("data")?.optJSONArray("orders")

                runOnUiThread {
                    swipe.isRefreshing = false
                    codBadge.text = "💰 Đang giữ COD: ${vnd(codPending)}"
                    myBox.removeAllViews()
                    if (mineArr == null || mineArr.length() == 0) {
                        myBox.addView(hint("Bạn chưa nhận đơn nào."))
                    } else {
                        for (i in 0 until mineArr.length()) addMyOrder(mineArr.getJSONObject(i))
                    }
                    box.removeAllViews()
                    if (availArr == null || availArr.length() == 0) {
                        box.addView(hint("Không có đơn sẵn sàng trong KCN #$kcn"))
                    } else {
                        for (i in 0 until availArr.length()) addAvailableOrder(availArr.getJSONObject(i))
                    }
                    status.text = "Đồng bộ lúc ${java.text.SimpleDateFormat("HH:mm:ss").format(java.util.Date())}"
                }
            } catch (e: UnauthorizedException) {
                runOnUiThread { swipe.isRefreshing = false; logout(); status.text = "Phiên đăng nhập hết hạn" }
            } catch (e: Exception) {
                runOnUiThread { swipe.isRefreshing = false; status.text = e.message ?: "Không thể đồng bộ" }
            }
        }
    }

    private fun hint(text: String) = TextView(this).apply { this.text = text; setPadding(0, 8, 0, 8) }

    private fun vnd(amount: Int): String = "%,d đ".format(amount).replace(',', '.')

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(20, 20, 20, 20)
        setBackgroundResource(android.R.drawable.dialog_holo_light_frame)
        val p = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        p.topMargin = 16
        layoutParams = p
    }

    private fun infoLines(card: LinearLayout, o: JSONObject) {
        card.addView(TextView(this).apply { text = "Đơn ${o.optString("code", "-")}"; textSize = 20f; setTypeface(typeface, android.graphics.Typeface.BOLD) })
        card.addView(TextView(this).apply { text = o.optString("address", "-"); textSize = 15f })
        val codAmount = o.optInt("cod_amount")
        val codText = if (codAmount > 0) "COD: ${vnd(codAmount)}" else "Đã thanh toán online (không thu COD)"
        card.addView(TextView(this).apply { text = "$codText  •  Công: ${vnd(o.optInt("shipper_fee"))}"; textSize = 15f })
        val note = o.optString("note", "")
        if (note.isNotBlank()) card.addView(TextView(this).apply { text = "Ghi chú: $note"; textSize = 13f })
    }

    private fun navButton(card: LinearLayout, o: JSONObject) {
        val nav = Button(this).apply { text = "🗺️ Chỉ đường" }
        card.addView(nav)
        nav.setOnClickListener {
            val q = Uri.encode(o.optString("address"))
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=$q")))
        }
    }

    private fun addAvailableOrder(o: JSONObject) {
        val orderId = o.optInt("order_id")
        val c = card()
        infoLines(c, o)
        val claim = Button(this).apply { text = "✅ Nhận đơn này" }
        c.addView(claim)
        claim.setOnClickListener {
            claim.isEnabled = false
            thread {
                try {
                    api.call("shipper_claim_order", JSONObject().put("order_id", orderId))
                    runOnUiThread { toast("Đã nhận đơn"); sync() }
                } catch (e: Exception) {
                    runOnUiThread { toast(e.message); claim.isEnabled = true }
                }
            }
        }
        navButton(c, o)
        box.addView(c)
    }

    private fun addMyOrder(o: JSONObject) {
        val orderId = o.optInt("order_id")
        val otpActive = o.optBoolean("otp_active", false)
        val c = card()
        infoLines(c, o)
        c.addView(TextView(this).apply { text = "Trạng thái: ${o.optString("status", "-")}"; textSize = 14f })

        if (!otpActive) {
            val start = Button(this).apply { text = "🛵 Bắt đầu giao" }
            c.addView(start)
            start.setOnClickListener {
                start.isEnabled = false
                thread {
                    try {
                        api.call("shipper_start_delivery", JSONObject().put("order_id", orderId))
                        runOnUiThread { toast("Đã bắt đầu giao. Khách sẽ thấy mã OTP trên web."); sync() }
                    } catch (e: Exception) {
                        runOnUiThread { toast(e.message); start.isEnabled = true }
                    }
                }
            }
        } else {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, 12, 0, 0) }
            val otpInput = EditText(this).apply {
                hint = "Mã OTP khách đọc"
                inputType = android.text.InputType.TYPE_CLASS_NUMBER
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            row.addView(otpInput)
            val confirm = Button(this).apply { text = "Xác nhận" }
            row.addView(confirm)
            c.addView(row)
            confirm.setOnClickListener {
                val code = otpInput.text.toString().trim()
                if (code.length < 4) { toast("OTP không hợp lệ"); return@setOnClickListener }
                confirm.isEnabled = false
                thread {
                    try {
                        api.call("shipper_confirm_otp", JSONObject().put("order_id", orderId).put("otp", code))
                        runOnUiThread { toast("Đã giao thành công"); sync() }
                    } catch (e: Exception) {
                        runOnUiThread { toast(e.message); confirm.isEnabled = true }
                    }
                }
            }
        }
        navButton(c, o)
        myBox.addView(c)
    }

    private fun toast(m: String?) { Toast.makeText(this, m ?: "Có lỗi", Toast.LENGTH_SHORT).show() }

    private fun scheduleSync() {
        val req = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork("shipper_sync", ExistingPeriodicWorkPolicy.UPDATE, req)
    }

    // --- Fast polling khi app đang mở (foreground) ---
    override fun onResume() { super.onResume(); startFastPolling() }
    override fun onPause() { super.onPause(); stopFastPolling() }

    private fun startFastPolling() {
        if (!::api.isInitialized) return
        stopFastPolling()
        val r = object : Runnable {
            override fun run() { pingOnce(); pingHandler.postDelayed(this, PING_INTERVAL_MS) }
        }
        pingRunnable = r
        pingHandler.postDelayed(r, PING_INTERVAL_MS)
    }

    private fun stopFastPolling() {
        pingRunnable?.let { pingHandler.removeCallbacks(it) }
        pingRunnable = null
    }

    // Gọi endpoint nhẹ "shipper_ping" thay vì shipper_my_orders/shipper_available_orders đầy đủ,
    // để không tải nặng server mỗi 8 giây. So sánh nội dung trả về với lần trước: nếu khác -> có
    // thay đổi (đơn mới/đổi trạng thái) -> mới gọi sync() để làm mới danh sách đầy đủ.
    private fun pingOnce() {
        if (!::api.isInitialized) return
        thread {
            try {
                val j = api.call("shipper_ping")
                val sig = (j.optJSONObject("data") ?: j).toString()
                val changed = lastPingSignature != null && sig != lastPingSignature
                lastPingSignature = sig
                if (changed) runOnUiThread { sync() }
            } catch (_: UnauthorizedException) {
                runOnUiThread { logout() }
            } catch (_: Exception) {
                // Lỗi mạng tạm thời khi ping: bỏ qua, vòng lặp 8s tiếp theo sẽ thử lại.
            }
        }
    }
}
