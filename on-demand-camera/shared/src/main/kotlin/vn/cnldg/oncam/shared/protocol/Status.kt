package vn.cnldg.oncam.shared.protocol

import kotlinx.serialization.Serializable
import vn.cnldg.oncam.shared.OnCam

/** Trạng thái camera của camera-node. */
@Serializable
enum class CameraState { OFF, STARTING, STREAMING, ERROR }

/** Loại mạng camera-node đang dùng. */
@Serializable
enum class NetworkType { WIFI, CELLULAR, ETHERNET, OTHER, NONE }

/**
 * Trạng thái camera-node publish lên `camera/{deviceId}/status` (retain).
 * Cũng là nội dung LWT (`online = false`) để viewer biết máy mất kết nối.
 *
 * Các trường ngoài spec gốc (displayName, flashOn, quality, charging, network, viewers, cameraReady, protocolVersion)
 * đều có giá trị mặc định nên bản cũ/mới vẫn đọc được của nhau.
 *
 * @property displayName tên hiển thị (vd. "Sân nhà"); [deviceId] chỉ dùng ký tự ASCII.
 * @property streamUrl địa chỉ MJPEG khi đang STREAMING, null khi tắt.
 * @property temperatureC nhiệt độ PIN (°C) đọc từ BatteryManager, không phải nhiệt độ CPU.
 * @property lastSeen thời điểm cập nhật trạng thái, giây kể từ 1970-01-01 UTC.
 * @property error mã lỗi ([CameraCodes]) khi [cameraState] = ERROR hoặc khi lần bật gần nhất thất bại.
 * @property viewers số người đang xem luồng MJPEG.
 * @property cameraReady false nếu camera-node chưa được phép mở camera từ xa (cần mở app một lần).
 * @property rtc camera hỗ trợ xem qua WebRTC (Phase 7, lệnh RTC_OFFER). Bản cũ không gửi → false → app Xem dùng MJPEG.
 * @property micReady micro máy camera dùng được (đã cấp quyền + service có loại microphone) → nghe/nói được.
 * @property location vị trí máy camera; null nếu không bật chia sẻ vị trí (app Xem ẩn nút Bản đồ / Cập nhật vị trí).
 * @property recording trạng thái quay video; null = camera bản cũ (app Xem ẩn nút Quay).
 * @property motion trạng thái canh chuyển động; null = camera bản cũ (app Xem ẩn nút Canh chuyển động).
 */
@Serializable
data class DeviceStatus(
    val deviceId: String,
    val displayName: String? = null,
    val online: Boolean,
    val cameraState: CameraState = CameraState.OFF,
    val currentCamera: CameraFacing = CameraFacing.BACK,
    val flashOn: Boolean = false,
    val quality: StreamQuality = StreamQuality.MEDIUM,
    val streamUrl: String? = null,
    val batteryPercent: Int? = null,
    val charging: Boolean? = null,
    val temperatureC: Double? = null,
    val network: NetworkType? = null,
    val lastSeen: Long,
    val error: String? = null,
    val viewers: Int = 0,
    val cameraReady: Boolean? = null,
    val protocolVersion: Int = OnCam.PROTOCOL_VERSION,
    val rtc: Boolean = false,
    val micReady: Boolean? = null,
    val location: LocationStatus? = null,
    val recording: RecordingStatus? = null,
    val motion: MotionStatus? = null,
) {
    companion object {
        /** Trạng thái "offline" dùng làm LWT khi camera-node kết nối broker (kèm vị trí cuối nếu có chia sẻ). */
        fun offline(deviceId: String, displayName: String?, lastSeen: Long, location: LocationStatus? = null): DeviceStatus =
            DeviceStatus(
                deviceId = deviceId,
                displayName = displayName,
                online = false,
                cameraState = CameraState.OFF,
                lastSeen = lastSeen,
                location = location,
            )
    }
}
