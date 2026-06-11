# KẾ HOẠCH THỰC HIỆN — App Kéo Điện AC
> Cập nhật: 2026-06-11

---

## 1. TỔNG QUAN DỰ ÁN

**Mục tiêu:** App PWA cho đội thi công kéo điện AC cấp nguồn trạm BTS.

**Tuyến điển hình:**
```
Công tơ điện lực → ~10 cột (VNPT/Viettel/Điện lực có sẵn + trồng mới) → Trạm BTS
```

**Nguyên tắc vàng:** Nhập 1 lần tại hiện trường — khối lượng và bản vẽ tự sinh từ dữ liệu đã chấm.

---

## 2. STACK & KIẾN TRÚC

### Frontend
- **PWA 1 file** `index.html` (HTML + CSS + JS thuần, không framework)
- Leaflet CDN (bản đồ), jsPDF CDN (xuất PDF)
- Offline-first: Service Worker + IndexedDB
- Tối ưu Android Chrome, Add to Home Screen

### Backend
- **Google Apps Script (GAS) + Google Sheets**
  - `doGet?type=vattu` → JSON danh mục vật tư
  - `doGet?type=congtrinh` → JSON danh sách công trình
  - `doPost` → append log vào sheet `DuLieu_HienTruong` (không ghi đè)
  - Miễn phí vĩnh viễn, data xem được trong Sheets

### Hosting
- **GitHub Pages** (HTTPS miễn phí, auto-deploy khi push)

### Mock server (test local)
- `mock-server.js` — Node.js thuần, mô phỏng đúng GAS API

---

## 3. CẤU TRÚC FILE

```
app-keo-dien-ac/
├── index.html              ← toàn bộ app (HTML + CSS + JS)
├── manifest.json           ← PWA installable
├── sw.js                   ← Service Worker (offline + cache tile map)
├── Code.gs                 ← Google Apps Script backend
├── mock-server.js          ← Mock server Node.js (test local)
├── data/template/
│   ├── DanhMuc_VatTu.csv   ← 12 vật tư chuẩn
│   └── KeHoach_KPI.csv     ← 3 công trình mẫu
├── KEHOACH_THUCHIEN.md     ← file này
├── HUONGDAN.md             ← hướng dẫn deploy cho người không biết code
├── BAOCAO_TEST.md          ← log lỗi + cách fix
└── QUYETDINH.md            ← quyết định kỹ thuật + việc vòng 2
```

---

## 4. DỮ LIỆU CHUẨN

### Sheet Google (3 sheet chính)
| Sheet | Mô tả |
|-------|-------|
| `DanhMuc_VatTu` | ma_vt, ten_vt, don_vi, nhom, mac_dinh, sl_mac_dinh |
| `KeHoach_KPI` | ma_tram, ma_hop_dong, ten_cong_trinh, trang_thai, ghi_chu |
| `DuLieu_HienTruong` | Log append-only: mã trạm, loại điểm, STT, lat, lng, vật tư JSON... |

### 12 vật tư chuẩn (DanhMuc_VatTu)
| Mã | Tên | ĐVT | mac_dinh |
|----|-----|-----|---------|
| DAY_MULLER_2x16 | Dây Muller 2x16 | m | |
| DAY_MULLER_4x16 | Dây Muller 4x16 | m | |
| KEP_NGUNG | Kẹp ngừng | cái | |
| GHI_NHOM | Ghíp nhôm | cái | x (sl=2) |
| SU_ONG_CHI | Sứ ống chỉ | cái | |
| XA_SAT | Xà sắt | cái | |
| NEO_TANG_DO | Dây néo + tăng đơ | bộ | |
| MONG_NEO | Móng néo | cái | |
| COC_TIEP_DIA | Cọc tiếp địa V63 | cái | |
| DAY_TIEP_DIA | Dây tiếp địa | m | |
| BANG_KEO | Băng keo | cuộn | x (sl=1) |
| ONG_NHUA | Ống nhựa luồn cáp | m | |
| APTMT_HOP | Aptomat + hộp công tơ | bộ | |

### Cấu trúc 1 điểm (IndexedDB)
```json
{
  "id": "uuid-v4",
  "loai_diem": "congto | cot | tram",
  "chu_so_huu": "congto | vnpt | viettel | dien_luc | trong_moi | tram",
  "stt": "CT | 1 | 2 | 5a | TRAM",
  "lat": 11.94,
  "lng": 108.40,
  "sai_so_gps": 8,
  "cham_bu": false,
  "vat_tu": [{"ma_vt": "GHI_NHOM", "sl": 2}],
  "chieu_dai_thu_cong": null,
  "ghi_chu": "",
  "thoi_gian": "2026-06-11T08:30:00"
}
```

