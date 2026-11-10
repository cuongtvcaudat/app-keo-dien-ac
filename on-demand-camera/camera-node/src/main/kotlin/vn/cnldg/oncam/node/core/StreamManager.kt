package vn.cnldg.oncam.node.core

import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
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
import kotlinx.coroutines.withTimeoutOrNull
import vn.cnldg.oncam.node.camera.FrameHub
import vn.cnldg.oncam.node.camera.MjpegStreamer
import vn.cnldg.oncam.node.camera.RecordingServer
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
import vn.cnldg.oncam.shared.protocol.MotionStatus
import vn.cnldg.oncam.shared.protocol.Recordings
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
 * @param guestAuth xác thực luồng hình của khách "chỉ xem" (0.13): chỉ xem MJPEG/ảnh chụp, không tải được video quay.
 * @param recordings thư mục video (0.10); null → không quay được. Đang quay được tính là người xem (camera không
 *   tự tắt); tắt/đổi camera thì clip đang quay được đóng lại trước. Quay xong → mở cổng tải video 10 phút.
 *
 * - SET_MOTION (0.11): canh chuyển động — camera bật liên tục (không tự tắt, STOP_STREAM bị từ chối với
 *   MOTION_ARMED), [MotionDetector] xem kênh Y; có chuyển động → quay clip, dừng sau [Settings.motionClipMs] kể từ
 *   lần chuyển động cuối (tối đa [Settings.motionMaxClipSec] giây/clip, còn chuyển động thì quay clip tiếp).
 *   Camera bị tắt vì lý do khác (quá nhiệt, lỗi, nút trên máy camera) → tự tắt canh, báo MOTION_STOPPED.
 *   Mỗi clip do chuyển động bắt đầu → gọi [onMotionClip] (tên file) để gửi cảnh báo.
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
    private val recordings: RecordingStore? = null,
    private val audioAllowed: () -> Boolean = { rtc?.micReady() == true },
    private val onMotionClip: (String?) -> Unit = {},
    private val guestAuth: StreamAuth? = null,
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
        val recordingPort: Int = vn.cnldg.oncam.shared.protocol.Recordings.PORT,
        val recordingServerOpenMs: Long = 10 * 60_000L,
        val recordingFinishWaitMs: Long = 5_000L,
        val motionClipMs: Long = 30_000L,
        val motionMaxClipSec: Int = 120,
        val motionIntervalMs: Long = 250L,
        val motionWarmupFrames: Int = 4,
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

    @Volatile
    private var recordingDone: CompletableDeferred<Unit>? = null

    private val detector = MotionDetector(minIntervalMs = settings.motionIntervalMs, warmupFrames = settings.motionWarmupFrames)

    @Volatile
    private var motionArmed = false

    @Volatile
    private var lastMotionMs = 0L

    /** Clip đang quay là do chuyển động (tự dừng khi hết chuyển động); clip bấm tay thì không tự dừng. */
    private var motionClip = false
    private var motionJob: Job? = null
    private val motionSignal = Channel<Unit>(Channel.CONFLATED)

    /** Gọi trên luồng camera cho mỗi khung khi đang canh. */
    private val lumaSink: LumaSink = { y, stride, w, h ->
        if (detector.analyze(y, stride, w, h, monoMs())?.motion == true) {
            lastMotionMs = monoMs()
            motionSignal.trySend(Unit)
        }
    }

    private val serverLock = Any()
    private var recordingServer: RecordingServer? = null
    private var recordingServerHost: String? = null
    private var recordingServerCloseJob: Job? = null

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

    override fun recordingSupported(): Boolean = recordings != null

    override fun latestFrame(): ByteArray? = hub?.latest()

    /** Đang canh chuyển động (camera bật liên tục). */
    fun motionArmed(): Boolean = motionArmed

    /** Số người đang xem luồng MJPEG (không tính WebRTC). */
    fun mjpegClients(): Int = server?.clientCount ?: 0

    override suspend fun perform(command: Command, guest: Boolean): ActionOutcome = mutex.withLock {
        val p = command.params
        when (command.action) {
            Action.START_STREAM -> startLocked(p)
            Action.STOP_STREAM -> when {
                _status.value.state == CameraState.OFF -> ActionOutcome.Done(CameraCodes.ALREADY_OFF)
                // App Xem rời màn hình / bấm Tắt camera: camera vẫn phải chạy để canh → tắt canh trước.
                motionArmed -> ActionOutcome.Failed(CameraCodes.MOTION_ARMED)
                else -> {
                    stopLocked(EventType.CAMERA_STOPPED, CameraCodes.STOP_BY_VIEWER)
                    ActionOutcome.Done()
                }
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
            Action.START_RECORDING -> {
                motionClip = false // bấm tay: không tự dừng khi hết chuyển động
                startRecordingLocked(p)
            }
            Action.STOP_RECORDING -> {
                motionClip = false
                stopRecordingLocked()
                ActionOutcome.Done(_status.value.recording.lastFile)
            }
            Action.OPEN_RECORDINGS -> openRecordingServer()?.let { ActionOutcome.Done(it) }
                ?: ActionOutcome.Failed(if (recordings == null) CameraCodes.RECORDING_UNSUPPORTED else CameraCodes.NO_TAILSCALE)
            Action.SET_MOTION -> motionLocked(p)
            Action.RTC_OFFER -> rtcOfferLocked(p, audio = !guest)
            Action.RTC_HANGUP -> {
                rtc?.hangup(checkNotNull(p.sessionId))
                ActionOutcome.Done()
            }
        }
    }

    override suspend fun stopStreaming(reason: String) = mutex.withLock {
        if (_status.value.state != CameraState.OFF) stopLocked(EventType.CAMERA_STOPPED, reason)
    }

    override suspend fun shutdown() {
        stopStreaming(CameraCodes.STOP_SHUTDOWN)
        closeRecordingServer()
    }

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

        val srv = MjpegStreamer(h, settings.httpPort, auth, newHub, settings.maxClients, log, guestAuth)
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

    private suspend fun rtcOfferLocked(p: CommandParams, audio: Boolean): ActionOutcome {
        val port = rtc ?: return ActionOutcome.Unsupported
        if (_status.value.state != CameraState.STREAMING) {
            val started = startLocked(p.copy(sdp = null, sessionId = null))
            if (started is ActionOutcome.Failed) return started
        }
        idleReset = true
        return when (val a = port.answer(checkNotNull(p.sessionId), checkNotNull(p.sdp), _status.value.quality, audio)) {
            is RtcAnswer.Ok -> ActionOutcome.Done(a.sdp)
            is RtcAnswer.Failed -> ActionOutcome.Failed(a.code)
        }
    }

    private suspend fun startRecordingLocked(p: CommandParams): ActionOutcome {
        val store = recordings ?: return ActionOutcome.Failed(CameraCodes.RECORDING_UNSUPPORTED)
        if (_status.value.recording.active) return ActionOutcome.Done()
        if (_status.value.state != CameraState.STREAMING) {
            val started = startLocked(p.copy(maxSeconds = null))
            if (started is ActionOutcome.Failed) return started
        }
        val file = store.newFile()
        val maxSec = p.maxSeconds ?: Recordings.DEFAULT_MAX_SECONDS
        val audio = audioAllowed()
        val done = CompletableDeferred<Unit>()
        recordingDone = done
        val ok = camera.startRecording(file, maxSec * 1000L, audio) { outcome -> onRecordingFinished(outcome, done) }
        if (!ok) {
            recordingDone = null
            _status.update { it.copy(recording = it.recording.copy(active = false, error = CameraCodes.RECORDING_FAILED)) }
            return ActionOutcome.Failed(CameraCodes.RECORDING_FAILED)
        }
        _status.update {
            it.copy(
                recording = it.recording.copy(active = true, startedAt = clock.nowSec(), maxSeconds = maxSec, audio = audio, error = null),
            )
        }
        idleReset = true
        // Thêm luồng quay làm CameraX mở lại camera → đèn tắt; bật lại nếu đang bật.
        if (_status.value.flashOn) camera.setTorch(true)
        log.log(LogSink.Level.INFO, "Bắt đầu quay ${file.name} (tối đa ${maxSec}s, tiếng: $audio)", null)
        return ActionOutcome.Done(file.name)
    }

    /** Dừng clip đang quay (nếu có) và chờ file ghi xong tối đa [Settings.recordingFinishWaitMs]. */
    private suspend fun stopRecordingLocked() {
        if (!_status.value.recording.active) return
        val done = recordingDone
        camera.stopRecording()
        if (done != null) withTimeoutOrNull(settings.recordingFinishWaitMs) { done.await() }
        if (_status.value.recording.active) {
            // Camera không báo ghi xong kịp: vẫn coi là đã dừng (file có thể thiếu phần cuối).
            log.log(LogSink.Level.WARN, "Không nhận được báo ghi xong video", null)
            _status.update { it.copy(recording = it.recording.copy(active = false, startedAt = null)) }
        }
    }

    /** Gọi từ luồng camera khi file ghi xong; không lấy mutex (stopRecordingLocked có thể đang chờ ở đây). */
    private fun onRecordingFinished(outcome: RecordingOutcome, done: CompletableDeferred<Unit>) {
        when (outcome) {
            is RecordingOutcome.Saved -> {
                recordings?.enforceLimit(keep = outcome.file)
                _status.update {
                    it.copy(
                        recording = it.recording.copy(
                            active = false,
                            startedAt = null,
                            audio = outcome.audio,
                            lastFile = outcome.file.name,
                            lastFileBytes = outcome.bytes,
                            error = null,
                        ),
                    )
                }
                log.log(LogSink.Level.INFO, "Đã lưu ${outcome.file.name} (${outcome.bytes} byte)", null)
                // Mở cổng tải để app Xem lấy clip ngay.
                scope.launch(Dispatchers.IO) { openRecordingServer() }
            }
            is RecordingOutcome.Failed -> _status.update {
                it.copy(recording = it.recording.copy(active = false, startedAt = null, error = outcome.code))
            }
        }
        if (recordingDone === done) recordingDone = null
        done.complete(Unit)
    }

    private suspend fun motionLocked(p: CommandParams): ActionOutcome {
        if (recordings == null) return ActionOutcome.Failed(CameraCodes.RECORDING_UNSUPPORTED)
        p.sensitivity?.let { s ->
            detector.sensitivity = s
            _status.update { it.copy(motion = it.motion.copy(sensitivity = s)) }
        }
        if (p.motion != true) {
            disarmLocked(error = null)
            return ActionOutcome.Done()
        }
        if (motionArmed) return ActionOutcome.Done()
        if (_status.value.state != CameraState.STREAMING) {
            // Canh ở mức Thấp (640×480, 10 hình/giây) cho đỡ nóng máy; video quay vẫn là HD.
            val started = startLocked(p.copy(motion = null, sensitivity = null, quality = p.quality ?: StreamQuality.LOW))
            if (started is ActionOutcome.Failed) return started
        }
        prepareMotionRecording()
        detector.sensitivity = _status.value.motion.sensitivity
        detector.reset()
        motionClip = false
        motionArmed = true
        camera.setLumaSink(lumaSink)
        _status.update {
            it.copy(motion = it.motion.copy(armed = true, error = null, clipSeconds = (settings.motionClipMs / 1000).toInt()))
        }
        motionJob = scope.launch { motionLoop() }
        log.log(LogSink.Level.INFO, "Bắt đầu canh chuyển động (độ nhạy ${_status.value.motion.sensitivity})", null)
        return ActionOutcome.Done()
    }

    /** Gắn sẵn bộ quay để khi có chuyển động là quay ngay (gắn thêm luồng làm tắt đèn → bật lại). */
    private suspend fun prepareMotionRecording() {
        if (camera.prepareRecording() && _status.value.flashOn) camera.setTorch(true)
    }

    /** Tắt canh; [error] = lý do tự tắt (null khi người dùng tắt). Clip đang quay do chuyển động được đóng lại. */
    private suspend fun disarmLocked(error: String?) {
        val wasArmed = motionArmed
        motionArmed = false
        camera.setLumaSink(null)
        val job = motionJob
        motionJob = null
        if (job != null && job != currentCoroutineContext()[Job]) job.cancel()
        if (motionClip) {
            motionClip = false
            stopRecordingLocked()
        }
        if (wasArmed || error == null) {
            _status.update { it.copy(motion = it.motion.copy(armed = false, error = error)) }
        }
        if (wasArmed) {
            idleReset = true // không ai xem thì camera tự tắt sau thời gian chờ như thường
            log.log(LogSink.Level.INFO, "Tắt canh chuyển động${error?.let { " ($it)" }.orEmpty()}", null)
        }
    }

    /** Nhận tín hiệu chuyển động từ luồng camera: quay clip, hết chuyển động [Settings.motionClipMs] thì dừng. */
    private suspend fun motionLoop() {
        while (currentCoroutineContext().isActive) {
            val signalled = withTimeoutOrNull(settings.checkIntervalMs) { motionSignal.receive() } != null
            mutex.withLock {
                if (!motionArmed || _status.value.state != CameraState.STREAMING) return
                val recording = _status.value.recording.active
                if (signalled) {
                    val nowSec = clock.nowSec()
                    val last = _status.value.motion.lastMotionAt
                    // Ghi lúc bắt đầu clip, hoặc mỗi 15 giây khi còn chuyển động (không gửi trạng thái 4 lần/giây).
                    if (!recording || last == null || nowSec - last >= MOTION_STATUS_EVERY_SEC) {
                        _status.update { it.copy(motion = it.motion.copy(lastMotionAt = nowSec)) }
                    }
                    if (!recording) {
                        val started = startRecordingLocked(CommandParams(maxSeconds = settings.motionMaxClipSec))
                        motionClip = started is ActionOutcome.Done
                        if (started is ActionOutcome.Done) {
                            log.log(LogSink.Level.INFO, "Có chuyển động → quay clip", null)
                            onMotionClip(started.message) // cảnh báo (0.12): AlertManager tự giới hạn tần suất
                        }
                    }
                } else if (motionClip && recording && monoMs() - lastMotionMs >= settings.motionClipMs) {
                    stopRecordingLocked()
                    motionClip = false
                }
                if (!_status.value.recording.active) motionClip = false
            }
        }
    }

    private fun monoMs(): Long = System.nanoTime() / 1_000_000

    /**
     * Mở (hoặc gia hạn) cổng tải video trên IP Tailscale trong [Settings.recordingServerOpenMs].
     * @return địa chỉ gốc (http://ip:port), null nếu không quay được hoặc chưa có IP Tailscale.
     */
    private fun openRecordingServer(): String? {
        val store = recordings ?: return null
        val h = addressProvider() ?: return null
        synchronized(serverLock) {
            if (recordingServer == null || recordingServerHost != h) {
                recordingServer?.stopServer()
                val srv = RecordingServer(h, settings.recordingPort, auth, store, log)
                try {
                    srv.startServer()
                } catch (e: IOException) {
                    log.log(LogSink.Level.WARN, "Không mở được cổng tải video tại $h:${settings.recordingPort}", e)
                    recordingServer = null
                    return null
                }
                recordingServer = srv
                recordingServerHost = h
            }
            recordingServerCloseJob?.cancel()
            recordingServerCloseJob = scope.launch {
                delay(settings.recordingServerOpenMs)
                closeRecordingServer()
            }
            return "http://$h:${settings.recordingPort}"
        }
    }

    private fun closeRecordingServer() = synchronized(serverLock) {
        recordingServerCloseJob?.cancel()
        recordingServerCloseJob = null
        recordingServer?.stopServer()
        recordingServer = null
        recordingServerHost = null
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
            if (motionArmed) disarmLocked(CameraCodes.MOTION_STOPPED)
            stopRecordingLocked()
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
        // Mở lại camera (đổi camera/chất lượng) làm đứt clip đang quay → đóng clip cho gọn trước.
        stopRecordingLocked()
        motionClip = false
        detector.reset() // độ phân giải/camera đổi → học lại nền
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
                if (motionArmed) prepareMotionRecording()
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
            val recordingNow = if (_status.value.recording.active) 1 else 0
            val clients = (server?.clientCount ?: 0) + (rtc?.sessions?.value ?: 0) + recordingNow
            _status.update { if (it.clients != clients) it.copy(clients = clients) else it }

            if (idleReset || clients > 0 || motionArmed) {
                idleReset = false
                idleSinceMs = if (clients > 0 || motionArmed) null else nowMs
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

    private companion object {
        const val MOTION_STATUS_EVERY_SEC = 15L
    }
}
