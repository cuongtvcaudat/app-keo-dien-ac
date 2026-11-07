package vn.cnldg.oncam.shared.protocol

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlinx.serialization.Serializable

/**
 * Trạng thái quay video trên máy camera (gửi kèm trạng thái). null trong [DeviceStatus] = camera bản cũ, chưa quay được.
 *
 * @property active đang quay.
 * @property startedAt lúc bắt đầu clip đang quay (giây từ 1970, UTC — giờ máy camera).
 * @property maxSeconds clip đang quay tự dừng sau chừng ấy giây.
 * @property audio clip đang/vừa quay có tiếng (micro máy camera được phép).
 * @property lastFile tên clip vừa quay xong gần nhất (app Xem thấy đổi là tự tải về).
 * @property lastFileBytes dung lượng clip đó.
 * @property error mã lỗi lần quay gần nhất (CameraCodes.RECORDING_*), null nếu ổn.
 */
@Serializable
data class RecordingStatus(
    val active: Boolean = false,
    val startedAt: Long? = null,
    val maxSeconds: Int? = null,
    val audio: Boolean? = null,
    val lastFile: String? = null,
    val lastFileBytes: Long? = null,
    val error: String? = null,
)

/** Một clip trên máy camera (danh sách ở `GET /recordings`). */
@Serializable
data class RecordingInfo(
    val name: String,
    val sizeBytes: Long,
    /** Lúc tạo (giây từ 1970, UTC). */
    val createdAt: Long,
)

/** Đường dẫn và tên file video. */
object Recordings {
    /** Danh sách clip (JSON mảng [RecordingInfo]). */
    const val LIST_PATH: String = "/recordings"

    /** Độ dài mặc định một clip khi app Xem không chỉ định (10 phút). */
    const val DEFAULT_MAX_SECONDS: Int = 600

    /** Cổng của máy chủ tải video (khác cổng luồng hình 8080). */
    const val PORT: Int = 8081

    private val NAME = Regex("^oncam-\\d{8}-\\d{6}(-\\d{1,3})?\\.mp4$")
    private val FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC)

    /** Đường dẫn tải một clip. */
    fun filePath(name: String): String = "$LIST_PATH/$name"

    /** Tên hợp lệ (chặn đường dẫn kiểu "../" — chỉ đúng mẫu do máy camera đặt). */
    fun isValidName(name: String): Boolean = NAME.matches(name)

    /** Tên clip theo thời điểm (giờ UTC để không phụ thuộc múi giờ máy); [suffix] chống trùng khi quay 2 clip/giây. */
    fun nameAt(epochMs: Long, suffix: Int = 0): String =
        "oncam-${FORMAT.format(Instant.ofEpochMilli(epochMs))}${if (suffix > 0) "-$suffix" else ""}.mp4"
}
