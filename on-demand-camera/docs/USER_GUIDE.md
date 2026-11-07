# Hướng dẫn sử dụng OnCam (bản 0.7)

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

- Từ bản 0.7, nếu **cả 2 app ≥ 0.7** thì video đi bằng **WebRTC** (nén như video gọi điện, có tiếng). Dòng trên cùng
  hiện vd. **WebRTC H264 1280×720 • 15 hình/giây • 820 kbit/s • 1 người xem**: *H264* là chuẩn nén (máy camera ưu tiên
  chuẩn có chip nén riêng cho đỡ nóng), *kbit/s* là dữ liệu **thật** đang tốn (1 000 kbit/s ≈ 450 MB/giờ). Camera bản cũ hoặc WebRTC lỗi thì app tự xem bằng **MJPEG**
  (như bản 0.6, không có tiếng) và ghi lý do ở cuối màn hình.
- Xem bằng MJPEG: dòng trên cùng hiện **số hình/giây • độ trễ • số người xem**; app tự đo lệch đồng hồ 2 máy nên
  độ trễ hiện đúng.
- Chạm video để ẩn/hiện nút. **Chụp ảnh** khi đang xem lưu ảnh từ máy camera (MJPEG: lưu đúng khung đang thấy).
- Xoay ngang để xem to; màn hình máy xem luôn sáng khi đang xem.
- **Quay lại**: ngắt video; ẩn app: ngắt cả hình lẫn tiếng, tắt luôn camera do app bật (đổi trong Cài đặt).

### Nghe tiếng sân và nói chuyện (từ bản 0.7, xem bằng WebRTC)

| Nút | Tác dụng |
|---|---|
| **Tắt tiếng sân / Nghe tiếng sân** | Bật/tắt tiếng từ micro máy camera trên máy xem (mặc định bật) |
| **Bật nói chuyện** | Lần đầu hỏi quyền **Micro** của máy xem; kết nối lại khoảng 1–2 giây để kèm micro |
| **Giữ để nói** | Giữ nút → tiếng máy xem phát ra **loa máy camera**; thả ra để nghe người ở sân trả lời |
| **Tắt nói** | Kết nối lại không kèm micro (máy xem không còn mở micro) |

- Nói kiểu **bộ đàm**: trong lúc giữ nút, máy xem tạm tắt tiếng từ sân để không nghe lại tiếng mình vọng qua loa.
- Máy camera phải cho phép micro: màn hình OnCam Quay → mục *Micro (nghe/nói từ xa)* phải là **Có**. Nếu là
  *Chưa cấp quyền* → bấm **Cho phép micro**; nếu là *Chưa* → mở app OnCam Quay một lần (giống camera).
- Trong lúc có người nghe/nói, máy camera tự chuyển loa ngoài và **âm lượng đàm thoại tối đa**, xong thì trả lại như cũ.
- ⚠️ Pháp lý: thu tiếng ở nơi có người khác là xử lý dữ liệu cá nhân (BLDS 2015 Điều 38 về bí mật đời tư;
  NĐ 13/2023/NĐ-CP). Chỉ hướng camera/micro vào khu vực nhà mình; nếu sân có người ngoài qua lại thường xuyên, nên
  dán thông báo "Khu vực có camera ghi hình, ghi âm". App chỉ lưu video khi bấm **Quay video** (mục dưới); xem
  trực tiếp, nghe, nói đều không lưu lại.

### Vị trí máy camera trên Google Maps (từ bản 0.8)

Dùng để biết camera đang ở đâu (nhiều camera ở nhiều nơi) và **tìm lại máy khi bị lấy trộm**.

1. Máy camera: **Sửa cấu hình** → tích **Chia sẻ vị trí máy này với app Xem** → Lưu → **Dừng** rồi **Khởi động**
   node → cho phép quyền **Vị trí** (chọn *Khi dùng ứng dụng*, nên bật *Vị trí chính xác*).
2. Bật **Vị trí** trên máy camera (kéo thanh thông báo xuống → *Vị trí*).
3. Máy xem: thẻ camera hiện **Vị trí** (toạ độ, sai số ±m, nguồn GPS hay Wi-Fi/mạng, cách đây bao lâu) với 2 nút:
   **Mở Google Maps** (mở đúng điểm đó trên Google Maps) và **Cập nhật vị trí** (lấy vị trí mới ngay, GPS có thể mất
   tới 30 giây).

