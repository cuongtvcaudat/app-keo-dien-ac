# Cài Tailscale cho OnCam (chỉ cần điện thoại Android)

Tailscale tạo một **mạng riêng ảo** giữa các thiết bị của bạn: 2 điện thoại ở 2 mạng khác nhau (Wi-Fi nhà, 4G) vẫn
"thấy" nhau qua địa chỉ dạng `100.x.y.z`. Không cần mở cổng modem, không cần tên miền.

Gói **Personal** miễn phí (tối đa 3 người dùng, 100 thiết bị) là đủ.

## 1. Cài trên điện thoại XEM (máy chính)

1. CH Play → cài **Tailscale**.
2. Mở app → **Sign in** → đăng nhập bằng tài khoản Google (ghi nhớ tài khoản này).
3. Cho phép tạo kết nối VPN khi Android hỏi.
4. Gạt nút sang **Connected**.

## 2. Cài trên điện thoại CAMERA (Samsung A54 hoặc máy khác)

1. CH Play → cài **Tailscale** → đăng nhập **đúng tài khoản Google ở bước 1**.
2. Cho phép VPN → **Connected**.
3. Trong app Tailscale sẽ thấy cả 2 máy, mỗi máy có một IP `100.x.y.z`. IP của máy camera là địa chỉ broker
   (app OnCam Quay tự hiện, không cần chép).

## 3. Giữ Tailscale luôn chạy trên máy camera (bắt buộc)

| Việc | Samsung (One UI 5–7) | Oppo (ColorOS 11–14) | Vì sao |
|---|---|---|---|
| VPN luôn bật | Cài đặt → Kết nối → Cài đặt kết nối khác → VPN → ⚙ cạnh Tailscale → **Luôn bật VPN** | Cài đặt → Kết nối khác → VPN → ⚙ Tailscale → **VPN luôn bật** | Khởi động lại máy là Tailscale tự kết nối. **Không** bật "Chặn kết nối không dùng VPN" |
| Pin không hạn chế | Thông tin ứng dụng Tailscale → Pin → **Không hạn chế** | Thông tin ứng dụng → Mức sử dụng pin → **Cho phép hoạt động ở nền** | Hệ thống không tắt app nền |
| Không cho ngủ | Cài đặt → Pin → Giới hạn sử dụng nền → **Ứng dụng không bao giờ ngủ** → thêm Tailscale | Thông tin ứng dụng → Pin → **Cho phép tự khởi chạy** | Chạy lại sau khởi động máy, không bị cho "ngủ" |
| Khoá đa nhiệm | Đa nhiệm → chạm biểu tượng Tailscale trên thẻ → **Giữ mở** | Đa nhiệm → nhấn giữ thẻ → **Khoá** | "Đóng tất cả" không tắt nó |

Tên mục có thể khác đôi chút giữa các bản One UI/ColorOS: tìm mục gần nghĩa nhất, hoặc gõ tên vào ô tìm kiếm của Cài đặt.

## 4. Tắt hết hạn khoá cho máy camera (rất quan trọng)

Mặc định Tailscale bắt đăng nhập lại sau **180 ngày**. Máy camera đặt ngoài sân sẽ tự mất kết nối đúng ngày đó.

1. Trên điện thoại, mở trình duyệt vào `https://login.tailscale.com/admin/machines` (đăng nhập cùng tài khoản Google).
2. Tìm máy camera (tên kiểu `samsung-sm-a546e`, `galaxy-a54`...) → bấm dấu `⋯` → **Disable key expiry**.

## 5. Kiểm tra

- App Tailscale trên máy XEM: máy camera hiện chấm xanh **Connected**.
- App **OnCam Quay** trên máy camera: mục *Broker* hiện `Đang chạy tại 100.x.y.z:1883`. Nếu hiện *Chờ Tailscale*
  → Tailscale trên máy camera chưa Connected.
- Khởi động lại máy camera một lần, đợi 2 phút: Tailscale phải tự Connected, OnCam Quay tự chạy lại.

## Lưu ý bảo mật

- Broker và luồng video chỉ mở trên IP Tailscale, không mở ra Internet. Dữ liệu trong Tailscale đã được mã hoá
  đầu-cuối (WireGuard).
- Bất kỳ thiết bị nào bạn thêm vào tài khoản Tailscale đều chạm được tới cổng broker → OnCam vẫn bắt buộc mật khẩu
  broker + chữ ký token cho mọi lệnh. Không chia sẻ tài khoản Tailscale cho người ngoài.
