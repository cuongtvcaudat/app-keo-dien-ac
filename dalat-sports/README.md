# DALAT SPORTS HUB: quản lý VĐV, HLV và thành tích thi đấu

Ứng dụng web (PWA, cài được lên Android) để quản lý nhân sự VĐV, HLV và thành tích thi đấu các bộ môn thể thao Đà Lạt.
Dữ liệu khởi tạo lấy từ `Huu_Bang_Tong_Hop_HLV_V_V_2026.xlsx` (bản mới, 81 HLV + 675 VĐV). Bản cũ là `bang_tong_hop_HLV_V_V_Thang_9_2026.xlsx` (79 + 662).

## 0. Đưa lên Vercel (làm 1 lần, khoảng 2 phút)

1. Vào https://vercel.com/new và đăng nhập đúng tài khoản đang có project `web-cuongtv1089-3159s-projects`.
2. Mục **Import Git Repository**, chọn `cuongtvcaudat/app-keo-dien-ac`. Nếu chưa thấy repo, bấm "Adjust GitHub App Permissions" và cấp quyền cho repo này.
3. **Root Directory**: bấm Edit, chọn `dalat-sports`. **Framework Preset**: `Other`. Không cần Build Command.
4. Bấm **Deploy**. Vercel sẽ cấp link dạng `https://<tên-project>.vercel.app`.
5. Sau đó mỗi lần có code mới đẩy lên GitHub, Vercel tự triển khai lại: nhánh `main` lên link chính, các nhánh khác lên link Preview.
   - Để chạy ngay bản trên nhánh `claude/athlete-management-app-xfwsqa`: vào project → Settings → Git → Production Branch, đặt tên nhánh này. Hoặc merge PR #4 vào `main`.

## Logic nghiệp vụ lấy từ file Excel mới

| Trong Excel | Trong app |
|---|---|
| `DS_VĐV[TRẠNG THÁI]` (Data Validation: "Thôi đào tạo", "Tuyển mới") | Trường `status`. Mọi thống kê **loại "Thôi đào tạo"**, giống tiêu chí `"<>Thôi đào tạo"` trong các hàm COUNTIFS. Hồ sơ người nghỉ vẫn giữ lại |
| `DS_VĐV[HỢP ĐỒNG]` (ngày) + `NGÀY CÒN LẠI = IF(ISBLANK(I),"Chưa xác định",I-TODAY())` | `contractEnd` + số ngày còn lại tính theo ngày hôm nay. Cảnh báo theo các mốc: đã hết hạn / ≤30 ngày / ≤90 ngày / ≤180 ngày / ≤1 năm |
| `DS_HLV[THỜI HẠN HĐLĐ]` (ngày, "Viên chức", "không xác định thời hạn") | `contractEnd` hoặc `contractType` |
| `Ghi chú` (NQ31, NQ15, NQ31- Kiêm HLV) / HLV `Column1` (BÁC SỸ, KIÊM VĐV, NQ31) | `note`: có bộ lọc riêng và hiện dạng thẻ (tag) |
| Sheet "Tuyển - Nghĩ" = `FILTER(TRẠNG THÁI = Thôi đào tạo hoặc Tuyển mới)` | Trang **Tuyển mới / Thôi**, có ghi ngày đổi trạng thái |
| 25 sheet theo đội (U15, U16, Judo…) = `FILTER(MÔN THỂ THAO = đội)` + bảng Nam/Nữ, Tuyển/Trẻ/Năng khiếu | Trang **Đội** (`#/doi/<tên đội>`), mở từ bảng chi tiết của từng môn; có nút xuất Excel theo đội |

Lỗi công thức trong file mới (app không dùng các ô này mà tự tính lại; chi tiết xem mục Kiểm tra dữ liệu):
- `VĐV!E692`, `F692`, `F693`: COUNTIFS bị lệch cột, luôn ra 0.
- `M692` gõ thiếu dấu "*Năng khiêu*".
- `N692` đếm "*Nam*" thay vì "*Nữ*".
- STT `B692` lệch.
- Bảng giới tính HLV lệch 1 dòng.
- 33 Defined Name đều `#REF!`.
- Sheet HLV chưa có cột TRẠNG THÁI.

Số liệu hợp đồng tại ngày 22/10/2026:
- 3 VĐV đã hết hạn: Trần Hữu Ngọc Long 30/09, Nguyễn Ngọc Tiến 01/10, Võ Khai Nguyên 01/10.
- 1 người hết hạn trong ≤30 ngày: Lê Hoàng Tỷ, 31/10.
- **92 người hết hạn trong ≤90 ngày**, phần lớn vào ngày 31/12/2026.

