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
import android.widget.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.Executors

/**
 * COM11H app — toàn bộ dữ liệu thực đơn, đơn hàng, thanh toán, quay số may
 * mắn và tài khoản được đồng bộ TRỰC TIẾP với com11h.com qua api/index.php
 * (dùng chung logic nghiệp vụ với web qua core.php, xem AccountSync.kt).
 * Chỉ có giỏ hàng (trước khi đặt) là lưu tạm trên máy.
 */
class MainActivity : SessionActivity() {
    private lateinit var account: AccountSync
    private lateinit var xuApi: XuApi
    private val executor = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private val primary = Color.rgb(56, 142, 60)
    private val dark = Color.rgb(38, 38, 38)
    private val secondary = Color.rgb(107, 107, 107)
    private val bgColor = Color.rgb(247, 255, 248)
    private val danger = Color.rgb(198, 40, 40)
    private val ok = Color.rgb(46, 125, 50)

    // Giỏ hàng: food_id -> số lượng. Lưu tạm cục bộ cho tới khi đặt hàng thật qua API.
    private val cart = linkedMapOf<Int, Int>()
    // Cache thực đơn tải gần nhất từ server, dùng để hiển thị tên/giá/ảnh trong giỏ hàng.
    private var foodsCache: List<Food> = emptyList()
    private var pollRunnable: Runnable? = null
    // Badge số lượng trên icon 🛒 Giỏ hàng ở thanh điều hướng của màn hình đang mở.
    private var cartBadge: TextView? = null

    // =========================================================================
    // NGĂN XẾP "MÀN HÌNH TRƯỚC ĐÓ" — dùng cho nút bấm "‹" (trở lại) ở đầu mỗi
    // màn. Mỗi khi khách bấm vào 1 món/nút để đi SÂU HƠN vào 1 màn con (ví dụ:
    // Thực đơn -> Chi tiết món, Đơn hàng -> Chi tiết đơn), ta lưu lại 1 hàm
    // dựng-lại-màn-hiện-tại vào ngăn xếp này TRƯỚC khi mở màn con. Bấm "‹" sẽ
    // lấy đúng màn vừa lưu ra và dựng lại — tức luôn quay về ĐÚNG mục trước đó
    // khách đang xem, thay vì luôn nhảy thẳng về Trang chủ như trước đây.
    // Chỉ khi ngăn xếp rỗng (đang ở màn gốc, mở thẳng từ Trang chủ/thanh điều
    // hướng dưới) thì "‹" mới về Trang chủ — đúng như kỳ vọng: đó chính là màn
    // trước đó thật sự trong trường hợp này.
    // =========================================================================
    private val backStack = ArrayDeque<() -> Unit>()

    /** Lưu màn [current] vào ngăn xếp rồi mở màn con [next]. */
    private fun push(current: () -> Unit, next: () -> Unit) {
        backStack.addLast(current)
        next()
    }

    /** Xử lý nút "‹" và nút Back của điện thoại: quay về đúng màn trước đó nếu có, hết ngăn xếp mới về Trang chủ. */
    private fun goBack() {
        if (backStack.isNotEmpty()) {
            val prev = backStack.removeLast()
            prev()
        } else {
            startActivity(Intent(this, HomeActivity::class.java)); finish()
        }
    }

    /** Bấm vào 1 trong 5 tab của thanh điều hướng dưới là chuyển hẳn sang mục khác (không phải "đi sâu vào 1 màn con"), nên xoá ngăn xếp — "‹" ở mục mới sẽ về Trang chủ, đúng như bấm trực tiếp từ Trang chủ. */
    private fun switchTab(render: () -> Unit) {
        backStack.clear()
        render()
    }

    override fun onBackPressed() {
        if (backStack.isNotEmpty()) { goBack() } else { super.onBackPressed() }
    }

    data class Food(
        val id: Int, val name: String, val price: Int, val stock: Int,
        val category: String, val description: String, val image: String
    )

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun money(v: Int) = String.format("%,d", v).replace(',', '.') + "đ"
    private fun bg(color: Int, radius: Int = 16) = GradientDrawable().apply { setColor(color); cornerRadius = dp(radius).toFloat() }
    private fun outline(color: Int, radius: Int = 16) = GradientDrawable().apply { setColor(Color.WHITE); setStroke(dp(1), color); cornerRadius = dp(radius).toFloat() }
    private fun label(v: String, size: Float = 17f, color: Int = dark, bold: Boolean = false) = TextView(this).apply { text = v; textSize = size; setTextColor(color); if (bold) setTypeface(null, Typeface.BOLD); setPadding(0, dp(4), 0, dp(4)) }
    private fun button(v: String, click: () -> Unit) = TextView(this).apply { text = v; textSize = 16f; gravity = Gravity.CENTER; setTextColor(Color.WHITE); setTypeface(null, Typeface.BOLD); background = bg(primary, 13); setPadding(dp(12), dp(13), dp(12), dp(13)); setOnClickListener { click() } }
    private fun ghostButton(v: String, click: () -> Unit) = TextView(this).apply { text = v; textSize = 15f; gravity = Gravity.CENTER; setTextColor(primary); background = outline(primary, 13); setPadding(dp(12), dp(11), dp(12), dp(11)); setOnClickListener { click() } }

