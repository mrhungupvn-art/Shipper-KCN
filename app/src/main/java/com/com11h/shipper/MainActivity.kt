package com.com11h.shipper

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.Gravity
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
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
    private lateinit var statsBox: TextView
    private lateinit var historyBox: LinearLayout
    private lateinit var loadMoreHistoryBtn: Button
    private var kcnList: List<KcnItem> = emptyList()
    private var kcn = 1

    // Lịch sử giao hàng: phân trang bằng offset, tải thêm 20 đơn mỗi lần bấm
    // "Xem thêm" thay vì tải hết 1 lần (shipper lâu năm có thể có hàng ngàn đơn).
    private val HISTORY_PAGE_SIZE = 20
    private var historyOffset = 0
    private var historyLoading = false

    // Fast polling: khi app đang mở (foreground), hỏi API mỗi ~8s bằng endpoint nhẹ "shipper_ping" thay vì
    // chờ WorkManager (tối thiểu 15 phút/lần, giới hạn cứng của Android). Nếu nội dung ping đổi khác lần trước
    // -> có thay đổi (đơn mới/đổi trạng thái) -> gọi sync() để làm mới danh sách ngay trong vài giây.
    private val pingHandler = Handler(Looper.getMainLooper())
    private var pingRunnable: Runnable? = null
    private var lastPingSignature: String? = null
    // Số đơn "sẵn sàng nhận" (avail_count) ở lần ping gần nhất — dùng để phát
    // hiện có ĐƠN MỚI thật sự xuất hiện (avail_count TĂNG), tách biệt với
    // lastPingSignature ở trên (đổi cho MỌI thay đổi, kể cả khi 1 đơn vừa bị
    // shipper khác nhận mất khỏi danh sách — không nên rung chuông lúc đó).
    private var lastAvailCount: Int? = null
    private val PING_INTERVAL_MS = 8000L
    private val NEW_ORDER_CHANNEL_ID = "shipper_new_orders"
    private val NEW_ORDER_NOTIF_ID = 3001

    override fun onCreate(b: Bundle?) {
        super.onCreate(b); setContentView(R.layout.activity_main)
        session = SecureSession(this)
        status = findViewById(R.id.status)
        codBadge = findViewById(R.id.codBadge)
        myBox = findViewById(R.id.myOrdersBox)
        box = findViewById(R.id.ordersBox)
        loginPanel = findViewById(R.id.loginPanel)
        appPanel = findViewById(R.id.appPanel)
        swipe = findViewById(R.id.swipeRefresh)
        kcnSpinner = findViewById(R.id.kcnSpinner)
        statsBox = findViewById(R.id.statsBox)
        historyBox = findViewById(R.id.historyBox)
        loadMoreHistoryBtn = findViewById(R.id.loadMoreHistoryBtn)
        loadKcnList()
        swipe.setOnRefreshListener { sync() }
        findViewById<Button>(R.id.loginBtn).setOnClickListener { login() }
        findViewById<Button>(R.id.logoutBtn).setOnClickListener { logout() }
        findViewById<Button>(R.id.refreshBtn).setOnClickListener { sync() }
        loadMoreHistoryBtn.setOnClickListener { loadHistory(reset = false) }
        session.token()?.let { t ->
            kcn = session.kcnId() ?: 1
            api = Api(BuildConfig.API_BASE_URL, kcn, t)
            showApp("Đã đăng nhập: ${session.name().orEmpty()}")
            sync()
            startFastPolling()
        }
        createNewOrderNotificationChannel()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED && android.os.Build.VERSION.SDK_INT >= 33)
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 12)
        scheduleSync()
    }

    /** Tải danh sách KCN từ server (action=kcn_list, không cần đăng nhập) để đổ vào Spinner. */
    private fun loadKcnList() {
        thread {
            try {
                val list = Api.fetchKcnList(BuildConfig.API_BASE_URL)
                runOnUiThread {
                    kcnList = list
                    val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, list)
                    adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
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
        findViewById<TextView>(R.id.kcnBadge).text = "KCN #$kcn"
    }

    private fun login() {
        val selected = kcnSpinner.selectedItem as? KcnItem
        kcn = selected?.id ?: 0
        val u = findViewById<EditText>(R.id.username).text.toString().trim()
        val p = findViewById<EditText>(R.id.password).text.toString()
        if (kcn <= 0) { status.text = "Vui lòng chọn Khu công nghiệp"; return }
        if (u.isBlank() || p.isBlank()) { status.text = "Vui lòng nhập tài khoản và mật khẩu"; return }
        api = Api(BuildConfig.API_BASE_URL, kcn)
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
        lastPingSignature = null; lastAvailCount = null
        loginPanel.visibility = LinearLayout.VISIBLE
        appPanel.visibility = LinearLayout.GONE
        status.text = "Đã đăng xuất"
    }

    /** Đồng bộ cả 2 danh sách (đang cầm + sẵn sàng nhận) trong 1 lần bấm "Đồng bộ". */
    private fun sync() {
        if (!::api.isInitialized) { swipe.isRefreshing = false; return }
        status.text = "Đang đồng bộ..."
        // Thống kê + lịch sử tải RIÊNG, không chặn danh sách đơn chính ở trên —
        // lỗi ở 2 mục này (vd mạng chập chờn) không nên làm hỏng luồng nhận/giao đơn.
        loadStats()
        loadHistory(reset = true)
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
        val phone = o.optString("phone", "").trim()
        val phoneAvailable = o.optBoolean("phone_available", phone.isNotBlank())
        if (phoneAvailable && phone.isNotBlank()) {
            val phoneRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 8, 0, 0)
            }
            phoneRow.addView(TextView(this).apply {
                text = "📞 Khách: $phone"
                textSize = 15f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            phoneRow.addView(Button(this).apply {
                text = "Gọi"
                setOnClickListener {
                    try {
                        val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:${Uri.encode(phone)}"))
                        startActivity(intent)
                    } catch (e: Exception) {
                        toast("Không thể mở cuộc gọi")
                    }
                }
            })
            card.addView(phoneRow)
        }
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

    /** Tải mục "📊 Thống kê của tôi": số đơn + thù lao hôm nay/tuần/tháng/tổng cộng. */
    private fun loadStats() {
        if (!::api.isInitialized) return
        thread {
            try {
                val j = api.call("shipper_stats")
                val d = j.optJSONObject("data") ?: JSONObject()
                fun line(label: String, key: String): String {
                    val b = d.optJSONObject(key) ?: return "$label: -"
                    return "$label: ${b.optInt("count")} đơn • ${vnd(b.optInt("payout"))}"
                }
                val text = listOf(
                    line("Hôm nay", "today"),
                    line("Tuần này", "week"),
                    line("Tháng này", "month"),
                    line("Tổng cộng", "all_time")
                ).joinToString("\n")
                runOnUiThread { statsBox.text = text }
            } catch (_: UnauthorizedException) {
                runOnUiThread { logout() }
            } catch (e: Exception) {
                runOnUiThread { statsBox.text = "Không tải được thống kê: ${e.message ?: "lỗi không xác định"}" }
            }
        }
    }

    /**
     * Tải mục "🕘 Lịch sử giao hàng". reset=true (gọi từ sync()) xoá danh sách
     * cũ và tải lại từ đầu; reset=false (bấm nút "Xem thêm") nối thêm trang kế
     * tiếp vào cuối danh sách đang hiện, dùng historyOffset đang có.
     */
    private fun loadHistory(reset: Boolean) {
        if (!::api.isInitialized || historyLoading) return
        if (reset) historyOffset = 0
        historyLoading = true
        val offset = historyOffset
        thread {
            try {
                val j = api.call("shipper_history", query = "&limit=$HISTORY_PAGE_SIZE&offset=$offset")
                val d = j.optJSONObject("data")
                val arr = d?.optJSONArray("orders")
                val hasMore = d?.optBoolean("has_more", false) ?: false
                runOnUiThread {
                    if (reset) historyBox.removeAllViews()
                    if (arr == null || arr.length() == 0) {
                        if (reset) historyBox.addView(hint("Chưa có đơn nào đã giao xong."))
                    } else {
                        for (i in 0 until arr.length()) addHistoryItem(arr.getJSONObject(i))
                        historyOffset = offset + arr.length()
                    }
                    loadMoreHistoryBtn.visibility = if (hasMore) LinearLayout.VISIBLE else LinearLayout.GONE
                    historyLoading = false
                }
            } catch (_: UnauthorizedException) {
                runOnUiThread { historyLoading = false; logout() }
            } catch (e: Exception) {
                runOnUiThread {
                    historyLoading = false
                    if (reset) historyBox.addView(hint("Không tải được lịch sử: ${e.message ?: "lỗi không xác định"}"))
                    else toast(e.message)
                }
            }
        }
    }

    private fun addHistoryItem(o: JSONObject) {
        val orderId = o.optInt("order_id")
        val c = card()
        c.addView(TextView(this).apply { text = "Đơn ${o.optString("code", "-")}"; textSize = 16f; setTypeface(typeface, android.graphics.Typeface.BOLD) })
        c.addView(TextView(this).apply { text = o.optString("address", "-"); textSize = 13f })
        c.addView(TextView(this).apply { text = "Giao lúc: ${o.optString("delivered_at", "-")}"; textSize = 13f })
        val codAmount = o.optInt("cod_amount")
        val codText = if (codAmount > 0) "Đã thu COD: ${vnd(codAmount)}" else "Không thu COD (đã thanh toán online)"
        c.addView(TextView(this).apply { text = "$codText  •  Công: ${vnd(o.optInt("payout_amount"))}"; textSize = 13f })
        val del = Button(this).apply { text = "🗑️ Xoá khỏi lịch sử" }
        c.addView(del)
        del.setOnClickListener {
            del.isEnabled = false
            thread {
                try {
                    api.call("shipper_hide_history", JSONObject().put("order_id", orderId))
                    runOnUiThread { toast("Đã xoá khỏi lịch sử"); loadHistory(reset = true) }
                } catch (e: Exception) {
                    runOnUiThread { toast(e.message); del.isEnabled = true }
                }
            }
        }
        historyBox.addView(c)
    }

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
                val data = j.optJSONObject("data") ?: JSONObject()
                val sig = data.toString()
                val changed = lastPingSignature != null && sig != lastPingSignature
                lastPingSignature = sig

                // 🔔 ĐƠN MỚI SẴN SÀNG NHẬN: chỉ rung chuông + phát âm thanh khi
                // avail_count THẬT SỰ TĂNG so với lần ping trước. Không dùng
                // "changed" ở trên vì nó đổi cho MỌI thay đổi (kể cả khi 1 đơn
                // vừa bị shipper khác nhận mất khỏi danh sách, hoặc đơn "đang
                // cầm" của chính mình đổi trạng thái) — sẽ báo nhầm.
                val availCount = data.optInt("avail_count", -1)
                if (availCount >= 0) {
                    val prev = lastAvailCount
                    if (prev != null && availCount > prev) {
                        runOnUiThread { notifyNewOrder(availCount) }
                    }
                    lastAvailCount = availCount
                }

                if (changed) runOnUiThread { sync() }
            } catch (_: UnauthorizedException) {
                runOnUiThread { logout() }
            } catch (_: Exception) {
                // Lỗi mạng tạm thời khi ping: bỏ qua, vòng lặp 8s tiếp theo sẽ thử lại.
            }
        }
    }

    /** Tạo kênh thông báo "Đơn hàng mới" (âm thanh + rung) — cần gọi trước khi notify trên Android 8+. */
    private fun createNewOrderNotificationChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        val pattern = longArrayOf(0, 400, 200, 400)
        val channel = NotificationChannel(NEW_ORDER_CHANNEL_ID, "Đơn hàng mới", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Thông báo khi có đơn hàng mới sẵn sàng để nhận"
            enableVibration(true)
            vibrationPattern = pattern
            enableLights(true)
            val audioAttrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION), audioAttrs)
        }
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    /** 🔔 ĐƠN HÀNG MỚI — hiện thông báo hệ thống + rung + phát âm thanh (kể cả khi app đang mở). */
    private fun notifyNewOrder(availCount: Int) {
        vibrateNewOrder()
        playNewOrderSound()
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
        val contentIntent = PendingIntent.getActivity(this, 0, openIntent, flags)
        val notif = NotificationCompat.Builder(this, NEW_ORDER_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("🔔 ĐƠN HÀNG MỚI")
            .setContentText(if (availCount > 1) "Có $availCount đơn sẵn sàng để nhận" else "Có đơn mới sẵn sàng để nhận")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
            .setVibrate(longArrayOf(0, 400, 200, 400))
            .build()
        runCatching { NotificationManagerCompat.from(this).notify(NEW_ORDER_NOTIF_ID, notif) }
    }

    private fun vibrateNewOrder() {
        val pattern = longArrayOf(0, 400, 200, 400)
        runCatching {
            if (Build.VERSION.SDK_INT >= 31) {
                val vm = getSystemService(VibratorManager::class.java)
                vm?.defaultVibrator?.vibrate(VibrationEffect.createWaveform(pattern, -1))
            } else {
                @Suppress("DEPRECATION")
                val v = getSystemService(VIBRATOR_SERVICE) as? Vibrator
                if (Build.VERSION.SDK_INT >= 26) v?.vibrate(VibrationEffect.createWaveform(pattern, -1))
                else @Suppress("DEPRECATION") v?.vibrate(pattern, -1)
            }
        }
    }

    private fun playNewOrderSound() {
        runCatching {
            val uri = RingtoneManager.getActualDefaultRingtoneUri(this, RingtoneManager.TYPE_NOTIFICATION)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            RingtoneManager.getRingtone(this, uri)?.play()
        }
    }
}
