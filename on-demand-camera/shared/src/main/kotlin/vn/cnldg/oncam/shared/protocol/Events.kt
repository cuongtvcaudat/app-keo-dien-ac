package vn.cnldg.oncam.shared.protocol

import kotlinx.serialization.Serializable

/**
 * Loại sự kiện camera-node publish lên `camera/{deviceId}/event`.
 *
 * [COMMAND_RESULT] bổ sung so với spec gốc: báo kết quả xử lý một lệnh (kể cả trả lời PING),
 * kèm [DeviceEvent.commandId] để viewer đối chiếu.
 */
@Serializable
enum class EventType {
    CAMERA_STARTED,
    CAMERA_STOPPED,
    IDLE_TIMEOUT,
    THERMAL_THROTTLE,
    ERROR,
    COMMAND_RESULT,
}

/** Lý do camera-node từ chối một lệnh (dùng trong [DeviceEvent.message] của COMMAND_RESULT). */
enum class RejectReason {
    MALFORMED,
    BAD_SIGNATURE,
    EXPIRED,
    REPLAYED,
    BAD_PARAMS,
    UNSUPPORTED,
}

/**
 * Một sự kiện từ camera-node.
 *
 * @property message mô tả ngắn hoặc mã lỗi (vd. tên [RejectReason]).
 * @property timestamp giây kể từ 1970-01-01 UTC.
 * @property commandId id lệnh liên quan (chỉ với COMMAND_RESULT).
 * @property success lệnh thành công hay không (chỉ với COMMAND_RESULT).
 */
@Serializable
data class DeviceEvent(
    val event: EventType,
    val message: String? = null,
    val timestamp: Long,
    val commandId: String? = null,
    val success: Boolean? = null,
)
