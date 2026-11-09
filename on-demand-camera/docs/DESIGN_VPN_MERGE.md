# Thiết kế: Nhúng VPN vào app + gộp 2 app thành 1

> Trạng thái: **bản thảo để chốt trước khi code.** Việc này ước 3–5 ngày, dùng
> thư viện Go, và **CI chỉ chạy máy ảo nên bắt buộc thử trên máy thật.** Vì vậy
> chốt kiến trúc ở đây trước, rồi mới làm từng bước, giữ bản 0.11 đang chạy ổn.

## 1. Mục tiêu (theo yêu cầu chủ dự án)

1. **Không cài Tailscale rời**: VPN nằm trong chính app OnCam.
2. **Chỉ 1 app để cài** (gộp "OnCam Quay" + "OnCam Xem" làm một), mở ra chọn vai trò.
3. Tên/icon **nói đúng đây là camera** — không nguỵ trang thành game hay app khác
   (xem D40). Đây là ranh giới giữ app là thiết bị giám sát minh bạch, không thành
   công cụ theo dõi lén.

## 2. Nhúng VPN như thế nào — 2 cách, chọn A2

Hiện mọi thứ (MQTT `tcp://100.x.y.z:1883`, video MJPEG/HTTP, tải clip cổng 8081,
WebRTC UDP giữa 2 IP Tailscale) đều đi trên **IP Tailscale `100.x.y.z`** do **app
Tailscale rời** cung cấp ở tầng hệ điều hành. Bỏ app rời thì phải có cái gì đó
cấp lại những IP `100.x.y.z` đó cho app mình.

| | **A1 — tsnet userspace + SOCKS5** | **A2 — app tự làm VPN (khuyến nghị)** |
|---|---|---|
| Cách | Nhúng `tailscaled` chạy userspace, mở proxy SOCKS5 ở `localhost`; code Java nối qua SOCKS5 để tới `100.x.y.z` | App tự chạy `VpnService` của Android bằng `libtailscale` — y hệt cách app Tailscale chính thức làm, nhưng nằm trong app mình |
| MQTT (TCP) | Phải sửa Paho đi qua SOCKS5 | **Chạy nguyên** (IP `100.x.y.z` có thật ở tầng mạng) |
| MJPEG + tải clip (TCP) | Phải sửa đi qua SOCKS5 | **Chạy nguyên** |
| **WebRTC (UDP)** | ⚠️ **Hỏng/khó**: SOCKS5 UDP không hợp ICE → mất tính năng nghe/nói, hình nén | **Chạy nguyên** |
| Phải viết lại lớp mạng | Nhiều (toàn bộ chỗ nối mạng) | **Gần như không** — IP không đổi |
| Android hiện gì | Không có icon VPN | Hộp thoại "Cho phép lập VPN" (1 lần) + **icon chìa khoá VPN** thường trực |
| Chỉ 1 app, không Tailscale rời | ✅ | ✅ |

**Chọn A2.** Lý do: yêu cầu của anh là "không cài app rời", **không phải** "không có
icon VPN". A2 đạt đúng yêu cầu đó mà **giữ nguyên toàn bộ tính năng** (kể cả WebRTC
nghe/nói ở Phase 7) và **không phải viết lại lớp mạng** → ít rủi ro hơn hẳn. Cái giá
là một hộp thoại xin quyền VPN lần đầu và icon chìa khoá trên thanh trạng thái — cái
này **đúng với tinh thần minh bạch** (máy báo rõ "đang có VPN chạy"), và Android
**bắt buộc** như vậy, không có cách bỏ hợp lệ.

> A1 để dành: chỉ cân nhắc nếu sau này anh thực sự cần bỏ icon VPN và chấp nhận mất
> WebRTC (quay về MJPEG).

## 3. Thư viện và giấy phép

| Thành phần | Dùng | Ghi chú |
|---|---|---|
| `libtailscale` (github.com/tailscale/libtailscale) | Lõi Tailscale biên dịch cho Android (gomobile) | Giấy phép BSD-3, Tailscale phát hành chính thức; chính app Tailscale Android dùng cái này |
| Mẫu ghép `VpnService` | Tham khảo repo `tailscale/tailscale-android` | Cách nối libtailscale ↔ `VpnService` của Android |
| Go + gomobile trong CI | Biên dịch ra file `.aar` | Thêm bước cài Go vào workflow; hoặc vendor sẵn 1 `.aar` dựng trước |

Kích thước: `.aar` khá nặng (~10–20 MB mỗi kiến trúc CPU). Giảm bằng `abiFilters`
chỉ giữ `arm64-v8a` + `armeabi-v7a` (2 máy của anh đều ARM).

## 4. Xử lý auth key — KHÔNG chôn vào APK

Nhắc lại lỗi đã tránh: **không nhúng auth key vào mã nguồn/APK** vì repo + APK công
khai, ai tải về cũng moi ra được khoá rồi chui vào mạng nhà anh.

Luồng đúng:

