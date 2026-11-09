package vn.cnldg.oncam.viewer.mqtt

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber
import vn.cnldg.oncam.shared.client.CameraClient
import vn.cnldg.oncam.shared.client.CameraView
import vn.cnldg.oncam.shared.client.CommandResult
import vn.cnldg.oncam.shared.protocol.Action
import vn.cnldg.oncam.shared.protocol.CameraState
import vn.cnldg.oncam.shared.protocol.CommandParams
import vn.cnldg.oncam.shared.protocol.DeviceAlert
import vn.cnldg.oncam.shared.protocol.DeviceEvent
import vn.cnldg.oncam.viewer.data.CameraStore
import vn.cnldg.oncam.viewer.data.SavedCamera
import vn.cnldg.oncam.viewer.data.ViewerSettings
import vn.cnldg.oncam.viewer.di.ApplicationScope
import vn.cnldg.oncam.viewer.log.TimberLogSink

/** Một camera đã thêm kèm trạng thái hiện tại. */
data class CameraEntry(val saved: SavedCamera, val view: CameraView)

/** Sự kiện từ một camera (IDLE_TIMEOUT, THERMAL_THROTTLE...). */
data class CameraNotice(val deviceId: String, val event: DeviceEvent)

/** Cảnh báo (đã kiểm chữ ký) từ camera [deviceId]; [name] = tên hiển thị. */
data class CameraAlert(val deviceId: String, val name: String, val alert: DeviceAlert)

/**
 * Quản lý kết nối MQTT tới mọi camera đã thêm và gửi lệnh.
 *
 * - Chỉ kết nối khi app đang hiển thị ([connectAll] / [onAppBackground]) để tiết kiệm pin máy Xem — trừ khi
 *   đang bật "Nhận cảnh báo" ([startWatching], do dịch vụ nền gọi): lúc app ẩn vẫn giữ kết nối, nhưng ping thưa
 *   ([WATCH_KEEP_ALIVE_SEC] giây thay vì 10 giây) cho đỡ tốn pin/dữ liệu.
 * - Rời app: tắt các camera do chính app này bật nếu bật cài đặt "Tắt camera khi rời app" (spec mục 7.3),
 *   sau đó ngắt kết nối. Camera vẫn tự tắt sau thời gian chờ nếu lệnh tắt không tới được.
 * - Có mạng lại → kết nối lại ngay.
 */
