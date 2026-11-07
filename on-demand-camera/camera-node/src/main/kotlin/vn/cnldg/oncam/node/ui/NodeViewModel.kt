package vn.cnldg.oncam.node.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import vn.cnldg.oncam.node.config.ConfigLoad
import vn.cnldg.oncam.node.config.NodeConfigRepository
import vn.cnldg.oncam.node.core.NodeConfig
import vn.cnldg.oncam.node.core.NodeState
import vn.cnldg.oncam.node.service.NodeRuntime
import vn.cnldg.oncam.shared.protocol.Action
import vn.cnldg.oncam.shared.protocol.CommandParams
import vn.cnldg.oncam.shared.security.SharedSecret

/**
 * ViewModel của camera-node: đọc cấu hình và trạng thái chạy, không giữ tham chiếu tới Service.
 * Việc khởi động/dừng Service cần Context nên do màn hình thực hiện.
 */
@HiltViewModel
class NodeViewModel @Inject constructor(
    private val repository: NodeConfigRepository,
    private val runtime: NodeRuntime,
) : ViewModel() {

    /** Cấu hình đã lưu. */
    val config: StateFlow<ConfigLoad> = repository.config

    /** Tự chạy khi khởi động máy. */
    val autoStart: StateFlow<Boolean> = repository.autoStart

    /** Có quyền mở camera khi app ở nền không. */
    val cameraAccess: StateFlow<Boolean> = runtime.cameraAccess

    /** Service được dùng micro (nghe/nói qua WebRTC). */
    val micAccess: StateFlow<Boolean> = runtime.micAccess

    /** Service được lấy vị trí. */
    val locationAccess: StateFlow<Boolean> = runtime.locationAccess

    /** Lấy vị trí mới ngay (nút "Cập nhật vị trí" trên máy camera). */
    fun updateLocation() {
        runtime.controller.value?.requestLocationUpdate()
    }

    /** Node có đang chạy không. */
    val running: StateFlow<Boolean> = runtime.controller
        .map { it != null }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), runtime.controller.value != null)

    /** Trạng thái chi tiết của node đang chạy. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val nodeState: StateFlow<NodeState> = runtime.controller
        .flatMapLatest { it?.state ?: flowOf(NodeState()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), NodeState())

    private val _selfTesting = MutableStateFlow(false)

    /** Đang chạy tự kiểm tra. */
    val selfTesting: StateFlow<Boolean> = _selfTesting.asStateFlow()

    /** Lưu cấu hình. */
    fun save(config: NodeConfig, autoStart: Boolean, onSaved: () -> Unit) {
        viewModelScope.launch {
            repository.save(config)
            repository.setAutoStart(autoStart)
            onSaved()
        }
    }

    /** Sinh token bí mật mới (256 bit). */
    fun generateSecret(): String = SharedSecret.generate()

    /** Gửi PING đi vòng qua broker để kiểm tra toàn bộ đường lệnh. */
    fun selfTest() {
        val controller = runtime.controller.value ?: return
        if (_selfTesting.value) return
        viewModelScope.launch {
            _selfTesting.value = true
            try {
                controller.selfTest()
            } finally {
                _selfTesting.value = false
            }
        }
    }

    /** Bật camera thử ngay trên máy (đi đúng đường lệnh như từ app Xem); tự tắt sau 60 giây không ai xem. */
    fun startCameraTest() {
        val controller = runtime.controller.value ?: return
        viewModelScope.launch {
            controller.sendLocalCommand(Action.START_STREAM, CommandParams(idleTimeoutSec = TEST_IDLE_TIMEOUT_SEC))
        }
    }

    /** Tắt camera ngay trên máy. */
    fun stopCamera() {
        val controller = runtime.controller.value ?: return
        viewModelScope.launch { controller.stopCamera() }
    }

    /** Khung JPEG mới nhất để xem thử. */
    fun latestFrame(): ByteArray? = runtime.controller.value?.latestFrame()

    private companion object {
        const val TEST_IDLE_TIMEOUT_SEC = 60
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
