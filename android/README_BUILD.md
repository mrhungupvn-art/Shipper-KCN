# COM11H Android v1.8.0

## Ghi chú bản 1.8.0 (so với 1.7.0)
- **Đăng ký tài khoản (ĐÃ ĐỔI — không còn OTP):** quay về 1 bước — nhập Họ
  tên + SĐT + Mật khẩu → bấm "Đăng ký" là tạo tài khoản ngay, không còn màn
  nhập mã xác thực. Action `register_request_otp` ĐÃ BỊ GỠ khỏi backend;
  action `register` không cần trường `otp` nữa. Xác thực (qua admin) giờ
  CHỈ áp dụng cho luồng quên mật khẩu, không áp dụng khi đăng ký.
- **Quên mật khẩu (không đổi so với 1.7.0):** màn Đăng nhập có nút "Quên mật
  khẩu?" — khách chỉ nhập Họ tên + SĐT rồi gửi (action `password_reset_request`).
  KHÔNG tự đặt mật khẩu trên app: hệ thống báo admin qua Telegram, admin vào
  trang quản trị cấp mật khẩu mới rồi tự nhắn SMS cho khách từ số
  0922 60 62 68.
- **Bắt buộc:** phải upload `api/index.php` mới (đã gỡ action
  `register_request_otp`; action `register` không cần `otp`; action
  `password_reset_request` cho quên mật khẩu) cùng `core.php`, `config.php`
  lên server TRƯỚC KHI phát hành app 1.8.0, nếu không app bản cũ (1.7.0 trở
  xuống, còn gọi `register_request_otp`) sẽ nhận lỗi "hành động không xác
  định" khi đăng ký — nên phát hành backend và app mới CÙNG LÚC (xem
  `HUONG_DAN_OTP.md` phía backend).
- Hằng số `OTP_REQUIRED_FOR_APP_REGISTER` trong `config.php` giờ không còn
  tác dụng gì (đăng ký app không còn kiểm tra OTP) — có thể bỏ qua, không
  cần đổi giá trị.

## Ghi chú bản 1.7.0 (so với 1.6.0)
- **Đăng ký tài khoản:** đổi sang xác thực OTP — sau khi nhập họ tên/SĐT/mật
  khẩu, app gọi `register_request_otp` để gửi mã 6 số về SĐT, hiện màn hình
  nhập mã (có nút "Gửi lại mã"), rồi mới gọi `register` (kèm `otp`) để thật
  sự tạo tài khoản. **(Đã đổi lại ở bản 1.8.0 — xem ở trên.)**
- **Quên mật khẩu (ĐÃ ĐỔI — không còn OTP):** màn Đăng nhập có nút "Quên mật
  khẩu?" — khách chỉ nhập Họ tên + SĐT rồi gửi (action `password_reset_request`).
  KHÔNG tự đặt mật khẩu trên app nữa: hệ thống báo admin qua Telegram, admin
  vào trang quản trị cấp mật khẩu mới rồi tự nhắn SMS cho khách từ số
  0922 60 62 68. 2 action cũ `password_reset_request_otp` và `password_reset`
  ĐÃ BỊ GỠ khỏi backend — bản app nào còn gọi 2 action này sẽ nhận lỗi "hành
  động không xác định", nên PHẢI build lại app với `MainActivity.kt` mới.
- **Bắt buộc:** phải upload `api/index.php` mới (đã thêm action
  `register_request_otp`, action `register` giờ nhận thêm trường `otp`, và
  action `password_reset_request` thay cho 2 action quên-mật-khẩu cũ) cùng
  `core.php`, `config.php` lên server TRƯỚC KHI phát hành app mới, nếu không
  app sẽ nhận lỗi "hành động không xác định" khi gọi các action này (xem
  `HUONG_DAN_OTP.md` phía backend).
- Sau khi bản 1.7.0 được phát hành và đa số khách đã cập nhật, đổi hằng số
  `OTP_REQUIRED_FOR_APP_REGISTER` sang `true` trong `config.php` phía server
  để bắt buộc OTP cho mọi lượt đăng ký từ app (bản app cũ hơn 1.7.0 sẽ không
  đăng ký được nữa sau khi đổi — đây là điều nên xảy ra sau khi rollout xong).

## Ghi chú bản 1.6.0 (so với 1.5.2)
- **Trang chủ – ô tìm kiếm:** thêm nút bấm 🔍 cạnh ô "Tìm món ăn..." (trước đó
  chỉ có ô nhập, không có cách nào để bấm tìm). Bấm nút hoặc bấm "Tìm kiếm"
  trên bàn phím sẽ mở màn Thực đơn và tự lọc theo từ khoá đã nhập. Màn Thực
  đơn cũng có sẵn ô tìm kiếm riêng để gõ lại/đổi từ khoá.
