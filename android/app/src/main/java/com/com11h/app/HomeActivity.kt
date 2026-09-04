package com.com11h.app

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.animation.AnimationUtils
import android.view.inputmethod.EditorInfo
import android.widget.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors

/**
 * Trang chủ COM11H. "Món ăn phổ biến" lấy trực tiếp từ api?action=menu (cùng
 * dữ liệu với web) qua AccountSync — không còn danh sách món ăn giả lập.
 * Banner ở giữa trang và ô tìm kiếm cũng đồng bộ trực tiếp với server:
 *   - Banner: lấy từ api?action=banners, cùng dữ liệu Admin > Banner trang
 *     chủ đang quản lý cho web (admin/banners.php) — đổi banner trên Admin
 *     là app tự cập nhật theo, không cần sửa code app.
 *   - Ô tìm kiếm: có nút bấm 🔍 (và bấm "Tìm kiếm" trên bàn phím) để mở
 *     màn Thực đơn và lọc sẵn theo từ khoá đã nhập.
 */
/**
 * FrameLayout tự tính chiều cao = bề rộng × (heightRatio / widthRatio) ngay
 * trong onMeasure — luôn khớp đúng khung mỗi lần layout (xoay màn hình, đổi
 * kích thước...), không phụ thuộc timing của post{}/callback nên khung
 * module (bo góc, nền đen) và khung trình phát video (WebView phủ kín bên
 * trong) không bao giờ bị lệch pixel với nhau.
 */
class AspectRatioFrameLayout @JvmOverloads constructor(
    context: android.content.Context,
    private val widthRatio: Float = 16f,
    private val heightRatio: Float = 9f
) : FrameLayout(context) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = (width * heightRatio / widthRatio).toInt()
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY))
    }
}

class HomeActivity : SessionActivity() {
    private val handler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var account: AccountSync
    private var selectedKcnId: Int = 0
    private var selectedKcnName: String = ""
    private val primary = Color.rgb(56, 142, 60)
    private val primaryDark = Color.rgb(27, 94, 32)
    private val accent = Color.rgb(129, 199, 132)
    private val bgColor = Color.rgb(247, 255, 248)
    private val text = Color.rgb(38, 38, 38)
    private val secondary = Color.rgb(107, 107, 107)
    // Badge số lượng trên icon 🛒 Giỏ hàng ở thanh điều hướng — cập nhật mỗi khi
    // dựng lại trang chủ và mỗi khi quay lại trang chủ từ màn hình khác (onResume).
    private var cartBadge: TextView? = null
    // Icon 👤 Tài khoản ở góc trên header — đổi màu nền + có chấm xanh khi khách
    // đã đăng nhập, để phân biệt rõ với lúc chưa đăng nhập.
    private var profileIcon: TextView? = null
    private var profileDot: View? = null
    // Khung chứa "Món ăn phổ biến" — giữ lại tham chiếu để có thể tải & xáo lại
    // danh sách món mỗi khi khách quay lại trang chủ (onResume), không chỉ lúc
    // dựng trang lần đầu.
    private var popularBox: LinearLayout? = null
    // "Menu Vip" — dải ảnh món ăn giá trên 40.000đ tự trôi từ phải qua trái.
    // vipRow chứa 2 bản sao danh sách món nối liền nhau để cuộn lặp vô tận
    // (mượt mà, không giật khi quay vòng); vipScrollView là khung cuộn cho
    // phép khách chạm để dừng và tự vuốt qua vuốt lại.
    private var vipScrollView: HorizontalScrollView? = null
    private var vipRow: LinearLayout? = null
    private var vipAutoScrollRunnable: Runnable? = null
    private var vipUserTouching = false
    private var vipSingleSetWidth = 0
    // Danh sách Vip KHÔNG lặp đôi (chỉ 1 bản), dùng để vuốt xem ảnh lần lượt đúng thứ tự món.
    private var vipUniqueList: List<JSONObject> = emptyList()
    // "Tin Tức" — module video YouTube thời sự lấy từ api?action=news_video,
    // cùng bề rộng với "Menu Vip", chiều cao tự tính theo tỉ lệ 16:9 của
    // video. Admin đổi video (bật/tắt) trên admin/news_videos.php thì app tự
    // cập nhật theo, không cần sửa code/cập nhật APK.
    // WebView phát video KHÔNG còn được HomeActivity giữ trực tiếp nữa — nó
    // do FloatingVideoManager quản lý dùng chung xuyên suốt app (xem file đó
    // để biết cơ chế "video docking + bong bóng nổi" tự viết trong app, thay
    // cho Picture-in-Picture của hệ điều hành).
    private var newsSectionRef: LinearLayout? = null
    // Khung chứa video (bên trong newsSectionRef) — nơi FloatingVideoManager
    // "cắm" WebView vào mỗi khi video còn nằm trong tầm nhìn trên Trang chủ.
    private var newsContainerRef: FrameLayout? = null
    // true khi Admin đang bật 1 video hợp lệ VÀ đã tải/hiển thị thành công —
    // chỉ cho phép docking/nổi bong bóng khi có video thật đang phát.
    private var newsVideoReady = false
    // ScrollView chính của Trang chủ — theo dõi để biết khung "📰 Tin Tức"
    // còn nằm trong tầm nhìn hay đã bị cuộn khuất (quyết định video nên hiển
    // thị inline hay "docking" thành bong bóng nhỏ), và để cuộn mượt về lại
    // đúng vị trí khi khách bấm vào bong bóng lúc đang đứng sẵn ở Trang chủ.
    private var homeScrollRef: ScrollView? = null

