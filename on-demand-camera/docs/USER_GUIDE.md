# Hướng dẫn sử dụng OnCam (bản 0.6)

OnCam biến một điện thoại Android cũ thành camera đặt ở sân nhà. Máy camera **luôn chờ lệnh** nhưng **chỉ mở camera
khi bạn gọi** từ điện thoại xem; không ai xem thì tự tắt để đỡ nóng, đỡ hao pin và đỡ tốn mạng.

| | Máy CAMERA | Máy XEM |
|---|---|---|
| Thiết bị của dự án | Samsung Galaxy A54 | Oppo Reno15 F |
| App cài | **OnCam Quay** (`OnCam-Quay.apk`) | **OnCam Xem** (`OnCam-Xem.apk`) |
| Tailscale | Có (cùng tài khoản Google) | Có (cùng tài khoản Google) |
| Nguồn điện | Cắm sạc 24/7 | Bình thường |

Thứ tự làm lần đầu (khoảng 20 phút): **Cài app → Tailscale → Cấu hình máy camera → Chống tắt app → Quét QR trên máy xem.**

---

## 1. Tải và cài app

Tải thẳng file `.apk` bằng trình duyệt trên điện thoại (không cần máy tính, không phải giải nén):

| App | Link tải (luôn là bản mới nhất) |
|---|---|
| OnCam Quay — cài lên máy CAMERA | https://github.com/cuongtvcaudat/app-keo-dien-ac/releases/download/oncam-latest/OnCam-Quay.apk |
| OnCam Xem — cài lên máy XEM | https://github.com/cuongtvcaudat/app-keo-dien-ac/releases/download/oncam-latest/OnCam-Xem.apk |

Trang ghi chú phiên bản: https://github.com/cuongtvcaudat/app-keo-dien-ac/releases/tag/oncam-latest

Lần đầu cài, Android hỏi **"Cài ứng dụng không rõ nguồn gốc"**:

| Máy | Cách cho phép |
|---|---|
| Samsung | Bấm **Cài đặt** trong hộp thoại → bật **Cho phép từ nguồn này** cho Chrome (hoặc app *Tệp của tôi*) → quay lại → **Cài đặt** |
| Oppo | Bấm **Cài đặt** → bật **Cho phép** cho trình duyệt/Quản lý tệp → quay lại → **Cài đặt** |

- Nếu hiện **Play Protect cảnh báo**: bấm **Chi tiết khác → Vẫn cài đặt** (app không có trên CH Play nên Google chưa biết).
- **Cập nhật**: tải lại đúng link trên rồi cài đè. Không mất cấu hình, không mất token.
- Cài đè báo **"Ứng dụng chưa được cài đặt"**: máy đang có bản thử nghiệm cũ ký bằng khoá khác (trước khi dự án dùng
  khoá chung) → **gỡ bản cũ một lần** rồi cài lại.
- Chỉ cài APK từ đúng 2 link trên. Khoá ký của dự án là khoá dùng chung công khai (xem `keystore/README.md`).

## 2. Cài Tailscale trên cả 2 máy

Làm theo [SETUP_TAILSCALE.md](SETUP_TAILSCALE.md). Kết quả cần đạt: trong app Tailscale của **máy xem** thấy máy
camera có chấm xanh, IP dạng `100.x.y.z`.

## 3. Cấu hình máy camera (OnCam Quay)

1. Mở **OnCam Quay** → màn hình *Cấu hình máy quay*:

   | Ô | Nhập | Ghi chú |
   |---|---|---|
   | Tên hiển thị | `Sân nhà` | Có dấu được, hiện trên app Xem |
   | Mã camera | `san-nha` | Chữ thường không dấu, số, `-`, `_`; tối đa 32 ký tự |
   | Token bí mật | (app tự tạo 43 ký tự) | **Không cần chép ra nữa** — chuyển sang máy xem bằng mã QR |
   | MQTT broker | *Broker trong máy này* | Để mặc định |
   | Hướng đặt máy | Đứng / Ngang trái / Ngang phải / Ngược | Theo cách gắn máy ngoài sân, để ảnh luôn đứng |
   | Tự chạy khi khởi động lại máy | Bật | |

2. Bấm **Lưu** → **Khởi động camera node** → cho phép **Camera** và **Thông báo**.
3. Thanh thông báo hiện *Camera node đang chờ lệnh*.

Kiểm tra máy camera chạy đúng:

| Mục trên màn hình | Giá trị đúng | Nếu sai |
|---|---|---|
| Broker | `Đang chạy tại 100.x.y.z:1883` | `Chờ Tailscale` → mở Tailscale, gạt Connected |
| MQTT | `Đã kết nối` | Chờ 10 giây; vẫn sai → Dừng rồi Khởi động lại |
| Mở camera từ xa | `Có` | `Chưa` → xem mục 8 |
| Bấm **Tự kiểm tra (PING)** | `✅ Thông suốt: … ms` | |

Thử camera ngay trên máy: mục **Camera** → **Bật camera thử** → thấy ảnh xem thử → để yên 60 giây camera tự tắt.

