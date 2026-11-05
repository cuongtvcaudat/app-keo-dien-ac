# Hướng dẫn sử dụng OnCam

> Bản đầy đủ viết ở **Phase 6**. Hiện có phần cài đặt máy camera (Phase 2).

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

### Thông tin cần nhập vào app Xem (Phase 4)

Mục *Nhập vào app OnCam Xem* trên màn hình: **địa chỉ broker**, **mã camera**, **token bí mật** (có nút sao chép).

### Khi nào cần mở lại app trên máy camera

Sau khi máy camera **khởi động lại** hoặc app bị hệ thống tắt rồi tự chạy lại, camera node vẫn nghe lệnh nhưng **chưa mở được camera từ xa** (giới hạn của Android 11 trở lên). Mục *Mở camera từ xa* sẽ hiện `Chưa`. Chỉ cần mở app OnCam Quay một lần là được.

## Ghi chú vận hành

- Máy camera cắm sạc liên tục sẽ làm chai/phồng pin theo thời gian. Phần mềm không khắc phục được; nên dùng ổ cắm hẹn giờ hoặc bộ sạc có giới hạn mức sạc nếu máy hỗ trợ, và kiểm tra pin định kỳ.
- Đặt máy camera nơi thoáng, tránh nắng trực tiếp: điện thoại cũ chạy camera rất nhanh nóng.
