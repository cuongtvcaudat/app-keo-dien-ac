# Cài Tailscale cho OnCam

> Hướng dẫn đầy đủ (kèm ảnh chụp từng bước) sẽ viết ở **Phase 6**, sau khi đã thử trên máy thật.

Những điểm đã biết cần làm:

1. Cài app Tailscale trên **cả 2 điện thoại** và **máy chạy MQTT broker**, đăng nhập **cùng một tài khoản**.
2. Ghi lại IP Tailscale (dạng `100.x.y.z`) của máy chạy broker — nhập vào app OnCam ở bước cấu hình.
3. Trên máy camera (máy cũ): Cài đặt → Mạng → VPN → Tailscale → bật **"VPN luôn bật" (Always-on VPN)**. Thiếu bước này, khởi động lại máy là Tailscale không tự chạy → camera offline.
4. Tắt tối ưu pin cho app Tailscale trên máy camera.
