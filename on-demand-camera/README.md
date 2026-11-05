# OnCam — Camera giám sát theo yêu cầu (On-Demand Android Security Camera)

Biến điện thoại Android cũ thành camera đặt ở sân nhà. Máy cũ **luôn nghe lệnh** nhưng **chỉ mở camera khi được gọi** từ điện thoại xem, xem xong tự tắt để giảm nóng, hao pin và băng thông.

| App | Cài trên | Package |
|---|---|---|
| `camera-node` — **OnCam Quay** | Điện thoại cũ đặt ở sân | `vn.cnldg.oncam.node` |
| `viewer` — **OnCam Xem** | Điện thoại chính | `vn.cnldg.oncam.viewer` |
| `shared` | (thư viện dùng chung) | `vn.cnldg.oncam.shared` |

Kiến trúc chi tiết: [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

## Tiến độ

| Phase | Nội dung | Trạng thái |
|---|---|---|
| 0 | Khung dự án 3 module, CI build | ✅ |
| 1 | `shared`: Commands/Status/Topics, ký HMAC, wrapper MQTT, 38 test (gồm test với broker thật) | ✅ |
| 2 | camera-node: Foreground Service, broker MQTT nhúng, trả lời `PING`, tự kiểm tra, chống tắt app | ✅ |
| 3 | camera-node: CameraX + MJPEG (NanoHTTPD), idle timeout | ⏳ |
| 4 | viewer: MQTT + UI điều khiển | ⏳ |
| 5 | viewer: xem live MJPEG | ⏳ |
| 6 | Đóng gói APK + tài liệu hướng dẫn | ⏳ |
| 7 | (tùy chọn) WebRTC | ⏳ |

## Cấu trúc

```
on-demand-camera/
├── docs/            # Kiến trúc + hướng dẫn cài đặt
├── shared/          # Kotlin/JVM thuần: giao thức + MQTT client
├── camera-node/     # App Android cho máy quay
└── viewer/          # App Android cho máy xem
```

## Build

### Cách 1 — GitHub Actions (đang dùng)

Mỗi lần push thay đổi trong `on-demand-camera/`, workflow [`OnCam Android build`](../.github/workflows/oncam-android.yml) chạy 2 job song song:

- **build**: `./gradlew build` (biên dịch, unit test, lint) và xuất APK.
- **emulator**: chạy test của camera-node trên emulator Android 13 (API 33) — kiểm chứng broker nhúng chạy được trên Android.

Tải APK về điện thoại:
1. Vào repo trên GitHub → tab **Actions** → chọn lần chạy mới nhất của **OnCam Android build** (dấu ✅ xanh).
2. Kéo xuống mục **Artifacts** → tải `oncam-debug-apk` (file .zip).
3. Giải nén: `camera-node-debug.apk` cài lên máy cũ, `viewer-debug.apk` cài lên máy chính.
4. Lần đầu cài, Android sẽ hỏi cho phép "Cài ứng dụng không rõ nguồn gốc" → cho phép với trình duyệt / trình quản lý file đang dùng.

### Cách 2 — Máy tính cá nhân

Yêu cầu: JDK 17+, Android SDK (cài qua Android Studio), platform API 36.

```bash
cd on-demand-camera
./gradlew build                 # biên dịch + test + lint
./gradlew :shared:test          # chỉ chạy test module shared (~30 giây)
./gradlew :camera-node:installDebug   # cài thẳng qua USB (bật USB debugging)
./gradlew :viewer:installDebug
```

## Phiên bản chính

| Thành phần | Phiên bản |
|---|---|
| minSdk / targetSdk / compileSdk | 26 / 35 / 36 |
| Gradle / AGP | 8.14.3 / 8.12.0 |
| Kotlin / KSP | 2.2.10 / 2.2.10-2.0.2 |
| Hilt | 2.57.1 |
| Compose BOM | 2025.08.00 |
| Paho MQTT (mqttv3) | 1.2.5 |
| kotlinx-serialization-json / coroutines | 1.9.0 / 1.10.2 |
| Moquette (broker nhúng trong camera-node) | 0.17 |
| security-crypto (EncryptedSharedPreferences) | 1.0.0 |

Toàn bộ phiên bản khai báo tập trung ở [`gradle/libs.versions.toml`](gradle/libs.versions.toml).

## Bảo mật

- Không commit secret: `local.properties`, `secrets.properties`, `*.jks` đã nằm trong `.gitignore`.
- Secret (mật khẩu MQTT, token chung) nhập trong màn hình cấu hình của app và lưu bằng EncryptedSharedPreferences (Phase 2/4).
