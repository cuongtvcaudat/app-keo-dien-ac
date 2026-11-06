package vn.cnldg.oncam.node.service

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import vn.cnldg.oncam.node.core.NodeController

/**
 * Cầu nối giữa Service và giao diện: Service đăng ký [NodeController] đang chạy,
 * ViewModel đọc trạng thái từ đây (ViewModel không giữ tham chiếu tới Service).
 */
@Singleton
class NodeRuntime @Inject constructor() {
    private val _controller = MutableStateFlow<NodeController?>(null)

    /** Bộ điều phối đang chạy, null nếu node đang dừng. */
    val controller: StateFlow<NodeController?> = _controller.asStateFlow()

    private val _cameraAccess = MutableStateFlow(false)

    /**
     * Service có được phép mở camera khi app ở nền không.
     * false nếu service bị khởi động lại từ nền (Android 11+ không cấp quyền camera "khi đang dùng").
     */
    val cameraAccess: StateFlow<Boolean> = _cameraAccess.asStateFlow()

    private val _micAccess = MutableStateFlow(false)

    /** Service được dùng micro (quyền RECORD_AUDIO + loại foreground "microphone") để gửi tiếng sân qua WebRTC. */
    val micAccess: StateFlow<Boolean> = _micAccess.asStateFlow()

    internal fun setMicAccess(allowed: Boolean) {
        _micAccess.value = allowed
    }

    private val _locationAccess = MutableStateFlow(false)

    /** Service được lấy vị trí (loại foreground "location" — chỉ có khi khởi động lúc app đang hiển thị). */
    val locationAccess: StateFlow<Boolean> = _locationAccess.asStateFlow()

    internal fun setLocationAccess(allowed: Boolean) {
        _locationAccess.value = allowed
    }

    /** Cấu hình đang chạy có bật "Chia sẻ vị trí" (để màn hình xin quyền / nâng loại service khi cần). */
    @Volatile
    var locationWanted: Boolean = false
        internal set

    internal fun attach(controller: NodeController) {
        _controller.value = controller
    }

    internal fun detach(controller: NodeController) {
        _controller.compareAndSet(controller, null)
    }

    internal fun setCameraAccess(allowed: Boolean) {
        _cameraAccess.value = allowed
    }
}
