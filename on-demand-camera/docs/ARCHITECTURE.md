# Kiến trúc OnCam

## 1. Mô hình tổng thể

```
┌──────────────────────┐   Lệnh / trạng thái (MQTT)   ┌──────────────────────┐
│ ĐIỆN THOẠI CŨ         │◄────────────────────────────►│ ĐIỆN THOẠI XEM        │
│ camera-node           │                              │ viewer                │
│  Foreground Service   │   Video MJPEG (HTTP)         │  UI Compose (MVVM)    │
│  - MQTT client        │─────────────────────────────►│  - MQTT client        │
│  - CameraX            │                              │  - MjpegView          │
│  - NanoHTTPD          │                              │                       │
└──────────┬───────────┘                              └───────────┬──────────┘
           │                 Mạng riêng Tailscale (100.x.y.z)      │
           └───────────────────────┬───────────────────────────────┘
                                   ▼
      Broker MQTT: nhúng trong camera-node (mặc định, D13)
      hoặc broker riêng (Mosquitto / cloud) — chỉ cần đổi cấu hình
```

- **Luôn nghe – chỉ quay khi được gọi:** service chỉ giữ kết nối MQTT. Camera và HTTP server chỉ chạy khi nhận `START_STREAM`. Camera tự tắt khi nhận `STOP_STREAM`, khi hết thời gian chờ không ai xem (mặc định 180s), hoặc khi quá nhiệt.
- **Không mở port router:** mọi kết nối đi trong Tailscale. HTTP server chỉ bind IP Tailscale, **không bao giờ bind `0.0.0.0`**.

## 2. Module

| Module | Loại | Trách nhiệm |
|---|---|---|
| `shared` | Kotlin/JVM thuần | Data class lệnh/trạng thái/sự kiện (`protocol/`), ký/kiểm HMAC (`security/`), wrapper MQTT Paho mqttv3 (`mqtt/`), **client giao thức cho viewer** (`client/CameraClient`, đồng bộ giờ `syncClock`), đọc MJPEG (`client/MjpegReader`), mã QR ghép đôi (`client/PairingCode`), chuẩn hoá địa chỉ broker (`client/BrokerAddress`). Không phụ thuộc Android → unit test chạy trên JVM, có test tích hợp với broker thật (Moquette) |
| `camera-node` | Android app | Foreground Service, broker MQTT nhúng (Moquette), xử lý lệnh, đọc pin/nhiệt, CameraX + MJPEG (NanoHTTPD). Phần lõi `core/`, `mqtt/`, `broker/`, `camera/FrameHub`, `camera/MjpegStreamer` không dùng API Android → test trên JVM |
| `viewer` | Android app | Danh sách camera (lưu mã hoá), kết nối khi app hiển thị, gửi lệnh, hiển thị trạng thái, lưu snapshot vào MediaStore, xem trực tiếp toàn màn hình (`stream/StreamClient`, `stream/MjpegView`, `ui/LiveViewScreen`) |

## 3. Kênh MQTT

| Topic | Hướng | QoS | Retain | Nội dung |
|---|---|---|---|---|
| `camera/{deviceId}/cmd` | viewer → node | 1 | không | Lệnh (JSON) |
| `camera/{deviceId}/status` | node → viewer | **1** (D9) | **có** | Trạng thái; đồng thời là topic LWT (`online:false`, QoS 1) |
| `camera/{deviceId}/event` | node → viewer | 0 | không | Sự kiện (đã mở, đã tắt, idle, quá nhiệt, lỗi, `COMMAND_RESULT`) |

**Phát hiện offline < 30s:** keepalive 15s, broker tuyên bố mất kết nối sau 1,5 × 15 = 22,5s rồi phát LWT `online:false` (retain).
**Kết nối lại < 30s:** lắng nghe `ConnectivityManager.NetworkCallback` + thử lại lùi dần, tối đa 10s/lần.

## 4. Luồng video (Phase 3)

```
START_STREAM ──► StreamManager ──► CameraController (CameraX ImageAnalysis, xoay theo hướng đặt máy)
                     │                    │ YUV_420_888 → NV21 → JPEG (giới hạn FPS theo chất lượng)
                     │                    ▼
                     │                FrameHub (giữ khung mới nhất)
                     │                    │
                     └──► MjpegStreamer (NanoHTTPD, chỉ bind IP Tailscale:8080, header X-OnCam-Auth)
                              ├─ GET /stream.mjpeg  → multipart/x-mixed-replace (tối đa 3 người xem)
                              └─ GET /snapshot.jpg  → khung mới nhất
Vòng giám sát mỗi 1s: số người xem → idle timeout · IP Tailscale · nhiệt độ pin (mỗi 10s)
STOP / idle / quá nhiệt / lỗi camera ──► đóng HTTP server + gỡ CameraX (nhả camera hoàn toàn)
```

