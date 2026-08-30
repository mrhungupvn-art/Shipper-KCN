# COM11H Tiệm Android

App Partner/Tiệm dùng chung API `https://com11h.com/api/index.php`.

## Flow
1. Partner chọn KCN và đăng nhập `partner_login`.
2. Đơn mới chỉ hiện khi đã thanh toán và pickup thuộc đúng store của Partner.
3. `Nhận đơn` -> `confirmed/preparing`.
4. `Món đã xong` -> `ready`.
5. Server chỉ đưa đơn vào app Shipper khi **mọi pickup của order đều `ready`**, tức `READY_FOR_PICKUP`.
6. Shipper nhận -> bắt đầu giao -> sinh Delivery OTP -> khách đọc OTP -> Shipper xác nhận -> Hoàn thành.

App cũng có tab quản lý tồn kho và đối soát `partner_ledger`.
