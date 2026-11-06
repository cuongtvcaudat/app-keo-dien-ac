package vn.cnldg.oncam.rtc

import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.resume
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.MediaConstraints
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.RTCStatsCollectorCallback
import org.webrtc.RtpTransceiver
import org.webrtc.SessionDescription
import org.webrtc.VideoFrame
import org.webrtc.VideoSink
import org.webrtc.VideoTrack
import vn.cnldg.oncam.shared.LogSink
import vn.cnldg.oncam.shared.rtc.RtcStatsSummary
import vn.cnldg.oncam.shared.rtc.SdpCandidates

/** Giai đoạn của một phiên xem WebRTC. */
enum class RtcPhase { IDLE, CONNECTING, CONNECTED, FAILED, CLOSED }

/**
 * Phía app Xem của WebRTC cho MỘT phiên: tạo offer, nhận answer (qua [connect]), hiện hình, nghe tiếng sân,
 * giữ để nói.
 *
 * Nói theo kiểu bộ đàm (bán song công): trong lúc giữ nút nói, tiếng từ sân bị tắt ở máy xem để không nghe
 * lại tiếng mình vọng qua loa → micro máy camera.
 *
 * @param talk true: xin gửi tiếng micro máy xem (cần quyền RECORD_AUDIO). false: chỉ nhận, máy xem không mở micro.
 */