- Bản thiết kế giao diện trên Canva: https://canva.link/nvc1a9ptviphgv9
- Có 3 giao diện, đổi bằng nút ở cuối sidebar hoặc nút 🎨 trên điện thoại. App nhớ lựa chọn của từng máy:
  - **Neon Đêm** (mặc định): nền tối, xanh chanh neon `#C6FF1A`, cyan `#00E0FF`, cam lửa `#FF4D2E`, hồng `#FF2E93`, tím `#8B3DFF`.
  - **Bùng Cháy**: nền sáng, cam lửa `#FF3D00`, xanh hoàng gia `#0050FF`, hồng `#E5007A`.
  - **Xanh Đà Lạt**: xanh thông `#0B4D3B` và `#00B37A`, vàng huy chương `#FFC727`.
- Chạy thử: `cd dalat-sports && python3 -m http.server 8765`, sau đó mở http://localhost:8765. Mở thẳng file `index.html` cũng chạy được, chỉ thiếu chế độ offline.

## 1. Cấu trúc file Excel gốc

| Sheet | Excel Table | Vùng dữ liệu | Số dòng | Cột |
|---|---|---|---|---|
| HLV | `DS_HLV` | A4:K84 | 79 HLV | TT (`=ROW()-4`), CODE MÔN, HỌ VÀ TÊN, NĂM SINH, GIỚI TÍNH, NHIỆM VỤ, MÔN THỂ THAO, ĐỘI TUYẾN, GHI CHÚ, Column1, Column2 |
| VĐV | `DS_VĐV` | A4:K666 | 662 VĐV | như trên, thêm cột HỢP ĐỒNG |

Bên dưới mỗi sheet có 3 bảng tổng hợp viết bằng `COUNTIFS` trên Table:
1. Giới tính: Nam/Nữ (HLV: 75/4; VĐV: 470/192).
2. Số lượng theo môn × tuyến (Tuyển / Trẻ / Năng khiếu), 25 dòng.
3. Môn × tuyến × giới tính, kèm cột tổng.

Mã hoá trong file:
- **21 CODE MÔN** (BDA, BRO, CLONG, BBAN, DKINH, BLAN, CANOE, ĐTTT, TAEK, JUDO, KARATE, VOVINAM, VCT, JUJITSU, VAT, BOXING, CTA, THEH, DGAY, CVUA, KVTT).
- **25 "MÔN THỂ THAO"**: môn BDA tách thành 5 đội là Đội tuyển Bóng đá, Bóng đá Trẻ, U17, U16, U15.
- **ĐỘI TUYẾN**: VĐV gồm Tuyển 247, Trẻ 183, Năng khiếu 232. HLV chỉ có Tuyển 64 và Trẻ 15.

## 2. Lỗi và rủi ro dữ liệu phát hiện được (app tự kiểm tra ở mục "Kiểm tra dữ liệu")

| # | Vấn đề | Số lượng | Ví dụ / đề xuất |
|---|---|---|---|
| 1 | Thiếu năm sinh | 3 | Nguyễn Thị Ngân Thương, Ka Cậy, Tô Anh Bảo Ngọc (Vật, Năng khiếu) |
| 2 | VĐV tuyến Trẻ nhưng trên 25 tuổi | 12 | Huỳnh Lê Anh Vi sinh **1976** ở KVTT Trẻ, khả năng cao nhập nhầm năm |
| 3 | VĐV Năng khiếu trên 18 tuổi | 9 | Cần xác nhận lại tuyến |
| 4 | Tuổi bất thường | 2 dưới 9 tuổi, 5 trên 45 tuổi | Cờ vua sinh 2019; Đua thuyền sinh 1958 |
| 5 | Trùng họ tên | 10 nhóm | Nguyễn Quốc Huy xuất hiện 3 lần. Cần **mã định danh** riêng (app tự sinh dạng `V-JUDO-012`) |
| 6 | Tuyến có VĐV nhưng 0 HLV | 29 | Không có HLV nào ghi "Năng khiếu", trong khi có 232 VĐV năng khiếu. Cần trường **HLV phụ trách** |
| 7 | Tỉ lệ VĐV/HLV cao | 3 môn | Judo 45/1, Jujitsu 20/1, Đua thuyền 46/3 |
| 8 | Cột HỢP ĐỒNG trống | 662/662 | Chưa quản lý được hợp đồng và hạn hợp đồng |
| 9 | Khoảng trắng thừa | 22 họ tên, "Huấn luyện viên " | Đã chuẩn hoá khi nhập |
| 10 | Không thống nhất chữ hoa | "Khiêu vũ Thể thao" / "Khiêu vũ thể thao" | `COUNTIFS` không phân biệt hoa thường nên vẫn đếm đúng, nhưng lọc theo kiểu "khớp chính xác" sẽ lệch |
| 11 | Bảng giới tính HLV lệch 1 dòng so với bảng số lượng (G95 so với B94) | n/a | Số đếm vẫn đúng nhưng in ra dễ đọc nhầm hàng |
| 12 | **Không có dữ liệu thành tích** | n/a | File chỉ có nhân sự. App bổ sung module Giải đấu / Thành tích |

