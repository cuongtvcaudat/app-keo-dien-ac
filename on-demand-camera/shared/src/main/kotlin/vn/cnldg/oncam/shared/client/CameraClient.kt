package vn.cnldg.oncam.shared.client

import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
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
import vn.cnldg.oncam.shared.protocol.DeviceAlert
import vn.cnldg.oncam.shared.protocol.DeviceEvent
import vn.cnldg.oncam.shared.protocol.DeviceStatus
import vn.cnldg.oncam.shared.protocol.EventType
import vn.cnldg.oncam.shared.protocol.GeoLocation
import vn.cnldg.oncam.shared.protocol.MotionSensitivity
import vn.cnldg.oncam.shared.protocol.RecordingStatus
import vn.cnldg.oncam.shared.protocol.ProtocolJson
import vn.cnldg.oncam.shared.protocol.StreamPaths
import vn.cnldg.oncam.shared.protocol.Topics
import vn.cnldg.oncam.shared.security.AlertAuth
import vn.cnldg.oncam.shared.security.BrokerCredentials
import vn.cnldg.oncam.shared.security.CommandSigner
import vn.cnldg.oncam.shared.security.EpochClock
import vn.cnldg.oncam.shared.security.RtcAuth
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

/**
 * Độ lệch đồng hồ giữa máy camera và máy xem.
 * @property offsetMs giờ camera − giờ máy xem (ms); dương = đồng hồ camera chạy nhanh hơn.
 * @property roundTripMs thời gian khứ hồi của lần đo được chọn; sai số của [offsetMs] ≤ một nửa giá trị này.
 */
data class ClockSync(val offsetMs: Long, val roundTripMs: Long)

/** Kết quả gửi SDP offer cho camera (Phase 7). */
sealed interface RtcAnswerResult {
    /** Camera đồng ý; [sdp] là answer đã kiểm chữ ký. */
    data class Answer(val sdp: String) : RtcAnswerResult

    /** Không có answer dùng được: camera từ chối / bản cũ không hỗ trợ / sai chữ ký / hết giờ. */
    data class Failed(val result: CommandResult) : RtcAnswerResult
}

