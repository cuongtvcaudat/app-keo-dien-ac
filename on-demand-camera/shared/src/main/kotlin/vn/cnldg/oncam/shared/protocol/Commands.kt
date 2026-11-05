package vn.cnldg.oncam.shared.protocol

import kotlinx.serialization.Serializable
import vn.cnldg.oncam.shared.OnCam

/** Các lệnh viewer gửi xuống camera-node (topic `camera/{deviceId}/cmd`). */
@Serializable
enum class Action {
    START_STREAM,
    STOP_STREAM,
    SWITCH_CAMERA,
    SET_FLASH,
    SNAPSHOT,
    SET_QUALITY,
    PING,
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
 */
@Serializable
data class CommandParams(
    val camera: CameraFacing? = null,
    val flash: Boolean? = null,
    val quality: StreamQuality? = null,
    val idleTimeoutSec: Int? = null,
)

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
}

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
        Action.START_STREAM,
        Action.STOP_STREAM,
        Action.SWITCH_CAMERA, // camera = null nghĩa là đổi sang camera còn lại
        Action.SNAPSHOT,
        Action.PING,
        -> null
    }
}