## 4. Chống hệ thống tắt app (bắt buộc)

Trên máy camera bấm **Hướng dẫn chống tắt app**. App tự nhận hãng máy và hiện đúng các bước (có nút chuyển sang
hướng dẫn hãng khác). Tóm tắt cho **Samsung A54**:

| # | Việc | Đường dẫn trên One UI |
|---|---|---|
| 1 | Bỏ tối ưu pin | Bấm nút trong app → **Cho phép** |
| 2 | Pin không hạn chế + không bao giờ ngủ | Thông tin ứng dụng → Pin → **Không hạn chế**. Cài đặt → Pin → Giới hạn sử dụng nền → thêm OnCam Quay và Tailscale vào **Ứng dụng không bao giờ ngủ**; tắt **Đặt ứng dụng không dùng sang chế độ ngủ** |
| 3 | Giữ mở trong đa nhiệm | Đa nhiệm → chạm biểu tượng app phía trên thẻ → **Giữ mở** |
| 4 | Tailscale luôn bật | Lặp lại 1–3 cho Tailscale; Cài đặt → Kết nối → Cài đặt kết nối khác → VPN → ⚙ Tailscale → **Luôn bật VPN**; trên login.tailscale.com → **Disable key expiry** cho máy camera |
| 5 | Tắt tự khởi động lại theo lịch | Cài đặt → Chăm sóc thiết bị → Tự động hoá → tắt **Tự động khởi động lại vào giờ đã đặt** |
| 6 | Bảo vệ pin | Cài đặt → Pin → **Bảo vệ pin** (mức dừng ở 80% nếu có) |

Oppo (ColorOS): bỏ tối ưu pin, *Cho phép hoạt động ở nền / tự khởi chạy*, **Khoá** thẻ trong đa nhiệm,
Tailscale *VPN luôn bật*.

## 5. Thêm camera vào máy xem bằng mã QR

1. Máy camera: mục *Nhập vào app OnCam Xem* → **Hiện mã QR cho app Xem**.
2. Máy xem: mở **OnCam Xem** → **Thêm camera** → **Quét mã QR trên máy camera** → cho phép Camera (lần đầu).
3. Đưa mã QR vào khung, cách 20–30 cm. Đọc được → các ô tự điền và hiện *Đã đọc mã QR của "Sân nhà"*.
4. Bấm **Lưu** → thẻ camera hiện **● Online** trong vài giây.

Mã QR chứa token bí mật nên **tự ẩn sau 2 phút** và ẩn ngay khi tắt màn hình. Không chụp màn hình mã QR, không gửi
qua Zalo/Facebook.

