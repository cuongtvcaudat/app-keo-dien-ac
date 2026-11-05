# Hướng dẫn sử dụng OnCam

> Bản đầy đủ viết ở **Phase 6**. Hiện có phần máy camera (Phase 2–3).

## Máy camera (app OnCam Quay) — từ Phase 2

### Cài lần đầu

1. Cài Tailscale và làm đủ các bước trong [SETUP_TAILSCALE.md](SETUP_TAILSCALE.md).
2. Cài file `camera-node-debug.apk` (tải từ GitHub Actions, xem README).
3. Mở **OnCam Quay** → màn hình *Cấu hình máy quay*:
   - **Tên hiển thị**: ví dụ `Sân nhà`.
   - **Mã camera**: chữ thường không dấu, ví dụ `san-nha`.
   - **Token bí mật**: app tự tạo sẵn 43 ký tự ngẫu nhiên. Bấm **Sao chép** và cất vào chỗ riêng tư (ghi chú cá nhân). Sẽ cần nhập ở app Xem.
   - **MQTT broker**: để mặc định *Broker trong máy này*.
   - Tích **Tự chạy khi khởi động lại máy**.
   - Bấm **Lưu**.
4. Bấm **Hướng dẫn chống tắt app** và làm đủ 5 bước (ColorOS tắt app nền rất mạnh tay).
5. Quay lại, bấm **Khởi động camera node** → cho phép **Camera** và **Thông báo**.
6. Thanh thông báo hiện *Camera node đang chờ lệnh*.

### Kiểm tra máy camera chạy đúng

| Mục trên màn hình | Giá trị đúng |
|---|---|
| Broker | `Đang chạy tại 100.x.y.z:1883` |
| MQTT | `Đã kết nối` |
| Mở camera từ xa | `Có` |
| Bấm **Tự kiểm tra (PING)** | `✅ Thông suốt: … ms` |

### Thử camera ngay trên máy (từ Phase 3)

1. Cấu hình → chọn đúng **Hướng đặt máy** (dựng đứng / nằm ngang...) → Lưu → Dừng rồi Khởi động lại node.
2. Màn hình trạng thái → mục **Camera** → bấm **Bật camera thử**.
3. Trong vài giây: trạng thái `Đang phát video`, hiện ảnh xem thử, notification có nút **Tắt camera**.
4. Không làm gì → sau **60 giây** camera tự tắt (vì không có ai xem). Từ app Xem, thời gian chờ mặc định là **3 phút**.
5. Bấm **Tắt camera** → mở app Camera của máy: phải mở được ngay (camera đã được nhả).

Camera tự tắt khi nhiệt độ pin ≥ 45 °C, tự hạ chất lượng khi ≥ 42 °C.

### Thông tin cần nhập vào app Xem (Phase 4)

Mục *Nhập vào app OnCam Xem* trên màn hình: **địa chỉ broker**, **mã camera**, **token bí mật** (có nút sao chép).

### Khi nào cần mở lại app trên máy camera

Sau khi máy camera **khởi động lại** hoặc app bị hệ thống tắt rồi tự chạy lại, camera node vẫn nghe lệnh nhưng **chưa mở được camera từ xa** (giới hạn của Android 11 trở lên). Mục *Mở camera từ xa* sẽ hiện `Chưa`. Chỉ cần mở app OnCam Quay một lần là được.

## Ghi chú vận hành

- Máy camera cắm sạc liên tục sẽ làm chai/phồng pin theo thời gian. Phần mềm không khắc phục được; nên dùng ổ cắm hẹn giờ hoặc bộ sạc có giới hạn mức sạc nếu máy hỗ trợ, và kiểm tra pin định kỳ.
- Đặt máy camera nơi thoáng, tránh nắng trực tiếp: điện thoại cũ chạy camera rất nhanh nóng.
