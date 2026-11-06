package vn.cnldg.oncam.shared.protocol

import kotlinx.serialization.Serializable
import vn.cnldg.oncam.shared.OnCam

/**
 * Các lệnh viewer gửi xuống camera-node (topic `camera/{deviceId}/cmd`).
 *
 * [RTC_OFFER]/[RTC_HANGUP] (Phase 7): mở/đóng phiên WebRTC (hình + tiếng 2 chiều). Camera bản cũ không biết
 * 2 lệnh này → trả MALFORMED → app Xem quay về xem MJPEG.
 *
 * [UPDATE_LOCATION]: lấy vị trí mới ngay (GPS/mạng) và gửi lại trạng thái; chỉ khi máy camera bật chia sẻ vị trí.
 */
@Serializable
enum class Action {
    START_STREAM,
    STOP_STREAM,
    SWITCH_CAMERA,
    SET_FLASH,
    SNAPSHOT,
    SET_QUALITY,
    PING,
    RTC_OFFER,
    RTC_HANGUP,
    UPDATE_LOCATION,
}

/** Camera trước/sau của máy camera-node. */
@Serializable
enum class CameraFacing { FRONT, BACK }

/**
 * Mức chất lượng stream. Giữ độ phân giải thấp để điện thoại cũ đỡ nóng.
 *
 * @property width chiều rộng khung hình (px).
 * @property height chiều cao khung hình (px).
 * @property fps số khung hình/giây.
 * @property jpegQuality chất lượng nén JPEG của từng khung MJPEG (0-100).
 */
@Serializable
enum class StreamQuality(val width: Int, val height: Int, val fps: Int, val jpegQuality: Int) {
    LOW(640, 480, 10, 60),
    MEDIUM(1280, 720, 15, 70),
    HIGH(1280, 720, 30, 80),
}

/**
 * Tham số kèm lệnh. Trường nào không dùng cho lệnh đó thì để null.
 *
 * @property sdp bản mô tả phiên WebRTC (SDP offer) của app Xem, kèm sẵn mọi ứng viên ICE (không trickle).
 * @property sessionId mã phiên WebRTC do app Xem tạo (dùng lại cho RTC_HANGUP).
 */
@Serializable
data class CommandParams(
    val camera: CameraFacing? = null,
    val flash: Boolean? = null,
    val quality: StreamQuality? = null,
    val idleTimeoutSec: Int? = null,
    val sdp: String? = null,
    val sessionId: String? = null,
) {
    /** Không in SDP (dài, chứa khoá ICE) ra log. */
    override fun toString(): String =
        "CommandParams(camera=$camera, flash=$flash, quality=$quality, idleTimeoutSec=$idleTimeoutSec, " +
            "sdp=${sdp?.let { "${it.length} ký tự" }}, sessionId=$sessionId)"
}

/**
 * Một lệnh từ viewer → camera-node.
 *
 * @property id mã duy nhất (UUID) của lệnh, dùng để chống gửi lại (replay) và đối chiếu kết quả.
 * @property token chữ ký HMAC-SHA256 (hex) do [vn.cnldg.oncam.shared.security.CommandSigner] tạo.
 * @property timestamp thời điểm tạo lệnh, giây kể từ 1970-01-01 UTC.
 */
@Serializable
data class Command(
    val id: String,
    val action: Action,
    val params: CommandParams = CommandParams(),
    val token: String,
    val timestamp: Long,
)

/** Lỗi tham số của một lệnh (đúng chữ ký nhưng thiếu/sai tham số). */
enum class CommandProblem {
    FLASH_REQUIRED,
    QUALITY_REQUIRED,
    IDLE_TIMEOUT_OUT_OF_RANGE,
    SDP_REQUIRED,
    SDP_TOO_LARGE,
    SESSION_REQUIRED,
}

/** Mã phiên WebRTC: 8–64 ký tự chữ, số, gạch ngang (thường là UUID). */
private val SESSION_ID = Regex("^[A-Za-z0-9-]{8,64}$")

/** SDP offer lớn nhất chấp nhận (offer thật ~4–8 KB). */
const val MAX_SDP_LENGTH: Int = 32_000

/**
 * Kiểm tra tham số theo từng loại lệnh. Trả về null nếu hợp lệ.
 */
fun Command.paramProblem(): CommandProblem? {
    params.idleTimeoutSec?.let {
        if (it !in OnCam.MIN_IDLE_TIMEOUT_SEC..OnCam.MAX_IDLE_TIMEOUT_SEC) {
            return CommandProblem.IDLE_TIMEOUT_OUT_OF_RANGE
        }
    }
    return when (action) {
        Action.SET_FLASH -> if (params.flash == null) CommandProblem.FLASH_REQUIRED else null
        Action.SET_QUALITY -> if (params.quality == null) CommandProblem.QUALITY_REQUIRED else null
        Action.RTC_OFFER -> when {
            params.sdp.isNullOrBlank() -> CommandProblem.SDP_REQUIRED
            params.sdp.length > MAX_SDP_LENGTH -> CommandProblem.SDP_TOO_LARGE
            params.sessionId == null || !SESSION_ID.matches(params.sessionId) -> CommandProblem.SESSION_REQUIRED
            else -> null
        }
        Action.RTC_HANGUP ->
            if (params.sessionId == null || !SESSION_ID.matches(params.sessionId)) CommandProblem.SESSION_REQUIRED else null
        Action.UPDATE_LOCATION,
        Action.START_STREAM,
        Action.STOP_STREAM,
        Action.SWITCH_CAMERA, // camera = null nghĩa là đổi sang camera còn lại
        Action.SNAPSHOT,
        Action.PING,
        -> null
    }
}