    /**
     * Mở màn hình xem ảnh PHÓNG TO ngay trong app, vuốt được sang ảnh khác
     * trong CÙNG danh sách [items] (đúng món đi theo đúng ảnh) — dùng lại
     * đúng màn hình zoom của banner (BannerViewActivity).
     */
    private fun openFoodImages(items: List<Food>, startIndex: Int) {
        if (items.isEmpty()) return
        val images = items.map { it.image }
        val names = items.map { it.name }
        startActivity(
            Intent(this, BannerViewActivity::class.java)
                .putStringArrayListExtra("images", ArrayList(images))
                .putStringArrayListExtra("titles", ArrayList(names))
                .putExtra("index", startIndex.coerceIn(0, items.size - 1))
        )
    }
    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    /** Icon 👤 Tài khoản ở header — đổi màu nền + có chấm xanh khi khách đã đăng nhập. */
    private fun profileIconCell(): View {
        val loggedIn = account.isLoggedIn()
        val cell = FrameLayout(this)
        cell.addView(TextView(this).apply {
            text = "👤"; textSize = 19f; gravity = Gravity.CENTER
            background = bg(if (loggedIn) Color.rgb(224, 247, 233) else Color.rgb(238, 238, 238), 22)
            setOnClickListener { showProfile() }
        }, FrameLayout.LayoutParams(dp(44), dp(44)))
        if (loggedIn) {
            cell.addView(View(this).apply {
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(ok); setStroke(dp(2), Color.WHITE) }
            }, FrameLayout.LayoutParams(dp(13), dp(13), Gravity.BOTTOM or Gravity.END).apply { bottomMargin = dp(3); rightMargin = dp(3) })
        }
        return cell
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); account = AccountSync(this); xuApi = XuApi(account); loadLocalCart()
        val code = intent.getStringExtra("code")
        when (intent.getStringExtra("screen")) {
            "menu" -> showMenu()
            "cart" -> showCart()
            "checkout" -> showCheckout()
            "orders" -> showOrders()
            "order_detail" -> if (code != null) showOrderDetail(code) else showOrders()
            "lucky" -> showLucky(code)
            "daily" -> showDaily()
            "loyalty" -> showLoyalty()
            "xu" -> showXu()
            "favorites" -> showFavorites()
            "profile" -> showProfile()
            // Mở thẳng "Chi tiết món" khi khách bấm vào 1 món ở Trang chủ (Menu
            // Vip hoặc Món ăn phổ biến) — xem HomeActivity.openFoodDetail(). Món
            // được truyền nguyên qua các extra bên dưới nên hiện đủ ảnh + thông
            // tin ngay lập tức, không cần gọi lại API, và bắt đầu tính thời gian
            // xem để tích XU giống hệt lúc bấm vào món trong màn Thực đơn.
            "food_detail" -> showFoodDetail(
                Food(
                    intent.getIntExtra("food_id", 0),
                    intent.getStringExtra("food_name") ?: "",
                    intent.getIntExtra("food_price", 0),
                    intent.getIntExtra("food_stock", 0),
                    intent.getStringExtra("food_category") ?: "",
                    intent.getStringExtra("food_description") ?: "",
                    intent.getStringExtra("food_image") ?: ""
                )
            )
            else -> { startActivity(Intent(this, HomeActivity::class.java)); finish() }
        }
    }
    override fun onDestroy() { stopPolling(); saveLocalCart(); executor.shutdownNow(); super.onDestroy() }

    private fun stopPolling() { pollRunnable?.let { handler.removeCallbacks(it) }; pollRunnable = null }

    private fun loadLocalCart() {
        val p = getSharedPreferences("com11h_local", MODE_PRIVATE); cart.clear()
        try {
            val a = JSONArray(p.getString("cart", "[]"))
            for (i in 0 until a.length()) { val o = a.getJSONObject(i); cart[o.optInt("id")] = o.optInt("qty") }
        } catch (_: Exception) { }
    }
    private fun saveLocalCart() {
        val p = getSharedPreferences("com11h_local", MODE_PRIVATE)
        val a = JSONArray(); cart.forEach { (id, q) -> a.put(JSONObject().put("id", id).put("qty", q)) }
        p.edit().putString("cart", a.toString()).apply()
    }
    private fun lastAddress(): String = getSharedPreferences("com11h_local", MODE_PRIVATE).getString("last_address", "") ?: ""
    private fun saveLastAddress(v: String) = getSharedPreferences("com11h_local", MODE_PRIVATE).edit().putString("last_address", v).apply()

    private fun shell(title: String, selected: Int): LinearLayout {
        val outer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(bgColor) }
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(12), dp(8), dp(12), dp(8)); setBackgroundColor(Color.WHITE) }
        header.addView(TextView(this).apply { text = "‹"; textSize = 34f; setTextColor(primary); gravity = Gravity.CENTER; setOnClickListener { goBack() } }, LinearLayout.LayoutParams(dp(42), dp(48)))
        header.addView(label(title, 20f, primary, true), LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(4) })
        header.addView(profileIconCell(), LinearLayout.LayoutParams(dp(44), dp(44))); outer.addView(header)
        val scroll = ScrollView(this); val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(12), dp(14), dp(18)) }; scroll.addView(content); outer.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        val nav = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setBackgroundColor(Color.WHITE); elevation = dp(8).toFloat() }
        listOf("⌂\nTrang chủ", "▦\nThực đơn", "🛒\nGiỏ hàng", "▤\nĐơn hàng", "♙\nTài khoản").forEachIndexed { i, name ->
            val cell = FrameLayout(this)
            cell.addView(TextView(this).apply { text = name; textSize = 10f; gravity = Gravity.CENTER; setTextColor(if (i == selected) primary else secondary); setTypeface(null, if (i == selected) Typeface.BOLD else Typeface.NORMAL); setOnClickListener { when (i) { 0 -> { startActivity(Intent(this@MainActivity, HomeActivity::class.java)); finish() }; 1 -> switchTab { showMenu() }; 2 -> switchTab { showCart() }; 3 -> switchTab { showOrders() }; 4 -> switchTab { showProfile() } } } }, FrameLayout.LayoutParams(-1, -1))
            if (i == 2) {
                cartBadge = TextView(this).apply {
                    textSize = 9.5f; setTextColor(Color.WHITE); gravity = Gravity.CENTER; setTypeface(null, Typeface.BOLD)
                    background = bg(Color.rgb(220, 38, 38), 20)
                    visibility = android.view.View.GONE
                }
                cell.addView(cartBadge, FrameLayout.LayoutParams(dp(18), dp(18), Gravity.TOP or Gravity.END).apply { topMargin = dp(2); marginEnd = dp(14) })
            }
            nav.addView(cell, LinearLayout.LayoutParams(0, dp(58), 1f))
        }
        outer.addView(nav); refreshCartBadge(); return outer
    }

    /** Cập nhật số lượng (badge đỏ) trên icon 🛒 Giỏ hàng ở thanh điều hướng theo giỏ hàng hiện tại. */
    private fun refreshCartBadge() {
        val n = cart.values.sum()
        cartBadge?.apply {
            text = if (n > 99) "99+" else n.toString()
            visibility = if (n > 0) android.view.View.VISIBLE else android.view.View.GONE
        }
    }
    private fun contentOf(s: LinearLayout) = (s.getChildAt(1) as ScrollView).getChildAt(0) as LinearLayout

    private fun loading(c: LinearLayout, msg: String = "Đang tải dữ liệu..."): TextView {
        val t = label("⏳ $msg", 15f, secondary); c.addView(t); return t
    }

    // =========================================================================
    // THỰC ĐƠN — lấy TOÀN BỘ dữ liệu món ăn từ server (api?action=menu), không
    // còn danh sách cứng trong app. Ảnh, giá, tồn kho luôn khớp với web.
    // =========================================================================
    private fun showMenu() {
        val s = shell("Thực đơn", 1); setContentView(s); val c = contentOf(s)
        val head = label("Món ăn ngon mỗi ngày", 22f, dark, true); c.addView(head)
        c.addView(label("Đồng bộ trực tiếp từ com11h.com", 14f, secondary))

        // Ô tìm kiếm ngay trong màn Thực đơn — nhận sẵn từ khoá được truyền từ
        // trang chủ (extra "query") khi khách bấm nút 🔍 hoặc "Tìm kiếm" trên
        // bàn phím ở ô tìm kiếm trang chủ, đồng thời cho phép gõ lại tại đây.
        val initialQuery = intent.getStringExtra("query") ?: ""
        val searchRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val searchInput = EditText(this).apply {
            hint = "Tìm món ăn..."; textSize = 15f; isSingleLine = true
            setText(initialQuery)
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
            setPadding(dp(14), 0, dp(14), 0); background = outline(primary, 14)
        }
        searchRow.addView(searchInput, LinearLayout.LayoutParams(0, dp(44), 1f))
        val searchBtn = TextView(this).apply { text = "🔍"; textSize = 17f; gravity = Gravity.CENTER; setTextColor(Color.WHITE); background = bg(primary, 14) }
        searchRow.addView(searchBtn, LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginStart = dp(8) })
        c.addView(searchRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })

        val chipsRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val chipsScroll = HorizontalScrollView(this).apply { addView(chipsRow) }
        c.addView(chipsScroll, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10); bottomMargin = dp(4) })
        val listBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        c.addView(listBox, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        val loadingView = loading(listBox)

        executor.execute {
            try {
                val r = account.request("menu", query = mapOf("kcn_id" to KcnStore.id(this).toString()))
                runOnUiThread {
                    listBox.removeView(loadingView)
                    if (!r.optBoolean("ok")) { listBox.addView(label("Không tải được thực đơn. ${r.optString("message")}", 14f, danger)); return@runOnUiThread }
                    val arr = r.optJSONObject("data")?.optJSONArray("foods") ?: JSONArray()
                    val foods = mutableListOf<Food>()
                    for (i in 0 until arr.length()) {
                        val f = arr.getJSONObject(i)
                        foods.add(Food(f.optInt("id"), f.optString("name"), f.optInt("price"), f.optInt("stock"), f.optString("category"), f.optString("description"), f.optString("image")))
                    }
                    foodsCache = foods
                    if (foods.isEmpty()) { listBox.addView(label("Hiện chưa có món nào đang bán.", 14f, secondary)); return@runOnUiThread }

                    val categories = listOf("Tất cả") + foods.map { it.category }.filter { it.isNotBlank() }.distinct()
                    var selectedCategory = "Tất cả"
                    fun renderList(cat: String) {
                        listBox.removeAllViews()
                        val keyword = searchInput.text.toString().trim()
                        var filtered = if (cat == "Tất cả") foods else foods.filter { it.category == cat }
                        if (keyword.isNotEmpty()) {
                            filtered = filtered.filter { it.name.contains(keyword, ignoreCase = true) || it.description.contains(keyword, ignoreCase = true) }
                        }
                        if (filtered.isEmpty()) {
                            listBox.addView(label(if (keyword.isNotEmpty()) "Không tìm thấy món nào khớp với \"$keyword\"." else "Không có món nào trong danh mục này.", 14f, secondary))
                            return
                        }
                        filtered.forEachIndexed { idx, f -> listBox.addView(foodCard(f, filtered, idx)) }
                    }
                    fun renderChips() {
                        chipsRow.removeAllViews()
                        categories.forEach { cat ->
                            chipsRow.addView(TextView(this@MainActivity).apply {
                                text = cat; textSize = 14f; gravity = Gravity.CENTER
                                setPadding(dp(14), dp(8), dp(14), dp(8))
                                setTextColor(if (cat == selectedCategory) Color.WHITE else primary)
                                background = if (cat == selectedCategory) bg(primary, 16) else outline(primary, 16)
                                setOnClickListener { selectedCategory = cat; renderChips(); renderList(cat) }
                            }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8) })
                        }
                    }
                    searchBtn.setOnClickListener { renderList(selectedCategory) }
                    searchInput.setOnEditorActionListener { _, actionId, _ ->
                        if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) { renderList(selectedCategory); true } else false
                    }
                    renderChips(); renderList(selectedCategory)
                }
            } catch (e: Exception) {
                runOnUiThread { listBox.removeView(loadingView); listBox.addView(label("Lỗi kết nối máy chủ. Kiểm tra mạng rồi thử lại.", 14f, danger)) }
            }
        }
    }

    private fun foodCard(f: Food, listContext: List<Food> = listOf(f), indexInList: Int = 0, backTo: () -> Unit = { showMenu() }): LinearLayout {
        val card = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; background = bg(Color.WHITE, 16); setPadding(dp(10), dp(10), dp(10), dp(10)); layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(9) } }
        // Ảnh món ăn to hơn trước và có thể bấm vào để xem phóng to (chụm/mở
        // 2 ngón tay để zoom, kéo xem chi tiết), giống hệt cách xem banner —
        // vuốt trái/phải để xem lần lượt các món khác trong danh sách đang hiện.
        val img = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; background = bg(Color.rgb(240, 250, 241), 14); clipToOutline = true }
        card.addView(img, LinearLayout.LayoutParams(dp(86), dp(86)))
        ImageLoader.load(img, f.image)
        // Bấm vào ẢNH và bấm vào TÊN/MÔ TẢ (info) đều mở "Chi tiết món" ngay lập
        // tức — hiện đủ ảnh + thông tin món để bắt đầu tính thời gian xem tích
        // XU. Muốn xem ảnh phóng to thì bấm vào đúng ảnh lớn NGAY TRONG màn chi
        // tiết đó (xem showFoodDetail).
        img.setOnClickListener { push(backTo) { showFoodDetail(f) } }
        val info = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(11), 0, dp(6), 0) }
        info.addView(label(f.name, 17f, dark, true))
        if (f.description.isNotBlank()) info.addView(label(f.description, 13.5f, secondary))
        info.addView(label("${money(f.price)}", 15f, primary, true)); info.addView(label("còn ${f.stock} phần", 13f, secondary))
        card.addView(info, LinearLayout.LayoutParams(0, -2, 1f))
        val actionBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        val favBtn = TextView(this).apply {
            text = if (FavoriteStore.contains(this@MainActivity, f.id)) "♥" else "♡"
            textSize = 23f; gravity = Gravity.CENTER; setTextColor(if (FavoriteStore.contains(this@MainActivity, f.id)) primary else secondary)
            setOnClickListener {
                val added = FavoriteStore.toggle(this@MainActivity, f.id)
                text = if (added) "♥" else "♡"
                setTextColor(if (added) primary else secondary)
                toast(if (added) "Đã thêm ${f.name} vào yêu thích" else "Đã bỏ ${f.name} khỏi yêu thích")
            }
        }
        actionBox.addView(favBtn, LinearLayout.LayoutParams(dp(42), dp(38)))
        val addBtn = TextView(this).apply {
            text = if (f.stock <= 0) "Hết" else "+"; textSize = 22f; gravity = Gravity.CENTER; setTextColor(Color.WHITE)
            background = bg(if (f.stock <= 0) secondary else primary, 22)
            setOnClickListener {
                if (f.stock <= 0) { toast("Món này đã hết hàng"); return@setOnClickListener }
                val current = cart[f.id] ?: 0
                if (current + 1 > f.stock) { toast("Chỉ còn ${f.stock} phần \"${f.name}\""); return@setOnClickListener }
                cart[f.id] = current + 1; saveLocalCart(); refreshCartBadge(); toast("Đã thêm \"${f.name}\" vào giỏ hàng")
            }
        }
        actionBox.addView(addBtn, LinearLayout.LayoutParams(dp(42), dp(42)).apply { topMargin = dp(2) })
        card.addView(actionBox, LinearLayout.LayoutParams(dp(48), -2))
        info.setOnClickListener { push(backTo) { showFoodDetail(f) } }
        return card
    }

    // =========================================================================
    // CHI TIẾT MÓN + TÍCH XU: đủ 30 giây xem một món -> +10 XU, cộng THẬT vào
    // ví XU trên server (bảng xu_wallets, qua api action 'xu_start_view' rồi
    // 'xu_complete_view' — xem XuApi.kt / core.php / api/index.php). Trước đây
    // màn này chỉ cộng XU vào bộ nhớ tạm trên máy (XuStore, bản demo) nên xem
    // đủ giờ mà tài khoản khách KHÔNG nhận được XU thật — đã sửa để gọi đúng
    // 2 action trên server, đồng thời báo rõ "Bạn đã nhận được N XU vào tài
    // khoản" khi server xác nhận cộng XU thành công.
    // Bấm vào tên/mô tả món trong Thực đơn (info) để mở màn này; bấm vào ẢNH
    // vẫn mở xem ảnh phóng to (openFoodImages) như trước, không đổi hành vi đó.
    // =========================================================================
    private fun showFoodDetail(f: Food) {
        val s = shell("Chi tiết món", 1); setContentView(s); val c = contentOf(s)
        val img = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; background = bg(Color.WHITE, 18); clipToOutline = true }
        c.addView(img, LinearLayout.LayoutParams(-1, dp(230)).apply { bottomMargin = dp(12) }); ImageLoader.load(img, f.image)
        img.setOnClickListener { openFoodImages(listOf(f), 0) }
        c.addView(label(f.name, 23f, dark, true))
        c.addView(label(money(f.price), 20f, primary, true))
        c.addView(label("Còn ${f.stock} phần", 13f, secondary))
        if (f.description.isNotBlank()) c.addView(label(f.description, 15f, dark))

        val info = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = bg(Color.rgb(232,245,233),16); setPadding(dp(14),dp(12),dp(14),dp(12)) }
        info.addView(label("🪙 XU khi xem món", 17f, dark, true))
        info.addView(label("Xem đủ 30 giây: +10 XU. Xem đủ 10 món: thưởng thêm 100 XU. Tối đa 200 XU/giờ và 2.000 XU/ngày.", 13f, secondary))
        val timer = label("⏱ Đang tính thời gian xem: 30 giây", 14f, primary, true); info.addView(timer)
        c.addView(info, LinearLayout.LayoutParams(-1,-2).apply { bottomMargin = dp(12) })

        // Cần đăng nhập vì XU gắn với tài khoản (customer_id) trên server, không
        // có khái niệm "khách vãng lai tích XU" — báo rõ và mời đăng nhập.
        if (!account.isLoggedIn()) {
            timer.text = "🔒 Đăng nhập để tích XU khi xem món"
            c.addView(button("Đăng nhập") { push({ showFoodDetail(f) }) { showLogin() } }.apply { layoutParams = LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(8) } })
        } else {
            timer.text = "⏱ Đang kết nối máy chủ..."
            executor.execute {
                val startResult = try { xuApi.startView(f.id) } catch (_: Exception) { null }
                runOnUiThread {
                    val ok = startResult?.optBoolean("ok") == true
                    if (!ok) {
                        timer.text = "⚠️ " + (startResult?.optString("message")?.takeIf { it.isNotBlank() } ?: "Không kết nối được máy chủ XU, vui lòng thử lại.")
                        return@runOnUiThread
                    }
                    val data = startResult!!.optJSONObject("data")
                    val viewId = data?.optInt("view_id") ?: 0
                    val requiredSeconds = data?.optInt("required_seconds")?.takeIf { it > 0 } ?: 30
                    if (viewId <= 0) { timer.text = "⚠️ Không thể bắt đầu phiên xem XU."; return@runOnUiThread }

                    val started = System.currentTimeMillis()
                    var finished = false
                    val runnable = object : Runnable {
                        override fun run() {
                            if (finished) return
                            val elapsed = ((System.currentTimeMillis() - started) / 1000).toInt()
                            if (elapsed >= requiredSeconds) {
                                finished = true
                                timer.text = "⏳ Đang ghi nhận XU..."
                                executor.execute {
                                    val completeResult = try { xuApi.completeView(viewId) } catch (_: Exception) { null }
                                    runOnUiThread {
                                        val cOk = completeResult?.optBoolean("ok") == true
                                        val cData = completeResult?.optJSONObject("data")
                                        when {
                                            cOk && cData?.optBoolean("already_rewarded") == true ->
                                                timer.text = "✓ Bạn đã nhận XU từ món này trước đó."
                                            cOk && (cData?.optInt("earned") ?: 0) > 0 -> {
                                                val earned = cData!!.optInt("earned")
                                                timer.text = "🎉 Bạn đã nhận được $earned xu vào tài khoản!"
                                                toast("🎉 Bạn đã nhận được $earned xu vào tài khoản!")
                                            }
                                            cOk -> timer.text = "⚠️ Đã đạt giới hạn XU hôm nay/giờ này."
                                            else -> timer.text = "⚠️ " + (completeResult?.optString("message")?.takeIf { it.isNotBlank() } ?: "Không thể ghi nhận XU lúc này, vui lòng thử lại.")
                                        }
                                    }
                                }
                                return
                            }
                            timer.text = "⏱ Còn ${requiredSeconds - elapsed} giây để nhận 10 XU"
                            handler.postDelayed(this, 1000)
                        }
                    }
                    handler.post(runnable)
                }
            }
            c.addView(button("🪙 Xem ví XU") { push({ showFoodDetail(f) }) { showXu() } }.apply { layoutParams = LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(8) } })
        }
        c.addView(button(if (f.stock > 0) "🛒 Thêm vào giỏ" else "Hết hàng") {
            if (f.stock <= 0) { toast("Món này đã hết hàng"); return@button }
            val q=cart[f.id] ?: 0; if(q+1>f.stock){toast("Chỉ còn ${f.stock} phần");return@button}
            cart[f.id]=q+1; saveLocalCart(); refreshCartBadge(); toast("Đã thêm ${f.name} vào giỏ hàng")
        }.apply { layoutParams=LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(8) } })
    }

    // =========================================================================
    // VÍ XU — lấy số dư THẬT từ server (api action 'xu_wallet'), không còn
    // đọc bộ nhớ tạm trên máy (XuStore) nữa, để luôn khớp với XU khách vừa
    // tích được ở màn "Chi tiết món" (showFoodDetail) và với web/admin.
    // Đổi thưởng cũng gọi thẳng server ('xu_rewards' / 'xu_redeem').
    // =========================================================================
    private fun showXu() {
        if (!account.isLoggedIn()) { toast("Vui lòng đăng nhập để sử dụng XU"); showLogin(); return }
        val s=shell("Ví XU",4); setContentView(s); val c=contentOf(s)
        c.addView(label("🪙 Ví XU",24f,dark,true))
        val loadingView = loading(c, "Đang tải ví XU...")

        executor.execute {
            val walletResult = try { xuApi.wallet() } catch (_: Exception) { null }
            val rewardsResult = try { xuApi.rewards() } catch (_: Exception) { null }
            runOnUiThread {
                c.removeView(loadingView)
                val walletOk = walletResult?.optBoolean("ok") == true
                if (!walletOk) {
                    c.addView(label("⚠️ " + (walletResult?.optString("message")?.takeIf { it.isNotBlank() } ?: "Không tải được ví XU, vui lòng thử lại."), 14f, danger))
                    c.addView(button("Tải lại") { showXu() }.apply { layoutParams = LinearLayout.LayoutParams(-1,-2).apply { topMargin = dp(8) } })
                    return@runOnUiThread
                }
                val data = walletResult!!.optJSONObject("data") ?: JSONObject()
                val wallet = data.optJSONObject("wallet") ?: JSONObject()
                val today = data.optJSONObject("today") ?: JSONObject()
                val thisHour = data.optJSONObject("this_hour") ?: JSONObject()
                val balance = wallet.optInt("balance", 0)

                val card=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;background=bg(Color.WHITE,18);setPadding(dp(16),dp(16),dp(16),dp(16))}
                card.addView(label("${String.format("%,d",balance)} XU",30f,primary,true))
                card.addView(label("Hôm nay: ${today.optInt("earned",0)}/${today.optInt("limit",2000)} XU",14f,secondary))
                card.addView(label("Giờ này: ${thisHour.optInt("earned",0)}/${thisHour.optInt("limit",200)} XU",14f,secondary))
                c.addView(card,LinearLayout.LayoutParams(-1,-2).apply{bottomMargin=dp(12)})
                c.addView(label("🎯 Cách nhận XU",18f,dark,true))
                c.addView(label("• Xem một sản phẩm đủ 30 giây → +10 XU\n• Xem đủ 10 sản phẩm khác nhau → +100 XU\n• Tối đa 200 XU mỗi giờ\n• Tối đa 2.000 XU mỗi ngày",14f,secondary))
                c.addView(label("🎁 Đổi XU",18f,dark,true).apply{setPadding(0,dp(16),0,dp(6))})

                val rewardsOk = rewardsResult?.optBoolean("ok") == true
                val rewards = rewardsResult?.optJSONObject("data")?.optJSONArray("rewards")
                if (!rewardsOk || rewards == null || rewards.length() == 0) {
                    c.addView(label("Chưa có phần thưởng nào để đổi.", 13f, secondary))
                } else {
                    for (i in 0 until rewards.length()) {
                        val r = rewards.getJSONObject(i)
                        val rewardId = r.optInt("id")
                        val cost = r.optInt("xu_cost")
                        val title = r.optString("title")
                        c.addView(ghostButton("$cost XU → $title"){
                            executor.execute {
                                val redeemResult = try { xuApi.redeem(rewardId) } catch (_: Exception) { null }
                                runOnUiThread {
                                    val rOk = redeemResult?.optBoolean("ok") == true
                                    if (rOk) {
                                        val code = redeemResult?.optJSONObject("data")?.optString("claim_code") ?: ""
                                        toast(if (code.isNotBlank()) "Đã đổi \"$title\" — mã: $code" else "Đã đổi \"$title\"")
                                        showXu()
                                    } else {
                                        toast(redeemResult?.optString("message")?.takeIf { it.isNotBlank() } ?: "Không đổi được XU lúc này")
                                    }
                                }
                            }
                        }.apply{layoutParams=LinearLayout.LayoutParams(-1,-2).apply{bottomMargin=dp(7)}})
                    }
                }
            }
        }
    }

    // =========================================================================
    // GIỎ HÀNG (cục bộ) -> ĐẶT HÀNG THẬT qua API (order_preview + create_order)
    // =========================================================================
    private fun showCart() {
        val s = shell("Giỏ hàng", 2); setContentView(s); val c = contentOf(s)
        if (cart.isEmpty()) { c.addView(label("🛒 Giỏ hàng đang trống", 20f, dark, true)); c.addView(button("Xem thực đơn") { push({ showCart() }) { showMenu() } }); return }

        val listBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }; c.addView(listBox)
        val loadingView = loading(listBox, "Đang cập nhật giỏ hàng...")

        fun renderCart(foods: List<Food>) {
            listBox.removeAllViews()
            val map = foods.associateBy { it.id }
            var total = 0
            cart.toMap().forEach { (id, qty) ->
                val f = map[id] ?: run { cart.remove(id); return@forEach }
                total += f.price * qty
                val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; background = bg(Color.WHITE, 14); setPadding(dp(10), dp(9), dp(10), dp(9)); layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) } }
                val img = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; background = bg(Color.rgb(240, 250, 241), 12) }
                row.addView(img, LinearLayout.LayoutParams(dp(52), dp(52))); ImageLoader.load(img, f.image)
                row.addView(label("${f.name}\n${money(f.price)}", 15f, dark, true), LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(8) })
                val stepper = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                stepper.addView(TextView(this).apply { text = "−"; textSize = 18f; setTextColor(primary); setTypeface(null, Typeface.BOLD); setPadding(dp(10), dp(4), dp(10), dp(4)); setOnClickListener { if (qty <= 1) cart.remove(id) else cart[id] = qty - 1; saveLocalCart(); refreshCartBadge(); renderCart(foods) } })
                stepper.addView(label("$qty", 15f, dark, true))
                stepper.addView(TextView(this).apply { text = "+"; textSize = 18f; setTextColor(primary); setTypeface(null, Typeface.BOLD); setPadding(dp(10), dp(4), dp(10), dp(4)); setOnClickListener { if (qty + 1 > f.stock) { toast("Chỉ còn ${f.stock} phần"); return@setOnClickListener }; cart[id] = qty + 1; saveLocalCart(); refreshCartBadge(); renderCart(foods) } })
                row.addView(stepper); listBox.addView(row)
            }
            if (cart.isEmpty()) { listBox.addView(label("🛒 Giỏ hàng đang trống", 18f, dark, true)); listBox.addView(button("Xem thực đơn") { push({ showCart() }) { showMenu() } }); return }
            listBox.addView(label("Tổng cộng: ${money(total)}", 20f, primary, true).apply { gravity = Gravity.END; setPadding(0, dp(12), 0, dp(12)) })
            listBox.addView(button("Đặt hàng — ${money(total)}") { push({ showCart() }) { showCheckout() } })
        }

        executor.execute {
            val r = if (foodsCache.isNotEmpty()) null else account.request("menu", query = mapOf("kcn_id" to KcnStore.id(this).toString()))
            runOnUiThread {
                listBox.removeView(loadingView)
                val foods = if (foodsCache.isNotEmpty()) foodsCache else {
                    val arr = r?.optJSONObject("data")?.optJSONArray("foods") ?: JSONArray()
                    val list = mutableListOf<Food>()
                    for (i in 0 until arr.length()) { val f = arr.getJSONObject(i); list.add(Food(f.optInt("id"), f.optString("name"), f.optInt("price"), f.optInt("stock"), f.optString("category"), f.optString("description"), f.optString("image"))) }
                    foodsCache = list; list
                }
                renderCart(foods)
            }
        }
    }

    // Đặt hàng: nhập địa chỉ -> order_preview (server tính lại giá/tồn kho, kiểm
    // tra khoảng cách giao hàng nếu có) -> create_order (kèm Idempotency-Key).
    private fun showCheckout() {
        if (!account.isLoggedIn()) { toast("Vui lòng đăng nhập để đặt hàng"); showLogin(); return }
        val s = shell("Xác nhận đặt hàng", 2); setContentView(s); val c = contentOf(s)
        c.addView(label("Thông tin giao hàng", 18f, dark, true))

        val noticeBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = bg(Color.rgb(255, 244, 230), 12)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        noticeBox.addView(label("🚴 Lưu ý khi đặt hàng", 16f, Color.rgb(180, 95, 6), true))
        noticeBox.addView(label(
            "Phí vận chuyển được tính từ trung tâm KCN. Vui lòng ghi rõ số nhà, đường, phường/xã, tỉnh/thành để hệ thống xác định chính xác khoảng cách. Vui lòng ghi rõ thời gian muốn nhận hàng. Giờ cao điểm có thể sai lệch giờ bạn muốn nhận",
            12.5f, Color.rgb(120, 76, 20)
        ).apply { setPadding(0, dp(3), 0, 0) })
        c.addView(noticeBox, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8); bottomMargin = dp(10) })

        val address = EditText(this).apply {
            hint = "Địa chỉ giao hàng (số nhà, đường, phường/xã...)"
            setText(lastAddress()); textSize = 15f
            setPadding(dp(12), dp(10), dp(12), dp(10)); background = bg(Color.WHITE, 12)
        }
        val time = EditText(this).apply {
            hint = "Giờ giao hàng (ví dụ : 11h30, bắt buộc)"; textSize = 15f
            setPadding(dp(12), dp(10), dp(12), dp(10)); background = bg(Color.WHITE, 12)
        }
        val note = EditText(this).apply {
            hint = " Số điện thoại người nhận hàng (bắt buộc)"; textSize = 15f
            setPadding(dp(12), dp(10), dp(12), dp(10)); background = bg(Color.WHITE, 12)
        }
        listOf(address, time, note).forEach { c.addView(it, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) }) }

        val summaryBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        c.addView(summaryBox, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })

        fun itemsJson(): JSONArray {
            val arr = JSONArray()
            cart.forEach { (id, qty) -> arr.put(JSONObject().put("food_id", id).put("qty", qty)) }
            return arr
        }

        val previewBtn = button("Xem lại tổng tiền") {
            if (address.text.toString().trim().isBlank()) { toast("Vui lòng nhập địa chỉ giao hàng"); return@button }
            if (time.text.toString().trim().isBlank()) { toast("Vui lòng nhập giờ giao hàng"); return@button }

            summaryBox.removeAllViews()
            val loadingView = loading(summaryBox, "Đang kiểm tra đơn hàng, XU & phí giao hàng...")
            executor.execute {
                val body = JSONObject()
                    .put("address", address.text.toString().trim())
                    .put("kcn_id", KcnStore.id(this))
                    .put("items", itemsJson())
                    .toString()
                val r = account.request("order_preview", "POST", body)
                val data = r.optJSONObject("data") ?: JSONObject()
                runOnUiThread {
                    summaryBox.removeView(loadingView)
                    if (!r.optBoolean("ok")) {
                        summaryBox.addView(label(r.optString("message", "Không thể tính đơn hàng."), 14f, danger))
                        return@runOnUiThread
                    }

                    val subtotal = data.optInt("subtotal", data.optInt("total"))
                    val shipping = data.optJSONObject("shipping") ?: JSONObject()
                    val distance = shipping.optDouble("distance_km", Double.NaN)
                    val shippingFee = shipping.optInt("fee", shipping.optInt("normal_fee", 0))
                    val shipperFee = shipping.optInt("shipper_fee", 0)
                    val freeShipping = shipping.optBoolean("free", shipping.optBoolean("free_shipping", false))
                    val kcnName = shipping.optString("kcn_name", KcnStore.name(this))
                    val centerAddress = shipping.optString("center_address", "")
                    val distanceSource = shipping.optString("distance_source", "road")

                    if (!distance.isNaN()) {
                        val distanceText = if (distanceSource == "road") "📍 Khoảng cách đường đi" else "📍 Khoảng cách ước tính"
                        summaryBox.addView(label(
                            "$distanceText: ${String.format("%.1f", distance)} km",
                            14f, ok, true
                        ))
                    }
                    if (kcnName.isNotBlank()) summaryBox.addView(label("🏭 $kcnName", 13f, secondary))
                    if (centerAddress.isNotBlank()) summaryBox.addView(label("Tính từ: $centerAddress", 12f, secondary))

                    summaryBox.addView(label("Tiền món: ${money(subtotal)}", 17f, dark, true))
                    summaryBox.addView(label(
                        if (freeShipping) "🚚 Phí vận chuyển: FREE SHIP" else "🚚 Phí vận chuyển: ${money(shippingFee)}",
                        16f, if (freeShipping) ok else dark, true
                    ))
                    if (shipperFee > 0) {
                        // Chỉ hiển thị phí khách; tiền shipper là dữ liệu nội bộ và
                        // không nên để lộ cho khách hàng.
                    }

                    val xu = data.optJSONObject("xu") ?: JSONObject()
                    val xuBalance = xu.optInt("balance", 0)
                    val maxXu = xu.optInt("max_discount", minOf(xuBalance, subtotal))
                    val xuRate = xu.optInt("rate", 1).coerceAtLeast(1)
                    val xuExpires = xu.optString("expires_at", "")

                    val xuBox = LinearLayout(this).apply {
                        orientation = LinearLayout.VERTICAL
                        background = bg(Color.rgb(255, 250, 230), 12)
                        setPadding(dp(12), dp(10), dp(12), dp(10))
                        layoutParams = LinearLayout.LayoutParams(-1, -2).apply {
                            topMargin = dp(10); bottomMargin = dp(8)
                        }
                    }
                    xuBox.addView(label("🪙 Sử dụng XU để giảm tiền", 15f, Color.rgb(160, 105, 0), true))
                    xuBox.addView(label(
                        "Số dư: ${xuBalance} XU • 1 XU = ${xuRate}đ. Tối đa dùng: ${maxXu} XU",
                        13f, secondary
                    ))
                    if (xuExpires.isNotBlank()) {
                        xuBox.addView(label("Hạn sử dụng số XU hiện có: $xuExpires", 12f, Color.rgb(160, 105, 0)))
                    }

                    val xuRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                    val useXu = CheckBox(this).apply { text = "Dùng XU"; textSize = 14f; isEnabled = maxXu > 0 }
                    val xuInput = EditText(this).apply {
                        hint = "0"; inputType = android.text.InputType.TYPE_CLASS_NUMBER
                        textSize = 15f; setSingleLine(true); setPadding(dp(10), dp(6), dp(10), dp(6)); background = bg(Color.WHITE, 10)
                        isEnabled = false
                    }
                    val maxBtn = button("Dùng tối đa") {
                        if (maxXu > 0) { useXu.isChecked = true; xuInput.isEnabled = true; xuInput.setText(maxXu.toString()) }
                    }
                    xuRow.addView(useXu, LinearLayout.LayoutParams(0, -2, 0.8f))
                    xuRow.addView(xuInput, LinearLayout.LayoutParams(dp(100), -2).apply { marginEnd = dp(6) })
                    xuRow.addView(maxBtn, LinearLayout.LayoutParams(dp(105), -2))
                    xuBox.addView(xuRow)
                    summaryBox.addView(xuBox)

                    val discountLabel = label("Giảm bằng XU: 0đ", 15f, primary, true)
                    val payableLabel = label("CẦN THANH TOÁN: ${money(subtotal + shippingFee)}", 21f, primary, true).apply {
                        setPadding(0, dp(8), 0, dp(8)); gravity = Gravity.END
                    }
                    summaryBox.addView(discountLabel)
                    summaryBox.addView(payableLabel)

                    fun selectedXu(): Int {
                        if (!useXu.isChecked) return 0
                        val raw = xuInput.text.toString().trim().toIntOrNull() ?: 0
                        return raw.coerceIn(0, maxXu)
                    }
                    fun refreshPayable() {
                        val xuUse = selectedXu()
                        val discount = xuUse * xuRate
                        discountLabel.text = "Giảm bằng XU: -${money(discount)}"
                        payableLabel.text = "CẦN THANH TOÁN: ${money(maxOf(0, subtotal + shippingFee - discount))}"
                    }
                    useXu.setOnCheckedChangeListener { _, checked ->
                        xuInput.isEnabled = checked && maxXu > 0
                        if (!checked) xuInput.setText("0")
                        refreshPayable()
                    }
                    xuInput.addTextChangedListener(object : android.text.TextWatcher {
                        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { refreshPayable() }
                        override fun afterTextChanged(s: android.text.Editable?) = Unit
                    })

                    summaryBox.addView(button("✅ Đặt hàng ngay") {
                        if (time.text.toString().trim().isBlank()) { toast("Vui lòng nhập giờ giao hàng"); return@button }
                        val xuUse = selectedXu()
                        if (xuUse > maxXu) { toast("Số XU sử dụng vượt quá số dư cho phép"); return@button }

                        summaryBox.addView(label("⏳ Đang tạo đơn hàng...", 14f, secondary))
                        val idem = UUID.randomUUID().toString()
                        val orderBody = JSONObject()
                            .put("address", address.text.toString().trim())
                            .put("delivery_time", time.text.toString().trim())
                            .put("note", note.text.toString().trim())
                            .put("items", itemsJson())
                            .put("xu_use", xuUse)
                            .toString()
                        executor.execute {
                            val cr = account.request("create_order", "POST", orderBody, mapOf("X-Idempotency-Key" to idem))
                            runOnUiThread {
                                if (!cr.optBoolean("ok")) {
                                    toast(cr.optString("message", "Đặt hàng thất bại"))
                                    return@runOnUiThread
                                }
                                saveLastAddress(address.text.toString().trim())
                                cart.clear(); saveLocalCart()
                                val code = cr.optJSONObject("data")?.optJSONObject("order")?.optString("code") ?: ""
                                toast("Đặt hàng thành công!")
                                push({ showOrders() }) { showOrderDetail(code) }
                            }
                        }
                    }.apply { layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) } })
                }
            }
        }
        c.addView(previewBtn, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
    }

    // =========================================================================
    // ĐƠN HÀNG — danh sách & chi tiết lấy từ server (không lưu cục bộ).
    // =========================================================================
    private fun showOrders() {
        if (!account.isLoggedIn()) { toast("Vui lòng đăng nhập để xem đơn hàng"); showLogin(); return }
        val s = shell("Đơn hàng", 3); setContentView(s); val c = contentOf(s)
        val listBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }; c.addView(listBox)
        val loadingView = loading(listBox)
        executor.execute {
            val r = account.request("orders")
            runOnUiThread {
                listBox.removeView(loadingView)
                if (!r.optBoolean("ok")) { listBox.addView(label(r.optString("message", "Không tải được đơn hàng."), 14f, danger)); return@runOnUiThread }
                val arr = r.optJSONObject("data")?.optJSONArray("orders") ?: JSONArray()
                if (arr.length() == 0) { listBox.addView(label("📦 Chưa có đơn hàng nào", 20f, dark, true)); listBox.addView(label("Đơn hàng được đồng bộ trực tiếp với tài khoản trên website.", 13f, secondary)); listBox.addView(button("Đặt món ngay") { push({ showOrders() }) { showMenu() } }); return@runOnUiThread }
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val payStatus = o.optString("payment_status", "pending")
                    val card = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL; background = bg(Color.WHITE, 15); setPadding(dp(14), dp(12), dp(14), dp(12)); layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(9) } }
                    card.addView(label("#${o.optString("code")}", 16f, dark, true))
                    card.addView(label("${money(o.optInt("total"))} • ${o.optString("created_at")}", 14f, secondary))
                    val row = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                    row.addView(label("Trạng thái: ${o.optString("status")}", 14f, primary, true), LinearLayout.LayoutParams(0, -2, 1f))
                    row.addView(label(if (payStatus == "paid") "✅ Đã thanh toán" else "💳 Chờ thanh toán", 13f, if (payStatus == "paid") ok else Color.rgb(198, 130, 8)))
                    card.addView(row)
                    card.setOnClickListener { push({ showOrders() }) { showOrderDetail(o.optString("code")) } }
                    listBox.addView(card)
                }
            }
        }
    }

    private fun showOrderDetail(code: String) {
        stopPolling()
        val s = shell("Đơn $code", 3); setContentView(s); val c = contentOf(s)
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }; c.addView(box)
        loading(box)
        renderOrderDetail(box, code)
    }

    private fun renderOrderDetail(box: LinearLayout, code: String) {
        executor.execute {
            val r = account.request("order", "GET", null, emptyMap(), mapOf("code" to code))
            runOnUiThread {
                box.removeAllViews()
                if (!r.optBoolean("ok")) { box.addView(label(r.optString("message", "Không tải được đơn hàng."), 14f, danger)); return@runOnUiThread }
                val data = r.optJSONObject("data") ?: JSONObject()
                val o = data.optJSONObject("order") ?: JSONObject()
                val items = data.optJSONArray("items") ?: JSONArray()
                val payment = data.optJSONObject("payment")
                val status = o.optString("status")
                val payStatus = o.optString("payment_status", "pending")
                val confirmed = status in listOf("Đã xác nhận", "Đang nấu", "Đang giao", "Hoàn thành")

                box.addView(label("Mã đơn: $code", 16f, dark, true))
                box.addView(label("Trạng thái: $status", 15f, primary, true))
                box.addView(label("Thanh toán: " + if (payStatus == "paid") "✅ Đã thanh toán" else "💳 Chờ thanh toán", 14f, if (payStatus == "paid") ok else Color.rgb(198, 130, 8)))
                box.addView(label("Địa chỉ: ${o.optString("address")}", 13f, secondary))
                if (o.optString("delivery_time").isNotBlank()) box.addView(label("Giờ giao: ${o.optString("delivery_time")}", 13f, secondary))
                if (o.optString("note").isNotBlank()) box.addView(label("Ghi chú: ${o.optString("note")}", 13f, secondary))

                box.addView(label("Món đã đặt", 16f, dark, true).apply { setPadding(0, dp(14), 0, dp(6)) })
                for (i in 0 until items.length()) {
                    val it = items.getJSONObject(i)
                    box.addView(label("• ${it.optString("name")} x${it.optInt("qty")} — ${money(it.optInt("price") * it.optInt("qty"))}", 14f, dark))
                }
                box.addView(label("Tổng cộng: ${money(o.optInt("total"))}", 18f, primary, true).apply { setPadding(0, dp(10), 0, dp(4)) })

                // ---- THANH TOÁN QR (VietQR chuyển khoản ngân hàng) ----
                if (payStatus != "paid" && payment != null) {
                    val payBox = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; background = bg(Color.WHITE, 16); setPadding(dp(14), dp(14), dp(14), dp(14)) }
                    payBox.addView(label("💳 Quét mã để thanh toán", 16f, dark, true))
                    val qr = ImageView(this@MainActivity).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
                    payBox.addView(qr, LinearLayout.LayoutParams(dp(200), dp(200)).apply { topMargin = dp(8) })
                    ImageLoader.load(qr, payment.optString("qr_url"))
                    payBox.addView(label("Ngân hàng: ${payment.optString("bank_display_name")}", 13f, secondary))
                    payBox.addView(label("Số TK: ${payment.optString("bank_account_no")}", 13f, secondary))
                    payBox.addView(label("Chủ TK: ${payment.optString("bank_account_name")}", 13f, secondary))
                    payBox.addView(label("Nội dung CK: ${payment.optString("transfer_content")}", 13f, dark, true))
                    payBox.addView(label("Số tiền: ${money(payment.optInt("amount"))}", 15f, primary, true))
                    payBox.addView(label("Hệ thống tự động xác nhận sau khi nhận được chuyển khoản.", 12f, secondary).apply { setPadding(0, dp(6), 0, 0) })
                    box.addView(payBox, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })

                    // Tự động dò trạng thái thanh toán mỗi 4 giây, giống order.php trên web.
                    stopPolling()
                    val runnable = object : Runnable {
                        override fun run() { renderOrderDetail(box, code); handler.postDelayed(this, 4000) }
                    }
                    pollRunnable = runnable
                    handler.postDelayed(runnable, 4000)
                } else if (status == "Đang giao") {
                    // BUGFIX 01/09: tự làm mới mỗi 4 giây trong lúc "Đang giao" để
                    // mã OTP hiện ra ngay khi shipper bấm "Bắt đầu giao", khách
                    // không cần thoát ra vào lại màn hình mới thấy.
                    stopPolling()
                    val runnable = object : Runnable {
                        override fun run() { renderOrderDetail(box, code); handler.postDelayed(this, 4000) }
                    }
                    pollRunnable = runnable
                    handler.postDelayed(runnable, 4000)
                } else stopPolling()

                // ---- MÃ OTP GIAO HÀNG (đọc cho tài xế khi nhận hàng) ----
                val otpCode = o.optString("otp_code", "")
                if (status == "Đang giao" && otpCode.isNotBlank() && otpCode != "null") {
                    val otpBox = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL; background = bg(Color.parseColor("#FFFAEB"), 12); setPadding(dp(14), dp(12), dp(14), dp(12)) }
                    otpBox.addView(label("🔑 Mã xác nhận giao hàng", 14f, dark, true))
                    otpBox.addView(label(otpCode, 22f, Color.parseColor("#B54708"), true))
                    otpBox.addView(label("Đọc mã này cho tài xế khi nhận hàng.", 12f, secondary))
                    box.addView(otpBox, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
                }

                // ---- XÁC NHẬN ĐÃ NHẬN HÀNG ----
                if ((o.optInt("delivery_confirmed") == 1)) {
                    box.addView(label("✅ Đã nhận hàng • +${o.optInt("points_earned")} điểm", 14f, ok, true).apply { setPadding(0, dp(10), 0, 0) })
                } else if (status == "Hoàn thành") {
                    box.addView(button("📦 Tôi đã nhận hàng") {
                        executor.execute {
                            val cr = account.request("confirm_delivery", "POST", JSONObject().put("code", code).toString())
                            runOnUiThread { toast(cr.optString("message", if (cr.optBoolean("ok")) "Đã xác nhận" else "Có lỗi xảy ra")); renderOrderDetail(box, code) }
                        }
                    }.apply { layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) } })
                }

                // ---- MÃ QUAY THƯỞNG ----
                val luckyCode = o.optString("lucky_code", "")
                if (confirmed && luckyCode.isNotBlank() && luckyCode != "null") {
                    box.addView(button("🎁 Dùng mã quay thưởng: $luckyCode") { push({ showOrderDetail(code) }) { showLucky(luckyCode) } }.apply { layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) } })
                }
            }
        }
    }

    // =========================================================================
    // QUAY SỐ MAY MẮN — quay thưởng theo mã đơn (dùng 1 lần).
    // =========================================================================
    private fun showLucky(prefillCode: String?) {
        if (!account.isLoggedIn()) { toast("Vui lòng đăng nhập"); showLogin(); return }
        val s = shell("Quay số trúng thưởng", 4); setContentView(s); val c = contentOf(s)
        c.addView(label("🎁 Bốc thăm trúng thưởng", 20f, dark, true))
        c.addView(label("Mỗi đơn hàng đã xác nhận tặng 1 mã quay thưởng, dùng được đúng 1 lần.", 13f, secondary))
        val codeInput = EditText(this).apply { hint = "VD: LK-7K9QRX"; setText(prefillCode ?: ""); textSize = 16f; setPadding(dp(12), dp(10), dp(12), dp(10)); background = bg(Color.WHITE, 12) }
        c.addView(codeInput, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10); bottomMargin = dp(10) })
        val resultBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }; c.addView(resultBox)
        c.addView(button("🎁 Quay ngay") {
            val code = codeInput.text.toString().trim().uppercase()
            if (code.isBlank()) { toast("Vui lòng nhập mã quay thưởng"); return@button }
            resultBox.removeAllViews(); val lv = loading(resultBox, "Đang quay số...")
            executor.execute {
                val r = account.request("lucky_draw", "POST", JSONObject().put("code", code).toString())
                runOnUiThread {
                    resultBox.removeView(lv)
                    if (!r.optBoolean("ok")) { resultBox.addView(label(r.optString("message", "Có lỗi xảy ra."), 14f, danger)); return@runOnUiThread }
                    val data = r.optJSONObject("data") ?: JSONObject()
                    val already = data.optBoolean("already")
                    val prizeBox = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; background = bg(Color.WHITE, 16); setPadding(dp(16), dp(16), dp(16), dp(16)) }
                    prizeBox.addView(label(if (already) "Mã này đã được sử dụng rồi!" else "🎉 Chúc mừng bạn!", 17f, dark, true))
                    prizeBox.addView(label("🎁 ${data.optString("prize_name")}", 20f, primary, true).apply { setPadding(0, dp(8), 0, dp(8)) })
                    prizeBox.addView(label(if (already) "Mỗi mã chỉ quay được 1 lần. Đặt thêm đơn để nhận mã mới nhé!" else "Vui lòng liên hệ quán để nhận thưởng.", 13f, secondary))
                    resultBox.addView(prizeBox)
                }
            }
        }.apply { layoutParams = LinearLayout.LayoutParams(-1, -2) })
        c.addView(ghostButton("🔢 Số may mắn hằng ngày") { push({ showLucky(prefillCode) }) { showDaily() } }.apply { layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) } })
    }

    // =========================================================================
    // SỐ MAY MẮN HẰNG NGÀY — chương trình tự động, đối 4 số cuối mã quay thưởng
    // với số công bố lúc 16:15 giờ VN hằng ngày.
    // =========================================================================
    private fun showDaily() {
        if (!account.isLoggedIn()) { toast("Vui lòng đăng nhập"); showLogin(); return }
        val s = shell("Số may mắn hằng ngày", 4); setContentView(s); val c = contentOf(s)
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }; c.addView(box)
        val lv = loading(box)
        executor.execute {
            val r = account.request("daily_number")
            runOnUiThread {
                box.removeView(lv)
                if (!r.optBoolean("ok")) { box.addView(label(r.optString("message", "Không tải được dữ liệu."), 14f, danger)); return@runOnUiThread }
                val d = r.optJSONObject("data") ?: JSONObject()
                val prizeBox = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; background = bg(Color.WHITE, 16); setPadding(dp(16), dp(16), dp(16), dp(16)) }
                prizeBox.addView(label("Số dự thưởng kỳ ${d.optString("period")}", 15f, dark, true))
                if (d.optBoolean("published")) {
                    prizeBox.addView(label(d.optString("number"), 30f, primary, true))
                    prizeBox.addView(label("✅ Số của kỳ này đã được công bố.", 13f, secondary))
                } else if (d.optBoolean("draw_due")) {
                    prizeBox.addView(label("⏳ Chưa công bố", 22f, secondary))
                    prizeBox.addView(label("Đã đến giờ quay nhưng số chưa được duyệt & công bố.", 13f, secondary))
                } else {
                    prizeBox.addView(label("⏳ Chưa quay", 22f, secondary))
                    prizeBox.addView(label("Hệ thống tự bốc số lúc ${d.optString("draw_time")} hôm nay.", 13f, secondary))
                }
                box.addView(prizeBox, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(14) })

                box.addView(label("Thể lệ (đối 4 số cuối mã quay thưởng, từ phải sang):", 14f, dark, true))
                val rules = d.optJSONArray("rules") ?: JSONArray()
                for (i in 0 until rules.length()) { val ru = rules.getJSONObject(i); box.addView(label("• ${ru.optString("label")} — ${ru.optString("prize")}", 13f, secondary)) }

                val history = d.optJSONArray("history") ?: JSONArray()
                if (history.length() > 0) {
                    box.addView(label("Lịch sử mã dự thưởng", 16f, dark, true).apply { setPadding(0, dp(14), 0, dp(6)) })
                    for (i in 0 until history.length()) {
                        val h = history.getJSONObject(i)
                        val matched = h.optBoolean("matched")
                        val row = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL; background = bg(Color.WHITE, 12); setPadding(dp(10), dp(9), dp(10), dp(9)); layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(7) } }
                        row.addView(label("#${h.optString("order_code")} • kỳ ${h.optString("week_key")} • số cuối ${h.optString("digits")}", 13f, dark))
                        row.addView(label(if (matched) "🎉 Trúng ${h.optString("tier_label")} — ${h.optString("prize_name")}" else "❌ Không trúng", 13f, if (matched) ok else secondary, matched))
                        box.addView(row)
                    }
                }
            }
        }
    }

    // =========================================================================
    // ĐIỂM TÍCH LUỸ / HẠNG THÀNH VIÊN
    // =========================================================================
    private fun showLoyalty() {
        if (!account.isLoggedIn()) { toast("Vui lòng đăng nhập"); showLogin(); return }
        val s = shell("Điểm tích luỹ", 4); setContentView(s); val c = contentOf(s)
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }; c.addView(box)
        val lv = loading(box)
        executor.execute {
            val r = account.request("loyalty")
            runOnUiThread {
                box.removeView(lv)
                if (!r.optBoolean("ok")) { box.addView(label(r.optString("message", "Không tải được dữ liệu."), 14f, danger)); return@runOnUiThread }
                val d = r.optJSONObject("data") ?: JSONObject()
                val points = d.optInt("points")
                val prizeBox = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL; background = bg(Color.WHITE, 16); setPadding(dp(16), dp(16), dp(16), dp(16)) }
                prizeBox.addView(label("★ ${String.format("%,d", points)} điểm", 24f, primary, true))
                val next = d.optJSONObject("next_tier")
                if (next != null) prizeBox.addView(label("Còn ${next.optInt("points_needed")} điểm nữa để đổi \"${next.optString("reward_name")}\".", 13f, secondary))
                else prizeBox.addView(label("Bạn đã đạt mốc quà cao nhất hiện có.", 13f, secondary))
                prizeBox.addView(label("Điểm được cộng sau khi bạn xác nhận đã nhận hàng. Mỗi 50.000đ = 1 điểm.", 12f, secondary).apply { setPadding(0, dp(6), 0, 0) })
                box.addView(prizeBox, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(14) })

                box.addView(label("Mốc quà", 16f, dark, true))
                val tiers = d.optJSONArray("tiers") ?: JSONArray()
                for (i in 0 until tiers.length()) {
                    val t = tiers.getJSONObject(i); val eligible = t.optBoolean("eligible")
                    val row = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; background = bg(Color.WHITE, 12); setPadding(dp(10), dp(9), dp(10), dp(9)); layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) } }
                    row.addView(label("${t.optInt("points_required")} điểm — ${t.optString("reward_name")}", 13.5f, dark), LinearLayout.LayoutParams(0, -2, 1f))
                    row.addView(label(if (eligible) "✓ Đủ điều kiện" else "Chưa đủ", 12.5f, if (eligible) ok else secondary))
                    box.addView(row)
                }

                val redemptions = d.optJSONArray("redemptions") ?: JSONArray()
                if (redemptions.length() > 0) {
                    box.addView(label("Lịch sử đổi quà", 16f, dark, true).apply { setPadding(0, dp(14), 0, dp(6)) })
                    for (i in 0 until redemptions.length()) {
                        val red = redemptions.getJSONObject(i)
                        box.addView(label("• ${red.optString("reward_name")} — ${red.optInt("points_used")} điểm (${red.optString("created_at")})", 13f, secondary))
                    }
                }
            }
        }
    }

    // =========================================================================
    // MÓN YÊU THÍCH — lưu cục bộ trên máy (FavoriteStore), không cần API riêng.
    // Lấy toàn bộ thực đơn hiện tại rồi lọc đúng các món đã lưu id.
    // =========================================================================
    private fun showFavorites() {
        val s = shell("Món yêu thích", 4); setContentView(s); val c = contentOf(s)
        c.addView(label("❤️ Món bạn yêu thích", 22f, dark, true))
        c.addView(label("Danh sách này được lưu trên điện thoại của bạn.", 13f, secondary))
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        c.addView(box, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        val favIds = FavoriteStore.ids(this)
        if (favIds.isEmpty()) {
            box.addView(label("Bạn chưa lưu món nào.", 15f, secondary))
            box.addView(button("🍚 Xem thực đơn") { push({ showFavorites() }) { showMenu() } }.apply { layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) } })
            return
        }
        val loadingView = loading(box, "Đang tải món yêu thích...")
        executor.execute {
            val r = try { account.request("menu", query = mapOf("kcn_id" to KcnStore.id(this).toString())) } catch (_: Exception) { null }
            runOnUiThread {
                box.removeView(loadingView)
                val arr = r?.optJSONObject("data")?.optJSONArray("foods") ?: JSONArray()
                val foods = mutableListOf<Food>()
                for (i in 0 until arr.length()) {
                    val f = arr.getJSONObject(i)
                    if (favIds.contains(f.optInt("id"))) foods.add(Food(f.optInt("id"), f.optString("name"), f.optInt("price"), f.optInt("stock"), f.optString("category"), f.optString("description"), f.optString("image")))
                }
                if (foods.isEmpty()) box.addView(label("Các món đã lưu có thể đã ngừng bán.", 14f, secondary))
                else foods.forEachIndexed { idx, food -> box.addView(foodCard(food, foods, idx, backTo = { showFavorites() })) }
            }
        }
    }

    // =========================================================================
    // TÀI KHOẢN — thông tin đầy đủ, đồng bộ trực tiếp với tài khoản trên web.
    // =========================================================================
    private fun showProfile() {
        val s = shell("Tài khoản", 4); setContentView(s); val c = contentOf(s)
        if (!account.isLoggedIn()) { c.addView(label("👤 Tài khoản khách hàng", 22f, dark, true)); c.addView(label("Đăng nhập để đồng bộ tài khoản với tài khoản COM11H đang dùng trên website.")); c.addView(button("🔐 Đăng nhập / Đăng ký") { push({ showProfile() }) { showLogin() } }); return }
        c.addView(label("👤 Tài khoản khách hàng", 22f, dark, true)); val loadingView = label("Đang đồng bộ thông tin tài khoản...", 15f, secondary); c.addView(loadingView)
        executor.execute {
            try {
                val r = account.request("profile")
                // Lấy luôn số dư XU thật từ server (cùng lượt tải với hồ sơ) thay vì
                // đọc bộ nhớ tạm trên máy — tránh hiển thị sai lệch với ví XU thật.
                val xuResult = try { xuApi.wallet() } catch (_: Exception) { null }
                val xuBalance = if (xuResult?.optBoolean("ok") == true) {
                    xuResult.optJSONObject("data")?.optJSONObject("wallet")?.optInt("balance", 0) ?: 0
                } else 0
                runOnUiThread {
                    c.removeView(loadingView)
                    if (r.optBoolean("ok")) {
                        val customer = r.optJSONObject("data")?.optJSONObject("customer") ?: r.optJSONObject("data") ?: JSONObject()
                        c.addView(label("Họ tên: ${customer.optString("name", "Chưa cập nhật")}", 17f, dark, true))
                        c.addView(label("Số điện thoại: ${customer.optString("phone", "")}"))
                        c.addView(label("Điểm tích lũy: ${customer.optInt("points", 0)} điểm", 18f, primary, true))
                        c.addView(label("🪙 Ví XU: $xuBalance XU", 18f, primary, true))
                        c.addView(label("Toàn bộ đơn hàng, thanh toán và mã quay thưởng của bạn được đồng bộ trực tiếp với tài khoản trên website.", 12.5f, secondary))

                        val grid = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }
                        c.addView(grid, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
                        grid.addView(ghostButton("▤ Đơn hàng của tôi") { push({ showProfile() }) { showOrders() } }.apply { layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) } })
                        grid.addView(ghostButton("⭐ Điểm tích luỹ & hạng thành viên") { push({ showProfile() }) { showLoyalty() } }.apply { layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) } })
                        grid.addView(ghostButton("❤️ Món yêu thích") { push({ showProfile() }) { showFavorites() } }.apply { layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) } })
                        grid.addView(ghostButton("🪙 Ví XU & ưu đãi") { push({ showProfile() }) { showXu() } }.apply { layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) } })
                        grid.addView(ghostButton("🎁 Quay số trúng thưởng") { push({ showProfile() }) { showLucky(null) } }.apply { layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) } })
                        grid.addView(ghostButton("🔢 Số may mắn hằng ngày") { push({ showProfile() }) { showDaily() } }.apply { layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) } })

                        c.addView(button("🔄 Đồng bộ lại") { showProfile() }.apply { layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) } })
                        c.addView(ghostButton("🔒 Chính sách bảo mật & dữ liệu") { showPrivacyPolicy() }.apply { layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) } })
                        c.addView(ghostButton("🗑️ Yêu cầu xóa tài khoản và dữ liệu") { confirmDeleteAccount() }.apply { layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) } })
                        c.addView(button("🚪 Đăng xuất") { account.logout(); showProfile() }.apply { layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) } })
                    } else {
                        account.logout(); c.addView(label(r.optString("message", "Phiên đăng nhập đã hết hạn."))); c.addView(button("Đăng nhập lại") { push({ showProfile() }) { showLogin() } })
                    }
                }
            } catch (_: Exception) { runOnUiThread { loadingView.text = "Không thể đồng bộ tài khoản. Kiểm tra mạng rồi thử lại."; c.addView(button("Thử lại") { showProfile() }) } }
        }
    }

    private fun showPrivacyPolicy() {
        val s = shell("Chính sách bảo mật", 4); setContentView(s); val c = contentOf(s)
        c.addView(label("Food KCN xử lý thông tin tài khoản, số điện thoại, địa chỉ giao hàng, đơn hàng và dữ liệu cần thiết để cung cấp dịch vụ. Dữ liệu được truyền qua HTTPS đến máy chủ com11h.com. Mật khẩu không được lưu dạng rõ trên thiết bị.", 14f, secondary))
        c.addView(label("Bạn có quyền yêu cầu truy cập, chỉnh sửa hoặc xóa tài khoản và dữ liệu cá nhân theo chính sách của dịch vụ.", 14f, secondary).apply { setPadding(0, dp(10), 0, 0) })
        c.addView(ghostButton("🌐 Mở Chính sách quyền riêng tư") {
            startActivity(Intent(this, WebActivity::class.java).putExtra("url", "https://com11h.com/privacy-policy.php"))
        }.apply { layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) } })
        c.addView(button("‹ Quay lại") { showProfile() }.apply { layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) } })
    }

    private fun confirmDeleteAccount() {
        AlertDialog.Builder(this)
            .setTitle("Xóa tài khoản")
            .setMessage("Yêu cầu này sẽ xóa tài khoản và dữ liệu cá nhân gắn với tài khoản theo chính sách lưu trữ của dịch vụ. Đơn hàng hoặc dữ liệu bắt buộc lưu theo pháp luật có thể được giữ lại ở dạng phù hợp. Bạn có chắc chắn muốn tiếp tục không?")
            .setNegativeButton("Hủy", null)
            .setPositiveButton("Xóa tài khoản") { _, _ ->
                executor.execute {
                    try {
                        val r = account.deleteAccount()
                        runOnUiThread {
                            if (r.optBoolean("ok")) {
                                account.logout()
                                clearLoginCredentials()
                                Toast.makeText(this, "Tài khoản đã được yêu cầu xóa.", Toast.LENGTH_LONG).show()
                                startActivity(Intent(this, HomeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK))
                                finish()
                            } else {
                                val msg = r.optString("message", "Máy chủ chưa hỗ trợ xóa tài khoản. Vui lòng dùng trang tài khoản để gửi yêu cầu.")
                                AlertDialog.Builder(this).setTitle("Chưa thể xóa ngay").setMessage(msg).setPositiveButton("Mở trang tài khoản") { _, _ ->
                                    startActivity(Intent(this, WebActivity::class.java).putExtra("url", "https://com11h.com/delete-account.php"))
                                }.setNegativeButton("Đóng", null).show()
                            }
                        }
                    } catch (_: Exception) {
                        runOnUiThread {
                            AlertDialog.Builder(this).setTitle("Không kết nối được").setMessage("Không thể gửi yêu cầu xóa lúc này. Bạn có thể mở trang tài khoản để gửi yêu cầu xóa dữ liệu.").setPositiveButton("Mở trang tài khoản") { _, _ ->
                                startActivity(Intent(this, WebActivity::class.java).putExtra("url", "https://com11h.com/delete-account.php"))
                            }.setNegativeButton("Đóng", null).show()
                        }
                    }
                }
            }.show()
    }

    private fun input(hint: String, password: Boolean = false) = EditText(this).apply { this.hint = hint; textSize = 16f; setPadding(dp(12), dp(10), dp(12), dp(10)); if (password) inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD }

    // Bảo mật: KHÔNG lưu mật khẩu dạng rõ trên thiết bị.
    // Tính năng "ghi nhớ" chỉ lưu số điện thoại; token phiên được lưu riêng
    // trong AccountSync và dùng cho API.
    private fun savedLoginPrefs() = getSharedPreferences("com11h_saved_login", MODE_PRIVATE)
    private fun saveLoginPhone(phone: String) =
        savedLoginPrefs().edit().putString("phone", phone).remove("password").apply()
    private fun clearLoginCredentials() = savedLoginPrefs().edit().clear().apply()
    private fun savedPhone(): String = savedLoginPrefs().getString("phone", "") ?: ""

    private fun showLogin() {
        val s = shell("Đăng nhập", 4); setContentView(s); val c = contentOf(s); val phone = input("Số điện thoại"); val pass = input("Mật khẩu", true); c.addView(phone); c.addView(pass)
        val hasSaved = savedPhone().isNotBlank()
        if (hasSaved) phone.setText(savedPhone())
        val rememberBox = CheckBox(this).apply { text = "Ghi nhớ số điện thoại"; textSize = 14f; isChecked = hasSaved }
        c.addView(rememberBox.apply { layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) } })
        c.addView(button("Đăng nhập") { val p = phone.text.toString().trim(); val pw = pass.text.toString(); if (p.isBlank() || pw.isBlank()) { toast("Vui lòng nhập đầy đủ thông tin"); return@button }; executor.execute { try { val r = account.request("login", "POST", JSONObject(mapOf("phone" to p, "password" to pw, "device" to "COM11H Android")).toString()); runOnUiThread { if (r.optBoolean("ok")) { account.saveToken(r.optJSONObject("data")?.optString("token", "") ?: ""); if (rememberBox.isChecked) saveLoginPhone(p) else clearLoginCredentials(); toast("Đăng nhập thành công"); showProfile() } else toast(r.optString("message", "Đăng nhập thất bại")) } } catch (_: Exception) { runOnUiThread { toast("Không kết nối được máy chủ tài khoản") } } } })
        c.addView(ghostButton("Quên mật khẩu?") { push({ showLogin() }) { showForgotPasswordPhone() } }.apply { layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) } })
        c.addView(button("Đăng ký tài khoản mới") { push({ showLogin() }) { showRegister() } }.apply { layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) } })
    }

    // =========================================================================
    // ĐĂNG KÝ — 1 bước: nhập thông tin -> tạo tài khoản luôn, KHÔNG cần mã
    // xác thực (OTP chỉ dùng cho luồng quên mật khẩu, không dùng ở đây).
    // Dùng action 'register' (xem HUONG_DAN_OTP.md phía backend).
    // =========================================================================
    private fun showRegister() {
        val s = shell("Đăng ký tài khoản", 4); setContentView(s); val c = contentOf(s); val name = input("Họ tên"); val phone = input("Số điện thoại"); val pass = input("Mật khẩu", true); val pass2 = input("Nhập lại mật khẩu", true); c.addView(name); c.addView(phone); c.addView(pass); c.addView(pass2)
        c.addView(button("Đăng ký") {
            val n = name.text.toString().trim(); val p = phone.text.toString().trim(); val pw = pass.text.toString(); val pw2 = pass2.text.toString()
            if (n.isBlank() || p.isBlank() || pw.length < 6 || pw != pw2) { toast("Kiểm tra lại thông tin đăng ký"); return@button }
            executor.execute {
                try {
                    val body = JSONObject(mapOf("name" to n, "phone" to p, "password" to pw, "password2" to pw2, "device" to "COM11H Android")).toString()
                    val r = account.request("register", "POST", body)
                    runOnUiThread {
                        if (r.optBoolean("ok")) { account.saveToken(r.optJSONObject("data")?.optString("token", "") ?: ""); toast("Đăng ký thành công"); showProfile() }
                        else toast(r.optString("message", "Đăng ký thất bại"))
                    }
                } catch (_: Exception) { runOnUiThread { toast("Không kết nối được máy chủ tài khoản") } }
            }
        }.apply { layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) } })
        c.addView(button("Đăng nhập") { push({ showRegister() }) { showLogin() } }.apply { layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) } })
    }

    // =========================================================================
    // QUÊN MẬT KHẨU — 1 bước: nhập Tên + SĐT -> gửi yêu cầu. KHÔNG còn OTP:
    // admin nhận thông báo (Telegram) và TỰ cấp mật khẩu mới, gửi SMS cho
    // khách từ số 0922 60 62 68 — xem admin/password_reset_requests.php và
    // create_password_reset_request() phía backend. Dùng action
    // 'password_reset_request' (KHÔNG còn 'password_reset_request_otp' /
    // 'password_reset' — 2 action cũ đã bị gỡ khỏi backend).
    // =========================================================================
    private fun showForgotPasswordPhone() {
        val s = shell("Quên mật khẩu", 4); setContentView(s); val c = contentOf(s)
        c.addView(label("Nhập họ tên và số điện thoại đã đăng ký, quản trị viên sẽ liên hệ và gửi mật khẩu mới qua SMS cho bạn.", 14f, secondary))
        val name = input("Họ tên"); c.addView(name)
        val phone = input("Số điện thoại"); c.addView(phone)
        c.addView(button("Gửi yêu cầu khôi phục") {
            val n = name.text.toString().trim(); val p = phone.text.toString().trim()
            if (n.isBlank()) { toast("Vui lòng nhập họ tên"); return@button }
            if (p.isBlank()) { toast("Vui lòng nhập số điện thoại"); return@button }
            executor.execute {
                try {
                    val r = account.request("password_reset_request", "POST", JSONObject(mapOf("name" to n, "phone" to p)).toString())
                    runOnUiThread {
                        if (r.optBoolean("ok")) { push({ showForgotPasswordPhone() }) { showForgotPasswordSent(r.optString("message", "Đã gửi yêu cầu khôi phục.")) } }
                        else toast(r.optString("message", "Không gửi được yêu cầu"))
                    }
                } catch (_: Exception) { runOnUiThread { toast("Không kết nối được máy chủ tài khoản") } }
            }
        }.apply { layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) } })
        c.addView(button("Quay lại đăng nhập") { push({ showForgotPasswordPhone() }) { showLogin() } }.apply { layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) } })
    }

    /** Màn hình xác nhận sau khi gửi yêu cầu quên mật khẩu thành công. */
    private fun showForgotPasswordSent(message: String) {
        val s = shell("Quên mật khẩu", 4); setContentView(s); val c = contentOf(s)
        c.addView(label(message, 14f, secondary))
        c.addView(label("Mật khẩu mới sẽ được gửi qua SMS từ số 0922 60 62 68 (COM11H). Vui lòng chú ý điện thoại.", 13.5f, secondary).apply { setPadding(0, dp(6), 0, 0) })
        c.addView(button("Quay lại đăng nhập") { push({ showForgotPasswordSent(message) }) { showLogin() } }.apply { layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) } })
    }
}
