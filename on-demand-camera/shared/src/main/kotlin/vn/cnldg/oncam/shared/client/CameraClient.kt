package vn.cnldg.oncam.shared.client

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import vn.cnldg.oncam.shared.LogSink
import vn.cnldg.oncam.shared.mqtt.IncomingMessage
import vn.cnldg.oncam.shared.mqtt.MqttClientWrapper
import vn.cnldg.oncam.shared.mqtt.MqttConfig
import vn.cnldg.oncam.shared.mqtt.MqttConnectionState
import vn.cnldg.oncam.shared.mqtt.OutgoingMessage
import vn.cnldg.oncam.shared.protocol.Action
import vn.cnldg.oncam.shared.protocol.CommandParams
import vn.cnldg.oncam.shared.protocol.DeviceEvent
import vn.cnldg.oncam.shared.protocol.DeviceStatus
import vn.cnldg.oncam.shared.protocol.EventType
import vn.cnldg.oncam.shared.protocol.ProtocolJson
import vn.cnldg.oncam.shared.protocol.StreamPaths
import vn.cnldg.oncam.shared.protocol.Topics
import vn.cnldg.oncam.shared.security.BrokerCredentials
import vn.cnldg.oncam.shared.security.CommandSigner
import vn.cnldg.oncam.shared.security.EpochClock
import vn.cnldg.oncam.shared.security.StreamAuth

/** Thông tin để viewer kết nối một camera (nhập ở màn hình "Thêm camera"). */
data class CameraEndpoint(
    val deviceId: String,
    val brokerUri: String,
    val secret: String,
    /** Để trống khi dùng broker nhúng trong camera-node (mật khẩu tự suy ra từ token). */
    val brokerUsername: String? = null,
    val brokerPassword: String? = null,
) {
    /** Không in token/mật khẩu ra log. */
    override fun toString(): String = "CameraEndpoint(deviceId=$deviceId, brokerUri=$brokerUri, secret=***)"
}

/** Kết quả gửi một lệnh. */
sealed interface CommandResult {
    /** Camera-node đã thực hiện; [message] tuỳ lệnh (vd. URL stream, "PONG"). */
    data class Ok(val message: String?) : CommandResult

    /** Camera-node từ chối; [code] là mã RejectReason/CameraCodes. */
    data class Rejected(val code: String?) : CommandResult

    /** Chưa kết nối được broker của camera. */
    data object NotConnected : CommandResult

    /** Không gửi được tới broker. */
    data object PublishFailed : CommandResult

    /** Gửi được nhưng không nhận kết quả trong thời gian chờ (camera offline hoặc quá tải). */
    data object Timeout : CommandResult
}

/** Trạng thái một camera nhìn từ viewer. */
data class CameraView(
    val connection: MqttConnectionState = MqttConnectionState.Idle,
    val status: DeviceStatus? = null,
    /** Thời điểm nhận trạng thái gần nhất (ms, đồng hồ máy viewer). */
    val statusReceivedAtMs: Long? = null,
) {
    /** Camera online: đang kết nối broker và camera báo online. */
    val online: Boolean get() = connection == MqttConnectionState.Connected && status?.online == true
}

/**
 * Phía client (viewer) của giao thức OnCam cho MỘT camera:
 * kết nối broker, theo dõi trạng thái/sự kiện, gửi lệnh đã ký và chờ COMMAND_RESULT tương ứng.
 *
 * Đặt trong module shared (thay vì viewer) để test đầu-cuối được với camera-node thật.
 *
 * @param keepAliveSec 10 giây: mất kết nối bị phát hiện trong ~20 giây (tiêu chí "offline trong 30 giây").
 */