@Singleton
class CommandSender @Inject constructor(
    @ApplicationContext private val context: Context,
    private val store: CameraStore,
    private val settings: ViewerSettings,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private class Connection(val saved: SavedCamera, val client: CameraClient, val eventsJob: Job, val keepAliveSec: Int)

    private val lifecycle = Mutex()
    private val connections = MutableStateFlow<Map<String, Connection>>(emptyMap())
    private val startedHere = ConcurrentHashMap.newKeySet<String>()

    @Volatile
    private var active = false

    /** App đang hiện trên màn hình. */
    @Volatile
    private var uiVisible = false

    /** Dịch vụ nhận cảnh báo đang chạy (giữ kết nối khi app ẩn). */
    @Volatile
    private var watching = false
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    private val _notices = MutableSharedFlow<CameraNotice>(extraBufferCapacity = 16)

    /** Sự kiện từ các camera để hiện thông báo. */
    val notices: SharedFlow<CameraNotice> = _notices.asSharedFlow()

    private val _alerts = MutableSharedFlow<CameraAlert>(extraBufferCapacity = 16)

    /** Cảnh báo từ các camera (chuyển động, mất điện, quá nhiệt...). */
    val alerts: SharedFlow<CameraAlert> = _alerts.asSharedFlow()

    /** Danh sách camera kèm trạng thái, cập nhật liên tục; null khi đang đọc danh sách. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val entries: Flow<List<CameraEntry>?> = combine(store.cameras, connections) { saved, conns -> saved to conns }
        .flatMapLatest { (saved, conns) ->
            when {
                saved == null -> flowOf(null)
                saved.isEmpty() -> flowOf(emptyList())
                else -> combine(
                    saved.map { s ->
                        (conns[s.deviceId]?.client?.view ?: flowOf(CameraView())).map { CameraEntry(s, it) }
                    },
                ) { it.toList() }
            }
        }

    init {
        scope.launch {
            store.cameras.filterNotNull().collect { list -> lifecycle.withLock { if (active) syncLocked(list) } }
        }
    }

    /** App hiện lên: kết nối mọi camera. */
    fun connectAll() {
        scope.launch {
            lifecycle.withLock {
                uiVisible = true
                active = true
                registerNetworkCallback()
                syncLocked(store.cameras.value.orEmpty())
            }
        }
    }

    /** Dịch vụ nhận cảnh báo bắt đầu: giữ kết nối cả khi app ẩn. */
    fun startWatching() {
        scope.launch {
            lifecycle.withLock {
                watching = true
                if (!active) {
                    active = true
                    registerNetworkCallback()
                    syncLocked(store.cameras.value.orEmpty())
                }
            }
        }
    }

    /** Dịch vụ nhận cảnh báo dừng: nếu app cũng đang ẩn thì ngắt kết nối. */
    fun stopWatching() {
        scope.launch {
            lifecycle.withLock {
                watching = false
                if (!uiVisible && active) disconnectLocked()
            }
        }
    }

    /** App bị ẩn: tắt camera do app bật (nếu bật cài đặt) rồi ngắt kết nối. */
    fun onAppBackground() {
        scope.launch {
            lifecycle.withLock {
                if (!active) return@withLock
                uiVisible = false
                if (settings.stopOnLeave.value) {
                    startedHere.toList().forEach { id ->
                        val c = connections.value[id]?.client ?: return@forEach
                        val status = c.view.value.status
                        // Đang canh chuyển động thì camera phải chạy tiếp (camera cũng từ chối lệnh tắt).
                        if (status?.cameraState == CameraState.STREAMING && status.motion?.armed != true) {
                            val r = c.send(Action.STOP_STREAM, timeoutMs = STOP_ON_LEAVE_TIMEOUT_MS)
                            Timber.i("Rời app: tắt camera %s → %s", id, r)
                        }
                    }
                }
                startedHere.clear()
                if (watching) {
                    // Vẫn nhận cảnh báo: nối lại với nhịp ping thưa.
                    syncLocked(store.cameras.value.orEmpty())
                } else {
                    disconnectLocked()
                }
            }
        }
    }

    private suspend fun disconnectLocked() {
        active = false
        unregisterNetworkCallback()
        connections.value.values.forEach { stop(it) }
        connections.value = emptyMap()
    }

    /** Client của một camera (để lấy URL ảnh/video và header xác thực). */
    fun client(deviceId: String): CameraClient? = connections.value[deviceId]?.client

    /** Chờ tới khi có kết nối tới camera [deviceId] (khi app vừa hiện lại, kết nối được tạo lại sau một nhịp). */
    suspend fun awaitClient(deviceId: String): CameraClient = connections.mapNotNull { it[deviceId]?.client }.first()

    /** Gửi lệnh tới camera [deviceId]. */
    suspend fun send(deviceId: String, action: Action, params: CommandParams = CommandParams()): CommandResult {
        val client = client(deviceId) ?: return CommandResult.NotConnected
        val result = client.send(action, params)
        when {
            action == Action.START_STREAM && result is CommandResult.Ok -> startedHere += deviceId
            action == Action.STOP_STREAM -> startedHere -= deviceId
        }
        return result
    }

    private suspend fun syncLocked(saved: List<SavedCamera>) {
        val current = connections.value
        val wanted = saved.associateBy { it.deviceId }
        // App đang hiện: ping 10 giây để thấy camera offline trong ~20 giây. App ẩn (chỉ chờ cảnh báo): ping thưa.
        val keepAlive = if (uiVisible) UI_KEEP_ALIVE_SEC else WATCH_KEEP_ALIVE_SEC
        // Bỏ camera đã xoá, đổi thông tin kết nối, hoặc đang dùng nhịp ping khác.
        val keep = { id: String, conn: Connection -> wanted[id] == conn.saved && conn.keepAliveSec == keepAlive }
        val next = current.filter { (id, conn) -> keep(id, conn) }.toMutableMap()
        current.filter { (id, conn) -> !keep(id, conn) }.values.forEach { stop(it) }
        // Thêm camera mới.
        wanted.values.filter { it.deviceId !in next }.forEach { s ->
            val client = CameraClient(
                endpoint = s.endpoint(),
                clientId = "oncam-viewer-${store.installationId}-${s.deviceId}",
                scope = scope,
                log = TimberLogSink,
                keepAliveSec = keepAlive,
                initialLocation = store.lastLocation(s.deviceId),
            )
            val eventsJob = scope.launch {
                launch { client.events.collect { _notices.emit(CameraNotice(s.deviceId, it)) } }
                launch {
                    client.alerts.collect { a ->
                        _alerts.emit(CameraAlert(s.deviceId, s.label.ifBlank { a.cameraName ?: s.deviceId }, a))
                    }
                }
                // Lưu vị trí cuối mỗi khi đổi (để vẫn xem được khi camera offline / mở lại app).
                launch {
                    client.view.map { it.lastLocation }.distinctUntilChanged().drop(1)
                        .collect { store.saveLocation(s.deviceId, it) }
                }
            }
            client.start()
            next[s.deviceId] = Connection(s, client, eventsJob, keepAlive)
        }
        connections.value = next
    }

    private suspend fun stop(conn: Connection) {
        conn.eventsJob.cancel()
        conn.client.stop()
    }

    private fun registerNetworkCallback() {
        if (networkCallback != null) return
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                connections.value.values.forEach { it.client.reconnectNow() }
            }
        }
        cm.registerDefaultNetworkCallback(cb)
        networkCallback = cb
    }

    private fun unregisterNetworkCallback() {
        val cb = networkCallback ?: return
        networkCallback = null
        context.getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(cb)
    }

    private companion object {
        const val STOP_ON_LEAVE_TIMEOUT_MS = 3_000L
        const val UI_KEEP_ALIVE_SEC = 10

        /** Nhịp ping khi chỉ chờ cảnh báo: 2 phút (broker coi mất kết nối sau 3 phút im lặng). */
        const val WATCH_KEEP_ALIVE_SEC = 120
    }
}