## 3. Chức năng đã làm (giai đoạn 1, MVP)

- **Tổng quan**: thẻ KPI (662 VĐV, 79 HLV, huy chương, cảnh báo), biểu đồ VĐV theo môn tách 3 tuyến, cơ cấu giới tính, cơ cấu tuyến, độ tuổi, bảng huy chương.
- **VĐV / HLV**: lọc theo môn, đội, tuyến, giới tính, khoảng tuổi; tìm kiếm không dấu; sắp xếp; phân trang; thêm, sửa, xoá; xuất Excel đúng danh sách đang lọc.
- **Hồ sơ cá nhân**: thông tin, lịch sử thi đấu, số huy chương, HLV cùng môn, cảnh báo riêng của người đó.
- **Bộ môn**: 21 thẻ có icon và màu riêng, tỉ lệ VĐV/HLV; trang chi tiết có bảng đội × tuyến × giới tính (thay cho bảng COUNTIFS).
- **Giải đấu & Thành tích**: tạo giải (cấp Quốc tế / Quốc gia / Khu vực / Tỉnh / Thành phố); nhập kết quả nhanh bằng nút huy chương lớn, chọn "lưu xong nhập tiếp"; Top VĐV; bảng huy chương theo môn.
- **Nhập / Xuất Excel**: nhập file cùng mẫu (tự bỏ các dòng tổng hợp), có 2 chế độ thay toàn bộ hoặc chỉ thêm người mới. Xuất 5 sheet: DS_HLV, DS_VĐV, TONG_HOP, GIAI_DAU, THANH_TICH. Sao lưu và khôi phục bằng JSON.
- **Android**: giao diện responsive, thanh điều hướng dưới, nút nổi 🏅 để nhập thành tích; cài lên màn hình chính bằng "Add to Home screen" và chạy offline.

> ⚠ Giai đoạn 1 lưu dữ liệu trong **trình duyệt của từng máy** (localStorage), nên chưa dùng chung nhiều người được. Hãy sao lưu JSON thường xuyên.

## 4. Lộ trình

| Giai đoạn | Thời gian | Nội dung | Kết quả |
|---|---|---|---|
| **1. MVP web (đã xong)** | Tuần 1 | Đọc Excel, dashboard, danh sách, hồ sơ, bộ môn, giải đấu, kiểm tra dữ liệu, nhập/xuất Excel, PWA | Bản demo để chốt nghiệp vụ |
| **2. Dữ liệu dùng chung** | Tuần 2–3 | Phương án A: Google Sheets + Apps Script (giống app Kéo điện AC, 0 đồng). Phương án B: Supabase (Postgres, phân quyền theo dòng, gói miễn phí 500 MB) | Nhiều người cùng xem và nhập |
| **3. Phân quyền** | Tuần 3 | Admin (Sở/Trung tâm), Trưởng bộ môn, HLV (chỉ thấy và nhập VĐV của mình), Xem | Nhật ký ai sửa gì, lúc nào |
| **4. Hồ sơ mở rộng** | Tuần 4–5 | Ảnh thẻ, CCCD, hợp đồng (số, từ ngày, đến ngày, tự cảnh báo trước 30 ngày khi sắp hết hạn), HLV phụ trách, chiều cao/cân nặng, chỉ số thể lực theo kỳ kiểm tra | Quản lý hồ sơ đầy đủ |
| **5. Thành tích nâng cao** | Tuần 5–6 | Lịch thi đấu, đăng ký VĐV dự giải, kỷ lục cá nhân và kỷ lục tỉnh, xếp hạng theo điểm (ví dụ HCV quốc tế = 10, HCV quốc gia = 5…), báo cáo tháng/quý/năm xuất Word/PDF | Báo cáo thi đua |
| **6. App Android thật** | Tuần 7–8 | Đóng gói PWA bằng Capacitor hoặc TWA thành file APK / AAB; quét QR thẻ VĐV; nhập offline rồi đồng bộ | Đưa lên CH Play hoặc cài nội bộ |

## 5. Cấu trúc mã nguồn

```
dalat-sports/
├── index.html          # khung app, sidebar, bottom-nav mobile
├── css/app.css         # bảng màu xanh thông Đà Lạt + vàng/cam/xanh điện, responsive
├── js/sports.js        # danh mục 21 môn: icon, màu, nhóm
├── js/seed-data.js     # 741 người sinh từ file Excel tháng 9/2026
├── js/app.js           # router, store, dashboard, CRUD, kiểm tra dữ liệu, Excel I/O
├── js/vendor/xlsx.full.min.js  # SheetJS 0.18.5 (Apache-2.0), đóng kèm để chạy offline
├── manifest.json, sw.js, icon.svg, icon-pines.svg   # PWA
```
