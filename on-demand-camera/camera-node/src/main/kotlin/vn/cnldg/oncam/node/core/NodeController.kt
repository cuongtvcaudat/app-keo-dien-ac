package vn.cnldg.oncam.node.core

import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import vn.cnldg.oncam.node.broker.EmbeddedBroker
import vn.cnldg.oncam.node.mqtt.ActionOutcome
import vn.cnldg.oncam.node.mqtt.CommandHandler
import vn.cnldg.oncam.shared.LogSink
import vn.cnldg.oncam.shared.mqtt.IncomingMessage
import vn.cnldg.oncam.shared.mqtt.MqttClientWrapper
import vn.cnldg.oncam.shared.mqtt.MqttConfig
import vn.cnldg.oncam.shared.mqtt.MqttConnectionState
import vn.cnldg.oncam.shared.mqtt.OutgoingMessage
import vn.cnldg.oncam.shared.protocol.Action
import vn.cnldg.oncam.shared.protocol.CameraCodes
import vn.cnldg.oncam.shared.protocol.CommandParams
import vn.cnldg.oncam.shared.protocol.DeviceEvent
import vn.cnldg.oncam.shared.protocol.DeviceStatus
import vn.cnldg.oncam.shared.protocol.ProtocolJson
import vn.cnldg.oncam.shared.protocol.Topics
import vn.cnldg.oncam.shared.security.BrokerCredentials
import vn.cnldg.oncam.shared.security.CommandSigner
import vn.cnldg.oncam.shared.security.CommandVerifier
import vn.cnldg.oncam.shared.security.EpochClock
import vn.cnldg.oncam.shared.security.RtcAuth

/**
 * Bộ điều phối của camera node: luôn nghe lệnh MQTT, giao lệnh camera cho [CameraSession].
 *
 * Khi [start]:
 * 1. Broker nhúng: chờ có IP Tailscale → mở broker trên IP đó. Nếu IP đổi/mất → tắt và mở lại.
 *    Broker ngoài: dùng thẳng [NodeConfig.externalUri].
 * 2. Kết nối MQTT (LWT = trạng thái offline), subscribe topic lệnh, publish trạng thái online.
 * 3. Nhận lệnh → [CommandHandler] → publish COMMAND_RESULT lên topic event.
 * 4. Mỗi [Timing.heartbeatMs] cập nhật pin/nhiệt độ/mạng và publish lại trạng thái.
 *
 * Không phụ thuộc Android → test được trên JVM với broker thật.
 *
 * @param scope nên chạy trên Dispatchers.IO (có thao tác chặn luồng: bind cổng, tắt Netty).
 * @param addressProvider trả về IP Tailscale hiện tại (null nếu chưa bật Tailscale).
 * @param camera phần camera + stream (Phase 3); null → các lệnh camera trả về "chưa hỗ trợ".
 */
