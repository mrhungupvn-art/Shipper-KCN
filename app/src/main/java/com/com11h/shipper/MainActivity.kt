package com.com11h.partner

import android.app.AlertDialog
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import androidx.work.*
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class MainActivity:AppCompatActivity(){
 private lateinit var api:Api;private lateinit var session:SecureSession;private lateinit var loginPanel:LinearLayout;private lateinit var appPanel:LinearLayout;private lateinit var content:LinearLayout;private lateinit var status:TextView;private lateinit var loginStatus:TextView;private lateinit var storeBadge:TextView;private lateinit var swipe:SwipeRefreshLayout;private lateinit var spinner:Spinner;private var kcnList:List<KcnItem> = emptyList();private var kcn=0
 // BUGFIX: app Partner trước đây KHÔNG có polling khi đang mở app (chỉ đồng
 // bộ lúc mở app / kéo-để-làm-mới / mỗi 15 phút qua WorkManager) — khác app
 // Shipper vốn có vòng lặp 8 giây (action "shipper_ping"). Thêm đúng cơ chế
 // "ping nhẹ" giống Shipper, dùng action "partner_ping" (đã có sẵn ở
 // api/index.php) — so sánh chữ ký trả về với lần trước, đổi mới gọi lại
 // syncPending() (tải danh sách đầy đủ), tránh phải poll nặng liên tục.
 private val pingHandler = Handler(Looper.getMainLooper())
 private var pingRunnable: Runnable? = null
 private var lastPingSignature: String? = null
 private val PING_INTERVAL_MS = 8000L
 override fun onCreate(b:Bundle?){super.onCreate(b);setContentView(R.layout.activity_main);session=SecureSession(this);loginPanel=findViewById(R.id.loginPanel);appPanel=findViewById(R.id.appPanel);content=findViewById(R.id.contentBox);status=findViewById(R.id.status);loginStatus=findViewById(R.id.loginStatus);storeBadge=findViewById(R.id.storeBadge);swipe=findViewById(R.id.swipeRefresh);spinner=findViewById(R.id.kcnSpinner);loadKcnList();findViewById<Button>(R.id.loginBtn).setOnClickListener{login()};findViewById<Button>(R.id.logoutBtn).setOnClickListener{logout()};findViewById<Button>(R.id.refreshBtn).setOnClickListener{syncPending()};findViewById<Button>(R.id.newTab).setOnClickListener{loadPickups("partner_pending_pickups")};findViewById<Button>(R.id.prepTab).setOnClickListener{loadPickups("partner_pickups","preparing")};findViewById<Button>(R.id.historyTab).setOnClickListener{loadPickups("partner_pickups","ready,rejected")};findViewById<Button>(R.id.foodsTab).setOnClickListener{loadFoods()};findViewById<Button>(R.id.ledgerTab).setOnClickListener{loadLedger()};swipe.setOnRefreshListener{syncPending()};session.token()?.let{t->kcn=session.kcnId()?:0;if(kcn>0){api=Api(BuildConfig.API_BASE_URL,kcn,t);showApp();syncPending()}};if(android.os.Build.VERSION.SDK_INT>=33&&ContextCompat.checkSelfPermission(this,"android.permission.POST_NOTIFICATIONS")!=PackageManager.PERMISSION_GRANTED)ActivityCompat.requestPermissions(this,arrayOf("android.permission.POST_NOTIFICATIONS"),12);scheduleSync()}

 // --- Fast polling khi app đang mở (foreground) — xem ghi chú BUGFIX ở trên. ---
 override fun onResume(){super.onResume();startFastPolling()}
 override fun onPause(){super.onPause();stopFastPolling()}
 private fun startFastPolling(){
  if(!::api.isInitialized) return
  stopFastPolling()
  val r=object:Runnable{override fun run(){pingOnce();pingHandler.postDelayed(this,PING_INTERVAL_MS)}}
  pingRunnable=r
  pingHandler.postDelayed(r,PING_INTERVAL_MS)
 }
 private fun stopFastPolling(){pingRunnable?.let{pingHandler.removeCallbacks(it)};pingRunnable=null}
 private fun pingOnce(){
  if(!::api.isInitialized) return
  thread{
   try{
    val j=api.call("partner_ping")
    val sig=(j.optJSONObject("data")?:j).toString()
    val changed=lastPingSignature!=null && sig!=lastPingSignature
    lastPingSignature=sig
    if(changed) runOnUiThread{syncPending()}
   }catch(_:UnauthorizedException){
    runOnUiThread{logout()}
   }catch(_:Exception){
    // Lỗi mạng tạm thời khi ping: bỏ qua, vòng lặp PING_INTERVAL_MS sau sẽ thử lại.
   }
  }
 }
 private fun loadKcnList(){thread{runCatching{Api.fetchKcnList(BuildConfig.API_BASE_URL)}.onSuccess{list->runOnUiThread{kcnList=list;spinner.adapter=ArrayAdapter(this,android.R.layout.simple_spinner_item,list).also{it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)}}}.onFailure{e->runOnUiThread{loginStatus.text="Không tải được KCN: ${e.message}"}}}}
 private fun showApp(){loginPanel.visibility=LinearLayout.GONE;appPanel.visibility=LinearLayout.VISIBLE;storeBadge.text="🏪 ${session.name().orEmpty()}  •  Store #${session.storeId()}  •  ${session.category()}";status.text="Sẵn sàng";startFastPolling()}
 private fun login(){val sel=spinner.selectedItem as? KcnItem;kcn=sel?.id?:0;val u=findViewById<EditText>(R.id.username).text.toString().trim();val p=findViewById<EditText>(R.id.password).text.toString();if(kcn<=0||u.isBlank()||p.isBlank()){loginStatus.text="Vui lòng chọn KCN và nhập tài khoản/mật khẩu";return};api=Api(BuildConfig.API_BASE_URL,kcn);loginStatus.text="Đang đăng nhập...";thread{try{val d=api.call("partner_login",JSONObject().put("username",u).put("password",p).put("device","android")).getJSONObject("data");val t=d.getString("token");val x=d.getJSONObject("partner");session.save(t,kcn,x.optString("store_name",u),x.optInt("store_id"),x.optString("category",""));api.setToken(t);runOnUiThread{showApp();syncPending()}}catch(e:Exception){runOnUiThread{loginStatus.text=e.message?:"Đăng nhập thất bại"}}}}
 private fun logout(){stopFastPolling();if(::api.isInitialized)thread{runCatching{api.call("partner_logout",JSONObject())}};session.clear();content.removeAllViews();loginPanel.visibility=LinearLayout.VISIBLE;appPanel.visibility=LinearLayout.GONE;loginStatus.text="Đã đăng xuất"}
 private fun syncPending(){if(!::api.isInitialized)return;status.text="Đang đồng bộ...";thread{try{val j=api.call("partner_pending_pickups");val n=j.optJSONObject("data")?.optJSONArray("pickups")?.length()?:0;runOnUiThread{swipe.isRefreshing=false;status.text="Có $n đơn mới • ${SimpleDateFormat("HH:mm:ss").format(Date())}";renderPickups(j.optJSONObject("data")?.optJSONArray("pickups")?:JSONArray(),true)}}catch(e:UnauthorizedException){runOnUiThread{swipe.isRefreshing=false;logout()}}catch(e:Exception){runOnUiThread{swipe.isRefreshing=false;status.text=e.message?:"Không đồng bộ được"}}}}
 private fun loadPickups(action:String,filter:String="") {
  if(!::api.isInitialized)return
  thread {
   try {
    val a=api.call(action).optJSONObject("data")?.optJSONArray("pickups") ?: JSONArray()
    runOnUiThread {
     val out=JSONArray()
     for(i in 0 until a.length()) {
      val o=a.getJSONObject(i)
      val st=o.optString("pickup_status")
      if(filter.isBlank() || filter.split(',').contains(st)) out.put(o)
     }
     renderPickups(out,false)
    }
   } catch(e:Exception) { runOnUiThread { toast(e.message) } }
  }
 }
 private fun renderPickups(arr:JSONArray,onlyPending:Boolean){content.removeAllViews();content.addView(title(if(onlyPending)"📥 Đơn mới — cần xác nhận trong 15 phút" else "📦 Danh sách pickup"));if(arr.length()==0){content.addView(hint("Không có dữ liệu."));return};for(i in 0 until arr.length())addPickup(arr.getJSONObject(i))}
 private fun addPickup(o:JSONObject){val c=card();val id=o.optInt("pickup_id");c.addView(text("Đơn ${o.optString("code")} • Pickup #$id",20,true));c.addView(text("Trạng thái: ${o.optString("pickup_status")}",15,false));c.addView(text("Khách: ${o.optString("customer")} • ${o.optString("phone")}",14,false));c.addView(text("Giao: ${o.optString("address")}",14,false));c.addView(text("Thanh toán: ${if(o.optString("payment_status")=="paid")"Đã thanh toán online" else "COD"}",14,false));val items=o.optJSONArray("items")?:JSONArray();for(i in 0 until items.length()){val x=items.getJSONObject(i);c.addView(text("• ${x.optString("name")} ×${x.optInt("qty")}",14,false))};val st=o.optString("pickup_status");if(st=="pending"){val row=LinearLayout(this);val ok=Button(this).apply{text="✅ Nhận đơn"};val no=Button(this).apply{text="❌ Từ chối"};row.addView(ok,LinearLayout.LayoutParams(0,-2,1f));row.addView(no,LinearLayout.LayoutParams(0,-2,1f));c.addView(row);ok.setOnClickListener{action(id,"partner_confirm_pickup"){loadPickups("partner_pending_pickups")}};no.setOnClickListener{rejectDialog(id)}}else if(st=="confirmed"||st=="preparing"){val ready=Button(this).apply{text="🍱 Món đã xong — báo Shipper"};c.addView(ready);ready.setOnClickListener{action(id,"partner_mark_ready"){loadPickups("partner_pickups","preparing")}}};content.addView(c)}
 private fun action(id:Int,name:String,done:()->Unit){thread{try{api.call(name,JSONObject().put("pickup_id",id));runOnUiThread{toast("Thành công");done()}}catch(e:Exception){runOnUiThread{toast(e.message)}}}}
 private fun rejectDialog(id:Int){val input=EditText(this).apply{hint="Lý do bắt buộc (hết món/quá tải/khác)";inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE};AlertDialog.Builder(this).setTitle("Từ chối pickup").setView(input).setNegativeButton("Huỷ",null).setPositiveButton("Từ chối"){_,_->val reason=input.text.toString().trim();if(reason.isBlank()){toast("Lý do bắt buộc");return@setPositiveButton};actionReject(id,reason)}.show()}
 private fun actionReject(id:Int,reason:String){thread{try{api.call("partner_reject_pickup",JSONObject().put("pickup_id",id).put("reason",reason));runOnUiThread{toast("Đã từ chối pickup");loadPickups("partner_pending_pickups")}}catch(e:Exception){runOnUiThread{toast(e.message)}}}}
 private fun loadFoods(){thread{try{val a=api.call("partner_food_list").optJSONObject("data")?.optJSONArray("foods")?:JSONArray();runOnUiThread{content.removeAllViews();content.addView(title("🍜 Món ăn / tồn kho"));for(i in 0 until a.length())addFood(a.getJSONObject(i))}}catch(e:Exception){runOnUiThread{toast(e.message)}}}}
 private fun addFood(o:JSONObject){val c=card();c.addView(text(o.optString("name"),18,true));c.addView(text("Danh mục: ${o.optString("category",session.category())}",13,false));c.addView(text("Giá: ${vnd(o.optInt("price"))} • ${if(o.optInt("is_active")==1)"Đang bán" else "Chờ/ẩn"}",14,false));val row=LinearLayout(this);val stock=EditText(this).apply{setText(o.optInt("stock").toString());inputType=InputType.TYPE_CLASS_NUMBER;hint="Tồn kho"};val save=Button(this).apply{text="Lưu tồn"};row.addView(stock,LinearLayout.LayoutParams(0,-2,1f));row.addView(save,LinearLayout.LayoutParams(0,-2,1f));c.addView(row);save.setOnClickListener{val n=stock.text.toString().toIntOrNull()?:0;thread{try{api.call("partner_food_toggle_stock",JSONObject().put("food_id",o.optInt("id")).put("stock",n));runOnUiThread{toast("Đã cập nhật")}}catch(e:Exception){runOnUiThread{toast(e.message)}}}};content.addView(c)}
 private fun loadLedger(){thread{try{val a=api.call("partner_ledger").optJSONObject("data")?.optJSONArray("ledger")?:JSONArray();runOnUiThread{content.removeAllViews();content.addView(title("💰 Đối soát"));if(a.length()==0)content.addView(hint("Chưa có phát sinh ledger."));for(i in 0 until a.length()){val o=a.getJSONObject(i);content.addView(card().apply{addView(text("${o.optString("type")} • ${vnd(o.optInt("amount"))}",17,true));addView(text("Đơn #${o.optInt("order_id")} • ${o.optString("status")}",14,false));addView(text(o.optString("description"),13,false));addView(text(o.optString("created_at"),12,false))})}}}catch(e:Exception){runOnUiThread{toast(e.message)}}}}
 private fun card()=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(18,18,18,18);setBackgroundResource(android.R.drawable.dialog_holo_light_frame);layoutParams=LinearLayout.LayoutParams(-1,-2).apply{topMargin=12}}
 private fun title(s:String)=text(s,20,true).apply{setPadding(0,18,0,4)};private fun hint(s:String)=text(s,14,false);private fun text(s:String,size:Int,bold:Boolean)=TextView(this).apply{text=s;textSize=size.toFloat();if(bold)setTypeface(typeface,android.graphics.Typeface.BOLD);setPadding(0,3,0,3)};private fun vnd(n:Int)="%,d đ".format(n).replace(',','.');private fun toast(s:String?)=Toast.makeText(this,s?:"Có lỗi",Toast.LENGTH_SHORT).show()
 private fun scheduleSync(){val r=PeriodicWorkRequestBuilder<SyncWorker>(15,TimeUnit.MINUTES).setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build();WorkManager.getInstance(this).enqueueUniquePeriodicWork("partner_sync",ExistingPeriodicWorkPolicy.UPDATE,r)}
}