```
Lần đầu mở app (vai trò Camera) → màn hình Cấu hình có ô "Dán Tailscale auth key"
   → app gọi libtailscale Up(authKey) → Tailscale tự cấp KHOÁ-MÁY riêng cho máy này
   → lưu state Tailscale trong thư mục riêng app (mã hoá) → XOÁ auth key vừa nhập
```

- Auth key chỉ sống trong RAM đủ lâu để join, join xong **xoá**, không ghi ra prefs.
- Máy xem: mã QR ghép đôi hiện có **kèm thêm auth key** (QR vốn đã tự ẩn sau 2 phút)
  để khỏi gõ tay. QR chứa khoá nên vẫn chỉ hiện khi bấm.
- Khoá dùng loại **reusable, hết hạn 90 ngày**; anh tạo mới mỗi lần cần, không gửi
  qua chat/Zalo chung.

## 5. Gộp 2 app thành 1

Hiện có 4 module: `shared`, `rtc`, `camera-node` (app), `viewer` (app).

| Bước | Việc |
|---|---|
| 1 | Biến `camera-node` → module **thư viện** `:node` (giữ nguyên code lõi) |
| 2 | Biến `viewer` → module **thư viện** `:view` (giữ nguyên code lõi) |
| 3 | Thêm module app mỏng `:app` (`vn.cnldg.oncam`) phụ thuộc `:node` + `:view` + `tailscale.aar` |
| 4 | Màn hình đầu: **"Máy này là CAMERA"** / **"Máy này để XEM"**; lưu lựa chọn, lần sau vào thẳng |
| 5 | CI build **1 APK duy nhất** `OnCam.apk` thay cho 2 file |

Code lõi 2 app **không phải viết lại**, chỉ đổi cách đóng gói.

## 6. Quyết định (nối tiếp D1–D35 trong ARCHITECTURE.md)

| # | Quyết định | Lý do |
|---|---|---|
| D36 | Nhúng VPN bằng **A2**: app tự chạy `VpnService` qua `libtailscale` | Giữ nguyên mọi tính năng (cả WebRTC UDP), không viết lại lớp mạng; đổi lại có hộp thoại + icon VPN (Android bắt buộc, và minh bạch) |
| D37 | **Auth key nhập 1 lần, không chôn vào APK**; join xong xoá khỏi bộ nhớ | Repo + APK công khai; chôn khoá = lộ khoá vào mạng nhà |
| D38 | Chỉ build `arm64-v8a` + `armeabi-v7a` | 2 máy đều ARM; giảm dung lượng `.aar` |
| D39 | Gộp thành **1 app** `vn.cnldg.oncam`, màn hình chọn vai trò; `camera-node`/`viewer` thành module thư viện | Cài 1 app theo yêu cầu; không phải viết lại code lõi |
| D40 | Tên + icon **nói đúng là camera** (giữ "OnCam"/"Camera Sân", icon ống kính). **Không** nguỵ trang thành game/app khác, **không** ẩn icon | App giám sát phải cho người cầm máy biết; giấu bản chất = stalkerware, vi phạm NĐ 13/2023/NĐ-CP và BLDS 2015 Đ.38 |
| D41 | Tailscale chạy userspace-state nhưng dùng `VpnService` (không cần root, không cần app rời) | Cân bằng giữa "1 app" và "giữ nguyên mạng" |

## 7. Rủi ro

| # | Rủi ro | Mức | Giảm thiểu |
|---|---|---|---|
| R1 | **CI không thử được join tailnet thật** (cần mạng + key thật) | Cao | Bắt buộc thử trên A54 + Reno15; CI chỉ bảo đảm biên dịch |
| R2 | Biên dịch Go-cho-Android trong CI phức tạp, hay vỡ | Trung bình | Vendor sẵn `.aar` dựng trước; hoặc cố định phiên bản Go + gomobile |
| R3 | APK to thêm ~15–25 MB | Thấp | `abiFilters` 2 ABI; tách theo ABI nếu cần |
| R4 | `VpnService` + Foreground camera cùng lúc trên máy nền có thể xung đột | Trung bình | Thử kỹ phần máy camera tự khởi động lại sau mất điện |
| R5 | Icon VPN làm người dùng tưởng "có gì đó lạ" | Thấp | Ghi trong hướng dẫn: đây là VPN của chính OnCam |

## 8. Kế hoạch từng bước (giữ CI xanh mỗi bước)

| Bước | Kết quả | Thử được ở đâu |
|---|---|---|
| 0 | Tài liệu này (đang làm) | — |
| 1 | Dựng/vendor `tailscale.aar`, thêm vào Gradle, build qua | CI (biên dịch) |
| 2 | Lớp `OncamVpn` (start/stop VpnService, Up(authKey), trạng thái) + ô nhập key | Máy thật |
| 3 | Gộp module: `:node`/`:view`/`:app`, màn hình chọn vai trò, ra 1 APK | CI + máy thật |
| 4 | QR ghép đôi kèm auth key; xoá key sau join | Máy thật |
| 5 | Cập nhật USER_GUIDE, gỡ mục "cài Tailscale rời" | — |

Mỗi bước 1 commit, CI phải xanh; bước 2 và 4 chờ anh xác nhận chạy được trên máy
thật rồi mới đi tiếp.