class CameraClient(
    val endpoint: CameraEndpoint,
    clientId: String,
    private val scope: CoroutineScope,
    private val log: LogSink = LogSink.NONE,
    private val clock: EpochClock = EpochClock.SYSTEM,
    private val nowMs: () -> Long = System::currentTimeMillis,
    keepAliveSec: Int = 10,
) {
    private val signer = CommandSigner(endpoint.secret, clock)
    private val streamAuth = StreamAuth(endpoint.secret, endpoint.deviceId, clock)
    private val statusTopic = Topics.status(endpoint.deviceId)
    private val eventTopic = Topics.event(endpoint.deviceId)

    private val mqtt = MqttClientWrapper(
        config = MqttConfig(
            serverUri = endpoint.brokerUri,
            clientId = clientId,
            username = endpoint.brokerUsername?.takeIf { it.isNotBlank() } ?: BrokerCredentials.USERNAME,
            password = endpoint.brokerPassword?.takeIf { it.isNotBlank() } ?: BrokerCredentials.password(endpoint.secret),
            keepAliveSec = keepAliveSec,
            connectTimeoutSec = 8,
        ),
        scope = scope,
        log = log,
    )

    private val _view = MutableStateFlow(CameraView())

    /** Trạng thái hiện tại của camera. */
    val view: StateFlow<CameraView> = _view.asStateFlow()

    private val _events = MutableSharedFlow<DeviceEvent>(extraBufferCapacity = 16)

    /** Sự kiện từ camera (trừ COMMAND_RESULT đã được trả về qua [send]). */
    val events: SharedFlow<DeviceEvent> = _events.asSharedFlow()

    private val pending = ConcurrentHashMap<String, CompletableDeferred<DeviceEvent>>()
    private val jobs = mutableListOf<Job>()

    /** Bắt đầu kết nối và theo dõi. */
    fun start() {
        if (jobs.isNotEmpty()) return
        jobs += scope.launch { mqtt.state.collect { s -> _view.update { it.copy(connection = s) } } }
        jobs += scope.launch { mqtt.messages.collect(::onMessage) }
        scope.launch {
            mqtt.subscribe(statusTopic, Topics.QOS_STATUS)
            mqtt.subscribe(eventTopic, Topics.QOS_EVENT)
        }
        mqtt.start()
    }

    /** Thử kết nối lại ngay (khi có mạng lại). */
    fun reconnectNow() = mqtt.reconnectNow()

    /** Ngắt kết nối. */
    suspend fun stop() {
        mqtt.stop()
        jobs.forEach { it.cancelAndJoin() }
        jobs.clear()
        pending.values.forEach { it.cancel() }
        pending.clear()
        _view.update { it.copy(connection = MqttConnectionState.Idle) }
    }

    /** Gửi lệnh [action] đã ký và chờ kết quả tối đa [timeoutMs]. */
    suspend fun send(action: Action, params: CommandParams = CommandParams(), timeoutMs: Long = 10_000): CommandResult {
        if (mqtt.state.value != MqttConnectionState.Connected) return CommandResult.NotConnected
        val command = signer.sign(endpoint.deviceId, action, params)
        val waiter = CompletableDeferred<DeviceEvent>()
        pending[command.id] = waiter
        try {
            val sent = mqtt.publish(
                OutgoingMessage(
                    topic = Topics.command(endpoint.deviceId),
                    payload = ProtocolJson.encodeCommand(command),
                    qos = Topics.QOS_COMMAND,
                    retained = false,
                ),
            )
            if (!sent) return CommandResult.PublishFailed
            val result = withTimeoutOrNull(timeoutMs) { waiter.await() } ?: return CommandResult.Timeout
            return if (result.success == true) CommandResult.Ok(result.message) else CommandResult.Rejected(result.message)
        } finally {
            pending.remove(command.id)
        }
    }

    /** URL ảnh chụp (khi camera đang phát), suy từ streamUrl trong trạng thái. */
    fun snapshotUrl(): String? =
        _view.value.status?.streamUrl?.let { it.removeSuffix(StreamPaths.STREAM) + StreamPaths.SNAPSHOT }

    /** Giá trị header xác thực cho request HTTP tới camera (stream/snapshot). */
    fun streamAuthHeader(): String = streamAuth.headerValue()

    private fun onMessage(msg: IncomingMessage) {
        when (msg.topic) {
            statusTopic -> ProtocolJson.decodeStatus(msg.payload)?.let { status ->
                _view.update { it.copy(status = status, statusReceivedAtMs = nowMs()) }
            }
            eventTopic -> ProtocolJson.decodeEvent(msg.payload)?.let { event ->
                val waiter = event.commandId?.let { pending[it] }
                if (event.event == EventType.COMMAND_RESULT) {
                    waiter?.complete(event)
                } else {
                    _events.tryEmit(event)
                }
            }
        }
    }
}
