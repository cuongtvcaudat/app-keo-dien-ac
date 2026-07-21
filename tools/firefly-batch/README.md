# Tool RPA tự động hoá Adobe Firefly (Batch Processing)

Tự động tạo ảnh nghiệm thu trạm **hàng loạt** qua giao diện **Adobe Firefly**
(bản Web hoặc Firefly Workspace). Tool đọc danh sách trạm từ Excel, tự động
upload ảnh mẫu, dán prompt đã ghép dữ liệu, bấm **Generate**, chờ render rồi
bấm **Download** và dọn ảnh về đúng thư mục `Output/<MaTram>`.

> ⚙️ Đây là công cụ **điều khiển giao diện (RPA)** dựa trên toạ độ chuột /
> nhận diện hình ảnh — nó "nhìn và bấm" y như người dùng. Vì vậy nó phụ thuộc
> vào bố cục màn hình: bạn cần **calibrate toạ độ** trên chính máy của mình.

---

## 1. Cài đặt

```bash
cd tools/firefly-batch
pip install -r requirements.txt
```

Yêu cầu: **Python 3.9+**. Chạy trên máy có **màn hình thật** (không headless),
vì tool điều khiển chuột/bàn phím.

- **Windows:** dùng được ngay.
- **macOS:** cấp quyền *Accessibility* và *Screen Recording* cho Terminal
  (System Settings → Privacy & Security).
- **Linux:** cần môi trường đồ hoạ X11 (Wayland có thể chặn pyautogui).

---

## 2. Chuẩn bị dữ liệu & ảnh mẫu

### a) File Excel `data.xlsx`
Bắt buộc đủ **4 cột** (đúng tên):

| MaTram      | ThoiGian                | ToaDo               | NoiDung                          |
|-------------|-------------------------|---------------------|----------------------------------|
| LDG0100-11  | 08:30 ngày 21/07/2026   | 11.9404, 108.4583   | Nghiệm thu kéo dây AC tuyến 1 pha |

Tạo nhanh file mẫu:
```bash
python firefly_batch.py make-sample
```

### b) Ảnh mẫu (template)
Đặt ảnh nền chuẩn (ảnh sẽ upload lên Firefly để thay chữ) và trỏ đường dẫn
trong `config.json` → `template_image` (mặc định `templates/mau_nghiem_thu.png`).

---

## 3. Tạo & chỉnh cấu hình

```bash
python firefly_batch.py init-config     # tạo config.json
```

Mở `config.json` và chỉnh các mục chính:

| Khoá                  | Ý nghĩa                                                        |
|-----------------------|---------------------------------------------------------------|
| `excel_file`          | Đường dẫn file Excel (mặc định `data.xlsx`)                    |
| `template_image`      | Ảnh mẫu upload lên Firefly                                     |
| `output_folder`       | Thư mục lưu kết quả (`Output/<MaTram>/result_1.jpg`)          |
| `downloads_folder`    | Để trống = tự dò thư mục Downloads của HĐH                     |
| `prompt_template`     | Mẫu prompt, chứa `[MaTram] [ThoiGian] [ToaDo] [NoiDung]`       |
| `images_per_generate` | Số ảnh Firefly trả ra mỗi lần (đặt tên result_1..N)           |
| `wait_after_generate` | Giây chờ Firefly render (15–30s tuỳ mạng)                    |
| `coords`              | Toạ độ pixel các nút (xem mục 4)                              |

Prompt mặc định:
> *Perform a precise text substitution in the top-right red text block of the
> image. Replace with [ThoiGian], [ToaDo], and [MaTram] [NoiDung]. Keep all
> other background elements unchanged.*

---

## 4. Lấy toạ độ các nút (calibrate) — QUAN TRỌNG

1. Mở Firefly trên trình duyệt, phóng to cửa sổ về đúng bố cục sẽ dùng khi chạy.
2. Chạy:
   ```bash
   python firefly_batch.py calibrate
   ```