    companion object { private const val SITE_URL = "https://com11h.com" }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun bg(color: Int, radius: Int = 18) = GradientDrawable().apply { setColor(color); cornerRadius = dp(radius).toFloat() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); account = AccountSync(this); selectedKcnId = KcnStore.id(this); selectedKcnName = KcnStore.name(this)
        // Video bị đóng hẳn (khách bấm ✕ trên bong bóng nổi, ở bất kỳ màn hình
        // nào) -> ẩn lại khối "📰 Tin Tức" trên Trang chủ như lúc chưa có video.
        FloatingVideoManager.onClosed = {
            runOnUiThread { if (!isFinishing && !isDestroyed) { newsSectionRef?.visibility = View.GONE; newsVideoReady = false } }
        }
        showSplash()
    }
    override fun onDestroy() { handler.removeCallbacksAndMessages(null); executor.shutdownNow(); super.onDestroy() }
    // Khách có thể đã thêm/bớt món ở màn Thực đơn hoặc Giỏ hàng, hoặc vừa đăng
    // nhập/đăng xuất, rồi bấm Back quay lại đây (không tạo lại Activity) — cập
    // nhật badge giỏ hàng, icon tài khoản và xáo lại "Món ăn phổ biến" để mỗi
    // lần quay về trang chủ khách luôn thấy các món khác nhau.
    override fun onResume() {
        super.onResume(); refreshCartBadge(); refreshProfileIcon(); loadPopularFoods()
        vipAutoScrollRunnable?.let { handler.post(it) }
        // Quay lại Trang chủ (kể cả khi video đang nổi bong bóng ở màn hình
        // khác) -> nếu khung video đang trong tầm nhìn thì "đòi" WebView về
        // hiển thị inline như bình thường; nếu đang cuộn khuất thì để nó tiếp
        // tục docking dạng bong bóng ngay trên Trang chủ.
        checkNewsDockState()
    }
    // Dừng dải "Menu Vip" tự trôi khi rời màn hình (đỡ tốn pin/CPU khi không hiển thị).
    // KHÔNG tạm dừng video ở đây: gỡ khung inline (detachInline) rồi để
    // FloatingVideoManager tự lo — video sẽ "chuyển nhà" mượt sang bong bóng
    // nổi ở màn hình kế tiếp trong app, hoặc tự tạm dừng nếu khách rời hẳn
    // khỏi app (xem FloatingVideoManager).
    override fun onPause() {
        vipAutoScrollRunnable?.let { handler.removeCallbacks(it) }
        FloatingVideoManager.detachInline()
        super.onPause()
    }
    // Phiên bị hết hạn NGAY trên Trang chủ (khách đứng yên quá lâu) -> icon 👤
    // đang hiện chấm xanh "đã đăng nhập" cần được cập nhật lại ngay lập tức.
    override fun onSessionExpired() { refreshProfileIcon() }

    /**
     * Kiểm tra khung "📰 Tin Tức" có đang nằm trong tầm nhìn của khách trên
     * Trang chủ hay không, để quyết định video nên hiển thị INLINE (ngay
     * trong khung, nếu còn thấy được) hay "docking" thành BONG BÓNG NỔI nhỏ
     * đè lên góc màn hình (nếu khách đã cuộn video ra khỏi tầm nhìn) — cả hai
     * trường hợp đều đang đứng ngay tại Trang chủ, không phải rời màn hình.
     */
    private fun checkNewsDockState() {
        if (!newsVideoReady || !FloatingVideoManager.isActive()) return
        val container = newsContainerRef ?: return
        val scroll = homeScrollRef ?: return
        val loc = IntArray(2); container.getLocationOnScreen(loc)
        val scrollLoc = IntArray(2); scroll.getLocationOnScreen(scrollLoc)
        val visibleTop = scrollLoc[1]
        val visibleBottom = visibleTop + scroll.height
        val containerTop = loc[1]
        val containerBottom = containerTop + container.height
        val threshold = dp(24)
        val mostlyOutOfView = containerBottom <= visibleTop + threshold || containerTop >= visibleBottom - threshold
        if (mostlyOutOfView) FloatingVideoManager.showBubble(this) else FloatingVideoManager.attachInline(container)
    }

    /**
     * FloatingVideoManager gọi khi khách bấm vào bong bóng nổi trong lúc đang
     * đứng SẴN ở Trang chủ (video đã "docking" do cuộn ra khỏi màn hình) —
     * cuộn mượt về lại đúng vị trí khung video thay vì mở lại Trang chủ (đằng
     * nào cũng đang đứng ở đây rồi).
     */
    fun scrollToNewsSection() {
        val scroll = homeScrollRef ?: return
        val section = newsSectionRef ?: return
        scroll.post { scroll.smoothScrollTo(0, section.top) }
    }

    /** Cập nhật số lượng (badge đỏ) trên icon 🛒 Giỏ hàng ở thanh điều hướng, đọc từ giỏ hàng cục bộ đã lưu. */
    private fun refreshCartBadge() {
        val n = CartStore.totalQty(this)
        cartBadge?.apply {
            text = if (n > 99) "99+" else n.toString()
            visibility = if (n > 0) android.view.View.VISIBLE else android.view.View.GONE
        }
    }

    /** Cập nhật màu nền + chấm trạng thái trên icon 👤 Tài khoản theo việc khách đã đăng nhập hay chưa. */
    private fun refreshProfileIcon() {
        val loggedIn = account.isLoggedIn()
        profileIcon?.background = bg(if (loggedIn) Color.rgb(224, 247, 233) else Color.rgb(238, 238, 238), 22)
        profileDot?.visibility = if (loggedIn) android.view.View.VISIBLE else android.view.View.GONE
    }

    private fun showSplash() {
        val root = FrameLayout(this)
        root.background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(primary, accent, Color.rgb(232, 245, 233)))
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(dp(28), dp(30), dp(28), dp(30)) }
        box.addView(ImageView(this).apply { setImageResource(R.drawable.com11h_logo); scaleType = ImageView.ScaleType.FIT_CENTER }, LinearLayout.LayoutParams(dp(230), dp(230)))
        box.addView(TextView(this).apply { text = "Cơm 11h"; textSize = 48f; setTextColor(Color.WHITE); setTypeface(null, Typeface.BOLD_ITALIC); gravity = Gravity.CENTER })
        box.addView(TextView(this).apply { text = "xin chào quý khách ❤️"; textSize = 27f; setTextColor(Color.WHITE); setTypeface(null, Typeface.BOLD); gravity = Gravity.CENTER; setPadding(0, dp(3), 0, dp(20)) })
        box.addView(TextView(this).apply { text = "Ngon mỗi ngày • Nóng hổi • Giao tận nơi"; textSize = 15f; setTextColor(Color.WHITE); gravity = Gravity.CENTER })
        root.addView(box, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER))
        setContentView(root)
        handler.postDelayed({ if (selectedKcnId > 0) showHome() else showKcnSelection() }, 3000)
    }

    private fun label(value: String, size: Float, color: Int = text, bold: Boolean = false) = TextView(this).apply { text = value; textSize = size; setTextColor(color); if (bold) setTypeface(null, Typeface.BOLD) }

    /**
     * Bong bóng nhắc nhở nổi ở GÓC PHẢI DƯỚI màn hình, chỉ có ở Trang chủ (do
     * chỉ được gắn trong shell() của HomeActivity) — nhắc khách "xem món để
     * nhận Xu", giống cơ chế "xem sản phẩm nhận xu" quen thuộc. Bấm vào mở
     * ngay Thực đơn để khách bắt đầu xem món (xem đủ 30 giây/món được +10 Xu,
     * xem đủ 10 món khác nhau được thưởng thêm 100 Xu — theo XuStore/README
     * XU). Tự ẩn nếu khách đã đạt giới hạn Xu trong giờ/ngày, để không nhắc
     * nhở vô ích khi không thể nhận thêm Xu nữa.
     */
    private fun xuReminderBubble(): View {
        val st = XuStore.state(this)
        if (st.hourXu >= 200 || st.dayXu >= 2000) return View(this).apply { visibility = View.GONE }

        val bubble = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(8), dp(14), dp(8))
            background = GradientDrawable().apply {
                setColor(Color.rgb(56, 142, 60)); cornerRadius = dp(30).toFloat()
                setStroke(dp(1), Color.rgb(27, 94, 32))
            }
            elevation = dp(7).toFloat()
            isClickable = true; isFocusable = true
            setOnClickListener { open("menu") }
        }
        val coin = TextView(this).apply {
            text = "🪙"; textSize = 19f; gravity = Gravity.CENTER
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.rgb(200, 230, 201)) }
        }
        bubble.addView(coin, LinearLayout.LayoutParams(dp(36), dp(36)).apply { marginEnd = dp(8) })
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(TextView(this).apply { text = "Xem món nhận Xu"; textSize = 12.5f; setTypeface(null, Typeface.BOLD); setTextColor(Color.WHITE) })
        col.addView(TextView(this).apply { text = "Xem 30s +10 Xu"; textSize = 10f; setTextColor(Color.WHITE) })
        bubble.addView(col)

        // Nhấp nháy phóng to/thu nhỏ nhẹ ở icon xu để thu hút sự chú ý, không
        // làm dịch chuyển bố cục xung quanh (chỉ animate chính icon).
        coin.startAnimation(android.view.animation.ScaleAnimation(
            1f, 1.12f, 1f, 1.12f,
            android.view.animation.Animation.RELATIVE_TO_SELF, 0.5f,
            android.view.animation.Animation.RELATIVE_TO_SELF, 0.5f
        ).apply {
            duration = 650
            repeatMode = android.view.animation.Animation.REVERSE
            repeatCount = android.view.animation.Animation.INFINITE
        })
        return bubble
    }

    private fun shell(): LinearLayout {
        val outer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(bgColor) }
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(14), dp(9), dp(14), dp(8)); setBackgroundColor(Color.WHITE) }
        header.addView(ImageView(this).apply { setImageResource(R.drawable.com11h_logo); scaleType = ImageView.ScaleType.FIT_CENTER }, LinearLayout.LayoutParams(dp(48), dp(48)))
        header.addView(label("Food KCN", 20f, primary, true), LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(8) })
        header.addView(TextView(this).apply {
            text = "📍 ${selectedKcnName.ifBlank { "Chọn KCN" }}"; textSize = 10.5f; gravity = Gravity.CENTER; setTextColor(primary)
            background = bg(Color.rgb(232,245,233), 12); setPadding(dp(6),0,dp(6),0); setOnClickListener { showKcnSelection() }
        }, LinearLayout.LayoutParams(dp(112), dp(40)).apply { marginEnd = dp(6) })
        val profileCell = FrameLayout(this)
        profileIcon = TextView(this).apply { text = "👤"; textSize = 20f; gravity = Gravity.CENTER; setTextColor(primary); setOnClickListener { open("profile") } }
        profileCell.addView(profileIcon, FrameLayout.LayoutParams(dp(44), dp(44)))
        profileDot = View(this).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.rgb(46, 125, 50)); setStroke(dp(2), Color.WHITE) }
            visibility = android.view.View.GONE
        }
        profileCell.addView(profileDot, FrameLayout.LayoutParams(dp(13), dp(13), Gravity.BOTTOM or Gravity.END).apply { bottomMargin = dp(3); rightMargin = dp(3) })
        refreshProfileIcon()
        header.addView(profileCell, LinearLayout.LayoutParams(dp(44), dp(44)))
        outer.addView(header)

        val scroll = ScrollView(this)
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(12), dp(14), dp(20)) }
        scroll.addView(content)
        // Theo dõi khách cuộn Trang chủ để tự động docking/undocking video
        // "📰 Tin Tức" — xem checkNewsDockState().
        scroll.setOnScrollChangeListener { _, _, _, _, _ -> checkNewsDockState() }
        homeScrollRef = scroll
        outer.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        val nav = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setBackgroundColor(Color.WHITE); elevation = dp(8).toFloat() }
        listOf("⌂\nTrang chủ", "▦\nThực đơn", "🛒\nGiỏ hàng", "▤\nĐơn hàng", "♙\nTài khoản").forEachIndexed { i, name ->
            val cell = FrameLayout(this)
            cell.addView(TextView(this).apply { text = name; textSize = 10.5f; gravity = Gravity.CENTER; setTextColor(if (i == 0) primary else secondary); setTypeface(null, if (i == 0) Typeface.BOLD else Typeface.NORMAL); setPadding(0, dp(5), 0, dp(5)); setOnClickListener { when (i) { 0 -> showHome(); 1 -> open("menu"); 2 -> open("cart"); 3 -> open("orders"); 4 -> open("profile") } } }, FrameLayout.LayoutParams(-1, -1))
            if (i == 2) {
                cartBadge = TextView(this).apply {
                    textSize = 10f; setTextColor(Color.WHITE); gravity = Gravity.CENTER; setTypeface(null, Typeface.BOLD)
                    background = bg(Color.rgb(220, 38, 38), 20)
                    visibility = android.view.View.GONE
                }
                cell.addView(cartBadge, FrameLayout.LayoutParams(dp(18), dp(18), Gravity.TOP or Gravity.END).apply { topMargin = dp(2); marginEnd = dp(14) })
            }
            nav.addView(cell, LinearLayout.LayoutParams(0, dp(58), 1f))
        }
        outer.addView(nav)
        refreshCartBadge()
        return outer
    }

    /** Mở màn Thực đơn, lọc sẵn theo từ khoá tìm kiếm đã nhập ở trang chủ. */
    private fun runSearch(keyword: String) {
        val q = keyword.trim()
        val i = Intent(this, MainActivity::class.java).putExtra("screen", "menu")
        if (q.isNotEmpty()) i.putExtra("query", q)
        startActivity(i)
    }

    private fun searchBox(): LinearLayout {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val input = EditText(this).apply {
            hint = "Tìm món ăn..."
            textSize = 15f
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setPadding(dp(14), 0, dp(14), 0)
            background = bg(Color.WHITE, 14)
            setOnEditorActionListener { v, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEARCH) { runSearch(v.text.toString()); true } else false
            }
        }
        row.addView(input, LinearLayout.LayoutParams(0, dp(46), 1f))
        row.addView(TextView(this).apply {
            text = "🔍"
            textSize = 18f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = bg(primary, 14)
            contentDescription = "Tìm kiếm"
            setOnClickListener { runSearch(input.text.toString()) }
        }, LinearLayout.LayoutParams(dp(46), dp(46)).apply { marginStart = dp(8) })
        return row
    }

    /** Banner tĩnh dự phòng khi Admin chưa tạo banner nào hoặc chưa tải được dữ liệu. */
    private fun staticBanner(): LinearLayout {
        val banner = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(17), dp(16), dp(12), dp(16)); background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(primaryDark, primary, accent)).apply { cornerRadius = dp(20).toFloat() } }
        val copy = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        copy.addView(label("Cơm ngon\nmỗi ngày", 25f, Color.WHITE, true)); copy.addView(label("Ngon – Sạch – Nhanh", 13f, Color.WHITE).apply { setPadding(0, dp(5), 0, 0) })
        banner.addView(copy, LinearLayout.LayoutParams(0, -2, 1f)); banner.addView(ImageView(this).apply { setImageResource(R.drawable.com11h_logo) }, LinearLayout.LayoutParams(dp(100), dp(88)))
        return banner
    }

    /**
     * Mở màn hình xem ảnh PHÓNG TO, có thể VUỐT sang ảnh khác trong CÙNG danh
     * sách [images]/[titles] (đúng món/tiêu đề luôn đi theo đúng ảnh).
     * [bannerIds] chỉ truyền khi đây là danh sách banner (để tự báo lượt xem
     * mỗi khi vuốt sang banner khác, giống hệt lúc bấm vào banner đó).
     */
    private fun openImages(images: List<String>, titles: List<String>, startIndex: Int, bannerIds: List<Int>? = null) {
        if (images.isEmpty()) return
        val i = Intent(this, BannerViewActivity::class.java)
            .putStringArrayListExtra("images", ArrayList(images))
            .putStringArrayListExtra("titles", ArrayList(titles))
            .putExtra("index", startIndex.coerceIn(0, images.size - 1))
        if (bannerIds != null) i.putIntegerArrayListExtra("bannerIds", ArrayList(bannerIds))
        startActivity(i)
    }

    /**
     * Mở ngay màn "Chi tiết món" (MainActivity.showFoodDetail) — hiện đầy đủ
     * ảnh + tên + giá + mô tả món ngay lập tức, để hệ thống bắt đầu tính thời
     * gian xem (đủ 30 giây được +10 XU). Dùng chung cho MỌI nơi khách bấm vào
     * món ăn ở Trang chủ (cả ảnh lẫn phần chữ), dù đang ở "Menu Vip" hay "Món
     * ăn phổ biến" — khớp với hành vi bấm vào món trong màn "Thực đơn".
     */
    private fun openFoodDetail(f: JSONObject) {
        val i = Intent(this, MainActivity::class.java).putExtra("screen", "food_detail")
        i.putExtra("food_id", f.optInt("id"))
        i.putExtra("food_name", f.optString("name"))
        i.putExtra("food_price", f.optInt("price"))
        i.putExtra("food_stock", f.optInt("stock"))
        i.putExtra("food_category", f.optString("category"))
        i.putExtra("food_description", f.optString("description"))
        i.putExtra("food_image", f.optString("image"))
        startActivity(i)
    }

    /** Tải banner trang chủ từ api?action=banners (đồng bộ Admin > Banner trang chủ) và hiển thị dạng slider tự chạy. */
    private fun loadBanners(container: FrameLayout) {
        executor.execute {
            val r = try { account.request("banners") } catch (_: Exception) { null }
            runOnUiThread {
                val arr = r?.optJSONObject("data")?.optJSONArray("banners") ?: JSONArray()
                if (r == null || !r.optBoolean("ok") || arr.length() == 0) {
                    container.removeAllViews(); container.addView(staticBanner(), FrameLayout.LayoutParams(-1, dp(120)))
                    return@runOnUiThread
                }

                val flipper = ViewFlipper(this).apply {
                    inAnimation = AnimationUtils.loadAnimation(this@HomeActivity, android.R.anim.fade_in)
                    outAnimation = AnimationUtils.loadAnimation(this@HomeActivity, android.R.anim.fade_out)
                    flipInterval = 4000
                }
                val bannerImages = mutableListOf<String>()
                val bannerTitles = mutableListOf<String>()
                val bannerIdList = mutableListOf<Int>()
                for (i in 0 until arr.length()) {
                    val b = arr.getJSONObject(i)
                    val id = b.optInt("id")
                    val title = b.optString("title")
                    val imageUrl = b.optString("image")
                    bannerImages.add(imageUrl); bannerTitles.add(title); bannerIdList.add(id)
                    val slide = FrameLayout(this)
                    val img = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
                    slide.addView(img, FrameLayout.LayoutParams(-1, -1))
                    if (title.isNotBlank()) {
                        slide.addView(TextView(this).apply {
                            text = title; textSize = 13f; setTextColor(Color.WHITE); setTypeface(null, Typeface.BOLD)
                            setPadding(dp(14), dp(8), dp(14), dp(8))
                            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Color.TRANSPARENT, Color.argb(160, 0, 0, 0)))
                        }, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
                    }
                    slide.clipToOutline = true
                    slide.background = bg(Color.rgb(240, 250, 241), 20)
                    val tapIndex = i
                    slide.setOnClickListener { openImages(bannerImages, bannerTitles, tapIndex, bannerIdList) }
                    flipper.addView(slide)
                    ImageLoader.load(img, b.optString("image"))
                }
                container.removeAllViews()
                container.addView(flipper, FrameLayout.LayoutParams(-1, dp(120)))
                if (arr.length() > 1) flipper.startFlipping()
            }
        }
    }

    /**
     * Tải "Món ăn phổ biến" từ api?action=menu và hiển thị NGẪU NHIÊN 6 món
     * (xáo trộn lại toàn bộ danh sách món đang bán mỗi lần gọi hàm này) — nhờ
     * vậy mỗi lần khách mở lại trang chủ (kể cả bấm Back từ Thực đơn/Giỏ hàng
     * quay về, xem onResume) sẽ thấy các món khác nhau, không cố định mãi
     * cùng vài món như trước (trước đây luôn lấy đúng 4 món đầu danh sách).
     */
    private fun loadPopularFoods() {
        val popularBox = this.popularBox ?: return
        popularBox.removeAllViews()
        val popularLoading = label("⏳ Đang tải món ăn...", 15f, secondary)
        popularBox.addView(popularLoading)
        executor.execute {
            val r = account.request("menu", query = mapOf("kcn_id" to selectedKcnId.toString()))
            runOnUiThread {
                if (popularBox != this.popularBox) return@runOnUiThread // màn hình đã đổi/hủy trong lúc chờ tải
                popularBox.removeView(popularLoading)
                val arr = r.optJSONObject("data")?.optJSONArray("foods") ?: JSONArray()
                if (arr.length() == 0) { popularBox.addView(label("Chưa có món nào đang bán.", 15f, secondary)); return@runOnUiThread }
                val list = mutableListOf<JSONObject>()
                for (i in 0 until arr.length()) list.add(arr.getJSONObject(i))
                list.shuffle()
                val count = minOf(6, list.size)
                for (i in 0 until count) {
                    val f = list[i]
                    val name = f.optString("name")
                    val imageUrl = f.optString("image")
                    val card = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(12), dp(12), dp(12), dp(12)); background = bg(Color.WHITE, 16); layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) } }
                    // Bấm vào món ăn (ẢNH và cả PHẦN CHỮ) mở ngay "Chi tiết món" để
                    // khách thấy đầy đủ ảnh + thông tin món và bắt đầu tính thời
                    // gian xem để tích XU — không còn mở màn xem ảnh rời hay nhảy
                    // thẳng sang Thực đơn nữa (giữ hành vi giống hệt nhau dù bấm
                    // vào ảnh hay vào tên/giá món).
                    val img = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; background = bg(Color.rgb(240, 250, 241), 14); clipToOutline = true }
                    card.addView(img, LinearLayout.LayoutParams(dp(68), dp(68))); ImageLoader.load(img, imageUrl)
                    img.setOnClickListener { openFoodDetail(f) }
                    val info = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(10), 0, 0, 0) }
                    info.addView(label(name, 17f, text, true)); info.addView(label(String.format("%,d", f.optInt("price")).replace(',', '.') + "đ", 16f, primary, true)); info.addView(label("còn ${f.optInt("stock")} phần", 13f, secondary)); card.addView(info, LinearLayout.LayoutParams(0, -2, 1f))
                    info.setOnClickListener { openFoodDetail(f) }
                    popularBox.addView(card)
                }
            }
        }
    }

    /**
     * Tải danh sách món cho "Menu Vip" (chỉ lấy món giá trên 50.000đ) từ cùng
     * api?action=menu, rồi dựng dải ảnh nằm ngang tự trôi. Nối 2 bản sao danh
     * sách liền nhau trong vipRow để khi cuộn hết bản 1 thì lặp lại y hệt bản
     * 2 — tạo cảm giác trôi vô tận không bị giật/khựng lại.
     */
    private fun loadVipCarousel() {
        val row = vipRow ?: return
        row.removeAllViews()
        row.addView(label("⏳ Đang tải...", 14f, secondary).apply { setPadding(dp(4), dp(10), dp(4), dp(10)) })
        executor.execute {
            val r = try { account.request("menu", query = mapOf("kcn_id" to selectedKcnId.toString())) } catch (_: Exception) { null }
            runOnUiThread {
                if (row != this.vipRow) return@runOnUiThread // màn hình đã đổi/hủy trong lúc chờ tải
                row.removeAllViews()
                val arr = r?.optJSONObject("data")?.optJSONArray("foods") ?: JSONArray()
                val list = mutableListOf<JSONObject>()
                for (i in 0 until arr.length()) { val f = arr.getJSONObject(i); if (f.optInt("price") > 40000) list.add(f) }
                if (list.isEmpty()) {
                    row.addView(label("Chưa có món Vip (trên 40.000đ).", 14f, secondary).apply { setPadding(dp(4), dp(10), dp(4), dp(10)) })
                    return@runOnUiThread
                }
                list.shuffle()
                vipUniqueList = list
                // Thêm đúng 2 lần cùng danh sách để cuộn lặp liền mạch.
                repeat(2) { list.forEach { f -> row.addView(vipCard(f)) } }
                row.post {
                    vipSingleSetWidth = row.width / 2
                    startVipAutoScroll()
                }
            }
        }
    }

    /**
     * Một thẻ ảnh món trong dải "Menu Vip": ảnh + tên + giá. Bấm vào bất kỳ
     * đâu trên thẻ (ảnh lẫn tên/giá) đều mở ngay "Chi tiết món" — hiện đầy đủ
     * ảnh + thông tin món để bắt đầu tính thời gian xem tích XU, giống hệt
     * hành vi bấm vào món ở "Món ăn phổ biến" và ở màn "Thực đơn". Việc thêm
     * vào giỏ hàng giờ thực hiện bằng nút "🛒 Thêm vào giỏ" ngay trong màn chi
     * tiết đó, không tự động thêm khi vừa chạm vào thẻ nữa.
     */
    private fun vipCard(f: JSONObject): View {
        val name = f.optString("name"); val imageUrl = f.optString("image")
        val price = f.optInt("price")
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(dp(112), dp(148)).apply { marginEnd = dp(10) }
        }
        val img = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; background = bg(Color.rgb(240, 250, 241), 14); clipToOutline = true }
        card.addView(img, LinearLayout.LayoutParams(dp(112), dp(100)))
        ImageLoader.load(img, imageUrl)
        card.addView(label(name, 12.5f, text, true).apply {
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; setPadding(0, dp(5), 0, 0)
        })
        card.addView(label(String.format("%,d", price).replace(',', '.') + "đ", 12.5f, primary, true))
        card.setOnClickListener { openFoodDetail(f) }
        return card
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    /**
     * Tự trôi dải "Menu Vip" từ phải qua trái (tăng dần scrollX), lặp vô tận
     * nhờ 2 bản sao nội dung. Tạm dừng khi khách đang chạm (vipUserTouching)
     * để không "giật" ảnh khỏi tay khi họ đang vuốt xem.
     */
    private fun startVipAutoScroll() {
        vipAutoScrollRunnable?.let { handler.removeCallbacks(it) }
        val scroll = vipScrollView ?: return
        val runnable = object : Runnable {
            override fun run() {
                if (!vipUserTouching && vipSingleSetWidth > 0) {
                    val newX = scroll.scrollX + 2
                    if (newX >= vipSingleSetWidth) scroll.scrollTo(newX - vipSingleSetWidth, 0) else scroll.scrollTo(newX, 0)
                }
                handler.postDelayed(this, 30)
            }
        }
        vipAutoScrollRunnable = runnable
        handler.post(runnable)
    }

    private fun showKcnSelection() {
        val root=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER_HORIZONTAL;setBackgroundColor(bgColor);setPadding(dp(20),dp(30),dp(20),dp(20))}
        root.addView(ImageView(this).apply{setImageResource(R.drawable.com11h_logo);scaleType=ImageView.ScaleType.FIT_CENTER},LinearLayout.LayoutParams(dp(150),dp(150)))
        root.addView(label("FOOD KCN",30f,primary,true))
        root.addView(label("Bạn đang làm việc tại KCN nào?",18f,text,true).apply{setPadding(0,dp(8),0,dp(18))})
        val loading=label("⏳ Đang tải danh sách KCN...",15f,secondary);root.addView(loading)
        executor.execute{
            val r=try{account.request("kcn_list")}catch(_:Exception){null}
            runOnUiThread{
                root.removeView(loading)
                if(r==null||!r.optBoolean("ok")){root.addView(label("Không tải được danh sách KCN. ${r?.optString("message")?:("Vui lòng thử lại.")}",14f,secondary));return@runOnUiThread}
                val data = r.opt("data")
                val arr = when (data) {
                    is JSONArray -> data
                    is JSONObject -> data.optJSONArray("industrial_zones") ?: JSONArray()
                    else -> JSONArray()
                }
                if(arr.length()==0){root.addView(label("Chưa có KCN nào đang hoạt động.",15f,secondary));return@runOnUiThread}
                for(i in 0 until arr.length()){
                    val z=arr.getJSONObject(i)
                    val card=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;background=bg(Color.WHITE,16);setPadding(dp(15),dp(13),dp(15),dp(13));setOnClickListener{
                        val newId=z.optInt("id")
                        if (selectedKcnId != 0 && selectedKcnId != newId) { getSharedPreferences("com11h_local", MODE_PRIVATE).edit().putString("cart", "[]").apply() }
                        KcnStore.save(this@HomeActivity,z); selectedKcnId=newId; selectedKcnName=z.optString("name"); showHome()
                    }}
                    card.addView(label("🏭 ${z.optString("name")}",18f,text,true)); val loc=listOf(z.optString("province"),z.optString("district")).filter{it.isNotBlank()}.joinToString(" • "); if(loc.isNotBlank())card.addView(label("📍 $loc",13f,secondary)); card.addView(label("🏪 ${z.optInt("store_count")} cửa hàng",13f,primary,true)); root.addView(card,LinearLayout.LayoutParams(-1,-2).apply{bottomMargin=dp(10)})
                }
            }
        }
        root.addView(label("Bạn có thể đổi KCN bất cứ lúc nào.",12.5f,secondary).apply{setPadding(0,dp(8),0,0)})
        setContentView(root)
    }

    private fun showHome() {
        val shell = shell()
        // Bọc shell (header + nội dung cuộn + thanh điều hướng) trong FrameLayout
        // để có thể "đè" bong bóng nhắc nhở Xu nổi cố định ở góc phải dưới màn
        // hình, luôn nổi trên mọi thứ kể cả khi cuộn trang — chỉ ở Trang chủ.
        val root = FrameLayout(this)
        root.addView(shell, FrameLayout.LayoutParams(-1, -1))
        root.addView(xuReminderBubble(), FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END).apply {
            bottomMargin = dp(58 + 16); rightMargin = dp(14)
        })
        setContentView(root)
        val scroll = shell.getChildAt(1) as ScrollView
        val content = scroll.getChildAt(0) as LinearLayout
        newsVideoReady = false
        content.addView(searchBox(), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })

        // Thanh thao tác nhanh: đưa các tính năng quan trọng lên ngay đầu trang,
        // giảm số lần khách phải tìm trong menu Tài khoản. "XU" và "Ưu đãi" cùng
        // mở Ví XU (MainActivity.showXu()) — xem món đủ 30 giây trong Thực đơn
        // để tích XU, đổi voucher ngay trong đó.
        val quick = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val quickScroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER; addView(quick) }
        listOf(
            "🍚\nThực đơn" to { open("menu") },
            "🛒\nGiỏ hàng" to { open("cart") },
            "❤️\nYêu thích" to { open("favorites") },
            "🪙\nXU" to { open("xu") },
            "🎁\nƯu đãi" to { open("xu") }
        ).forEach { (title, action) ->
            quick.addView(TextView(this).apply {
                text = title; textSize = 12f; gravity = Gravity.CENTER; setTextColor(primary)
                background = bg(Color.WHITE, 15); setPadding(dp(13), dp(8), dp(13), dp(8)); setOnClickListener { action() }
            }, LinearLayout.LayoutParams(dp(82), dp(54)).apply { marginEnd = dp(8) })
        }
        content.addView(quickScroll, LinearLayout.LayoutParams(-1, dp(56)).apply { bottomMargin = dp(10) })

        val bannerContainer = FrameLayout(this)
        bannerContainer.addView(staticBanner(), FrameLayout.LayoutParams(-1, dp(120)))
        content.addView(bannerContainer, LinearLayout.LayoutParams(-1, dp(120)).apply { bottomMargin = dp(15) })
        loadBanners(bannerContainer)

        content.addView(label("Menu Vip", 21f, text, true).apply { setPadding(0, 0, 0, dp(9)) })
        val vipScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val vipRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        vipScroll.addView(vipRow, LinearLayout.LayoutParams(-2, -2))
        // Chạm vào để tạm dừng tự trôi (vẫn vuốt qua vuốt lại bình thường), buông tay
        // ra một lúc thì tự trôi tiếp — không chặn sự kiện chạm nên ScrollView vẫn
        // xử lý vuốt/fling như bình thường.
        vipScroll.setOnTouchListener { _, event ->
            when (event.action) {
                android.view.MotionEvent.ACTION_DOWN -> vipUserTouching = true
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL ->
                    handler.postDelayed({ vipUserTouching = false }, 2500)
            }
            false
        }
        content.addView(vipScroll, LinearLayout.LayoutParams(-1, dp(152)).apply { bottomMargin = dp(16) })
        this.vipScrollView = vipScroll
        this.vipRow = vipRow
        loadVipCarousel()

        // "Tin Tức" — ngay dưới "Menu Vip", cùng bề rộng, cao hơn theo tỉ lệ
        // video 16:9. Ẩn cả khối (kể cả tiêu đề) nếu Admin chưa bật video nào.
        // Dùng AspectRatioFrameLayout để khung module luôn khớp đúng pixel
        // với khung trình phát video, không bị lệch dù xoay màn hình.
        val newsSection = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        newsSection.addView(label("Thư Giản Âm Nhạc ", 21f, text, true).apply { setPadding(0, 0, 0, dp(9)) })
        val newsContainer = AspectRatioFrameLayout(this, 16f, 9f).apply { background = bg(Color.BLACK, 18); clipToOutline = true }
        newsSection.addView(newsContainer, LinearLayout.LayoutParams(-1, -2))
        content.addView(newsSection, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(16) })
        this.newsSectionRef = newsSection
        this.newsContainerRef = newsContainer
        loadNewsVideo(newsSection, newsContainer)

        content.addView(label("Món ăn phổ biến", 21f, text, true).apply { setPadding(0, 0, 0, dp(8)) })
        val popularBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(popularBox)
        this.popularBox = popularBox
        loadPopularFoods()
    }

    /**
     * Tải video "Tin Tức" đang bật từ api?action=news_video (đồng bộ với
     * admin/news_videos.php — đổi video trên Admin thì app tự cập nhật theo,
     * không cần cập nhật APK). Nếu Admin chưa bật video nào thì ẩn hẳn cả
     * khối "Tin Tức" (kể cả tiêu đề) để không để lại khoảng trống thừa.
     * Chiều cao khung video tự tính theo đúng bề rộng thực tế của khối
     * (bằng bề rộng "Menu Vip") nhân tỉ lệ 16:9 (video ngang chuẩn YouTube).
     *
     * WebView phát video thật sự được FloatingVideoManager.start() tạo và
     * quản lý (dùng chung xuyên suốt app, né lỗi YouTube "153" bằng
     * loadDataWithBaseURL — xem file đó) — HomeActivity chỉ cần "xin" nó về
     * hiển thị ngay trong container này qua attachInline()/checkNewsDockState().
     */
    private fun loadNewsVideo(section: LinearLayout, container: FrameLayout) {
        executor.execute {
            val r = try { account.request("news_video") } catch (_: Exception) { null }
            runOnUiThread {
                // Toàn bộ khối này được bọc try/catch: một số máy Android (đặc
                // biệt máy phổ thông đã bị tắt/đóng băng app hệ thống "Android
                // System WebView", hoặc app đó đang giữa đợt tự cập nhật) sẽ
                // ném exception ngay khi khởi tạo WebView. Nếu không bắt lỗi ở
                // đây, exception đó sẽ làm crash toàn bộ Activity Trang chủ
                // (kéo theo mất luôn Banner và mọi module khác phía trên,
                // không riêng gì Tin Tức) — vì vậy TUYỆT ĐỐI không được để lỗi
                // của module Tin Tức thoát ra ngoài phạm vi của chính nó.
                try {
                    if (section != this.newsSectionRef) return@runOnUiThread // màn hình đã đổi/hủy trong lúc chờ tải
                    val video = r?.optJSONObject("data")?.optJSONObject("video")
                    val embedUrl = video?.optString("embed_url")?.trim().orEmpty()
                    if (r == null || !r.optBoolean("ok") || embedUrl.isBlank()) {
                        section.visibility = View.GONE
                        newsVideoReady = false
                        return@runOnUiThread
                    }
                    val title = video?.optString("title").orEmpty()
                    // Không cần tự đo/gán width-height thủ công nữa: newsContainer
                    // là AspectRatioFrameLayout, tự khớp đúng khung 16:9 trong
                    // onMeasure — WebView chỉ cần MATCH_PARENT là phủ khít khung.
                    FloatingVideoManager.start(this, embedUrl, title)
                    section.visibility = View.VISIBLE
                    newsVideoReady = true
                    checkNewsDockState() // gắn WebView vào đúng chỗ (inline hay bong bóng) ngay khi vừa sẵn sàng
                } catch (e: Exception) {
                    // WebView không dựng được (hoặc lỗi bất kỳ khác) trên máy này
                    // -> chỉ ẩn khối Tin Tức, các module khác (Banner, Menu Vip,
                    // Món ăn phổ biến...) vẫn hiển thị bình thường.
                    section.visibility = View.GONE
                    newsVideoReady = false
                }
            }
        }
    }

    private fun open(screen: String) { startActivity(Intent(this, MainActivity::class.java).putExtra("screen", screen)) }
}
