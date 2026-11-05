# Cài Tailscale cho OnCam (chỉ cần điện thoại Android)

Tailscale tạo một **mạng riêng ảo** giữa các thiết bị của bạn: 2 điện thoại ở 2 mạng khác nhau (Wi-Fi nhà, 4G) vẫn "thấy" nhau qua địa chỉ dạng `100.x.y.z`. Không cần mở cổng modem, không cần tên miền.

Gói **Personal** miễn phí (tối đa 3 người dùng, 100 thiết bị) là đủ.

## 1. Cài trên điện thoại XEM (máy chính)

1. CH Play → cài **Tailscale**.
2. Mở app → **Sign in** → chọn đăng nhập bằng tài khoản Google (ghi nhớ tài khoản này).
3. Cho phép tạo kết nối VPN khi Android hỏi.
4. Gạt nút sang **Connected**.

## 2. Cài trên điện thoại CAMERA (Oppo Reno 5)

1. CH Play → cài **Tailscale** → đăng nhập **đúng tài khoản Google ở bước 1**.
2. Cho phép VPN → **Connected**.
3. Trong app Tailscale sẽ thấy cả 2 máy, mỗi máy có một IP `100.x.y.z`.

## 3. Giữ Tailscale luôn chạy trên máy camera (bắt buộc)

| Việc | Đường dẫn trên ColorOS (tên có thể khác chút giữa bản 11/12/13) | Vì sao |
|---|---|---|
| VPN luôn bật | Cài đặt → Kết nối khác (hoặc Mạng & Internet) → VPN → bánh răng cạnh Tailscale → bật **VPN luôn bật** | Khởi động lại máy là Tailscale tự kết nối. **Không** bật "Chặn kết nối không dùng VPN" |
| Bỏ tối ưu pin | Cài đặt → Pin → Tối ưu hoá pin / Mức sử dụng pin ứng dụng → Tailscale → **Không tối ưu** / **Cho phép hoạt động ở nền** | ColorOS tắt app nền rất mạnh tay |
| Tự khởi chạy | Thông tin ứng dụng Tailscale → Pin → bật **Cho phép tự khởi chạy** | Để chạy sau khi khởi động máy |
| Khoá đa nhiệm | Màn hình đa nhiệm → nhấn giữ thẻ Tailscale → **Khoá** | "Dọn tất cả" không tắt nó |

## 4. Tắt hết hạn khoá cho máy camera (rất quan trọng)

Mặc định Tailscale bắt đăng nhập lại sau **180 ngày**. Máy camera đặt ngoài sân sẽ tự mất kết nối đúng ngày đó.

1. Trên điện thoại, mở trình duyệt vào `https://login.tailscale.com/admin/machines` (đăng nhập cùng tài khoản Google).
2. Tìm máy camera (tên kiểu `oppo-reno5`...) → bấm dấu `⋯` → **Disable key expiry**.

## 5. Kiểm tra

- App Tailscale trên máy XEM: máy camera hiện chấm xanh **Connected**.
- App **OnCam Quay** trên máy camera: mục *Broker* hiện `Đang chạy tại 100.x.y.z:1883`. Nếu hiện *Chờ Tailscale* → Tailscale trên máy camera chưa Connected.

## Lưu ý bảo mật

- Broker và luồng video chỉ mở trên IP Tailscale, không mở ra Internet. Mọi dữ liệu trong Tailscale đã được mã hoá đầu-cuối (WireGuard).
- Bất kỳ thiết bị nào bạn thêm vào tài khoản Tailscale đều chạm được tới cổng broker → OnCam vẫn bắt buộc mật khẩu broker + chữ ký token cho mọi lệnh.
