package vn.cnldg.oncam.rtc

import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.MediaConstraints
import org.webrtc.MediaStreamTrack
import org.webrtc.NV21Buffer
import org.webrtc.PeerConnection
import org.webrtc.RtpTransceiver
import org.webrtc.SessionDescription
import org.webrtc.VideoFrame
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import vn.cnldg.oncam.shared.LogSink
import vn.cnldg.oncam.shared.protocol.StreamQuality
import vn.cnldg.oncam.shared.rtc.CodecPreference
import vn.cnldg.oncam.shared.rtc.SdpCandidates

/** Kết quả trả lời một SDP offer. */
sealed interface CameraRtcAnswer {
    /** SDP answer (đã lọc ứng viên) để gửi lại app Xem. */
    data class Ok(val sdp: String) : CameraRtcAnswer

    /** Không mở được phiên; [code] là mã lỗi gửi về app Xem. */
    data class Failed(val code: String) : CameraRtcAnswer
}

/**
 * Phía máy camera của WebRTC: trả lời offer của app Xem, gửi hình (khung NV21 từ CameraX) + tiếng micro,
 * phát tiếng app Xem nói ra loa.
 *
 * - Tối đa [maxSessions] phiên cùng lúc; mọi phiên dùng chung một nguồn hình/tiếng (mã hoá riêng từng phiên).
 * - Phiên tự đóng khi kết nối hỏng (FAILED) hoặc mất quá [disconnectGraceMs].
 * - Khung hình chỉ được đưa vào khi có ít nhất 1 phiên ([onFrame] gần như không tốn gì khi không ai xem).
 *
 * @param keepCandidate giữ ứng viên ICE nào (thật: chỉ IP Tailscale; test trên emulator: mọi địa chỉ).
 * @param micAllowed micro dùng được không (quyền RECORD_AUDIO + service có loại microphone).
 */
