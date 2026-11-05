package vn.cnldg.oncam.node.core

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import vn.cnldg.oncam.node.mqtt.ActionOutcome
import vn.cnldg.oncam.shared.OnCam
import vn.cnldg.oncam.shared.protocol.CameraFacing
import vn.cnldg.oncam.shared.protocol.CameraState
import vn.cnldg.oncam.shared.protocol.Command
import vn.cnldg.oncam.shared.protocol.DeviceEvent
import vn.cnldg.oncam.shared.protocol.StreamQuality

/** Hướng đặt máy camera, để xoay ảnh cho đứng. [degrees] = góc máy bị xoay ngược chiều kim đồng hồ. */
enum class MountOrientation(val degrees: Int) {
    /** Đứng bình thường, đầu máy hướng lên. */
    UP(0),

    /** Nằm ngang, đầu máy hướng sang trái. */
    LEFT(90),

    /** Lộn ngược, đầu máy hướng xuống. */
    DOWN(180),

    /** Nằm ngang, đầu máy hướng sang phải. */
    RIGHT(270),
}

/**
 * Trạng thái camera/stream hiện tại.
 *
 * @property error mã lỗi [vn.cnldg.oncam.shared.protocol.CameraCodes] của lần bật/chạy gần nhất.
 * @property clients số người đang xem luồng MJPEG.
 */
data class CameraStatus(
    val state: CameraState = CameraState.OFF,
    val facing: CameraFacing = CameraFacing.BACK,
    val flashOn: Boolean = false,
    val quality: StreamQuality = StreamQuality.MEDIUM,
    val idleTimeoutSec: Int = OnCam.DEFAULT_IDLE_TIMEOUT_SEC,
    val streamUrl: String? = null,
    val clients: Int = 0,
    val error: String? = null,
)

/** Phần camera + stream mà [NodeController] điều khiển (cài đặt: [StreamManager]). */
interface CameraSession {
    /** Trạng thái hiện tại. */
    val status: StateFlow<CameraStatus>

    /** Sự kiện cần publish (CAMERA_STARTED, CAMERA_STOPPED, IDLE_TIMEOUT, THERMAL_THROTTLE, ERROR). */
    val events: Flow<DeviceEvent>

    /** Có được phép mở camera ngay lúc này không (quyền + loại foreground service). */
    fun cameraReady(): Boolean

    /** Thực hiện một lệnh camera đã qua kiểm tra chữ ký và tham số. */
    suspend fun perform(command: Command): ActionOutcome

    /** Tắt camera ngay (nút trên máy camera / notification). */
    suspend fun stopStreaming(reason: String)

    /** Khung JPEG mới nhất (để xem thử trên máy camera), null nếu camera tắt. */
    fun latestFrame(): ByteArray?

    /** Tắt hẳn khi dừng node. */
    suspend fun shutdown()
}

/** Kết quả mở camera. */
sealed interface OpenResult {
    /** Đã mở; [hasFlash] = camera này có đèn flash. */
    data class Opened(val hasFlash: Boolean) : OpenResult

    /** Không mở được, [reason] là mã CameraCodes. */
    data class Failed(val reason: String) : OpenResult
}

/**
 * Cổng tới phần cứng camera (Android: CameraX trong [vn.cnldg.oncam.node.camera.CameraController]).
 * Tách interface để test logic stream trên JVM bằng camera giả.
 */
interface CameraPort {
    /** Máy có camera [facing] không. */
    suspend fun hasCamera(facing: CameraFacing): Boolean

    /**
     * Mở (hoặc mở lại với cấu hình mới) camera [facing] ở [quality]; mỗi khung JPEG được gửi vào [sink]
     * (gọi từ luồng nền của camera, không phải luồng chính).
     */
    suspend fun open(facing: CameraFacing, quality: StreamQuality, sink: (ByteArray) -> Unit): OpenResult

    /** Bật/tắt đèn flash; false nếu camera đang mở không có flash hoặc camera chưa mở. */
    suspend fun setTorch(on: Boolean): Boolean

    /** Đóng camera, nhả hoàn toàn để app khác dùng được. */
    suspend fun close()

    /** Lỗi xảy ra khi camera đang chạy (mã CameraCodes). */
    val errors: Flow<String>
}
