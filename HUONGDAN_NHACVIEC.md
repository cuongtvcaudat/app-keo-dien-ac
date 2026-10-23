# HƯỚNG DẪN — Bot nhắc việc 8h sáng vào nhóm Zalo

Code: `gas/NhacViec.js` (chung project Apps Script với `Code.js`, dùng chung Google Sheet).
Bot gửi mỗi sáng **T2–T7, 8:00–8:05** (giờ VN). Quá 10h mà chưa gửi được thì bỏ qua, không gửi muộn.

## Bước 1 — Tạo Zalo Bot (5 phút, trên điện thoại)
1. Mở Zalo → ô tìm kiếm gõ **"Zalo Bot Creator"** (Mini App, icon robot xanh) → chọn **Tạo bot**.
2. Đặt tên bot (VD `Bot Nhắc Việc HT LDG`) → Zalo gửi về **Bot Token** (dạng `12345:abcXYZ...`).
3. **Giữ bí mật token** — ai có token là gửi tin được bằng bot của anh.

## Bước 2 — Dán code vào Apps Script
1. Mở Google Sheet (ID trong `Code.js`) → **Tiện ích mở rộng → Apps Script**.
2. Tạo file mới `NhacViec` → dán nội dung `gas/NhacViec.js` → Lưu.
   (Nếu dùng clasp: `cd gas && clasp push`.)
3. **Cài đặt dự án (⚙️) → Thuộc tính tập lệnh** → thêm:

| Thuộc tính | Giá trị | Bắt buộc |
|---|---|---|
| `ZALO_BOT_TOKEN` | token ở Bước 1 | ✔ |
| `ZALO_CHAT_ID` | lấy ở Bước 3 | ✔ |
| `NV_NGAY_GUI` | mặc định `T2,T3,T4,T5,T6,T7` (VD bỏ T7: `T2,T3,T4,T5,T6`) | |
| `ZALO_API_BASE` | mặc định `https://bot-api.zapps.me` — chỉ đổi nếu Zalo đổi domain | |

## Bước 3 — Lấy chat_id của nhóm
1. Vào nhóm Zalo → **Thêm thành viên** → tìm tên bot → thêm vào nhóm.
2. Nhắn 1 tin trong nhóm, **@tag bot** (VD `@Bot Nhắc Việc test`).
3. Trong Apps Script chọn hàm `nv_layChatId` → **Chạy** → xem **Nhật ký thực thi**.
4. Copy `chat_id` dòng có `loại=GROUP` → dán vào thuộc tính `ZALO_CHAT_ID`.

## Bước 4 — THỬ NGHIỆM
| Hàm | Làm gì |
|---|---|
| `nv_xemTruoc` | Chỉ in nội dung ra Nhật ký, **không gửi** — kiểm tra nội dung trước |
| `nv_guiThu` | Gửi 1 tin có tiêu đề `🧪 [THỬ NGHIỆM]` vào nhóm |

Lần chạy đầu Google hỏi cấp quyền (Sheets, gửi request ra ngoài, trigger) → **Cho phép**.

## Bước 5 — Bật lịch hằng ngày
Chạy `nv_caiDat` → tạo sheet `NhacViec` (6 dòng mẫu) + bật lịch. Tắt: chạy `nv_tatLich`.

## Cách nhập việc (sheet `NhacViec`)
| Cột | Ví dụ | Ghi chú |
|---|---|---|
| A Việc | Đối soát 4A/4B HĐ-2026-001 | |
| B Người phụ trách | XLVT / tên người | hiển thị trong [ ] |
| C Hạn | 25/09/2026 | để trống + không lặp → nhắc mỗi ngày tới khi Xong |
| D Lặp lại | `Hằng ngày` · `T2` · `T2,T5` · `Ngày 5` · `Ngày 5,20` · `Cuối tháng` | có lặp thì bỏ qua cột Hạn |
| E Trạng thái | `Xong` | bot ngừng nhắc |

Bot tự phân loại: 🔴 Quá hạn → 🟠 Hạn hôm nay → 🔁 Định kỳ → 🟡 Sắp đến hạn (≤3 ngày) → 🏗 Công trình chưa "Hoàn thành" (sheet `KeHoach_CongTrinh`, tối đa 15 dòng).

## Rủi ro / lưu ý
- **Zalo Bot còn mới** (ra mắt 2025): nếu không thêm được bot vào nhóm → dùng tạm chat_id cá nhân (anh nhắn riêng cho bot, chạy `nv_layChatId` lấy id `PRIVATE`), rồi forward vào nhóm; hoặc chuyển sang Zalo OA (cần OA đã xác thực, có phí).
- **Không dùng thư viện Zalo không chính thức** (zca-js, zlapi — đăng nhập bằng tài khoản cá nhân): vi phạm điều khoản, dễ bị khóa nick.
- Quota Apps Script (tài khoản Gmail thường): trigger tổng 90 phút/ngày. Trigger 5 phút = 288 lần/ngày × <1 giây ≈ 5 phút/ngày → dư.
- Tin dài > 1.800 ký tự tự cắt thành nhiều tin.
- Test offline: `TZ=Asia/Ho_Chi_Minh node tests/nhacviec.test.js`