class CameraRtc(
    private val engine: RtcEngine,
    private val scope: CoroutineScope,
    private val keepCandidate: (String) -> Boolean,
    private val micAllowed: () -> Boolean,
    private val audioRouting: AudioRouting? = null,
    private val log: LogSink = LogSink.NONE,
    private val maxSessions: Int = 2,
    private val disconnectGraceMs: Long = 10_000,
    private val answerTimeoutMs: Long = 10_000,
) {
    private class Session(val id: String, val pc: PeerConnection, val watcher: PeerWatcher, val withAudio: Boolean) {
        var watchJob: Job? = null
    }

    private val videoSource: VideoSource = engine.factory.createVideoSource(false)
    private val videoTrack: VideoTrack = engine.factory.createVideoTrack(VIDEO_TRACK_ID, videoSource)
    private var audioSource: AudioSource? = null
    private var audioTrack: AudioTrack? = null
    private val sessions = ConcurrentHashMap<String, Session>()
    private val _count = MutableStateFlow(0)

    /** Số phiên WebRTC đang mở (tính là người xem để camera không tự tắt). */
    val sessionCount: StateFlow<Int> = _count.asStateFlow()

    @Volatile
    private var quality: StreamQuality = StreamQuality.MEDIUM

    init {
        videoSource.capturerObserver.onCapturerStarted(true)
    }

    /**
     * Đưa một khung hình (NV21, đã xoay đúng chiều) vào WebRTC. Gọi từ luồng camera.
     * [nv21] không được sửa sau khi gọi (WebRTC giữ tham chiếu tới khi mã hoá xong).
     */
    fun onFrame(nv21: ByteArray, width: Int, height: Int, timestampNs: Long) {
        if (sessions.isEmpty()) return
        val frame = VideoFrame(NV21Buffer(nv21, width, height, null), 0, timestampNs)
        videoSource.capturerObserver.onFrameCaptured(frame)
        frame.release()
    }

    /** Trả lời [offerSdp] của phiên [sessionId] ở chất lượng [quality]. Camera phải đang phát. */
    suspend fun answer(sessionId: String, offerSdp: String, quality: StreamQuality): CameraRtcAnswer {
        sessions[sessionId]?.let { close(it) } // app Xem gửi lại cùng phiên (vd. kết nối lại) → thay phiên cũ
        if (sessions.size >= maxSessions) return CameraRtcAnswer.Failed(CODE_BUSY)
        this.quality = quality

        val watcher = PeerWatcher()
        val pc = engine.factory.createPeerConnection(RtcEngine.rtcConfiguration(), watcher)
            ?: return CameraRtcAnswer.Failed(CODE_FAILED)
        val withAudio = micAllowed()
        return try {
            val sdp = withTimeout(answerTimeoutMs) {
                pc.setRemoteAwait(SessionDescription(SessionDescription.Type.OFFER, SdpCandidates.filter(offerSdp, keepCandidate)))
                attachTracks(pc, withAudio)
                pc.setLocalAwait(pc.createAnswerAwait())
                withTimeoutOrNull(RtcEngine.GATHER_MAX_MS) { watcher.gathered.await() }
                pc.localDescription?.description ?: throw RtcException("Không có SDP cục bộ")
            }
            val filtered = SdpCandidates.filter(sdp, keepCandidate)
            if (SdpCandidates.addresses(filtered).isEmpty()) {
                // Máy camera chưa có IP Tailscale mà WebRTC thấy được → app Xem quay về MJPEG.
                val all = SdpCandidates.addresses(sdp).distinct()
                log.log(LogSink.Level.WARN, "WebRTC: máy camera không có địa chỉ Tailscale, chỉ có $all", null)
                pc.dispose()
                return CameraRtcAnswer.Failed("$CODE_NO_ADDRESS:${all.joinToString(" ").ifEmpty { "-" }}")
            }
            applyQuality(pc, quality)
            val session = Session(sessionId, pc, watcher, withAudio)
            sessions[sessionId] = session
            _count.value = sessions.size
            if (withAudio) audioRouting?.acquire()
            session.watchJob = scope.launch { watch(session) }
            log.log(LogSink.Level.INFO, "Mở phiên WebRTC $sessionId (tiếng: $withAudio)", null)
            CameraRtcAnswer.Ok(filtered)
        } catch (e: TimeoutCancellationException) {
            log.log(LogSink.Level.WARN, "Trả lời offer WebRTC quá ${answerTimeoutMs} ms", e)
            pc.dispose()
            CameraRtcAnswer.Failed(CODE_FAILED)
        } catch (e: CancellationException) {
            pc.dispose()
            throw e
        } catch (e: Exception) {
            log.log(LogSink.Level.WARN, "Không trả lời được offer WebRTC", e)
            pc.dispose()
            CameraRtcAnswer.Failed(CODE_FAILED)
        }
    }

    /** Đổi chất lượng (giới hạn bitrate/khung hình) cho mọi phiên đang mở. */
    fun setQuality(quality: StreamQuality) {
        this.quality = quality
        sessions.values.forEach { applyQuality(it.pc, quality) }
    }

    /** Đóng phiên [sessionId]; false nếu không có phiên đó. */
    fun hangup(sessionId: String): Boolean {
        val s = sessions[sessionId] ?: return false
        close(s)
        return true
    }

    /** Đóng mọi phiên (camera tắt). */
    fun closeAll() {
        sessions.values.toList().forEach(::close)
    }

    /** Giải phóng nguồn hình/tiếng (service dừng). */
    fun dispose() {
        closeAll()
        videoSource.capturerObserver.onCapturerStopped()
        videoTrack.dispose()
        videoSource.dispose()
        audioTrack?.dispose()
        audioSource?.dispose()
    }

    private fun attachTracks(pc: PeerConnection, withAudio: Boolean) {
        for (t in pc.transceivers) {
            when (t.mediaType) {
                MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO -> {
                    t.sender.setTrack(videoTrack, false)
                    t.setDirection(RtpTransceiver.RtpTransceiverDirection.SEND_ONLY)
                    preferHardwareCodec(t)
                }
                MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO -> {
                    if (withAudio) {
                        t.sender.setTrack(micTrack(), false)
                        // Hướng thật = giao của 2 bên: app Xem không bật nói → thành SEND_ONLY.
                        t.setDirection(RtpTransceiver.RtpTransceiverDirection.SEND_RECV)
                    } else {
                        t.setDirection(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY)
                    }
                }
                null -> Unit
            }
        }
    }

    /**
     * Đặt chuẩn nén có phần cứng lên đầu (H.264 → VP8 …): answer theo thứ tự này nên camera nén bằng chip riêng,
     * đỡ nóng hơn nén bằng CPU. Chỉ dùng codec có ở cả 2 chiều gửi/nhận (WebRTC từ chối danh sách khác).
     * Lỗi thì giữ thứ tự mặc định — không bao giờ làm hỏng phiên.
     */
    private fun preferHardwareCodec(t: RtpTransceiver) {
        runCatching {
            val video = MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO
            val sendNames = engine.factory.getRtpSenderCapabilities(video).codecs.map { it.name.uppercase() }.toSet()
            val caps = engine.factory.getRtpReceiverCapabilities(video).codecs
                .filter { it.name.uppercase() in sendNames || CodecPreference.isAuxiliary(it.name) }
            if (caps.isEmpty()) return
            val order = CodecPreference.order(caps.map { it.name }, engine.hardwareVideoEncoders)
            t.setCodecPreferences(order.map { caps[it] })
        }.onFailure { log.log(LogSink.Level.WARN, "Không đặt được thứ tự chuẩn nén, dùng mặc định", it) }
    }

    @Synchronized
    private fun micTrack(): AudioTrack = audioTrack ?: run {
        val constraints = MediaConstraints().apply {
            // Tự cân âm lượng để nghe rõ tiếng nhỏ ở xa; khử vọng khi đang phát tiếng app Xem ra loa.
            mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
        }
        val src = engine.factory.createAudioSource(constraints)
        audioSource = src
        engine.factory.createAudioTrack(AUDIO_TRACK_ID, src).also { audioTrack = it }
    }

    private fun applyQuality(pc: PeerConnection, quality: StreamQuality) {
        val sender = pc.senders.firstOrNull { it.track()?.kind() == MediaStreamTrack.VIDEO_TRACK_KIND } ?: return
        val params = sender.parameters
        params.encodings.forEach {
            it.maxBitrateBps = RtcQuality.maxBitrateBps(quality)
            it.maxFramerate = quality.fps
        }
        sender.setParameters(params)
    }

    private suspend fun watch(s: Session) {
        s.watcher.state.collect { st ->
            when (st) {
                PeerConnection.PeerConnectionState.FAILED, PeerConnection.PeerConnectionState.CLOSED -> close(s)
                // Mất sóng ngắn (đổi Wi-Fi ↔ 4G) thường tự nối lại; quá [disconnectGraceMs] mới đóng.
                PeerConnection.PeerConnectionState.DISCONNECTED -> scope.launch {
                    delay(disconnectGraceMs)
                    if (s.watcher.state.value == PeerConnection.PeerConnectionState.DISCONNECTED) close(s)
                }
                else -> Unit
            }
        }
    }

    /**
     * Đóng đồng bộ (pc.close() chờ luồng WebRTC xong) để [dispose] giải phóng factory an toàn ngay sau đó.
     * Chỉ gọi từ coroutine/luồng của app — không bao giờ từ callback của WebRTC ([PeerWatcher] chỉ đổi StateFlow,
     * việc đóng chạy trong coroutine [watch]).
     */
    private fun close(s: Session) {
        if (!sessions.remove(s.id, s)) return
        _count.value = sessions.size
        s.watchJob?.cancel()
        if (s.withAudio) audioRouting?.release()
        s.pc.close()
        s.pc.dispose()
        log.log(LogSink.Level.INFO, "Đóng phiên WebRTC ${s.id}", null)
    }

    companion object {
        /** Đã đủ số phiên WebRTC. */
        const val CODE_BUSY: String = "RTC_BUSY"

        /** Lỗi bắt tay/khởi tạo. */
        const val CODE_FAILED: String = "RTC_FAILED"

        /** WebRTC không thấy địa chỉ Tailscale nào của máy camera. */
        const val CODE_NO_ADDRESS: String = "RTC_NO_ADDRESS"

        private const val VIDEO_TRACK_ID = "oncam-video"
        private const val AUDIO_TRACK_ID = "oncam-mic"
    }
}

/** Giới hạn bitrate video WebRTC theo mức chất lượng (H.264/VP8 nén tốt hơn MJPEG ~10 lần). */
object RtcQuality {
    /** Bitrate tối đa (bit/giây). */
    fun maxBitrateBps(q: StreamQuality): Int = when (q) {
        StreamQuality.LOW -> 300_000
        StreamQuality.MEDIUM -> 900_000
        StreamQuality.HIGH -> 1_800_000
    }
}