| Chất lượng | Độ phân giải | FPS | JPEG |
|---|---|---|---|
| LOW | 640×480 | 10 | 60 |
| MEDIUM (mặc định) | 1280×720 | 15 | 70 |
| HIGH | 1280×720 | 30 | 80 |

## 5. Quyết định đã chốt (ngoài/bổ sung spec gốc)

| # | Quyết định | Lý do |
|---|---|---|
| D1 | Dự án nằm trong thư mục con `on-demand-camera/` của repo | Repo đã có app web khác ở thư mục gốc |
| D2 | Package `vn.cnldg.oncam.{node,viewer,shared}` thay cho `com.example.*` | Tránh đổi về sau (ảnh hưởng toàn bộ code) |
| D3 | Build/xuất APK bằng GitHub Actions | Container phát triển không tải được Android SDK |
| D4 | targetSdk 35 (theo spec), compileSdk 36 | compileSdk chỉ ảnh hưởng biên dịch; thư viện AndroidX mới yêu cầu compileSdk cao. App cài trực tiếp, không lên Play |
| D5 | Lệnh ký **HMAC-SHA256** bằng token chung trên chuỗi `oncam-cmd-v1\|deviceId\|id\|action\|timestamp\|camera\|flash\|quality\|idleTimeoutSec`; từ chối lệch giờ > 60s và `id` trùng | Chống phát lại (replay) và chống sửa tham số; lệnh cho camera A không dùng được cho camera B. Cả 2 máy cần bật giờ tự động |
| D6 | `/stream.mjpeg` và `/snapshot.jpg` yêu cầu token (header) | Tránh thiết bị khác trong cùng mạng Tailscale xem lén |
| D7 | Thêm foreground service type `specialUse` (Android 14+) bên cạnh `camera` | Khi service bị hệ thống kill và tự khởi động lại từ nền, Android cấm type `camera`; service vẫn phải nghe lệnh được |
| D8 | MQTT dùng `org.eclipse.paho.client.mqttv3` (bản lõi Java), **không** dùng `org.eclipse.paho.android.service` | Bản android.service bị crash trên Android 12+ (cờ PendingIntent) |
| D9 | Topic `status` dùng **QoS 1** (spec gốc: QoS 0) | MQTT 3.1.1 cho phép broker bỏ tin retain QoS 0; Moquette xoá luôn (đã kiểm chứng). QoS 1 đảm bảo viewer mở app là thấy trạng thái mới nhất |
| D10 | Không dựa vào cờ `retained` của tin nhận được | Moquette 0.17 không bật cờ này khi gửi tin đã lưu (đã kiểm chứng) |
| D11 | `deviceId` chỉ gồm `a-z 0-9 - _` (≤ 32 ký tự); tên có dấu để ở `displayName` | Bàn phím 2 máy có thể sinh 2 dạng Unicode khác nhau cho cùng chữ "â" → topic không khớp |
| D13 | **Đã duyệt P1**: broker Moquette 0.17 nhúng trong camera-node, chỉ nghe IP Tailscale (giao diện `tun*`, dải 100.64.0.0/10), tắt anonymous, user `oncam` + mật khẩu = HMAC(token) 128 bit | Người dùng chỉ có điện thoại Android. Viewer chỉ cần nhập địa chỉ + mã camera + token. Đã loại các thư viện phụ của Moquette (bugsnag, metrics, HikariCP, epoll, log4j). **Tắt telemetry** (`telemetry_enabled=false`): Moquette mặc định gửi OS, phiên bản, UUID và IP công cộng của mạng nhà về telemetry.moquette.io — phát hiện qua log chặn của proxy, có test khoá lại |
| D14 | Foreground service khai báo `camera\|specialUse`; chỉ xin loại `camera` khi được gọi từ màn hình đang hiển thị | Android 11+ không cấp quyền camera cho service khởi động từ nền; Android 14+ ném lỗi nếu xin loại `camera` từ nền. Mở app → service tự nâng lên loại `camera` |
| D15 | Giữ partial WakeLock suốt khi node chạy | Ping MQTT (Paho dùng Timer) bị trễ khi CPU ngủ → broker tưởng mất kết nối. Máy camera luôn cắm sạc |
| D16 | BootReceiver tự chạy lại node sau khởi động máy/cập nhật app nếu người dùng chưa bấm Dừng | Tiêu chí tự khôi phục (spec 2.1.5). ColorOS cần bật "Cho phép tự khởi chạy" |
| D17 | Video: CameraX `ImageAnalysis` (không cần màn hình xem trước) → YUV → JPEG một lần mỗi khung → `FrameHub` → NanoHTTPD phát cho tối đa 3 người xem | Nhiều người xem không làm tăng tải mã hoá; người xem chậm tự bỏ khung cũ |
| D18 | Tự tắt khi 0 người xem liên tục `idleTimeoutSec` (mặc định 180s); nhiệt độ pin ≥ 42°C → hạ 1 mức chất lượng (tối đa 1 lần/phút), ≥ 45°C → tắt camera; IP Tailscale đổi → tắt | Spec mục 4.2. Ngưỡng là nhiệt độ PIN (BatteryManager), sẽ chỉnh theo đo thực tế trên Reno 5 |
| D19 | Thêm cấu hình **Hướng đặt máy** (đứng / ngang trái / ngang phải / ngược) để xoay ảnh trước khi mã hoá | Camera đặt cố định có thể nằm ngang; ảnh và snapshot luôn đứng đúng chiều |
| D20 | Nút **Bật camera thử** trên máy camera gửi lệnh START_STREAM đã ký qua broker (đi đúng đường của app Xem) và có xem thử ngay trên máy | Test được Phase 3 khi chưa có app Xem |
| D21 | `CameraClient` (phía viewer của giao thức) đặt trong module `shared` | Test đầu-cuối được viewer ↔ camera-node thật trên JVM (5 kịch bản theo tiêu chí nghiệm thu) |
| D22 | App Xem chỉ kết nối MQTT khi đang hiển thị; rời app → tắt camera do chính app bật (cài đặt, mặc định bật) rồi ngắt; keepalive 10 s | Spec mục 7.3; tiết kiệm pin máy Xem; phát hiện offline ≤ ~20 s |
| D23 | Khoá ký dùng chung commit trong `keystore/` (mật khẩu mặc định công khai "android") | APK từ các lần CI khác nhau cài đè được, không mất cấu hình. Không phải bí mật. Từ Phase 6 dùng cho cả bản release (D30) |
| D24 | App Xem bật `cleartextTrafficPermitted` (network_security_config) | Android không cho khai báo theo dải IP 100.64.0.0/10; đường truyền đã được Tailscale mã hoá và request video/ảnh bắt buộc header HMAC |
| D25 | Mỗi phần MJPEG có header `X-OnCam-Timestamp` (giờ chụp) | App Xem hiện **độ trễ thực tế** để kiểm tiêu chí < 2 s (2 máy cần bật giờ tự động) |
| D26 | Bộ đọc MJPEG (`client/MjpegReader`) đặt trong `shared` | Test đầu-cuối được với MJPEG server thật của camera-node: đo FPS, độ trễ, số người xem |
| D27 | App Xem giải mã RGB_565 và tái sử dụng 3 bitmap luân phiên | 720p: ~1,8 MB/khung thay vì tạo mới 3,7 MB/khung (~55 MB rác/giây ở 15 hình/giây) → máy xem đỡ giật |
| D28 | Chưa có âm thanh (spec cho phép để phase sau) | MJPEG không mang âm thanh; làm cùng WebRTC (Phase 7) |
| D12 | Thêm sự kiện `COMMAND_RESULT` (kèm `commandId`, `success`) và các trường trạng thái `displayName`, `flashOn`, `quality`, `charging`, `network`, `protocolVersion` | Cần để trả lời PING, báo lỗi lệnh, hiển thị đúng nút flash/chất lượng và tình trạng mạng (mục 2.1.4 spec). Đều có giá trị mặc định nên tương thích ngược |
| D29 | **Mã QR ghép đôi** `oncam://pair?v=1&d=<mã>&b=<broker>&t=<token>&n=<tên>[&u=&p=]` (`client/PairingCode`). Máy camera vẽ QR bằng zxing core 3.5.3; app Xem quét bằng zxing-android-embedded 4.3.0 | Không phải gõ/gửi token 43 ký tự qua tin nhắn. QR chứa token nên chỉ hiện khi bấm, tự ẩn sau 120 s và ẩn ngay khi app bị ẩn. Broker nhúng không đưa mật khẩu broker vào QR (suy từ token); broker riêng thì kèm. zxing-android-embedded chạy offline, không cần Google Play Services; tự xin quyền CAMERA lúc quét (app Xem khai báo `camera` `required=false`) |
| D30 | **Bản release ký bằng khoá dùng chung** (phương án B, chủ dự án chọn) | Cài đè lên bản debug đã cài, không mất cấu hình; không phải giữ khoá bí mật. Đánh đổi: ai có repo cũng ký được APK cùng chữ ký → chỉ cài từ Release `oncam-latest`; CI kiểm vân tay chứng chỉ trước khi đăng |
| D31 | **Chưa bật R8** (thu gọn/làm rối mã) cho release | Moquette/Netty nạp lớp bằng reflection; thu gọn sai chỉ lỗi lúc chạy trên máy thật, test JVM không phát hiện. APK vì thế không nhỏ hơn bản debug. Bật khi có bộ test chạy trên chính APK release |
| D32 | **Đồng bộ giờ qua PING**: mọi `COMMAND_RESULT` kèm `timestampMs` (giờ máy camera); `CameraClient.syncClock()` gửi 3 PING, lấy lần khứ hồi nhanh nhất, lệch = giờ camera − (t gửi + t nhận)/2 (cách của NTP). Độ trễ = giờ nhận − giờ chụp + lệch; âm ≤ 300 ms coi là 0 | Phase 5 thực tế: 2 máy lệch giờ vài trăm ms → độ trễ ra số âm và bị ẩn. Đo được cả khi lệch > 60 s (PING bị từ chối EXPIRED vẫn kèm giờ) → app Xem báo người dùng bật giờ tự động. Trường mới có giá trị mặc định → tương thích ngược |
| D33 | Ô địa chỉ broker chỉ cần IP (`client/BrokerAddress`): tự thêm `tcp://` và `:1883`; kiểm IPv4 đủ 4 số 0–255 hoặc tên miền, cổng 1–65535 | Thực tế người dùng gõ nhầm `1883` vào ô địa chỉ |
| D34 | Hướng dẫn chống tắt app chọn theo `Build.MANUFACTURER`: Samsung (One UI), Oppo/Realme/OnePlus (ColorOS), hãng khác | Máy camera đổi từ Oppo sang Samsung A54; tên mục cài đặt mỗi hãng khác nhau |
| D35 | CI đăng **GitHub Release `oncam-latest`** (xoá và tạo lại mỗi lần push, chỉ khi build + test emulator đạt): `OnCam-Quay.apk`, `OnCam-Xem.apk`, `SHA256SUMS.txt`. Trước khi đăng: kiểm chữ ký, không debuggable, đúng mã ứng dụng/phiên bản (`ci/check-apk.sh`), mở thử 2 APK release trên emulator (`ci/smoke-release.sh`) | Link tải cố định, tải thẳng `.apk` trên điện thoại (artifact của Actions là file zip, cần đăng nhập GitHub) |