| Điều cần biết | Chi tiết |
|---|---|
| Tự cập nhật | Chọn trong Sửa cấu hình máy camera: **15 phút** (mặc định), **60 phút**, hoặc **Tắt (chỉ khi bấm)** |
| Theo dõi (từ bản 0.9) | Máy xem bấm **Theo dõi 15 phút (1 phút/lần)** → máy camera lấy vị trí mỗi phút trong 15 phút rồi tự dừng; bấm lại để gia hạn, **Dừng theo dõi** để dừng ngay |
| Pin | GPS **không bật liên tục**: mỗi lần lấy chỉ chạy vài giây (tối đa 30 giây) rồi tắt. Ước tính: tự cập nhật 15 phút/lần ≈ 1 mAh/giờ (< 1% pin/ngày); đang theo dõi 1 phút/lần ≈ 30 mAh/giờ (~0,6% pin/giờ, chỉ trong lúc theo dõi). Công tắc **Vị trí** trong Cài đặt cứ để bật — tự nó không tốn pin, và Android không cho app tự bật công tắc này |
| Độ chính xác | GPS ngoài trời: 3–10 m. Trong nhà/dưới mái tôn: GPS yếu, app dùng Wi-Fi/mạng (sai số 20–500 m) |
| Camera offline | App Xem **giữ vị trí cuối cùng** (lưu mã hoá trong máy xem), ghi rõ "Vị trí cuối (camera đang offline)" — vẫn mở bản đồ được |
| Sau khi máy camera tự khởi động lại | Giống camera/micro: phải mở app OnCam Quay một lần. Muốn vị trí vẫn cập nhật mà không cần mở app: Cài đặt → Ứng dụng → OnCam Quay → Quyền → Vị trí → **Luôn cho phép** |
| Dữ liệu | Rất nhỏ (vài trăm byte mỗi lần), không ảnh hưởng gói 4G |

⚠️ Vị trí là dữ liệu cá nhân (NĐ 13/2023/NĐ-CP): chỉ bật trên máy của mình; ai có token camera thì xem được vị trí.

### Quay video (từ bản 0.10, cả 2 app ≥ 0.10)

Video **quay trên máy camera** (không phụ thuộc mạng lúc quay — mạng chập chờn clip vẫn đủ), xong thì **tự chép về
Thư viện máy xem** (album *Movies/OnCam*, mở bằng Thư viện/Google Photos như video thường).

1. Thẻ camera (hoặc màn hình xem trực tiếp) → **● Quay video**. Camera đang tắt thì tự bật.
2. Đang quay: thẻ hiện **● Đang quay 01:23 / 10:00**, thông báo trên máy camera đổi thành *Đang quay video* (người
   cầm máy camera luôn biết).
3. Bấm **■ Dừng quay** (hoặc tự dừng sau 10 phút) → máy xem tự tải clip về và báo *Đã lưu 1 video (… MB)*.
4. Bấm **Tải video về máy** để tải mọi clip chưa có (ví dụ lúc quay mà máy xem đang tắt app). Clip đã tải không tải lại.

| Điều cần biết | Chi tiết |
|---|---|
| Chất lượng | 1280×720 (HD), 2 Mbit/s, có tiếng nếu micro máy camera được phép (thẻ ghi *không có tiếng* nếu không) |
| Dung lượng | ≈ **15 MB/phút** → 1 clip 10 phút ≈ 150 MB. Cơ sở: (2 Mbit/s hình + ~0,1 Mbit/s tiếng) × 60 giây ÷ 8 |
| Máy camera giữ tối đa | **2 GB** (≈ 2 giờ 10 phút video) trong bộ nhớ riêng của app; đầy thì **tự xoá clip cũ nhất** |
| Tải về khi máy xem đi 4G | Chỉ tự tải clip ≤ 25 MB (~1,5 phút); clip dài hơn chờ bấm **Tải video về máy** hoặc tự tải khi máy xem về Wi-Fi |
| Đường tải | Thẳng từ máy camera qua Tailscale (cổng 8081, chỉ mở ~10 phút mỗi lần tải, có xác thực) — không qua máy chủ nào |
| Thời gian tải | Cùng Wi-Fi: 150 MB ≈ 15–30 giây. Qua 4G/Tailscale chuyển tiếp: có thể vài phút |
| Gỡ app OnCam Quay | Xoá luôn các clip còn trên máy camera (clip đã chép về máy xem thì còn) |

⚠️ Chưa thử trên máy thật:
- **Đang xem trực tiếp có tiếng mà bấm quay**: 2 bên cùng dùng micro máy camera — trên một số máy, clip có thể không
  có tiếng hoặc tiếng nghe trực tiếp bị ngắt. Nếu gặp, báo lại để em xử lý.
- Lần đầu bấm quay, hình xem trực tiếp có thể **khựng ~1 giây** (camera gắn thêm bộ quay); đổi camera trước/sau hoặc
  đổi chất lượng trong lúc quay sẽ **kết thúc clip** đang quay (clip đến lúc đó vẫn được lưu).

