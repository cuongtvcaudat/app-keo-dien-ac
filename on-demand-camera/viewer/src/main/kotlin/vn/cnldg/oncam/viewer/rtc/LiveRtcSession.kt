package vn.cnldg.oncam.viewer.rtc

import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.webrtc.EglBase
import org.webrtc.VideoTrack
import vn.cnldg.oncam.rtc.RtcPhase
import vn.cnldg.oncam.rtc.ViewerRtc
import vn.cnldg.oncam.shared.LogSink
import vn.cnldg.oncam.shared.client.CameraClient
import vn.cnldg.oncam.shared.client.CommandResult
import vn.cnldg.oncam.shared.client.RtcAnswerResult
import vn.cnldg.oncam.shared.rtc.RtcStatsSummary
import vn.cnldg.oncam.shared.rtc.TailscaleAddress

/** Đường truyền đang dùng ở màn hình xem trực tiếp. */
enum class LiveTransport { NONE, CONNECTING, WEBRTC, MJPEG }

/**
 * Trạng thái phần WebRTC của màn hình xem trực tiếp.
 *
 * @property hasAudio camera có gửi tiếng (micro máy camera dùng được).
 * @property talkEnabled phiên đang mở có kèm micro máy xem (bấm "Bật nói chuyện").
 * @property fallbackCode lý do đang xem bằng MJPEG (mã lỗi / CommandResult), null nếu không lỗi.
 * @property stats chuẩn nén, độ phân giải, kbit/s của phiên WebRTC (để biết có chạy phần cứng, tốn bao nhiêu dữ liệu).
 */
data class LiveRtcState(
    val transport: LiveTransport = LiveTransport.NONE,
    val video: VideoTrack? = null,
    val fps: Int = 0,
    val hasAudio: Boolean = false,
    val listening: Boolean = false, // mặc định tắt tiếng sân; người xem bấm "Bật nghe" khi cần
    val talkEnabled: Boolean = false,
    val talking: Boolean = false,
    val fallbackCode: String? = null,
    val stats: RtcStatsSummary = RtcStatsSummary(),
)

/**
 * Một phiên xem trực tiếp qua WebRTC cho màn hình Live: bắt tay qua [CameraClient.rtcOffer] (lệnh đã ký,
 * answer kiểm chữ ký), theo dõi đứt kết nối, điều khiển nghe/nói.
 */
