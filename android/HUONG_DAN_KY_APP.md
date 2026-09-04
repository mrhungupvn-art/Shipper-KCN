# Hướng dẫn tạo chữ ký (keystore) & build file AAB nộp CH Play

## 1. Tạo file keystore (chỉ làm 1 lần, giữ file này MÃI MÃI)
Mở terminal trong Android Studio (hoặc cmd/terminal máy bạn, cần có JDK), chạy:

```
keytool -genkeypair -v -keystore com11h-release.jks -alias com11h -keyalg RSA -keysize 2048 -validity 10000
```

Lệnh sẽ hỏi mật khẩu keystore, mật khẩu key, và vài thông tin (tên, tổ chức...).
Đặt file `com11h-release.jks` này ngay tại thư mục gốc (ngang hàng thư mục `android`).

**Quan trọng:** Lưu file `.jks` này và mật khẩu ở nơi an toàn (kèm backup).
Mất file này = không thể cập nhật app đã lên CH Play nữa, phải tạo app mới.

## 2. Khai báo cho Gradle
Sao chép `android/keystore.properties.example` thành `android/keystore.properties`,
điền đúng đường dẫn tới file `.jks` và mật khẩu bạn vừa tạo ở bước 1.
File `keystore.properties` đã được thêm vào `.gitignore`, không lo bị lộ khi
đẩy code lên GitHub.

## 3. Build file AAB (Android App Bundle) — CH Play yêu cầu định dạng này
Trong Android Studio: **Build > Generate Signed Bundle / APK... > Android App
Bundle**, chọn keystore vừa tạo, chọn build variant `release`.

Hoặc bằng dòng lệnh trong thư mục `android`:
```
./gradlew bundleRelease
```
File kết quả nằm ở: `android/app/build/outputs/bundle/release/app-release.aab`

## 4. Mỗi lần cập nhật app sau này
Trước khi build bản mới để nộp CH Play, luôn tăng 2 dòng trong
`android/app/build.gradle`:
```
versionCode 12        // tăng thêm 1 mỗi lần nộp bản mới, không được trùng bản cũ
versionName '1.7.0'   // số hiển thị cho người dùng, tuỳ bạn đặt
```
