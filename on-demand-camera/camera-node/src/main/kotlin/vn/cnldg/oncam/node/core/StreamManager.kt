package vn.cnldg.oncam.node.core

import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import vn.cnldg.oncam.node.camera.FrameHub
import vn.cnldg.oncam.node.camera.MjpegStreamer
import vn.cnldg.oncam.node.mqtt.ActionOutcome
import vn.cnldg.oncam.shared.LogSink
import vn.cnldg.oncam.shared.OnCam
import vn.cnldg.oncam.shared.protocol.Action
import vn.cnldg.oncam.shared.protocol.CameraCodes
import vn.cnldg.oncam.shared.protocol.CameraFacing
import vn.cnldg.oncam.shared.protocol.CameraState
import vn.cnldg.oncam.shared.protocol.Command
import vn.cnldg.oncam.shared.protocol.CommandParams
import vn.cnldg.oncam.shared.protocol.DeviceEvent
import vn.cnldg.oncam.shared.protocol.EventType
import vn.cnldg.oncam.shared.protocol.StreamPaths
import vn.cnldg.oncam.shared.protocol.StreamQuality
import vn.cnldg.oncam.shared.security.EpochClock
import vn.cnldg.oncam.shared.security.StreamAuth

/**
 * Bật/tắt camera và luồng MJPEG theo lệnh (Phase 3).
 *
 * - START_STREAM: mở camera + HTTP server trên IP Tailscale, publish CAMERA_STARTED.
 * - STOP_STREAM / nút trên máy: đóng server, NHẢ camera hoàn toàn, publish CAMERA_STOPPED.
 * - Tự tắt khi không có ai xem liên tục [CameraStatus.idleTimeoutSec] giây → IDLE_TIMEOUT.
 * - Nhiệt độ pin ≥ [Settings.lowerQualityAtC] → hạ 1 mức chất lượng; ≥ [Settings.stopAtC] → tắt (THERMAL_THROTTLE).
 * - IP Tailscale đổi khi đang phát → tắt (server đang bind IP cũ).
 *
 * - RTC_OFFER (Phase 7): bật camera nếu đang tắt rồi mở phiên WebRTC qua [rtc]; phiên WebRTC được tính là
 *   người xem. Camera tắt → đóng mọi phiên. MJPEG vẫn chạy song song (ảnh chụp, máy xem bản cũ).
 *
 * Các lệnh được xử lý tuần tự (Mutex), kể cả khi vòng giám sát tự tắt camera cùng lúc.
 *
 * @param cameraAllowed false nếu service không có loại foreground "camera" (bị khởi động lại từ nền).
 * @param rtc phiên WebRTC; null nếu máy không chạy được WebRTC → RTC_OFFER trả UNSUPPORTED.
 */
