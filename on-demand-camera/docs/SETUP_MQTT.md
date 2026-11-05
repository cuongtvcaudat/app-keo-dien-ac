# MQTT broker cho OnCam

**Mặc định không cần làm gì**: app camera-node có sẵn broker nhúng (Moquette), tự mở trên IP Tailscale của máy camera, tự tạo mật khẩu từ token bí mật (quyết định D13).

Phần dưới chỉ dành cho trường hợp muốn dùng **broker riêng** (chọn "Broker riêng" trong màn hình cấu hình). Hướng dẫn chi tiết Mosquitto/Docker sẽ viết ở **Phase 6** nếu cần.

Yêu cầu đã biết:

- Cần **một máy chạy 24/7** nằm trong mạng Tailscale: PC, Raspberry Pi, NAS hoặc VPS.
- Mosquitto bật xác thực username/password, **tắt anonymous**.
- Trong mạng Tailscale, dữ liệu đã được mã hóa ở tầng mạng (WireGuard), nên có thể chạy MQTT không TLS (cổng 1883). Đổi lại: bất kỳ thiết bị nào trong tailnet đều chạm được tới cổng broker → bắt buộc phải có mật khẩu mạnh. Nếu broker mở ra Internet thì **phải** dùng TLS (cổng 8883).