/** Trạng thái một camera nhìn từ viewer. */
data class CameraView(
    val connection: MqttConnectionState = MqttConnectionState.Idle,
    val status: DeviceStatus? = null,
    /** Thời điểm nhận trạng thái gần nhất (ms, đồng hồ máy viewer). */
    val statusReceivedAtMs: Long? = null,
    /**
     * Vị trí cuối cùng biết được của camera — giữ lại cả khi camera offline (mất mạng, bị tắt, mất trộm);
     * chỉ xoá khi camera đang online báo đã tắt chia sẻ vị trí.
     */
    val lastLocation: GeoLocation? = null,
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
    /** Vị trí cuối đã lưu từ lần trước (hiện ngay cả khi camera đang offline). */
    initialLocation: GeoLocation? = null,
) {
    private val signer = CommandSigner(endpoint.secret, clock)
    private val streamAuth = StreamAuth(endpoint.secret, endpoint.deviceId, clock)
    private val rtcAuth = RtcAuth(endpoint.secret, endpoint.deviceId)
    private val statusTopic = Topics.status(endpoint.deviceId)
    private val eventTopic = Topics.event(endpoint.deviceId)
    private val alertTopic = Topics.alert(endpoint.deviceId)
    private val alertAuth = AlertAuth(endpoint.secret, endpoint.deviceId)

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

    private val _view = MutableStateFlow(CameraView(lastLocation = initialLocation))

    /** Trạng thái hiện tại của camera. */
    val view: StateFlow<CameraView> = _view.asStateFlow()

    private val _events = MutableSharedFlow<DeviceEvent>(extraBufferCapacity = 16)

    /** Sự kiện từ camera (trừ COMMAND_RESULT đã được trả về qua [send]). */
    val events: SharedFlow<DeviceEvent> = _events.asSharedFlow()

    private val _alerts = MutableSharedFlow<DeviceAlert>(extraBufferCapacity = 8)

    /** Cảnh báo từ camera (0.12), đã kiểm chữ ký; bỏ cảnh báo cũ hơn [MAX_ALERT_AGE_MS] (gửi lại/kẻ phát lại). */
    val alerts: SharedFlow<DeviceAlert> = _alerts.asSharedFlow()

    private val _clock = MutableStateFlow<ClockSync?>(null)

    /** Kết quả đồng bộ giờ gần nhất (null khi chưa đo). */
    val clockSync: StateFlow<ClockSync?> = _clock.asStateFlow()

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
            mqtt.subscribe(alertTopic, Topics.QOS_ALERT)
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

    /** Yêu cầu camera lấy vị trí mới (trả lời ngay; vị trí mới đến qua trạng thái sau vài giây). */
    suspend fun updateLocation(): CommandResult = send(Action.UPDATE_LOCATION)

    /** Bắt đầu quay video trên máy camera (tự bật camera nếu đang tắt); kết quả Ok mang tên file. */
    suspend fun startRecording(maxSeconds: Int? = null): CommandResult =
        send(Action.START_RECORDING, CommandParams(maxSeconds = maxSeconds, idleTimeoutSec = null))

    /** Dừng quay; file hoàn tất sau ~1 giây, trạng thái [RecordingStatus.lastFile] đổi theo. */
    suspend fun stopRecording(): CommandResult = send(Action.STOP_RECORDING)

    /** Mở cổng tải video trên máy camera; trả về địa chỉ gốc (vd. http://100.x.y.z:8081) hoặc null nếu lỗi. */
    suspend fun openRecordings(): String? = (send(Action.OPEN_RECORDINGS) as? CommandResult.Ok)?.message

    /**
     * Bật/tắt canh chuyển động (0.11): camera chạy liên tục, có chuyển động thì tự quay clip.
     * [sensitivity] null = giữ độ nhạy đang dùng.
     */
    suspend fun setMotion(on: Boolean, sensitivity: MotionSensitivity? = null, idleTimeoutSec: Int? = null): CommandResult =
        send(Action.SET_MOTION, CommandParams(motion = on, sensitivity = sensitivity, idleTimeoutSec = idleTimeoutSec))

    /** Theo dõi vị trí 1 phút/lần trong [minutes] phút (0 = dừng). Chỉ camera có `location.canTrack` mới hiểu. */
    suspend fun trackLocation(minutes: Int): CommandResult =
        send(Action.UPDATE_LOCATION, CommandParams(trackMinutes = minutes))

    /** Gửi lệnh [action] đã ký và chờ kết quả tối đa [timeoutMs]. */
    suspend fun send(action: Action, params: CommandParams = CommandParams(), timeoutMs: Long = 10_000): CommandResult =
        when (val r = exchange(action, params, timeoutMs)) {
            is Exchange.Replied ->
                if (r.event.success == true) CommandResult.Ok(r.event.message) else CommandResult.Rejected(r.event.message)
            is Exchange.Failed -> r.result
        }

    /**
     * Đo độ lệch đồng hồ (giờ camera − giờ máy này, ms) bằng [samples] lần PING, lấy lần khứ hồi nhanh nhất
     * (cách tính của NTP: giờ camera lúc trả lời ≈ giữa lúc gửi và lúc nhận).
     * Dùng được cả khi lệch quá 60 giây (PING bị từ chối EXPIRED nhưng vẫn kèm giờ camera).
     *
     * @return độ lệch, hoặc null khi không đo được (chưa kết nối, camera bản cũ không gửi giờ ms).
     */
    suspend fun syncClock(samples: Int = 3, timeoutMs: Long = 5_000): ClockSync? {
        var best: ClockSync? = null
        for (i in 0 until samples) {
            val t0 = nowMs()
            val r = exchange(Action.PING, CommandParams(), timeoutMs)
            val t1 = nowMs()
            val cameraMs = (r as? Exchange.Replied)?.event?.timestampMs ?: continue
            val sample = ClockSync(offsetMs = cameraMs - (t0 + t1) / 2, roundTripMs = t1 - t0)
            if (best == null || sample.roundTripMs < best.roundTripMs) best = sample
        }
        if (best != null) _clock.value = best
        return best
    }

    /**
     * Gửi SDP offer (đã gom đủ ứng viên ICE) mở phiên WebRTC [sessionId]; camera tự bật nếu đang tắt.
     * Chỉ nhận answer có chữ ký đúng của camera ([RtcAuth]) — answer giả mạo bị coi như camera từ chối.
     */
    suspend fun rtcOffer(
        sessionId: String,
        offerSdp: String,
        idleTimeoutSec: Int? = null,
        timeoutMs: Long = 15_000,
    ): RtcAnswerResult {
        val params = CommandParams(sdp = offerSdp, sessionId = sessionId, idleTimeoutSec = idleTimeoutSec)
        return when (val r = exchange(Action.RTC_OFFER, params, timeoutMs)) {
            is Exchange.Failed -> RtcAnswerResult.Failed(r.result)
            is Exchange.Replied -> {
                val answer = r.event.message
                when {
                    r.event.success != true -> RtcAnswerResult.Failed(CommandResult.Rejected(answer))
                    answer.isNullOrBlank() || !rtcAuth.verifyAnswer(r.commandId, answer, r.event.signature) -> {
                        log.log(LogSink.Level.WARN, "Bỏ SDP answer sai chữ ký từ ${endpoint.deviceId}", null)
                        RtcAnswerResult.Failed(CommandResult.Rejected(BAD_ANSWER_SIGNATURE))
                    }
                    else -> RtcAnswerResult.Answer(answer)
                }
            }
        }
    }

    /** Báo camera đóng phiên WebRTC [sessionId] (rời màn hình xem). */
    suspend fun rtcHangup(sessionId: String, timeoutMs: Long = 5_000): CommandResult =
        send(Action.RTC_HANGUP, CommandParams(sessionId = sessionId), timeoutMs)

    private sealed interface Exchange {
        data class Replied(val commandId: String, val event: DeviceEvent) : Exchange
        data class Failed(val result: CommandResult) : Exchange
    }

    private suspend fun exchange(action: Action, params: CommandParams, timeoutMs: Long): Exchange {
        if (mqtt.state.value != MqttConnectionState.Connected) return Exchange.Failed(CommandResult.NotConnected)
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
            if (!sent) return Exchange.Failed(CommandResult.PublishFailed)
            val event = withTimeoutOrNull(timeoutMs) { waiter.await() } ?: return Exchange.Failed(CommandResult.Timeout)
            return Exchange.Replied(command.id, event)
        } finally {
            pending.remove(command.id)
        }
    }

    /** Mã lỗi khi answer không có chữ ký đúng (dùng trong [CommandResult.Rejected]). */
    companion object {
        const val BAD_ANSWER_SIGNATURE: String = "BAD_ANSWER_SIGNATURE"

        /** Cảnh báo cũ hơn chừng này (theo giờ 2 máy, lệch tối đa 60 giây) thì bỏ. */
        const val MAX_ALERT_AGE_MS: Long = 10 * 60_000L
        private const val SEEN_ALERTS = 32
    }

    /** URL ảnh chụp (khi camera đang phát), suy từ streamUrl trong trạng thái. */
    fun snapshotUrl(): String? =
        _view.value.status?.streamUrl?.let { it.removeSuffix(StreamPaths.STREAM) + StreamPaths.SNAPSHOT }

    /** Giá trị header xác thực cho request HTTP tới camera (stream/snapshot). */
    fun streamAuthHeader(): String = streamAuth.headerValue()

    private fun onMessage(msg: IncomingMessage) {
        when (msg.topic) {
            statusTopic -> ProtocolJson.decodeStatus(msg.payload)?.let { status ->
                _view.update {
                    val keep = status.location != null || !status.online
                    it.copy(
                        status = status,
                        statusReceivedAtMs = nowMs(),
                        lastLocation = status.location?.point ?: it.lastLocation.takeIf { keep },
                    )
                }
            }
            eventTopic -> ProtocolJson.decodeEvent(msg.payload)?.let { event ->
                val waiter = event.commandId?.let { pending[it] }
                if (event.event == EventType.COMMAND_RESULT) {
                    waiter?.complete(event)
                } else {
                    _events.tryEmit(event)
                }
            }
            alertTopic -> ProtocolJson.decodeAlert(msg.payload)?.let(::onAlert)
        }
    }

    private val seenAlerts = ArrayDeque<String>()

    private fun onAlert(alert: DeviceAlert) {
        if (!alertAuth.verify(alert)) {
            log.log(LogSink.Level.WARN, "Bỏ cảnh báo sai chữ ký từ ${endpoint.deviceId}", null)
            return
        }
        if (abs(nowMs() - alert.timestampMs) > MAX_ALERT_AGE_MS) return // cũ (gửi lại sau khi nối lại) hoặc phát lại
        val key = "${alert.type}/${alert.timestampMs}"
        synchronized(seenAlerts) {
            if (key in seenAlerts) return // QoS 1 có thể gửi trùng
            seenAlerts.addLast(key)
            if (seenAlerts.size > SEEN_ALERTS) seenAlerts.removeFirst()
        }
        _alerts.tryEmit(alert)
    }
}
