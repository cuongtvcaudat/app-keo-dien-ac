# MQTT broker cho OnCam

**Mặc định không cần làm gì**: app OnCam Quay có sẵn broker nhúng (Moquette), tự mở trên IP Tailscale của máy
camera, tự tạo mật khẩu từ token bí mật (quyết định D13). Đây là cách dự án đã kiểm thử trên máy thật.

Phần dưới chỉ dành cho trường hợp muốn dùng **broker riêng** (chọn "Broker riêng" trong cấu hình máy camera), ví dụ
có nhiều camera và muốn một broker chung chạy trên PC/Raspberry Pi.

> ⚠️ **Chưa kiểm thử thực tế** trong dự án (test tự động chỉ chạy với broker Moquette). Cấu hình dưới đây theo tài
> liệu Mosquitto 2.x; làm xong hãy bấm **Tự kiểm tra (PING)** trên máy camera để xác nhận.

## So sánh

| | Broker nhúng (mặc định) | Broker riêng (Mosquitto) |
|---|---|---|
| Thiết bị thêm | Không | 1 máy chạy 24/7 trong tailnet (PC, Raspberry Pi, NAS) |
| Mật khẩu | Tự suy từ token | Tự đặt, nhập ở cả máy camera và máy xem (QR tự mang theo) |
| Máy camera tắt thì | Broker tắt theo → máy xem mất kết nối, báo Offline trong ~20 giây | Broker gửi tin "di chúc" (LWT) → máy xem báo Offline trong ~25 giây |
| Nhiều camera | Mỗi camera một broker, app Xem kết nối từng cái | Một broker cho tất cả |

## Cài Mosquitto bằng Docker (máy Linux/Raspberry Pi đã cài Tailscale)

1. Lấy IP Tailscale của máy chạy broker: `tailscale ip -4` → ví dụ `100.80.1.2`.
2. Tạo thư mục và file cấu hình:

   ```bash
   mkdir -p ~/oncam-mqtt/config && cd ~/oncam-mqtt
   cat > config/mosquitto.conf <<'CONF'
   # Chỉ nghe trên IP Tailscale, không mở ra mạng LAN/Internet.
   listener 1883 100.80.1.2
   allow_anonymous false
   password_file /mosquitto/config/passwd
   # OnCam không cần lưu tin xuống đĩa (trạng thái được gửi lại khi camera kết nối).
   persistence false
   CONF
   ```

3. Tạo tài khoản (mật khẩu ≥ 20 ký tự ngẫu nhiên):

   ```bash
   docker run --rm -it -v "$PWD/config:/mosquitto/config" eclipse-mosquitto:2 \
     mosquitto_passwd -c /mosquitto/config/passwd oncam
   ```

4. Chạy broker (dùng mạng của máy chủ để nghe được IP Tailscale):

   ```bash
   docker run -d --name oncam-mqtt --restart unless-stopped --network host \
     -v "$PWD/config:/mosquitto/config" eclipse-mosquitto:2
   ```

5. Kiểm tra từ máy khác trong tailnet: `mosquitto_sub -h 100.80.1.2 -u oncam -P '<mật khẩu>' -t 'camera/#' -v`.

## Cấu hình OnCam dùng broker riêng

1. Máy camera → **Sửa cấu hình** → *MQTT broker* → **Broker riêng**:
   - Địa chỉ broker: `100.80.1.2` (app tự thêm `tcp://` và `:1883`).
   - Tên đăng nhập / mật khẩu: tài khoản vừa tạo.
2. **Lưu** → Dừng → Khởi động node → **Tự kiểm tra (PING)** phải ra `✅ Thông suốt`.
3. Máy xem: **Thêm camera → Quét mã QR**. Mã QR của chế độ broker riêng mang theo cả tên đăng nhập và mật khẩu
   broker, nên vẫn chỉ cần quét.

## Lưu ý bảo mật

- Trong mạng Tailscale, dữ liệu đã được mã hoá ở tầng mạng (WireGuard), nên chạy MQTT không TLS (cổng 1883) được.
  Đổi lại, mọi thiết bị trong tailnet đều chạm được tới cổng broker → **bắt buộc mật khẩu mạnh, tắt anonymous**.
- Nếu broker mở ra Internet thì **phải** dùng TLS (`ssl://tên-miền:8883`) và chứng chỉ hợp lệ.
- Lộ mật khẩu broker vẫn **không** điều khiển được camera: mọi lệnh phải có chữ ký bằng token của từng camera.
  Nhưng kẻ có mật khẩu broker đọc được trạng thái camera (pin, nhiệt độ, IP) → vẫn phải giữ kín.
