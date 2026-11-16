package vn.cnldg.oncam.shared.protocol

import kotlinx.serialization.Serializable

/** Loại cảnh báo máy camera gửi (0.12). */
@Serializable
enum class AlertType {
    /** Có chuyển động (đang canh), kèm ảnh + tên clip đang quay. */
    MOTION,

    /** Rút nguồn / mất điện lưới (máy camera hết cắm sạc ≥ 30 giây). */
    POWER_LOST,

    /** Có điện lại (cắm nguồn lại sau POWER_LOST). */
    POWER_RESTORED,

    /** Pin yếu khi đang không cắm nguồn. */
    BATTERY_LOW,

    /** Máy camera quá nóng. */
    OVERHEAT,
}

/**
 * Một cảnh báo từ máy camera (topic `camera/{deviceId}/alert`, QoS 1, không retain; kèm email nếu cài).
 *
 * @property timestampMs giờ máy camera (mili-giây từ 1970, UTC).
 * @property cameraName tên máy camera (cho email/thông báo).
 * @property batteryPercent pin lúc báo.
 * @property temperatureC nhiệt độ pin lúc báo.
 * @property clip tên clip đang quay (MOTION).
 * @property snapshot ảnh JPEG lúc báo (base64), null nếu camera đang tắt.
 * @property mapsUrl link Google Maps vị trí máy camera (nếu bật chia sẻ vị trí).
 * @property signature chữ ký HMAC của máy camera ([vn.cnldg.oncam.shared.security.AlertAuth]).
 */
@Serializable
data class DeviceAlert(
    val type: AlertType,
    val timestampMs: Long,
    val cameraName: String? = null,
    val batteryPercent: Int? = null,
    val temperatureC: Double? = null,
    val clip: String? = null,
    val snapshot: String? = null,
    val mapsUrl: String? = null,
    val signature: String? = null,
)

/** Ảnh kèm cảnh báo lớn nhất (byte JPEG, trước base64): ảnh 640×480 chất lượng 60 thường 30–60 KB. */
const val MAX_ALERT_SNAPSHOT_BYTES: Int = 200 * 1024
