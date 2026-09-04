# COM11H 1.8.0 — XU & Chăm sóc khách hàng

## Cơ chế XU bản test
- Xem một sản phẩm đủ 30 giây: +10 XU.
- Xem đủ 10 sản phẩm khác nhau: thưởng thêm 100 XU.
- Tối đa 200 XU/giờ.
- Tối đa 2.000 XU/ngày.
- XU hiển thị trong Ví XU và có màn hình đổi thử voucher.

## Lưu ý quan trọng
Bản 1.8.0 hiện dùng `XuStore.kt` để TEST trên thiết bị. Đây chưa phải số dư XU chống gian lận trên server.

Để chạy production, backend `api/index.php` cần bổ sung 2 action:

### GET `action=xu_balance`
Authorization: Bearer TOKEN

Response mẫu:
```json
{"ok":true,"data":{"xu":1280,"day_xu":650,"hour_xu":120,"watched_today":8}}
```

### POST `action=xu_watch`
Body:
```json
{"product_id":123,"watch_seconds":30}
```

Server phải kiểm tra token, sản phẩm, thời gian xem, sản phẩm đã nhận XU, giới hạn 200 XU/giờ, 2.000 XU/ngày và tự cộng thưởng 100 XU khi đủ 10 sản phẩm.

## Hướng production
Không tin số giây do Android tự gửi. Server nên tạo `watch_session_id` khi mở chi tiết sản phẩm và xác nhận phiên xem trước khi cộng XU.