## 6. Ràng buộc Android cần nhớ

- **Quyền camera là quyền "khi đang dùng" (while-in-use).** Service type `camera` phải được khởi động lúc app đang hiển thị (người dùng mở app). Nếu service bị kill rồi tự khởi động lại từ nền (`START_STICKY`, boot), service chỉ nghe lệnh được; mở camera sẽ bị chặn → trạng thái gửi `cameraReady: false`, màn hình máy camera hiện *Mở camera từ xa: Chưa*. Cách xử lý (D14): mở app trên máy camera một lần. Không có cách hợp lệ nào vượt qua giới hạn này.
- Android 15 chặn khởi động foreground service type `camera` từ `BOOT_COMPLETED`.
- Nhiệt độ đọc được từ `BatteryManager` là **nhiệt độ pin** (đơn vị 0,1°C), không phải CPU. Từ Android 10 có thêm `PowerManager.getCurrentThermalStatus()`.
- Từ Android 9, `HttpURLConnection`/OkHttp **chặn HTTP không mã hoá (cleartext)** mặc định (đã gặp trong test emulator). App **viewer** tải `http://100.x.y.z:8080` nên phải có `network_security_config` cho phép cleartext — chấp nhận được vì đường truyền đã được Tailscale (WireGuard) mã hoá. App camera-node chỉ làm server nên không cần.
- Camera trước thường không có đèn flash → `SET_FLASH` khi đang dùng camera trước trả lỗi.
- Máy Xiaomi/Oppo/Samsung có cơ chế diệt app riêng → cần tắt tối ưu pin + cho phép tự khởi chạy (màn hình hướng dẫn trong app, theo hãng — D34).
- `URLEncoder.encode(String, Charset)` chỉ có từ Android 13 (API 33) → mã dùng bản nhận tên bảng mã `"UTF-8"` để chạy được từ Android 8.

## 7. Đề xuất chờ duyệt

(Không có. P1 đã duyệt → D13.)