### Cấu trúc tuyến (IndexedDB store `tuyen_dien`)
```json
{
  "ma_tram": "LDG_TEST01",
  "ma_hop_dong": "HD-2026-001",
  "ten_cong_trinh": "Kéo AC trạm Tà Nung",
  "ten_doi": "Đội 1 - Đức Trọng",
  "loai_tuyen": "1pha",
  "loai_day": "Muller 2x16",
  "he_so_chung": 1.03,
  "trang_thai": "khao_sat | da_thi_cong",
  "diem": [ ...mảng điểm theo thứ tự tuyến... ],
  "thoi_gian_tao": "...",
  "thoi_gian_cap_nhat": "..."
}
```

---

## 5. GIAO DIỆN — 3 MÀN HÌNH

### Screen 1: Cài đặt
- Ô nhập URL endpoint GAS (lưu localStorage)
- Ô nhập tên/mã đội (lưu localStorage, gắn vào mọi bản ghi)

### Screen 2: Chọn công trình
- Tìm kiếm theo mã trạm / mã HĐ / tên
- Hiện đủ 3 thông tin xác nhận sau khi chọn
- Chọn loại tuyến (1 pha / 3 pha) + loại dây

### Screen 3: Main (Map + Panel)
```
┌────────────────────────┐
│       MAP              │  ← ~65% màn hình
│  [marker + line]       │
│                    📍  │  ← FAB định vị (≥56px)
├────────────────────────┤
│   ▲ PANEL TRƯỢT ▲▼    │
│  ➕ Thêm tại GPS       │  ← màu sai số: xanh/vàng/đỏ
│  [Tuyến | KL | Sync]  │
└────────────────────────┘
```

---

## 6. LOGIC NGHIỆP VỤ

### 6 loại điểm & màu marker
| Loại | Màu | STT |
|------|-----|-----|
| Công tơ | 🟧 Cam | CT |
| VNPT | 🟨 Vàng | 1, 2, 3... |
| Viettel | 🟩 Xanh lá | 1, 2, 3... |
| Điện lực | 🟥 Đỏ | 1, 2, 3... |
| Trồng mới | 🟦 Xanh dương | 1, 2, 3... |
| Trạm BTS | 🟪 Tím | TRAM |

### 2 cách thêm điểm
- **Cách A:** Bấm nút **➕ "Thêm tại GPS"** (đứng tại chân cột)
- **Cách B:** **Bấm giữ 1.5 giây** trên map → rung nhẹ → mở hộp thoại

### Hộp thoại điểm (3 bước)
1. Chọn loại điểm (6 nút to ≥48px)
2. Vật tư (tự tích sẵn mac_dinh, chỉnh số lượng, tìm kiếm)
3. Ghi chú + ảnh (tùy chọn) → Lưu

### Logic STT & thứ tự tuyến
- Thứ tự: `CT → C1 → C2 → ... → TRAM`
- Chèn giữa C4–C5 → `C4a, C4b` (không đánh lại số cũ)
- Chấm bù (xa GPS >100m) → `cham_bu=true`, marker **viền đứt**
- Kéo-thả marker: chỉ đổi tọa độ, không đổi thứ tự

### Tính chiều dài & mét dây
```
mét_dây = Σ(Haversine mỗi đoạn) × hệ_số_chùng × số_sợi
         (ưu tiên chiều dài nhập tay nếu có)
số_sợi: 1pha = 2, 3pha = 4
```

### Chỉ báo GPS (màu)
- 🟢 Xanh: sai số ≤10m (chấm tốt)
- 🟡 Vàng: 10–20m (chấm được)
- 🔴 Đỏ: >20m (cảnh báo, vẫn cho lưu)

---

## 7. OFFLINE STRATEGY

### Cái gì offline được
| Chức năng | Offline |
|-----------|---------|
| GPS định vị | ✅ Phần cứng máy |
| Chấm điểm / vật tư | ✅ IndexedDB |
| Tính KL, bản vẽ | ✅ Local |
| Xuất PDF | ✅ jsPDF local |
| App shell | ✅ Sau lần đầu |
| Tile bản đồ | ⚠️ Cần pre-cache |
| Đồng bộ | ❌ Cần mạng |