class LiveRtcSession(
    private val provider: RtcProvider,
    private val scope: CoroutineScope,
    private val log: LogSink,
) {
    private val _state = MutableStateFlow(LiveRtcState())

    /** Trạng thái hiện tại. */
    val state: StateFlow<LiveRtcState> = _state.asStateFlow()

    /** Ngữ cảnh OpenGL để giao diện vẽ video. */
    val eglContext: EglBase.Context? get() = provider.engine?.eglBase?.eglBaseContext

    /** Máy này chạy được WebRTC không. */
    val available: Boolean get() = provider.engine != null

    private var rtc: ViewerRtc? = null
    private var client: CameraClient? = null
    private var sessionId: String? = null
    private val jobs = mutableListOf<Job>()

    /**
     * Kết nối WebRTC tới camera của [client].
     * @param idleTimeoutSec thời gian chờ tự tắt gửi kèm (camera tự bật nếu đang tắt).
     * @param onLost gọi khi phiên đang chạy bị đứt (để màn hình thử lại hoặc chuyển MJPEG).
     * @return true nếu đã có hình; false → đã dọn dẹp, [LiveRtcState.fallbackCode] cho biết lý do.
     */
    suspend fun connect(client: CameraClient, idleTimeoutSec: Int, onLost: () -> Unit): Boolean {
        stop()
        val engine = provider.engine ?: return fallback(CODE_NO_ENGINE)
        val talk = _state.value.talkEnabled
        val id = UUID.randomUUID().toString()
        val v = ViewerRtc(engine, scope, TailscaleAddress::isTailscale, talk, provider.audioRouting, log)
        rtc = v
        this.client = client
        sessionId = id
        _state.update { it.copy(transport = LiveTransport.CONNECTING, fallbackCode = null, video = null, fps = 0) }

        var failure: String? = null
        val ok = v.connect { offer ->
            when (val r = client.rtcOffer(id, offer, idleTimeoutSec)) {
                is RtcAnswerResult.Answer -> r.sdp
                is RtcAnswerResult.Failed -> {
                    failure = codeOf(r.result)
                    null
                }
            }
        }
        if (!ok) {
            // Mã lỗi từ camera (từ chối/hết giờ) nếu có, không thì chi tiết bước hỏng ở máy xem (để chẩn đoán).
            val detail = failure ?: v.failureDetail ?: CODE_CONNECT_FAILED
            stop()
            return fallback(detail)
        }
        v.setListening(_state.value.listening)
        _state.update {
            it.copy(transport = LiveTransport.WEBRTC, video = v.video.value, hasAudio = v.hasRemoteAudio, talking = false)
        }
        jobs += scope.launch { v.fps.collect { f -> _state.update { it.copy(fps = f) } } }
        jobs += scope.launch { v.stats.collect { st -> _state.update { it.copy(stats = st) } } }
        jobs += scope.launch {
            v.phase.first { it == RtcPhase.FAILED }
            log.log(LogSink.Level.WARN, "Phiên WebRTC bị đứt", null)
            stop()
            onLost()
        }
        return true
    }

    /** Đóng phiên (rời màn hình / ẩn app): báo camera đóng phiên rồi giải phóng. */
    fun stop() {
        jobs.forEach { it.cancel() }
        jobs.clear()
        val v = rtc
        val c = client
        val id = sessionId
        rtc = null
        client = null
        sessionId = null
        _state.update {
            it.copy(transport = LiveTransport.NONE, video = null, fps = 0, talking = false, stats = RtcStatsSummary())
        }
        v?.close()
        if (c != null && id != null) scope.launch { c.rtcHangup(id) }
    }

    /** Đánh dấu đang xem bằng MJPEG (không có WebRTC). */
    fun markMjpeg(code: String?) = _state.update { it.copy(transport = LiveTransport.MJPEG, fallbackCode = code) }

    /** Bật/tắt nghe tiếng sân. */
    fun setListening(on: Boolean) {
        _state.update { it.copy(listening = on) }
        rtc?.setListening(on)
    }

    /** Ghi nhớ có kèm micro máy xem cho phiên sau (màn hình phải kết nối lại để áp dụng). */
    fun setTalkEnabled(on: Boolean) = _state.update { it.copy(talkEnabled = on, talking = false) }

    /** Đang giữ nút nói. */
    fun setTalking(on: Boolean) {
        val v = rtc ?: return
        if (!_state.value.talkEnabled) return
        v.setTalking(on)
        _state.update { it.copy(talking = on) }
    }

    private fun fallback(code: String): Boolean {
        _state.update { it.copy(transport = LiveTransport.MJPEG, fallbackCode = code, video = null, fps = 0) }
        return false
    }

    private fun codeOf(r: CommandResult): String = when (r) {
        is CommandResult.Rejected -> r.code ?: CODE_CONNECT_FAILED
        is CommandResult.Ok -> CODE_CONNECT_FAILED
        CommandResult.NotConnected -> "NOT_CONNECTED"
        CommandResult.PublishFailed -> "PUBLISH_FAILED"
        CommandResult.Timeout -> "TIMEOUT"
    }

    companion object {
        /** Máy xem không chạy được WebRTC. */
        const val CODE_NO_ENGINE: String = "RTC_NO_ENGINE"

        /** Không nối được (thường do Tailscale chặn UDP hoặc mạng đổi). */
        const val CODE_CONNECT_FAILED: String = "RTC_CONNECT_FAILED"
    }
}