**Nhập tay** (khi 2 máy không ở cạnh nhau): trên máy camera bấm **Sao chép địa chỉ**/**Sao chép token**, gửi qua
kênh riêng tư của bạn, dán vào app Xem. Ô *Địa chỉ broker* chỉ cần gõ IP Tailscale của máy camera
(vd. `100.101.102.103`), app tự thêm `tcp://` và cổng `1883`. Xoá tin nhắn chứa token sau khi dán.

## 6. Dùng hằng ngày

### Trên thẻ camera

| Nút | Tác dụng |
|---|---|
| Bật / Tắt camera | Mở/nhả camera trên máy camera. Không ai xem video thì tự tắt sau thời gian chờ (Cài đặt, mặc định 3 phút) |
| Xem trực tiếp | Mở video toàn màn hình (tự bật camera nếu đang tắt) |
| Đổi camera | Camera trước ↔ sau |
| Bật/Tắt flash | Đèn chiếu sáng (chỉ camera sau có đèn) |
| Chụp ảnh | Lưu ảnh vào Thư viện › Pictures/OnCam |
| Thấp / Vừa / Cao | Chất lượng video (bảng mục 7) |

### Xem trực tiếp

- Dòng trên cùng: **số hình/giây • độ trễ • số người xem**. Từ bản 0.6 app **tự đo độ lệch đồng hồ 2 máy** khi mở
  màn hình xem nên độ trễ hiện đúng kể cả khi giờ 2 máy lệch nhau vài giây.
- Chạm video để ẩn/hiện nút. **Chụp ảnh** khi đang xem lưu đúng khung đang thấy.
- Xoay ngang để xem to; màn hình máy xem luôn sáng khi đang xem.
- **Quay lại**: ngắt video; ẩn app: tắt luôn camera do app bật (đổi trong Cài đặt).
- Chưa có âm thanh (dự kiến làm cùng WebRTC).

## 7. Dung lượng dữ liệu (ước tính)

Video là chuỗi ảnh JPEG (MJPEG), tốn dữ liệu hơn video nén như YouTube. Máy camera ở nhà dùng Wi-Fi thì
**máy xem đi 4G mới là bên tốn tiền**.

| Chất lượng | Khung hình | Ảnh/khung (ước tính) | Dữ liệu/phút | Xem 5 phút | Xem 1 giờ |
|---|---|---|---|---|---|
| Thấp | 640×480, 10 hình/giây | 20–40 KB | 12–24 MB | 60–120 MB | 0,7–1,4 GB |
| Vừa | 1280×720, 15 hình/giây | 60–110 KB | 55–100 MB | 0,3–0,5 GB | 3,2–5,9 GB |
| Cao | 1280×720, 30 hình/giây | 80–140 KB | 145–250 MB | 0,7–1,3 GB | 8,6–15 GB |

Cơ sở: dữ liệu = cỡ ảnh × số hình/giây × thời gian. Cỡ ảnh là ước tính cho cảnh sân nhà (chất lượng nén 60/70/80);
cảnh nhiều cây lá, ban đêm nhiều nhiễu thì lớn hơn. **Chưa đo trên máy thật** — xem số thật ở Cài đặt → Kết nối →
Sử dụng dữ liệu → OnCam Xem.

Khuyến nghị: đi 4G thì để **Thấp**; chỉ chuyển **Vừa/Cao** khi cần nhìn rõ chi tiết. Chụp ảnh (~100 KB/ảnh) rẻ hơn
xem video nhiều. Khi không xem, cả 2 app chỉ trao đổi tin trạng thái nhỏ (< 1 MB/ngày).

## 8. Xử lý sự cố

| Hiện tượng / thông báo | Nguyên nhân | Cách xử lý |
|---|---|---|
| Thẻ camera **Offline**, "Không kết nối được… Kiểm tra Tailscale" | Tailscale một trong 2 máy chưa Connected, hoặc máy camera mất mạng | Mở Tailscale 2 máy → Connected; máy camera xem mục *Broker* |
| "Sai token bí mật" | Token ở app Xem khác máy camera (vd. vừa tạo token mới) | Sửa camera → **Quét mã QR** lại |
| "Máy camera báo đã dừng" | Có người bấm Dừng, hoặc hệ thống tắt app | Mở OnCam Quay → Khởi động; làm lại mục 4 |
| Broker trên máy camera hiện **Chờ Tailscale** | Tailscale chưa Connected / bị tắt sau khởi động máy | Bật *Luôn bật VPN* cho Tailscale (mục 4, bước 4) |
| **Mở camera từ xa: Chưa** | Máy camera vừa khởi động lại hoặc app bị tắt rồi tự chạy lại: Android 11+ chỉ cho mở camera khi app từng được mở trên màn hình | Mở app OnCam Quay trên máy camera **một lần** |
| "Đồng hồ máy … lệch … giây" / "Lệnh bị từ chối vì đồng hồ 2 máy lệch quá 1 phút" | Giờ 2 máy lệch > 60 giây → lệnh bị coi là cũ (chống phát lại lệnh) | Bật **Ngày và giờ tự động** trên cả 2 máy |
| Xem trực tiếp quay mãi "Đang kết nối", báo HTTP 401 | Giờ lệch hoặc token sai | Như 2 dòng trên |
| Quét QR báo "Không phải mã QR của OnCam" | Quét nhầm mã khác, hoặc máy camera bản cũ (< 0.6) | Cập nhật OnCam Quay, bấm **Hiện mã QR** rồi quét lại |
| Nút **Hiện mã QR** mờ | Máy camera chưa có IP Tailscale | Bật Tailscale trên máy camera |
| Camera tự tắt, báo quá nhiệt | Pin ≥ 45 °C (≥ 42 °C thì tự hạ chất lượng) | Dời máy chỗ thoáng, tránh nắng; dùng chất lượng Thấp |
| Cài APK báo "Ứng dụng chưa được cài đặt" | Đang có bản thử nghiệm cũ ký khoá khác | Gỡ app cũ rồi cài lại (mất cấu hình, phải quét QR lại) |
| Video giật, hình/giây thấp | Mạng 4G yếu ở máy xem hoặc máy camera | Chọn **Thấp**; kiểm tra sóng Wi-Fi chỗ đặt máy camera |

## 9. Bảo mật

- **Token bí mật là chìa khoá điều khiển camera.** Ai có token + ở trong mạng Tailscale của bạn là bật được camera.
- Đổi token khi nghi bị lộ (vd. từng chụp màn hình có token): máy camera → **Sửa cấu hình** → **Tạo token mới** →
  Lưu → **Dừng** rồi **Khởi động** lại node → trên máy xem: Sửa camera → **Quét mã QR** lại.
- Broker và video chỉ mở trên IP Tailscale, không mở ra Internet; mọi lệnh đều được ký bằng token, video bắt buộc
  header xác thực có hạn 60 giây.
- Gỡ một máy khỏi tài khoản Tailscale (login.tailscale.com → Machines → Remove) là máy đó hết đường vào camera.

## 10. Ghi chú vận hành

- Cắm sạc 24/7 làm pin chai/phồng theo thời gian. Bật *Bảo vệ pin* (Samsung), kiểm tra pin 3 tháng/lần; pin phồng
  thì thay ngay (nguy cơ cháy).
- Đặt máy nơi thoáng, có mái che mưa, tránh nắng chiếu thẳng vào lưng máy.
- Sau mỗi lần cập nhật app máy camera: mở app một lần để mục *Mở camera từ xa* về `Có`.