### Giải pháp tile offline
- **Nút "Tải bản đồ khu vực"**: cache tile bán kính 3km quanh tọa độ BTS, zoom 14–18
- **Fallback**: nếu không có tile → nền trắng + GPS dot + line tuyến vẫn vẽ đúng
- **Hướng dẫn:** bấm "Tải bản đồ" khi có WiFi tối trước khi ra hiện trường

---

## 8. LỘ TRÌNH ANDROID

```
GIAI ĐOẠN 1 — Web PWA (làm trước)
├── Deploy GitHub Pages
├── Cài qua Chrome "Thêm vào màn hình chính"
└── Test thực tế trên điện thoại Android

GIAI ĐOẠN 2 — Android APK (sau khi web OK)
├── Dùng Capacitor (giữ nguyên code HTML/JS/CSS)
├── Thêm Capacitor GPS plugin (chính xác hơn)
├── Thêm Capacitor Camera plugin
├── Build APK bằng Android Studio
└── Phân phối: gửi APK qua Zalo/Telegram cho thợ
```

**Yêu cầu giai đoạn 2:** Android Studio + Node.js (không cần Play Store)

### Code từ đầu đã chuẩn bị cho Capacitor
- Chỉ dùng Web API chuẩn: `navigator.geolocation`, `IndexedDB`, `navigator.vibrate()`
- Tách lớp Platform API riêng → đổi 1 chỗ khi convert
- Không dùng `localStorage` cho data lớn

---

## 9. KẾ HOẠCH XÂY DỰNG (3 PHASE)

### PHASE 1 — HIỆN TRƯỜNG

| Bước | Nội dung | File |
|------|----------|------|
| 1a | Scaffold: CSV danh mục, mock-server | `data/`, `mock-server.js` |
| 1b | PWA shell: manifest, sw.js | `manifest.json`, `sw.js` |
| 1c | Screen chọn công trình | `index.html` |
| 1d | Map + GPS: FAB, bám theo, màu sai số | `index.html` |
| 1e | Hộp thoại điểm: 6 loại + vật tư | `index.html` |
| 1f | Logic tuyến: line, chèn, chấm bù | `index.html` |
| 1g | Haversine + bảng khối lượng | `index.html` |
| 1h | Nút "Tải bản đồ khu vực" (offline tile) | `index.html` |
| 1i | PDF bản vẽ thi công (jsPDF sơ đồ) | `index.html` |
| 1j | Sync queue + xuất/nhập JSON | `index.html` |
| 1k | GAS backend | `Code.gs` |

### PHASE 2 — HOÀN CÔNG

| Bước | Nội dung |
|------|----------|
| 2a | Nút "Xác nhận đã thi công" → `trang_thai: da_thi_cong` |
| 2b | Cho sửa vật tư/vị trí → số liệu hoàn công |
| 2c | Xuất PDF bản vẽ hoàn công (tiêu đề + ngày thực tế) |

### PHASE 3 — QUYẾT TOÁN

| Bước | Nội dung |
|------|----------|
| 3a | Bảng so sánh KL: khảo sát vs hoàn công |
| 3b | Bảng vật tư quyết toán tổng hợp |
| 3c | Xuất PDF báo cáo quyết toán |

---

## 10. VIỆC VÒNG 2 (CHƯA LÀM)

- Nhật ký thi công hàng ngày
- Upload ảnh thi công lên Google Drive
- Checklist nghiệm thu
- So sánh dự toán vs thực tế
- Telegram bot báo tiến độ
- Server-side permission check
- Đếm ngược KPI

---

## 11. QUY TẮC CODE

- Commit nhỏ, message tiếng Việt
- Mỗi lỗi ghi `BAOCAO_TEST.md`: lỗi → nguyên nhân → fix → kết quả
- Quyết định kỹ thuật ghi `QUYETDINH.md`
- PDF dùng **jsPDF vẽ sơ đồ** (không chụp Leaflet) → tránh CORS, offline được
- CSS: tránh `position:fixed` gây lỗi khi bàn phím mở trên Android

---

## 12. LUỒNG DEPLOY

```
Local (index.html) → git push → GitHub Pages (auto HTTPS)
                                      ↑
                              PWA trên điện thoại thợ

Anh deploy Code.gs → GAS Web App URL
Dán URL vào Cài đặt trong app
```
