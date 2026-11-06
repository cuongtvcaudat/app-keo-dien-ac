package vn.cnldg.oncam.viewer.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlin.math.abs
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import vn.cnldg.oncam.shared.OnCam
import vn.cnldg.oncam.shared.client.CommandResult
import vn.cnldg.oncam.shared.mqtt.MqttConnectionState
import vn.cnldg.oncam.shared.protocol.Action
import vn.cnldg.oncam.shared.protocol.CameraFacing
import vn.cnldg.oncam.shared.protocol.CameraState
import vn.cnldg.oncam.shared.protocol.CommandParams
import vn.cnldg.oncam.shared.protocol.DeviceEvent
import vn.cnldg.oncam.shared.protocol.StreamQuality
import vn.cnldg.oncam.viewer.data.CameraStore
import vn.cnldg.oncam.viewer.data.SavedCamera
import vn.cnldg.oncam.viewer.data.ViewerSettings
import vn.cnldg.oncam.viewer.mqtt.CameraEntry
import vn.cnldg.oncam.viewer.mqtt.CommandSender
import vn.cnldg.oncam.viewer.log.TimberLogSink
import vn.cnldg.oncam.viewer.stream.SnapshotSaver
import vn.cnldg.oncam.viewer.stream.StreamClient
import vn.cnldg.oncam.viewer.stream.StreamSource
import vn.cnldg.oncam.viewer.stream.StreamState

/** Thông báo ngắn hiện ở cuối màn hình (giao diện đổi sang câu tiếng Việt). */
sealed interface UiMessage {
    /** Kết quả một lệnh không thành công. */
    data class CommandFailed(val action: Action, val result: CommandResult) : UiMessage

    /** Sự kiện từ camera (tự tắt, quá nhiệt...). */
    data class CameraEvent(val cameraName: String, val event: DeviceEvent) : UiMessage

    /** Đã lưu ảnh vào Thư viện. */
    data object SnapshotSaved : UiMessage

    /** Không tải/lưu được ảnh. */
    data object SnapshotFailed : UiMessage

    /** Đồng hồ máy camera lệch quá giới hạn cho phép ([seconds] giây, dương = camera chạy nhanh). */
    data class ClockSkew(val cameraName: String, val seconds: Long) : UiMessage
}

/**
 * ViewModel của app Xem: danh sách camera + gửi lệnh. Không giữ tham chiếu Activity/Context.
 */
