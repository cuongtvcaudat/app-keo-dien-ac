# Dựng MQTT broker (Mosquitto) cho OnCam

> Hướng dẫn đầy đủ (Docker Compose, file cấu hình, tạo user/mật khẩu) sẽ viết ở **Phase 6**; cấu hình tối thiểu để test sẽ có từ **Phase 2**.

Yêu cầu đã biết:

- Cần **một máy chạy 24/7** nằm trong mạng Tailscale: PC, Raspberry Pi, NAS hoặc VPS.
- Mosquitto bật xác thực username/password, **tắt anonymous**.
- Trong mạng Tailscale, dữ liệu đã được mã hóa ở tầng mạng (WireGuard), nên có thể chạy MQTT không TLS (cổng 1883). Đổi lại: bất kỳ thiết bị nào trong tailnet đều chạm được tới cổng broker → bắt buộc phải có mật khẩu mạnh. Nếu broker mở ra Internet thì **phải** dùng TLS (cổng 8883).