3. Rê chuột lần lượt lên từng nút, đọc toạ độ `(x, y)` hiển thị và ghi vào
   `config.json` → `coords`:

   ```json
   "coords": {
     "upload_button":    [420, 640],
     "prompt_box":       [760, 900],
     "generate_button":  [1180, 950],
     "download_button":  [1240, 210],
     "confirm_download": null
   }
   ```
   - `upload_button`: vùng/nút tải ảnh lên.
   - `prompt_box`: ô nhập prompt.
   - `generate_button`: nút **Generate** (xanh dương, góc phải dưới).
   - `download_button`: nút **Download** (phía trên ảnh kết quả).
   - `confirm_download`: chỉ điền nếu có hộp thoại xác nhận tải; không thì để `null`.

4. Nhấn **Ctrl+C** để thoát chế độ calibrate.

> 💡 **Mẹo ổn định:** luôn mở Firefly ở cùng một kích thước/độ phân giải màn
> hình. Nếu đổi màn hình hoặc zoom trình duyệt, phải calibrate lại.

### (Tuỳ chọn) Nhận diện nút bằng hình ảnh thay vì toạ độ cố định
Bền hơn khi bố cục xê dịch nhẹ. Trong `config.json`:
```json
"use_image_recognition": true,
"button_images": {
  "upload_button":   "buttons/upload.png",
  "generate_button": "buttons/generate.png",
  "download_button": "buttons/download.png"
}
```
Cắt ảnh từng nút (ảnh nhỏ, rõ) lưu vào `buttons/`. Cần `opencv-python` (đã có
trong requirements). Không tìm thấy ảnh → tool tự rơi về toạ độ cố định.

---

## 5. Chạy

Chạy thử trước (in ra các bước, **không** click chuột, kiểm tra prompt ghép đúng):
```bash
python firefly_batch.py run --dry-run
```

Chạy thật:
```bash
python firefly_batch.py run
```
Có 5 giây đếm ngược để bạn click sang cửa sổ Firefly. Trong lúc chạy, **đừng
đụng chuột**. Muốn **DỪNG KHẨN CẤP**: kéo nhanh chuột lên **góc trên-trái**
màn hình (cơ chế fail-safe của pyautogui).

Kết quả:
```
Output/
├── LDG0100-11/
│   ├── result_1.jpg
│   └── result_2.jpg
└── LDG_TA_NUNG/
    └── result_1.jpg
```

---

## 6. Quy trình mỗi trạm (workflow)

1. Đọc dòng Excel hiện tại.
2. Tạo thư mục `Output/<MaTram>`.
3. Upload ảnh mẫu (click nút Upload → gõ đường dẫn vào hộp thoại HĐH).
4. Click ô prompt → dán prompt đã ghép dữ liệu (qua clipboard, an toàn tiếng Việt).
5. Click **Generate**.
6. Chờ `wait_after_generate` giây.
7. Click **Download**.
8. Phát hiện file mới tải xong trong Downloads → đổi tên `result_N` → chuyển vào
   thư mục trạm → sang dòng tiếp theo.

---

## 7. Xử lý sự cố

| Hiện tượng | Cách khắc phục |
|-----------|----------------|
| Click sai chỗ | Calibrate lại; giữ nguyên kích thước cửa sổ & độ phân giải |
| Prompt không dán được | Kiểm tra `pyperclip` đã cài; thử tăng `PAUSE` |
| Không thấy file tải về | Kiểm tra `downloads_folder`; tăng `wait_download` |
| Firefly render lâu | Tăng `wait_after_generate` (ví dụ 35–40s) |
| Hộp thoại chọn file ở macOS không nhận path | Tool tự dùng `Cmd+Shift+G`; đảm bảo đã cấp quyền Accessibility |
| Đặt sai số ảnh | Chỉnh `images_per_generate` cho khớp số ảnh Firefly trả ra |

> ⚠️ **Lưu ý về điều khoản dịch vụ:** hãy đảm bảo việc tự động hoá tuân thủ
> điều khoản sử dụng của Adobe Firefly và bạn có quyền hợp lệ với dữ liệu/ảnh
> đầu vào. Đặt thời gian chờ hợp lý để không gây tải bất thường lên dịch vụ.
