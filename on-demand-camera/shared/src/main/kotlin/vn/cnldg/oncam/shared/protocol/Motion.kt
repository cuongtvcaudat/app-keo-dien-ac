package vn.cnldg.oncam.shared.protocol

import kotlinx.serialization.Serializable

/**
 * Độ nhạy phát hiện chuyển động (0.11).
 *
 * @property cellDelta độ chênh độ sáng (0–255) để một ô lưới bị coi là "đổi".
 * @property areaPercent phần trăm ô lưới phải đổi mới tính là có chuyển động.
 */
@Serializable
enum class MotionSensitivity(val cellDelta: Int, val areaPercent: Double) {
    /** Chỉ người/xe đi gần (ít báo nhầm do lá cây, mưa). */
    LOW(30, 5.0),

    /** Mặc định: người đi ngang sân. */
    MEDIUM(22, 2.5),

    /** Vật nhỏ/xa cũng báo (dễ báo nhầm khi gió, mưa, côn trùng trước ống kính). */
    HIGH(15, 1.0),
}

/**
 * Trạng thái "canh chuyển động" trên máy camera (gửi kèm trạng thái). null trong [DeviceStatus] = camera bản cũ.
 *
 * @property armed đang canh (camera bật liên tục ở độ phân giải thấp, có chuyển động thì tự quay).
 * @property sensitivity độ nhạy đang dùng.
 * @property lastMotionAt lần gần nhất thấy chuyển động (giây từ 1970, UTC — giờ máy camera).
 * @property clipSeconds mỗi lần có chuyển động quay ít nhất chừng ấy giây (kéo dài thêm khi còn chuyển động).
 * @property error lý do tự tắt canh (quá nhiệt, lỗi camera...), null nếu ổn.
 */
@Serializable
data class MotionStatus(
    val armed: Boolean = false,
    val sensitivity: MotionSensitivity = MotionSensitivity.MEDIUM,
    val lastMotionAt: Long? = null,
    val clipSeconds: Int = 30,
    val error: String? = null,
)