class NodeController(
    private val config: NodeConfig,
    private val scope: CoroutineScope,
    private val deviceInfo: DeviceInfoSource,
    private val addressProvider: () -> String?,
    private val broker: EmbeddedBroker?,
    private val clock: EpochClock = EpochClock.SYSTEM,
    private val log: LogSink = LogSink.NONE,
    private val timing: Timing = Timing(),
    private val camera: CameraSession? = null,
) {
    /** Các mốc thời gian (rút ngắn được trong test). */
    data class Timing(
        val heartbeatMs: Long = 60_000,
        val addressCheckMs: Long = 15_000,
        val keepAliveSec: Int = 15,
        val maxReconnectDelayMs: Long = 10_000,
    )

    private val _state = MutableStateFlow(NodeState())

    /** Trạng thái hiện tại để hiển thị. */
    val state: StateFlow<NodeState> = _state.asStateFlow()

    private val embedded = config.brokerMode == BrokerMode.EMBEDDED
    private val cmdTopic = Topics.command(config.deviceId)
    private val handler = CommandHandler(
        verifier = CommandVerifier(config.secret, config.deviceId, clock),
        clock = clock,
        rtcAuth = RtcAuth(config.secret, config.deviceId),
    ) { cmd ->
        camera?.perform(cmd) ?: ActionOutcome.Unsupported
    }
    private val signer = CommandSigner(config.secret, clock)
    private val pending = ConcurrentHashMap<String, CompletableDeferred<DeviceEvent>>()
    private val networkChanged = Channel<Unit>(Channel.CONFLATED)

    @Volatile
    private var mqtt: MqttClientWrapper? = null
    private var mainJob: Job? = null
    private var cameraJob: Job? = null

    init {
        require(config.problems().isEmpty()) { "Cấu hình không hợp lệ: ${config.problems()}" }
        require(!embedded || broker != null) { "Chế độ broker nhúng cần EmbeddedBroker" }
    }

    /** Bắt đầu chạy (gọi nhiều lần không sao). */
    @Synchronized
    fun start() {
        if (mainJob?.isActive == true) return
        _state.value = NodeState(
            running = true,
            startedAtSec = clock.nowSec(),
            broker = if (embedded) BrokerState.WaitingForAddress else BrokerState.NotUsed,
            device = deviceInfo.snapshot(),
        )
        mainJob = scope.launch { runSessions() }
        camera?.let { cam ->
            cameraJob = scope.launch { cam.status.collect { s -> _state.update { it.copy(camera = s) } } }
        }
    }

    /** Báo mạng vừa thay đổi: kiểm tra lại IP Tailscale và thử kết nối MQTT lại ngay. */
    fun onNetworkChanged() {
        networkChanged.trySend(Unit)
        mqtt?.reconnectNow()
    }

    /** Dừng êm: tắt camera, publish trạng thái offline, ngắt MQTT, tắt broker. */
    suspend fun stop() {
        val job = synchronized(this) { mainJob.also { mainJob = null } }
        camera?.shutdown()
        cameraJob?.cancel()
        cameraJob = null
        val wrapper = mqtt
        if (wrapper != null && wrapper.state.value == MqttConnectionState.Connected) {
            wrapper.publish(statusMessage(online = false))
        }
        wrapper?.stop()
        job?.cancelAndJoin()
        withContext(Dispatchers.IO) { broker?.stop() }
        _state.update {
            it.copy(running = false, mqtt = MqttConnectionState.Idle, broker = BrokerState.NotUsed, brokerUri = null)
        }
    }

    /**
     * Tự kiểm tra: gửi một lệnh PING đã ký tới chính topic lệnh của máy này, đi vòng qua broker,
     * chờ được xử lý. Kiểm cùng lúc broker + MQTT + chữ ký + bộ xử lý lệnh.
     */
    suspend fun selfTest(timeoutMs: Long = 5_000): SelfTestResult {
        val wrapper = mqtt
        val result = if (wrapper == null || wrapper.state.value != MqttConnectionState.Connected) {
            SelfTestResult.Failed(SelfTestFailure.NOT_CONNECTED)
        } else {
            val cmd = signer.sign(config.deviceId, Action.PING)
            val deferred = CompletableDeferred<DeviceEvent>()
            pending[cmd.id] = deferred
            val startedNs = System.nanoTime()
            try {
                val sent = wrapper.publish(
                    OutgoingMessage(cmdTopic, ProtocolJson.encodeCommand(cmd), Topics.QOS_COMMAND, retained = false),
                )
                if (!sent) {
                    SelfTestResult.Failed(SelfTestFailure.PUBLISH_FAILED)
                } else {
                    val event = withTimeoutOrNull(timeoutMs) { deferred.await() }
                    when {
                        event == null -> SelfTestResult.Failed(SelfTestFailure.TIMEOUT)
                        event.success == true -> SelfTestResult.Passed((System.nanoTime() - startedNs) / 1_000_000)
                        else -> SelfTestResult.Failed(SelfTestFailure.REJECTED)
                    }
                }
            } finally {
                pending.remove(cmd.id)
            }
        }
        _state.update { it.copy(selfTest = result) }
        return result
    }

    private suspend fun runSessions() {
        if (!embedded) {
            _state.update { it.copy(brokerUri = config.externalUri) }
            runMqttSession(
                uri = config.externalUri,
                username = config.externalUsername.ifBlank { null },
                password = config.externalPassword.ifBlank { null },
                watchHost = null,
            )
            return
        }
        val b = checkNotNull(broker)
        val password = BrokerCredentials.password(config.secret)
        while (currentCoroutineContext().isActive) {
            val host = awaitAddress()
            try {
                withContext(Dispatchers.IO) { b.start(host, config.brokerPort, BrokerCredentials.USERNAME, password) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.log(LogSink.Level.WARN, "Không mở được broker tại $host:${config.brokerPort}", e)
                _state.update { it.copy(broker = BrokerState.Failed(e.message ?: e.javaClass.simpleName)) }
                waitForNetworkOr(timing.addressCheckMs)
                continue
            }
            val uri = "tcp://$host:${config.brokerPort}"
            _state.update { it.copy(broker = BrokerState.Running(host, config.brokerPort), brokerUri = uri) }
            try {
                runMqttSession(uri, BrokerCredentials.USERNAME, password, watchHost = host)
            } finally {
                withContext(NonCancellable + Dispatchers.IO) { b.stop() }
            }
            _state.update { it.copy(broker = BrokerState.WaitingForAddress, brokerUri = null) }
        }
    }

    private suspend fun awaitAddress(): String {
        while (true) {
            addressProvider()?.let { return it }
            _state.update { it.copy(broker = BrokerState.WaitingForAddress, brokerUri = null) }
            waitForNetworkOr(timing.addressCheckMs)
        }
    }

    private suspend fun waitForNetworkOr(timeoutMs: Long) {
        withTimeoutOrNull(timeoutMs) { networkChanged.receive() }
    }

    /** Chạy một phiên MQTT; trả về khi IP Tailscale đổi (broker nhúng) hoặc khi bị huỷ. */
    private suspend fun runMqttSession(uri: String, username: String?, password: String?, watchHost: String?) =
        coroutineScope {
            val wrapper = MqttClientWrapper(
                config = MqttConfig(
                    serverUri = uri,
                    clientId = "oncam-node-${config.deviceId}",
                    username = username,
                    password = password,
                    keepAliveSec = timing.keepAliveSec,
                    maxReconnectDelayMs = timing.maxReconnectDelayMs,
                    will = statusMessage(online = false),
                ),
                scope = this,
                log = log,
            )
            mqtt = wrapper
            val jobs = listOfNotNull(
                launch { trackConnection(wrapper) },
                launch { wrapper.messages.collect { onMessage(wrapper, it) } },
                launch { heartbeat(wrapper) },
                camera?.let { cam -> launch { publishCameraChanges(wrapper, cam) } },
            )
            wrapper.subscribe(cmdTopic, Topics.QOS_COMMAND) // ghi nhớ, tự áp dụng mỗi lần kết nối
            wrapper.start()
            try {
                if (watchHost == null) {
                    awaitCancellation()
                } else {
                    while (true) {
                        waitForNetworkOr(timing.addressCheckMs)
                        val current = addressProvider()
                        if (current != watchHost) {
                            log.log(LogSink.Level.INFO, "IP Tailscale đổi: $watchHost → $current", null)
                            break
                        }
                    }
                }
            } finally {
                withContext(NonCancellable) {
                    if (mqtt === wrapper) mqtt = null
                    wrapper.stop()
                    // Chờ các tác vụ của phiên cũ dừng hẳn rồi mới đặt lại trạng thái,
                    // tránh màn hình còn báo "Đã kết nối" của phiên cũ.
                    jobs.forEach { it.cancelAndJoin() }
                    _state.update { it.copy(mqtt = MqttConnectionState.Idle) }
                }
            }
        }

    private suspend fun trackConnection(wrapper: MqttClientWrapper) {
        var wasConnected = false
        wrapper.state.collect { s ->
            val dropped = wasConnected &&
                (s is MqttConnectionState.Disconnected || s == MqttConnectionState.Connecting)
            wasConnected = s == MqttConnectionState.Connected
            _state.update { it.copy(mqtt = s, disconnects = it.disconnects + if (dropped) 1 else 0) }
            if (s == MqttConnectionState.Connected) wrapper.publish(statusMessage(online = true))
        }
    }

    private suspend fun onMessage(wrapper: MqttClientWrapper, msg: IncomingMessage) {
        if (msg.topic != cmdTopic) return
        val handled = handler.handle(msg.payload)
        wrapper.publish(
            OutgoingMessage(
                topic = Topics.event(config.deviceId),
                payload = ProtocolJson.encodeEvent(handled.event),
                qos = Topics.QOS_EVENT,
                retained = false,
            ),
        )
        _state.update {
            it.copy(
                lastCommand = CommandRecord(
                    action = handled.command?.action,
                    success = handled.event.success == true,
                    message = handled.event.message,
                    atSec = clock.nowSec(),
                ),
            )
        }
        handled.event.commandId?.let { id -> pending.remove(id)?.complete(handled.event) }
    }

    /** Camera đổi trạng thái → publish trạng thái mới; sự kiện camera → publish lên topic event. */
    private suspend fun publishCameraChanges(wrapper: MqttClientWrapper, cam: CameraSession) = coroutineScope {
        launch {
            cam.status
                .map { listOf(it.state, it.facing, it.flashOn, it.quality, it.streamUrl, it.clients, it.error) }
                .distinctUntilChanged()
                .drop(1) // trạng thái ban đầu đã gửi khi kết nối
                .collect {
                    if (wrapper.state.value == MqttConnectionState.Connected) wrapper.publish(statusMessage(online = true))
                }
        }
        launch {
            cam.events.collect { event ->
                wrapper.publish(
                    OutgoingMessage(Topics.event(config.deviceId), ProtocolJson.encodeEvent(event), Topics.QOS_EVENT, false),
                )
            }
        }
    }

    /** Gửi một lệnh đã ký tới chính máy này qua broker (nút "Bật camera thử" trên máy camera). */
    suspend fun sendLocalCommand(action: Action, params: CommandParams = CommandParams()): Boolean {
        val wrapper = mqtt ?: return false
        if (wrapper.state.value != MqttConnectionState.Connected) return false
        val cmd = signer.sign(config.deviceId, action, params)
        return wrapper.publish(OutgoingMessage(cmdTopic, ProtocolJson.encodeCommand(cmd), Topics.QOS_COMMAND, false))
    }

    /** Tắt camera ngay trên máy (nút trên màn hình / notification), không cần qua broker. */
    suspend fun stopCamera() {
        camera?.stopStreaming(CameraCodes.STOP_ON_DEVICE)
    }

    /** Khung JPEG mới nhất để xem thử trên máy camera. */
    fun latestFrame(): ByteArray? = camera?.latestFrame()

    private suspend fun heartbeat(wrapper: MqttClientWrapper) {
        while (true) {
            delay(timing.heartbeatMs)
            if (wrapper.state.value == MqttConnectionState.Connected) {
                wrapper.publish(statusMessage(online = true))
            } else {
                _state.update { it.copy(device = deviceInfo.snapshot()) }
            }
        }
    }

    private fun statusMessage(online: Boolean): OutgoingMessage {
        val now = clock.nowSec()
        val status = if (online) {
            val d = deviceInfo.snapshot()
            _state.update { it.copy(device = d) }
            val cam = camera?.status?.value ?: CameraStatus()
            DeviceStatus(
                deviceId = config.deviceId,
                displayName = config.displayName,
                online = true,
                cameraState = cam.state,
                currentCamera = cam.facing,
                flashOn = cam.flashOn,
                quality = cam.quality,
                streamUrl = cam.streamUrl,
                error = cam.error,
                viewers = cam.clients,
                cameraReady = camera?.cameraReady(),
                rtc = camera?.rtcAvailable() == true,
                micReady = camera?.micReady(),
                batteryPercent = d.batteryPercent,
                charging = d.charging,
                temperatureC = d.temperatureC,
                network = d.network,
                lastSeen = now,
            )
        } else {
            DeviceStatus.offline(config.deviceId, config.displayName, now)
        }
        return OutgoingMessage(
            topic = Topics.status(config.deviceId),
            payload = ProtocolJson.encodeStatus(status),
            qos = Topics.QOS_STATUS,
            retained = true,
        )
    }
}
