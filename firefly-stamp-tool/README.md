# Công cụ dán stamp ảnh hiện trường (Firefly + tự động)

Tạo một **folder ảnh mới** từ folder ảnh gốc, dán lên mỗi ảnh lớp thông tin
(ngày giờ, toạ độ, địa chỉ, mã trạm, hướng, độ cao...) theo đúng kiểu overlay
của app **GPS Map Camera** — dữ liệu lấy từ **file Excel** hoặc **gõ tay**.

> ⚠️ **Dùng đúng mục đích:** công cụ này để **chuẩn hoá / dán lại nhãn cho ảnh
> thật đã chụp tại từng trạm** (mỗi ảnh là một địa điểm có thật). Không dùng để
> nhân bản một ảnh mẫu thành nhiều toạ độ/thời gian khác nhau làm bằng chứng giả.

---

## 1. Chạy nhanh (1 click)

| Máy | File bấm |
|-----|----------|
| Windows | **`CHAY_WINDOWS.bat`** (nhấp đúp) |
| macOS / Linux | **`CHAY_MAC.command`** (nhấp đúp) |

Lần đầu chạy cần **Python** (tải ở https://www.python.org/downloads/ — Windows nhớ
tích *“Add Python to PATH”*). Công cụ tự cài thư viện cần thiết.

Hiện menu:
```
1. Chạy hàng loạt (từ file Excel)
2. Gõ tay 1 ảnh
3. Tạo / làm mới file Excel mẫu
```

---

## 2. Quy trình 3 bước

1. **Bấm mục 3** → tạo file `data/du_lieu.xlsx` (đã có sẵn cột + vài dòng ví dụ).
2. **Mở Excel**, điền dữ liệu — **mỗi dòng = 1 ảnh**. Cột `file` = tên ảnh gốc
   (không cần đuôi `.jpg`). Copy ảnh gốc vào thư mục **`data/input/`**.
3. **Bấm mục 1** → ảnh đã dán stamp nằm trong **`data/output/`**. Xong.

### Các cột trong Excel
| Cột | Ý nghĩa | Ví dụ |
|-----|---------|-------|
| `file` | Tên ảnh gốc trong `data/input` | `LDG0772` |
| `template` | Kiểu stamp: `kieu_A` hoặc `kieu_B` | `kieu_B` |
| `ngay` | Ngày/giờ hiển thị | `14/07/2026` |
| `lat`, `lon` | Vĩ độ / kinh độ | `11.71146542` `108.37751334` |
| `bearing_deg` | Góc hướng (để vẽ kim la bàn) | `34` |
| `huong` | Chữ hướng hiển thị | `34° NE` |
| `dia_chi` | Địa chỉ (để trống nếu không có) | `19 Nguyễn Bá Ngọc` |
| `phuong` / `huyen` | Phường / huyện | `B'Lao` / `Đức Trọng` |
| `tinh` | Tỉnh | `Lâm Đồng` |
| `altitude` / `speed` | Độ cao / tốc độ | `913.2` / `0.0` |
| `ma_tram` | Mã trạm | `LDG0772 M1` |
| `ghi_chu` | Ghi chú thêm | `.t3` |
| `index` | Index number | `49` |

> Dòng nào để trống một cột thì stamp **tự bỏ dòng đó** (vd không có `dia_chi`).

---

## 3. Hai kiểu stamp (đổi ở cột `template`)

- **`kieu_A`** — chữ canh trái + thumbnail bản đồ góc dưới-trái (giống ảnh cột B'Lao).
- **`kieu_B`** — chữ canh phải góc dưới + la bàn góc trên-trái (giống ảnh Đức Trọng).

Muốn chỉnh font, vị trí, cỡ chữ, thêm/bớt dòng → sửa file trong thư mục
`templates/` (chỉ sửa số, **không cần code**).

---

## 4. Xử lý ảnh đã có stamp cũ (thay số liệu)

Mở `config.json`, mục `old_stamp_cleanup` → `mode`:

| mode | Ý nghĩa | Cần gì |
|------|---------|--------|
| `cover` *(mặc định)* | Phủ dải gradient tối lên vùng stamp cũ rồi vẽ stamp mới. Miễn phí, offline. | — |
| `firefly` | Dùng **Adobe Firefly Generative Fill** xoá sạch stamp cũ, tái tạo nền, rồi vẽ stamp mới. Sạch nhất. | Bản quyền Firefly API |
| `none` | Không xử lý — dùng khi **ảnh gốc sạch** (chưa có stamp). Kết quả giống app nhất. | — |

### Bật Firefly
Trong `config.json` → `firefly`:
```json
"enabled": true,
"client_id": "DIEN_CLIENT_ID",
"client_secret": "DIEN_CLIENT_SECRET"
```
và đặt `old_stamp_cleanup.mode` = `"firefly"`.

> `client_id` / `client_secret` lấy trong **Adobe Developer Console** (gói Firefly
> Services / Firefly API doanh nghiệp). Nếu Firefly lỗi hoặc thiếu key, công cụ
> **tự động quay về chế độ `cover`** — không bao giờ đứng giữa chừng.

---

## 5. Ghi chú kỹ thuật

- **Font:** kèm sẵn `DejaVuSans` (hỗ trợ tiếng Việt). Muốn giống app 100% có thể
  thay bằng `Roboto` — đổi file trong `fonts/` và tên trong `src/stamp.py`.
- **Thumbnail bản đồ:** lấy từ OpenStreetMap (cần mạng). Không có mạng → tự bỏ qua,
  không lỗi. Tắt/bật ở `map.enabled` trong file template.
- **Bảo mật/riêng tư:** ảnh trong `data/input`, `data/output` và file Excel
  **không** được commit lên Git (đã cấu hình `.gitignore`).

## 6. Cấu trúc thư mục
```
firefly-stamp-tool/
├── CHAY_WINDOWS.bat / CHAY_MAC.command   ← bấm để chạy
├── config.json                           ← bật/tắt Firefly, cách xử lý stamp cũ
├── templates/  kieu_A.json, kieu_B.json  ← hình dạng stamp (sửa số, không code)
├── fonts/                                ← font tiếng Việt
├── src/                                  ← mã nguồn
└── data/  input/ · output/ · du_lieu.xlsx
```