class StreamManager(
    private val camera: CameraPort,
    private val auth: StreamAuth,
    private val scope: CoroutineScope,
    private val addressProvider: () -> String?,
    private val cameraAllowed: () -> Boolean,
    private val deviceInfo: DeviceInfoSource,
    private val clock: EpochClock = EpochClock.SYSTEM,
    private val log: LogSink = LogSink.NONE,
    private val settings: Settings = Settings(),
    private val rtc: RtcPort? = null,
) : CameraSession {

    /**
     * Thông số vận hành.
     * @property msPerIdleSecond số mili-giây cho mỗi "giây chờ" (1000; test đặt nhỏ để chạy nhanh).
     */
    data class Settings(
        val httpPort: Int = OnCam.DEFAULT_HTTP_PORT,
        val maxClients: Int = 3,
        val checkIntervalMs: Long = 1_000,
        val msPerIdleSecond: Long = 1_000,
        val thermalCheckMs: Long = 10_000,
        val thermalLowerCooldownMs: Long = 60_000,
        val lowerQualityAtC: Double = 42.0,
        val stopAtC: Double = 45.0,
    )

    private val mutex = Mutex()
    private val _status = MutableStateFlow(CameraStatus())
    override val status: StateFlow<CameraStatus> = _status.asStateFlow()

    private val _events = MutableSharedFlow<DeviceEvent>(extraBufferCapacity = 16)
    override val events: Flow<DeviceEvent> = _events.asSharedFlow()

    private var server: MjpegStreamer? = null
    private var hub: FrameHub? = null
    private var host: String? = null
    private var monitorJob: Job? = null

    @Volatile
    private var idleReset = false

    init {
        scope.launch {
            camera.errors.collect { code ->
                mutex.withLock {
                    if (_status.value.state != CameraState.OFF) {
                        log.log(LogSink.Level.WARN, "Camera báo lỗi: $code", null)
                        stopLocked(EventType.ERROR, code, finalState = CameraState.ERROR, error = code)
                    }
                }
            }
        }
    }

    override fun cameraReady(): Boolean = cameraAllowed()

    override fun rtcAvailable(): Boolean = rtc != null

    override fun micReady(): Boolean = rtc?.micReady() == true

    override fun latestFrame(): ByteArray? = hub?.latest()

    /** Số người đang xem luồng MJPEG (không tính WebRTC). */
    fun mjpegClients(): Int = server?.clientCount ?: 0

    override suspend fun perform(command: Command): ActionOutcome = mutex.withLock {
        val p = command.params
        when (command.action) {
            Action.START_STREAM -> startLocked(p)
            Action.STOP_STREAM -> if (_status.value.state == CameraState.OFF) {
                ActionOutcome.Done(CameraCodes.ALREADY_OFF)
            } else {
                stopLocked(EventType.CAMERA_STOPPED, CameraCodes.STOP_BY_VIEWER)
                ActionOutcome.Done()
            }
            Action.SWITCH_CAMERA -> switchLocked(p.camera)
            Action.SET_FLASH -> flashLocked(checkNotNull(p.flash))
            Action.SET_QUALITY -> qualityLocked(checkNotNull(p.quality))
            Action.SNAPSHOT -> when {
                _status.value.state != CameraState.STREAMING -> ActionOutcome.Failed(CameraCodes.CAMERA_OFF)
                hub?.latest() == null -> ActionOutcome.Failed(CameraCodes.NO_FRAME)
                else -> ActionOutcome.Done(StreamPaths.SNAPSHOT)
            }
            Action.PING -> ActionOutcome.Done() // CommandHandler đã trả lời PING trước khi tới đây
            Action.UPDATE_LOCATION -> ActionOutcome.Unsupported // NodeController xử lý (không liên quan camera)
            Action.RTC_OFFER -> rtcOfferLocked(p)
            Action.RTC_HANGUP -> {
                rtc?.hangup(checkNotNull(p.sessionId))
                ActionOutcome.Done()
            }
        }
    }

    override suspend fun stopStreaming(reason: String) = mutex.withLock {
        if (_status.value.state != CameraState.OFF) stopLocked(EventType.CAMERA_STOPPED, reason)
    }

    override suspend fun shutdown() = stopStreaming(CameraCodes.STOP_SHUTDOWN)

    // --- bên dưới: chỉ gọi khi đang giữ mutex ---

    private suspend fun startLocked(p: CommandParams): ActionOutcome {
        _status.update {
            it.copy(
                facing = p.camera ?: it.facing,
                quality = p.quality ?: it.quality,
                idleTimeoutSec = p.idleTimeoutSec ?: it.idleTimeoutSec,
            )
        }
        val current = _status.value
        if (current.state == CameraState.STREAMING) {
            // Đang phát: áp dụng tham số mới (nếu có) và tính lại thời gian chờ.
            idleReset = true
            if (p.camera != null || p.quality != null) reopenLocked()?.let { return it }
            p.flash?.let { flashLocked(it) }
            return ActionOutcome.Done(_status.value.streamUrl)
        }

        if (!cameraAllowed()) return failStart(CameraCodes.NEEDS_APP_OPEN)
        val h = addressProvider() ?: return failStart(CameraCodes.NO_TAILSCALE)

        _status.update { it.copy(state = CameraState.STARTING, error = null, flashOn = false) }
        val newHub = FrameHub()
        when (val opened = camera.open(current.facing, current.quality, newHub::publish)) {
            is OpenResult.Failed -> {
                camera.close()
                return failStart(opened.reason, CameraState.ERROR)
            }
            is OpenResult.Opened -> Unit
        }

        val srv = MjpegStreamer(h, settings.httpPort, auth, newHub, settings.maxClients, log)
        try {
            withContext(Dispatchers.IO) { srv.startServer() }
        } catch (e: IOException) {
            log.log(LogSink.Level.WARN, "Không mở được HTTP stream tại $h:${settings.httpPort}", e)
            newHub.close()
            camera.close()
            return failStart(CameraCodes.HTTP_BIND_FAILED, CameraState.ERROR)
        }

        server = srv
        hub = newHub
        host = h
        val url = "http://$h:${settings.httpPort}${StreamPaths.STREAM}"
        _status.update { it.copy(state = CameraState.STREAMING, streamUrl = url, clients = 0, error = null) }
        idleReset = true
        monitorJob = scope.launch { monitor() }
        emit(EventType.CAMERA_STARTED, url)
        p.flash?.takeIf { it }?.let { flashLocked(true) }
        return ActionOutcome.Done(url)
    }

    private suspend fun rtcOfferLocked(p: CommandParams): ActionOutcome {
        val port = rtc ?: return ActionOutcome.Unsupported
        if (_status.value.state != CameraState.STREAMING) {
            val started = startLocked(p.copy(sdp = null, sessionId = null))
            if (started is ActionOutcome.Failed) return started
        }
        idleReset = true
        return when (val a = port.answer(checkNotNull(p.sessionId), checkNotNull(p.sdp), _status.value.quality)) {
            is RtcAnswer.Ok -> ActionOutcome.Done(a.sdp)
            is RtcAnswer.Failed -> ActionOutcome.Failed(a.code)
        }
    }

    private fun failStart(code: String, state: CameraState = CameraState.OFF): ActionOutcome {
        _status.update { it.copy(state = state, error = code, streamUrl = null, flashOn = false) }
        emit(EventType.ERROR, code)
        return ActionOutcome.Failed(code)
    }

    private suspend fun stopLocked(
        type: EventType,
        message: String,
        finalState: CameraState = CameraState.OFF,
        error: String? = null,
    ) {
        val job = monitorJob
        monitorJob = null
        if (job != null && job != currentCoroutineContext()[Job]) job.cancel()
        val srv = server
        server = null
        hub = null
        host = null
        withContext(NonCancellable) {
            rtc?.closeAll()
            srv?.let { withContext(Dispatchers.IO) { it.stopServer() } }
            camera.close()
        }
        _status.update {
            it.copy(state = finalState, streamUrl = null, clients = 0, flashOn = false, error = error)
        }
        emit(type, message)
    }

    private suspend fun switchLocked(target: CameraFacing?): ActionOutcome {
        val facing = target ?: if (_status.value.facing == CameraFacing.BACK) CameraFacing.FRONT else CameraFacing.BACK
        if (!camera.hasCamera(facing)) return ActionOutcome.Failed(CameraCodes.NO_CAMERA)
        _status.update { it.copy(facing = facing) }
        if (_status.value.state == CameraState.STREAMING) reopenLocked()?.let { return it }
        return ActionOutcome.Done(facing.name)
    }

    private suspend fun qualityLocked(quality: StreamQuality): ActionOutcome {
        _status.update { it.copy(quality = quality) }
        rtc?.setQuality(quality)
        if (_status.value.state == CameraState.STREAMING) reopenLocked()?.let { return it }
        return ActionOutcome.Done(quality.name)
    }

    private suspend fun flashLocked(on: Boolean): ActionOutcome {
        if (_status.value.state != CameraState.STREAMING) return ActionOutcome.Failed(CameraCodes.CAMERA_OFF)
        if (!on) {
            camera.setTorch(false)
            _status.update { it.copy(flashOn = false) }
            return ActionOutcome.Done()
        }
        if (!camera.setTorch(true)) return ActionOutcome.Failed(CameraCodes.NO_FLASH)
        _status.update { it.copy(flashOn = true) }
        return ActionOutcome.Done()
    }

    /**
     * Mở lại camera với hướng/chất lượng hiện tại, giữ nguyên HTTP server và người xem.
     * @return null nếu thành công, ngược lại là kết quả thất bại (camera đã được tắt hẳn).
     */
    private suspend fun reopenLocked(): ActionOutcome? {
        val currentHub = hub ?: return ActionOutcome.Failed(CameraCodes.CAMERA_OFF)
        val wasFlash = _status.value.flashOn
        val s = _status.value
        return when (val opened = camera.open(s.facing, s.quality, currentHub::publish)) {
            is OpenResult.Failed -> {
                stopLocked(EventType.ERROR, opened.reason, CameraState.ERROR, opened.reason)
                ActionOutcome.Failed(opened.reason)
            }
            is OpenResult.Opened -> {
                // Mở lại camera làm tắt đèn; bật lại nếu camera mới có flash.
                val flash = wasFlash && opened.hasFlash && camera.setTorch(true)
                _status.update { it.copy(flashOn = flash) }
                null
            }
        }
    }

    /** Vòng giám sát chạy trong lúc phát: người xem, thời gian chờ, IP, nhiệt độ. */
    private suspend fun monitor() {
        var idleSinceMs: Long? = null
        var lastThermalMs = 0L
        var lastLoweredMs = Long.MIN_VALUE / 2
        while (currentCoroutineContext().isActive) {
            delay(settings.checkIntervalMs)
            val nowMs = System.nanoTime() / 1_000_000
            val clients = (server?.clientCount ?: 0) + (rtc?.sessions?.value ?: 0)
            _status.update { if (it.clients != clients) it.copy(clients = clients) else it }

            if (idleReset || clients > 0) {
                idleReset = false
                idleSinceMs = if (clients > 0) null else nowMs
            } else if (idleSinceMs == null) {
                idleSinceMs = nowMs
            } else if (nowMs - idleSinceMs >= _status.value.idleTimeoutSec * settings.msPerIdleSecond) {
                stopFromMonitor(EventType.IDLE_TIMEOUT, "${_status.value.idleTimeoutSec}s")
                return
            }

            if (addressProvider() != host) {
                stopFromMonitor(EventType.ERROR, CameraCodes.ADDRESS_CHANGED, CameraCodes.ADDRESS_CHANGED)
                return
            }

            if (nowMs - lastThermalMs >= settings.thermalCheckMs) {
                lastThermalMs = nowMs
                val temp = deviceInfo.snapshot().temperatureC ?: continue
                if (temp >= settings.stopAtC) {
                    stopFromMonitor(EventType.THERMAL_THROTTLE, "${CameraCodes.THERMAL_STOPPED}:${format(temp)}")
                    return
                }
                if (temp >= settings.lowerQualityAtC && nowMs - lastLoweredMs >= settings.thermalLowerCooldownMs) {
                    val lowered = lower(_status.value.quality)
                    if (lowered != null) {
                        lastLoweredMs = nowMs
                        mutex.withLock {
                            if (_status.value.state == CameraState.STREAMING) {
                                _status.update { it.copy(quality = lowered) }
                                rtc?.setQuality(lowered)
                                reopenLocked()
                                emit(EventType.THERMAL_THROTTLE, "${CameraCodes.THERMAL_LOWERED}:${lowered.name}:${format(temp)}")
                            }
                        }
                    }
                }
            }
        }
    }

    private suspend fun stopFromMonitor(type: EventType, message: String, error: String? = null) {
        mutex.withLock {
            if (_status.value.state == CameraState.STREAMING) {
                stopLocked(type, message, error = error)
            }
        }
    }

    private fun emit(type: EventType, message: String?) {
        _events.tryEmit(DeviceEvent(event = type, message = message, timestamp = clock.nowSec()))
    }

    private fun lower(q: StreamQuality): StreamQuality? = when (q) {
        StreamQuality.HIGH -> StreamQuality.MEDIUM
        StreamQuality.MEDIUM -> StreamQuality.LOW
        StreamQuality.LOW -> null
    }

    private fun format(c: Double) = String.format(java.util.Locale.US, "%.1f", c)
}