### Canh chuyển động — tự quay khi có người/xe vào (từ bản 0.11, cả 2 app ≥ 0.11)

1. Thẻ camera → **Bật canh chuyển động** (camera đang tắt thì tự bật ở mức **Thấp** cho đỡ nóng; video quay vẫn HD).
2. Có chuyển động → máy camera **tự quay**, quay tiếp **30 giây sau khi hết chuyển động** (tối đa 2 phút/clip, còn
   chuyển động thì quay clip tiếp). Clip tự chép về Thư viện máy xem như mục Quay video.
3. Thẻ hiện **Chuyển động gần nhất: 5 phút trước**. Thông báo trên máy camera ghi *Đang canh chuyển động*.
4. **Tắt canh chuyển động** khi không cần. Trong lúc canh, nút *Tắt camera* và việc rời app Xem **không tắt camera**.

| Độ nhạy | Báo khi | Dùng khi |
|---|---|---|
| Thấp | Vật chiếm ≥ 5 % khung hình (người đi gần, xe vào cổng) | Sân có cây lá, hay mưa gió |
| Vừa (mặc định) | ≥ 2,5 % khung (người đi ngang sân ~10 m) | Đa số trường hợp |
| Cao | ≥ 1 % khung (người ở xa, vật nhỏ) | Cảnh tĩnh, trong nhà/kho |

Cách nhận biết: chia khung thành lưới 32×24 ô, so độ sáng từng ô với ảnh nền (tự thích nghi khi trời sáng/tối dần),
phải thấy đổi **2 lần liên tiếp** (~0,5 giây) mới tính. Bật/tắt đèn, mây che nắng (đổi ≥ 70 % khung cùng lúc) **không
tính** là chuyển động.

⚠️ Rủi ro cần biết (chưa đo trên máy thật):
- **Pin và nhiệt**: camera chạy liên tục — ước tính 1–1,5 W, pin 5.000 mAh cạn trong ~13–19 giờ nếu không cắm sạc.
  **Bắt buộc cắm sạc**. Samsung: Cài đặt → Chăm sóc pin → Bảo vệ pin → *Tối đa 85 %* để pin không phồng khi cắm
  sạc lâu ngày. Đặt máy chỗ thoáng, không phơi nắng: ≥ 45 °C máy tự tắt camera và tự tắt canh (thẻ báo lý do).
- **Báo nhầm**: lá cây, bóng nắng di chuyển, mưa, côn trùng bám ống kính, đèn xe quét qua. Hướng camera tránh cây/
  đường lớn, chọn độ nhạy Thấp nếu quay nhầm nhiều.
- **Ban đêm**: điện thoại không có đèn hồng ngoại → cảnh tối nhiều nhiễu, dễ bỏ sót người đi trong bóng tối. Cần
  đèn sân (đèn cảm biến chuyển động là tốt nhất).
- **Clip bắt đầu chậm ~0,5–1 giây** sau lúc có chuyển động (không có đoạn quay trước).
- Bộ nhớ 2 GB tự xoá clip cũ nhất: 1 clip 30 giây ≈ 7,5 MB → giữ được ~270 clip. Muốn giữ lâu thì để app Xem tự chép về.
- Máy camera **khởi động lại** (mất điện, cập nhật) thì **không tự canh lại** — phải mở app OnCam Quay rồi bật canh
  từ máy xem (giới hạn của Android với camera chạy nền).

## 7. Dung lượng dữ liệu (ước tính)

Máy camera ở nhà dùng Wi-Fi thì **máy xem đi 4G mới là bên tốn tiền**.

**WebRTC (bản 0.7, cả 2 app ≥ 0.7)** — số dưới là **mức trần** app đặt cho camera; cảnh đứng yên dùng ít hơn:

| Chất lượng | Video tối đa | + Tiếng sân | Dữ liệu/phút | Xem 5 phút | Xem 1 giờ |
|---|---|---|---|---|---|
| Thấp | 300 kbit/s | ~32 kbit/s | ≤ 2,5 MB | ≤ 12,5 MB | ≤ 0,15 GB |
| Vừa | 900 kbit/s | ~32 kbit/s | ≤ 7,0 MB | ≤ 35 MB | ≤ 0,42 GB |
| Cao | 1,8 Mbit/s | ~32 kbit/s | ≤ 13,7 MB | ≤ 69 MB | ≤ 0,82 GB |