- **Trang chủ – banner giữa trang:** không còn banner tĩnh cố định trong code
  app. Banner giờ tải trực tiếp từ `api?action=banners`, dùng CHUNG bảng dữ
  liệu với **Admin > Banner trang chủ** (`admin/banners.php`) mà web đang
  dùng — Admin thêm/sửa/xoá/đổi thứ tự banner trên trang quản lý là app tự
  cập nhật theo ngay lần mở app kế tiếp, không cần build lại app. App hiển
  thị banner dạng slider tự chạy (đổi ảnh mỗi 4 giây) nếu có từ 2 banner trở
  lên, bấm vào banner sẽ mở đúng link đã cấu hình trên Admin (qua
  `banner_click.php` để thống kê lượt click giống web). Nếu Admin chưa tạo
  banner nào, app tự hiển thị lại banner mặc định như cũ.
- **Bắt buộc:** phải upload `api/index.php` mới (đã thêm action `banners`)
  lên server TRƯỚC KHI phát hành app 1.6.0, nếu không banner sẽ không tải
  được và app sẽ tự dùng lại banner mặc định.

## Ghi chú bản 1.4.2 (so với 1.4.1)
Chỉnh giao diện thẻ món ăn ở màn Thực đơn theo yêu cầu:
  - Ô ảnh to hơn: 88dp -> 128dp.
  - Logo COM11H nhỏ (26dp) ở góc trên-trái mỗi ảnh món.
  - Bấm vào ảnh món -> mở hộp thoại xem ảnh cỡ lớn (bấm "Đóng" hoặc
    bấm vào ảnh để tắt).
  - Nút "+ Thêm": cỡ chữ giảm còn một nửa (16f -> 8f), nền đổi sang
    vàng nhạt (thay vì nền mặc định của hệ thống).

## Ghi chú bản 1.4.1 (sửa lỗi so với 1.4.0)
Bản 1.4.0 trước đó bị build nhầm từ 1 bản `MainActivity.kt` cũ hơn cả v1.3.0
(bản đang có trên GitHub `main`), nên đã VÔ TÌNH MẤT phần hiển thị ảnh món ăn
(Thực đơn + Giỏ hàng) và phần thẻ đơn hàng đầy đủ 6 cột (giống `account.php`
bên web). Bản 1.4.1 này khôi phục lại toàn bộ phần đó, đồng thời giữ nguyên
các tính năng mới của 1.4.0 (xóa tài khoản, đăng xuất gọi API thu hồi token,
link chính sách quyền riêng tư, xử lý lỗi mạng rõ ràng hơn).
Đồng thời API (`api/index.php`) đã được sửa để trả URL ẢNH TUYỆT ĐỐI thay vì
đường dẫn tương đối — bắt buộc phải upload web/api mới TRƯỚC KHI phát hành
app 1.4.1 thì ảnh mới hiển thị đúng (xem HUONG_DAN_UPLOAD.txt).

## Build in Android Studio
1. Open the `android` folder as the project root.
2. Use JDK 17.
3. Use Gradle 8.9 (AGP 8.7.3), JDK 17, compileSdk/targetSdk 36.
4. Sync Gradle and run the `app` configuration on the phone.

## Build signed AAB from GitHub
The repository root includes `.github/workflows/build-android.yml`.
It builds a **signed release App Bundle** (not a debug APK) whenever code is
pushed to `main`, or when run manually via Actions > "Run workflow".

Before it can sign the build, add these 4 repository secrets (Settings >
Secrets and variables > Actions):
- `KEYSTORE_BASE64` — base64 of your `.jks` file (`base64 -w0 com11h-release.jks`)
- `KEYSTORE_PASSWORD`
- `KEY_ALIAS`
- `KEY_PASSWORD`

(See `HUONG_DAN_KY_APP.md` for how to create the keystore first.)

Output: `android/app/build/outputs/bundle/release/app-release.aab`,
published as the workflow artifact `FOOD-KCN-v1.8.1-release-AAB`
(bump the version number in this doc — and in the artifact `name:` in the
workflow file — whenever `versionName` changes in `app/build.gradle`).

If the secrets are missing, the workflow fails fast with a clear error
instead of producing an unsigned/broken bundle.

## Verified business flow to preserve
Login -> Menu (with food images) -> Cart (with thumbnails) -> Order Preview -> Create Order -> QR -> Payment (auto-hide QR + refresh points on paid) -> Stock reduction -> Delivery -> Customer delivery confirmation -> Points/Lucky Code -> Order list (6-column card matching web's account.php).

Payment must be confirmed by the server before stock is reduced.
