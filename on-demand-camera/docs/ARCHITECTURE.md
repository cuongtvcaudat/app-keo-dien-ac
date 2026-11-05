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
                     Máy chạy 24/7: Mosquitto broker
```

- **Luôn nghe – chỉ quay khi được gọi:** service chỉ giữ kết nối MQTT. Camera và HTTP server chỉ chạy khi nhận `START_STREAM`. Camera tự tắt khi nhận `STOP_STREAM`, khi hết thời gian chờ không ai xem (mặc định 180s), hoặc khi quá nhiệt.
- **Không mở port router:** mọi kết nối đi trong Tailscale. HTTP server chỉ bind IP Tailscale, **không bao giờ bind `0.0.0.0`**.

## 2. Module

| Module | Loại | Trách nhiệm |
|---|---|---|
| `shared` | Kotlin/JVM thuần | Data class lệnh/trạng thái/sự kiện (`protocol/`), ký/kiểm HMAC (`security/`), wrapper MQTT Paho mqttv3 (`mqtt/`). Không phụ thuộc Android → unit test chạy trên JVM, có test tích hợp với broker thật (Moquette) |
| `camera-node` | Android app | Foreground Service, CameraX, MJPEG server, xử lý lệnh, đọc pin/nhiệt |
| `viewer` | Android app | Gửi lệnh, hiển thị trạng thái, xem live, lưu snapshot vào MediaStore |

## 3. Kênh MQTT

| Topic | Hướng | QoS | Retain | Nội dung |
|---|---|---|---|---|
| `camera/{deviceId}/cmd` | viewer → node | 1 | không | Lệnh (JSON) |
| `camera/{deviceId}/status` | node → viewer | **1** (D9) | **có** | Trạng thái; đồng thời là topic LWT (`online:false`, QoS 1) |
| `camera/{deviceId}/event` | node → viewer | 0 | không | Sự kiện (đã mở, đã tắt, idle, quá nhiệt, lỗi, `COMMAND_RESULT`) |

**Phát hiện offline < 30s:** keepalive 15s, broker tuyên bố mất kết nối sau 1,5 × 15 = 22,5s rồi phát LWT `online:false` (retain).
**Kết nối lại < 30s:** lắng nghe `ConnectivityManager.NetworkCallback` + thử lại lùi dần, tối đa 10s/lần.

## 4. Quyết định đã chốt (ngoài/bổ sung spec gốc)

| # | Quyết định | Lý do |
|---|---|---|
| D1 | Dự án nằm trong thư mục con `on-demand-camera/` của repo | Repo đã có app web khác ở thư mục gốc |
| D2 | Package `vn.cnldg.oncam.{node,viewer,shared}` thay cho `com.example.*` | Tránh đổi về sau (ảnh hưởng toàn bộ code) |
| D3 | Build/xuất APK bằng GitHub Actions | Container phát triển không tải được Android SDK |
| D4 | targetSdk 35 (theo spec), compileSdk 36 | compileSdk chỉ ảnh hưởng biên dịch; thư viện AndroidX mới yêu cầu compileSdk cao. App cài trực tiếp, không lên Play |
| D5 | Lệnh ký **HMAC-SHA256** bằng token chung trên chuỗi `oncam-cmd-v1|deviceId|id|action|timestamp|camera|flash|quality|idleTimeoutSec`; từ chối lệch giờ > 60s và `id` trùng | Chống phát lại (replay) và chống sửa tham số; lệnh cho camera A không dùng được cho camera B. Cả 2 máy cần bật giờ tự động |
| D6 | `/stream.mjpeg` và `/snapshot.jpg` yêu cầu token (header) | Tránh thiết bị khác trong cùng mạng Tailscale xem lén |
| D7 | Thêm foreground service type `specialUse` (Android 14+) bên cạnh `camera` | Khi service bị hệ thống kill và tự khởi động lại từ nền, Android cấm type `camera`; service vẫn phải nghe lệnh được |
| D8 | MQTT dùng `org.eclipse.paho.client.mqttv3` (bản lõi Java), **không** dùng `org.eclipse.paho.android.service` | Bản android.service bị crash trên Android 12+ (cờ PendingIntent) |
| D9 | Topic `status` dùng **QoS 1** (spec gốc: QoS 0) | MQTT 3.1.1 cho phép broker bỏ tin retain QoS 0; Moquette xoá luôn (đã kiểm chứng). QoS 1 đảm bảo viewer mở app là thấy trạng thái mới nhất |
| D10 | Không dựa vào cờ `retained` của tin nhận được | Moquette 0.17 không bật cờ này khi gửi tin đã lưu (đã kiểm chứng) |
| D11 | `deviceId` chỉ gồm `a-z 0-9 - _` (≤ 32 ký tự); tên có dấu để ở `displayName` | Bàn phím 2 máy có thể sinh 2 dạng Unicode khác nhau cho cùng chữ "â" → topic không khớp |
| D12 | Thêm sự kiện `COMMAND_RESULT` (kèm `commandId`, `success`) và các trường trạng thái `displayName`, `flashOn`, `quality`, `charging`, `network`, `protocolVersion` | Cần để trả lời PING, báo lỗi lệnh, hiển thị đúng nút flash/chất lượng và tình trạng mạng (mục 2.1.4 spec). Đều có giá trị mặc định nên tương thích ngược |

## 5. Ràng buộc Android cần nhớ

- **Quyền camera là quyền "khi đang dùng" (while-in-use).** Service type `camera` phải được khởi động lúc app đang hiển thị (người dùng mở app). Nếu service bị kill rồi tự khởi động lại từ nền (`START_STICKY`, boot), service chỉ nghe lệnh được; mở camera sẽ bị chặn → node báo `cameraState: ERROR` + thông điệp "cần mở app trên máy camera". Phương án khắc phục sẽ chốt ở Phase 3.
- Android 15 chặn khởi động foreground service type `camera` từ `BOOT_COMPLETED`.
- Nhiệt độ đọc được từ `BatteryManager` là **nhiệt độ pin** (đơn vị 0,1°C), không phải CPU. Từ Android 10 có thêm `PowerManager.getCurrentThermalStatus()`.
- Camera trước thường không có đèn flash → `SET_FLASH` khi đang dùng camera trước trả lỗi.
- Máy Xiaomi/Oppo/Samsung có cơ chế diệt app riêng → cần tắt tối ưu pin + cho phép tự khởi chạy (màn hình hướng dẫn trong app).

## 6. Đề xuất chờ duyệt

| # | Đề xuất | Lý do |
|---|---|---|
| P1 | **Nhúng MQTT broker (Moquette) vào app camera-node** thay cho Mosquitto trên máy riêng | Người dùng chỉ có điện thoại Android, không có PC/Raspberry Pi. Giao thức không đổi; sau này chuyển sang broker riêng chỉ cần đổi địa chỉ |