Cơ sở: (bitrate video + tiếng Opus ~32 kbit/s) ÷ 8 × thời gian. Giữ để nói tốn thêm ~32 kbit/s chiều lên trong lúc
giữ nút. So với MJPEG cùng mức chất lượng: ít hơn **khoảng 5–18 lần** (Thấp ~5–9 lần, Vừa ~8–14 lần, Cao ~10–18 lần).

**MJPEG (camera bản cũ, hoặc khi WebRTC lỗi)** — video là chuỗi ảnh JPEG, tốn hơn nhiều:

| Chất lượng | Khung hình | Ảnh/khung (ước tính) | Dữ liệu/phút | Xem 5 phút | Xem 1 giờ |
|---|---|---|---|---|---|
| Thấp | 640×480, 10 hình/giây | 20–40 KB | 12–24 MB | 60–120 MB | 0,7–1,4 GB |
| Vừa | 1280×720, 15 hình/giây | 60–110 KB | 55–100 MB | 0,3–0,5 GB | 3,2–5,9 GB |
| Cao | 1280×720, 30 hình/giây | 80–140 KB | 145–250 MB | 0,7–1,3 GB | 8,6–15 GB |

Cơ sở: dữ liệu = cỡ ảnh × số hình/giây × thời gian. Cỡ ảnh là ước tính cho cảnh sân nhà (chất lượng nén 60/70/80);
cảnh nhiều cây lá, ban đêm nhiều nhiễu thì lớn hơn. **Chưa đo trên máy thật** — xem số thật ở Cài đặt → Kết nối →
Sử dụng dữ liệu → OnCam Xem.

Khuyến nghị: đi 4G thì để **Thấp** (nhất là khi đang xem MJPEG); chỉ chuyển **Vừa/Cao** khi cần nhìn rõ chi tiết. Chụp ảnh (~100 KB/ảnh) rẻ hơn
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
| Cuối màn hình xem: "Đang xem MJPEG … `RTC_NO_ADDRESS`" | WebRTC trên máy camera không thấy IP Tailscale | Kiểm tra Tailscale máy camera Connected; Dừng rồi Khởi động lại node |
| "… `RTC_CONNECT_FAILED`" / "… `TIMEOUT`" | 2 máy không nối thẳng được qua Tailscale (mạng chặn UDP), hoặc mạng quá yếu | Vẫn xem được MJPEG. Thử đổi Wi-Fi ↔ 4G trên máy xem rồi mở lại màn hình xem |
| "… `NO_LOCAL_ADDRESS: máy xem không có IP Tailscale`" | Tailscale máy xem chưa Connected | Mở Tailscale trên máy xem → Connected, mở lại màn hình xem |
| "… `ICE_FAILED`/`ICE_TIMEOUT: xem 100.a ↔ camera 100.b`" | 2 máy có địa chỉ Tailscale nhưng gói UDP không qua được | Chụp màn hình gửi người cài đặt (có đủ địa chỉ 2 bên để chẩn đoán); vẫn xem được MJPEG |
| "… `RTC_BUSY`" | Đã có 2 máy xem bằng WebRTC cùng lúc (giới hạn để máy camera đỡ nóng) | Máy thứ 3 tự xem bằng MJPEG |
| "Máy camera chưa cho dùng micro" | Máy camera chưa cấp quyền micro hoặc vừa khởi động lại | Trên máy camera: **Cho phép micro** hoặc mở app một lần |
| Giữ để nói nhưng ở sân không nghe | Âm lượng máy camera bị chặn (Không làm phiền) hoặc loa máy camera bị che | Tắt *Không làm phiền* trên máy camera; đặt máy để loa không áp vào tường |
| Nghe tiếng mình vọng lại | Thả nút nói ra quá sớm / loa máy camera quá to sát micro | Nói xong mới thả nút; để máy camera cách tường ít nhất 10 cm |
| Thẻ camera không có mục Vị trí | Máy camera chưa bật *Chia sẻ vị trí*, hoặc máy camera bản < 0.8 | Bật trong Sửa cấu hình; cập nhật OnCam Quay |
| "Máy camera chưa lấy được vị trí: mở app…" | Máy camera vừa khởi động lại (service chạy từ nền không được dùng vị trí) | Mở app OnCam Quay một lần, hoặc chọn quyền Vị trí *Luôn cho phép* |
| "Máy camera chưa bắt được vị trí" | Trong nhà kín, chưa có vệ tinh | Đặt máy gần cửa/ngoài hiên; bật Wi-Fi để có vị trí theo mạng |
| Vị trí lệch vài trăm mét | Đang dùng vị trí theo Wi-Fi/mạng (GPS không bắt được) | Xem cột nguồn: *Wi-Fi/mạng* thì sai số lớn là bình thường |

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
