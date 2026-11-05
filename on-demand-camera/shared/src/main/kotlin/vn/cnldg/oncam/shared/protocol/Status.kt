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
 * Các trường ngoài spec gốc (displayName, flashOn, quality, charging, network, protocolVersion)
 * đều có giá trị mặc định nên bản cũ/mới vẫn đọc được của nhau.
 *
 * @property displayName tên hiển thị (vd. "Sân nhà"); [deviceId] chỉ dùng ký tự ASCII.
 * @property streamUrl địa chỉ MJPEG khi đang STREAMING, null khi tắt.
 * @property temperatureC nhiệt độ PIN (°C) đọc từ BatteryManager, không phải nhiệt độ CPU.
 * @property lastSeen thời điểm cập nhật trạng thái, giây kể từ 1970-01-01 UTC.
 * @property error mô tả lỗi khi [cameraState] = ERROR.
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
    val protocolVersion: Int = OnCam.PROTOCOL_VERSION,
) {
    companion object {
        /** Trạng thái "offline" dùng làm LWT khi camera-node kết nối broker. */
        fun offline(deviceId: String, displayName: String?, lastSeen: Long): DeviceStatus =
            DeviceStatus(
                deviceId = deviceId,
                displayName = displayName,
                online = false,
                cameraState = CameraState.OFF,
                lastSeen = lastSeen,
            )
    }
}