@HiltViewModel
class ViewerViewModel @Inject constructor(
    private val store: CameraStore,
    private val sender: CommandSender,
    private val settings: ViewerSettings,
    private val snapshots: SnapshotSaver,
) : ViewModel() {

    /** Danh sách camera kèm trạng thái; null khi đang đọc. */
    val cameras: StateFlow<List<CameraEntry>?> =
        sender.entries.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    /** Thời gian chờ tự tắt (giây). */
    val idleTimeoutSec: StateFlow<Int> = settings.idleTimeoutSec

    /** Tắt camera khi rời app. */
    val stopOnLeave: StateFlow<Boolean> = settings.stopOnLeave

    private val _busy = MutableStateFlow<Set<String>>(emptySet())

    /** Các camera đang chờ kết quả lệnh (khoá nút để tránh bấm liên tục). */
    val busy: StateFlow<Set<String>> = _busy.asStateFlow()

    private val _lastSnapshot = MutableStateFlow<Map<String, ByteArray>>(emptyMap())

    /** Ảnh chụp gần nhất của từng camera (hiện ảnh nhỏ trên thẻ camera). */
    val lastSnapshot: StateFlow<Map<String, ByteArray>> = _lastSnapshot.asStateFlow()

    private val stream = StreamClient(viewModelScope, TimberLogSink)

    /** Luồng xem trực tiếp. */
    val streamState: StateFlow<StreamState> = stream.state

    private var clockJob: Job? = null

    private val _liveDeviceId = MutableStateFlow<String?>(null)

    /** Camera đang xem trực tiếp (null nếu không ở màn hình xem). */
    val liveDeviceId: StateFlow<String?> = _liveDeviceId.asStateFlow()

    private val _messages = MutableSharedFlow<UiMessage>(extraBufferCapacity = 8)

    /** Thông báo cho người dùng. */
    val messages: SharedFlow<UiMessage> = _messages.asSharedFlow()

    init {
        viewModelScope.launch {
            sender.notices.collect { notice ->
                val name = cameras.value?.firstOrNull { it.saved.deviceId == notice.deviceId }?.let(::displayName)
                    ?: notice.deviceId
                _messages.emit(UiMessage.CameraEvent(name, notice.event))
            }
        }
    }

    /** Bật camera nếu đang tắt, tắt nếu đang bật. */
    fun toggleCamera(entry: CameraEntry) {
        val on = entry.view.status?.cameraState.let { it == CameraState.STREAMING || it == CameraState.STARTING }
        if (on) {
            command(entry, Action.STOP_STREAM)
        } else {
            command(entry, Action.START_STREAM, CommandParams(idleTimeoutSec = settings.idleTimeoutSec.value))
        }
    }

    /** Đổi camera trước/sau. */
    fun switchCamera(entry: CameraEntry) {
        val current = entry.view.status?.currentCamera ?: CameraFacing.BACK
        val target = if (current == CameraFacing.BACK) CameraFacing.FRONT else CameraFacing.BACK
        command(entry, Action.SWITCH_CAMERA, CommandParams(camera = target))
    }

    /** Bật/tắt đèn flash. */
    fun toggleFlash(entry: CameraEntry) {
        command(entry, Action.SET_FLASH, CommandParams(flash = entry.view.status?.flashOn != true))
    }

    /** Đổi chất lượng stream. */
    fun setQuality(entry: CameraEntry, quality: StreamQuality) {
        command(entry, Action.SET_QUALITY, CommandParams(quality = quality))
    }

    /** Chụp ảnh: hỏi camera, tải `/snapshot.jpg` rồi lưu vào Thư viện. */
    fun snapshot(entry: CameraEntry) {
        val id = entry.saved.deviceId
        if (!markBusy(id)) return
        viewModelScope.launch {
            try {
                val result = sender.send(id, Action.SNAPSHOT)
                if (result !is CommandResult.Ok) {
                    _messages.emit(UiMessage.CommandFailed(Action.SNAPSHOT, result))
                    return@launch
                }
                val client = sender.client(id)
                val url = client?.snapshotUrl()
                val jpeg = if (client != null && url != null) snapshots.download(url, client.streamAuthHeader()) else null
                val saved = jpeg?.let { snapshots.save(it, id) }
                if (jpeg != null && saved != null) {
                    _lastSnapshot.update { it + (id to jpeg) }
                    _messages.emit(UiMessage.SnapshotSaved)
                } else {
                    _messages.emit(UiMessage.SnapshotFailed)
                }
            } finally {
                _busy.update { it - id }
            }
        }
    }

    /** Mở màn hình xem trực tiếp: bật camera nếu đang tắt rồi kết nối luồng MJPEG khi camera sẵn sàng. */
    fun openLive(entry: CameraEntry) {
        _liveDeviceId.value = entry.saved.deviceId
        val state = entry.view.status?.cameraState
        if (entry.view.online && state != CameraState.STREAMING && state != CameraState.STARTING) {
            command(entry, Action.START_STREAM, CommandParams(idleTimeoutSec = settings.idleTimeoutSec.value))
        }
        playLive(entry.saved.deviceId)
    }

    /** Rời màn hình xem trực tiếp. Camera tự tắt sau thời gian chờ nếu không còn ai xem. */
    fun closeLive() {
        clockJob?.cancel()
        stream.stop()
        _liveDeviceId.value = null
    }

    /** App bị ẩn: ngắt luồng video (đỡ tốn dữ liệu), giữ nguyên màn hình đang xem. */
    fun pauseLive() {
        clockJob?.cancel()
        stream.stop()
    }

    /** App hiện lại: nối lại luồng nếu đang ở màn hình xem (không tự bật lại camera). */
    fun resumeLive() {
        _liveDeviceId.value?.let { playLive(it) }
    }

    /** Chụp ảnh trong lúc xem: lưu ngay khung đang hiển thị (không cần tải lại). */
    fun snapshotFromLive(entry: CameraEntry) {
        val jpeg = stream.state.value.jpeg ?: return snapshot(entry)
        val id = entry.saved.deviceId
        viewModelScope.launch {
            if (snapshots.save(jpeg, id) != null) {
                _lastSnapshot.update { it + (id to jpeg) }
                _messages.emit(UiMessage.SnapshotSaved)
            } else {
                _messages.emit(UiMessage.SnapshotFailed)
            }
        }
    }

    private fun playLive(deviceId: String) {
        syncClock(deviceId)
        stream.play {
            val client = sender.client(deviceId) ?: return@play null
            val status = client.view.value.status ?: return@play null
            if (status.cameraState != CameraState.STREAMING) return@play null
            status.streamUrl?.let { url -> StreamSource(url, client.streamAuthHeader()) { client.clockSync.value?.offsetMs } }
        }
    }

    /**
     * Đo độ lệch đồng hồ 2 máy (3 lần PING) để tính đúng độ trễ hình; báo người dùng nếu lệch quá 60 giây
     * (khi đó camera từ chối mọi lệnh và luồng hình).
     */
    private fun syncClock(deviceId: String) {
        clockJob?.cancel()
        clockJob = viewModelScope.launch {
            val client = withTimeoutOrNull(CLOCK_WAIT_MS) {
                sender.awaitClient(deviceId).also { c -> c.view.first { it.connection == MqttConnectionState.Connected } }
            } ?: return@launch
            val sync = client.syncClock() ?: return@launch
            val seconds = sync.offsetMs / 1000
            if (abs(seconds) > OnCam.MAX_CLOCK_SKEW_SEC) {
                val name = cameras.value?.firstOrNull { it.saved.deviceId == deviceId }?.let(::displayName) ?: deviceId
                _messages.emit(UiMessage.ClockSkew(name, seconds))
            }
        }
    }

    override fun onCleared() {
        stream.stop()
    }

    /** Thêm hoặc sửa camera. */
    fun saveCamera(camera: SavedCamera, replacingDeviceId: String?, onDone: () -> Unit) {
        viewModelScope.launch {
            store.upsert(camera, replacingDeviceId)
            onDone()
        }
    }

    /** Xoá camera. */
    fun removeCamera(deviceId: String) {
        viewModelScope.launch { store.remove(deviceId) }
    }

    /** Đặt thời gian chờ tự tắt. */
    fun setIdleTimeoutSec(value: Int) = settings.setIdleTimeoutSec(value)

    /** Bật/tắt "tắt camera khi rời app". */
    fun setStopOnLeave(value: Boolean) = settings.setStopOnLeave(value)

    /** Tên hiển thị của camera. */
    fun displayName(entry: CameraEntry): String =
        entry.saved.label.ifBlank { entry.view.status?.displayName ?: entry.saved.deviceId }

    private fun command(entry: CameraEntry, action: Action, params: CommandParams = CommandParams()) {
        val id = entry.saved.deviceId
        if (!markBusy(id)) return
        viewModelScope.launch {
            try {
                val result = sender.send(id, action, params)
                if (result !is CommandResult.Ok) _messages.emit(UiMessage.CommandFailed(action, result))
            } finally {
                _busy.update { it - id }
            }
        }
    }

    private fun markBusy(id: String): Boolean {
        var marked = false
        _busy.update { if (id in it) it else it + id.also { marked = true } }
        return marked
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
        const val CLOCK_WAIT_MS = 30_000L
    }
}