class ViewerRtc(
    private val engine: RtcEngine,
    private val scope: CoroutineScope,
    private val keepCandidate: (String) -> Boolean,
    private val talk: Boolean,
    private val audioRouting: AudioRouting? = null,
    private val log: LogSink = LogSink.NONE,
    private val connectTimeoutMs: Long = 15_000,
    private val statsIntervalMs: Long = 2_000,
) {
    private val watcher = PeerWatcher()
    private var pc: PeerConnection? = null
    private var micSource: AudioSource? = null
    private var micTrack: AudioTrack? = null
    private var remoteAudio: AudioTrack? = null
    private var routed = false
    private var watchJob: Job? = null

    private val _phase = MutableStateFlow(RtcPhase.IDLE)

    /** Giai đoạn hiện tại. */
    val phase: StateFlow<RtcPhase> = _phase.asStateFlow()

    private val _video = MutableStateFlow<VideoTrack?>(null)

    /** Hình từ camera (giao diện gắn SurfaceViewRenderer vào), null khi chưa kết nối. */
    val video: StateFlow<VideoTrack?> = _video.asStateFlow()

    private val _stats = MutableStateFlow(RtcStatsSummary())

    /** Chuẩn nén, độ phân giải, kbit/s đang nhận (đo mỗi [statsIntervalMs]). */
    val stats: StateFlow<RtcStatsSummary> = _stats.asStateFlow()

    private var statsJob: Job? = null

    private val _fps = MutableStateFlow(0)

    /** Số khung hình nhận được trong 1 giây gần nhất. */
    val fps: StateFlow<Int> = _fps.asStateFlow()

    /** Camera có gửi tiếng không (micro máy camera dùng được). */
    @Volatile
    var hasRemoteAudio: Boolean = false
        private set

    @Volatile
    private var listening = true

    @Volatile
    private var talking = false

    private val fpsCounter = object : VideoSink {
        private var windowStartNs = 0L
        private var frames = 0

        override fun onFrame(frame: VideoFrame?) {
            val now = System.nanoTime()
            frames++
            if (now - windowStartNs >= 1_000_000_000L) {
                _fps.value = frames
                frames = 0
                windowStartNs = now
            }
        }
    }

    /**
     * Mở phiên: tạo offer (đã gom ứng viên, chỉ giữ địa chỉ thoả [keepCandidate]), gửi qua [signal]
     * (lệnh RTC_OFFER đã ký) và chờ kết nối xong.
     *
     * @param signal trả về SDP answer đã kiểm chữ ký, hoặc null nếu camera từ chối/không trả lời.
     * @return true nếu đã kết nối và đang nhận hình. false → gọi [close] rồi dùng MJPEG.
     */
    suspend fun connect(signal: suspend (offerSdp: String) -> String?): Boolean {
        _phase.value = RtcPhase.CONNECTING
        val peer = engine.factory.createPeerConnection(RtcEngine.rtcConfiguration(), watcher) ?: return fail("Không tạo được PeerConnection")
        pc = peer
        return try {
            peer.addTransceiver(
                MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO,
                RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY),
            )
            if (talk) {
                val src = engine.factory.createAudioSource(MediaConstraints())
                val track = engine.factory.createAudioTrack(MIC_TRACK_ID, src)
                track.setEnabled(false) // chỉ gửi tiếng khi giữ nút nói
                micSource = src
                micTrack = track
                peer.addTransceiver(track, RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.SEND_RECV))
            } else {
                peer.addTransceiver(
                    MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO,
                    RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY),
                )
            }

            val offer = withTimeout(connectTimeoutMs) {
                peer.setLocalAwait(peer.createOfferAwait())
                watcher.gathered.await()
                peer.localDescription?.description ?: throw RtcException("Không có SDP cục bộ")
            }
            val filteredOffer = SdpCandidates.filter(offer, keepCandidate)
            if (SdpCandidates.addresses(filteredOffer).isEmpty()) return fail("Máy xem chưa có IP Tailscale")

            val answer = signal(filteredOffer) ?: return fail("Camera không trả lời offer")
            peer.setRemoteAwait(SessionDescription(SessionDescription.Type.ANSWER, SdpCandidates.filter(answer, keepCandidate)))
            bindRemoteTracks(peer)
            val reached = withTimeout(connectTimeoutMs) {
                watcher.state.first {
                    it == PeerConnection.PeerConnectionState.CONNECTED || it == PeerConnection.PeerConnectionState.FAILED
                }
            }
            if (reached != PeerConnection.PeerConnectionState.CONNECTED) return fail("Không nối được tới camera qua Tailscale")
            _phase.value = RtcPhase.CONNECTED
            if (hasRemoteAudio || talk) {
                audioRouting?.acquire()
                routed = true
            }
            watchJob = scope.launch { watch() }
            statsJob = scope.launch { pollStats(peer) }
            true
        } catch (_: TimeoutCancellationException) {
            fail("Quá thời gian chờ kết nối")
        } catch (e: CancellationException) {
            throw e // người dùng rời màn hình: chủ phiên sẽ gọi close()
        } catch (e: Exception) {
            log.log(LogSink.Level.WARN, "Kết nối WebRTC lỗi", e)
            fail(e.message ?: e.javaClass.simpleName)
        }
    }

    /** Bật/tắt nghe tiếng từ sân (không ảnh hưởng camera). */
    fun setListening(on: Boolean) {
        listening = on
        applyVolume()
    }

    /** Đang giữ nút nói: gửi tiếng micro máy xem, tạm tắt tiếng từ sân. */
    fun setTalking(on: Boolean) {
        val mic = micTrack ?: return
        talking = on
        mic.setEnabled(on)
        applyVolume()
    }

    /**
     * Đóng phiên và giải phóng ngay (đồng bộ, vài ms). Huỷ coroutine đang chạy [connect] trước khi gọi.
     * Không gọi từ callback của WebRTC.
     */
    fun close() {
        if (_phase.value == RtcPhase.CLOSED) return
        _phase.value = RtcPhase.CLOSED
        watchJob?.cancel()
        watchJob = null
        statsJob?.cancel()
        statsJob = null
        _video.value?.let { v -> runCatching { v.removeSink(fpsCounter) } }
        _video.value = null
        if (routed) {
            audioRouting?.release()
            routed = false
        }
        val peer = pc
        pc = null
        val track = micTrack
        val src = micSource
        micTrack = null
        micSource = null
        remoteAudio = null
        peer?.close()
        peer?.dispose()
        track?.dispose()
        src?.dispose()
    }

    private fun bindRemoteTracks(peer: PeerConnection) {
        for (t in peer.transceivers) {
            when (val track = t.receiver.track()) {
                is VideoTrack -> {
                    track.addSink(fpsCounter)
                    _video.value = track
                }
                is AudioTrack -> {
                    remoteAudio = track
                    // Hướng đã thoả thuận cho biết camera có gửi tiếng (micro máy camera dùng được) hay không.
                    val dir = t.currentDirection
                    hasRemoteAudio = dir == RtpTransceiver.RtpTransceiverDirection.RECV_ONLY ||
                        dir == RtpTransceiver.RtpTransceiverDirection.SEND_RECV
                }
                else -> Unit
            }
        }
        applyVolume()
    }

    private fun applyVolume() {
        remoteAudio?.setVolume(if (listening && !talking) 1.0 else 0.0)
    }

    private suspend fun watch() {
        watcher.state.first {
            it == PeerConnection.PeerConnectionState.FAILED || it == PeerConnection.PeerConnectionState.CLOSED
        }
        if (_phase.value == RtcPhase.CONNECTED) {
            log.log(LogSink.Level.WARN, "Phiên WebRTC bị ngắt", null)
            _phase.value = RtcPhase.FAILED
        }
    }

    private suspend fun pollStats(peer: PeerConnection) {
        var last: RtcStatsSummary? = null
        var lastMs = System.currentTimeMillis()
        while (true) {
            delay(statsIntervalMs)
            val report = withTimeoutOrNull(STATS_TIMEOUT_MS) {
                suspendCancellableCoroutine { cont -> peer.getStats(RTCStatsCollectorCallback { r -> cont.resume(r) }) }
            } ?: continue
            val now = System.currentTimeMillis()
            val flat = report.statsMap.mapValues { (_, st) -> st.type to st.members }
            val summary = RtcStatsSummary.from(flat, last, now - lastMs)
            _stats.value = summary
            last = summary
            lastMs = now
        }
    }

    private fun fail(reason: String): Boolean {
        log.log(LogSink.Level.WARN, "WebRTC không dùng được: $reason", null)
        _phase.value = RtcPhase.FAILED
        return false
    }

    private companion object {
        const val MIC_TRACK_ID = "oncam-viewer-mic"
        const val STATS_TIMEOUT_MS = 1_500L
    }
}
