package vn.cnldg.oncam.rtc

import android.content.Context
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.audio.JavaAudioDeviceModule
import vn.cnldg.oncam.shared.LogSink

/**
 * Bộ máy WebRTC của một app: thư viện native, bộ mã hoá/giải mã, micro/loa.
 *
 * Tạo một lần cho cả vòng đời service (camera) hoặc app (máy xem) rồi [dispose].
 * [create] trả về null nếu máy không nạp được thư viện native (CPU lạ, thiếu bộ nhớ…) → app dùng MJPEG.
 */
class RtcEngine private constructor(
    val factory: PeerConnectionFactory,
    /** Ngữ cảnh OpenGL dùng chung để giải mã phần cứng và vẽ video (chỉ có ở máy xem). */
    val eglBase: EglBase?,
    private val audio: JavaAudioDeviceModule,
) {
    /** Tắt/mở micro ở tầng thiết bị (vẫn giữ phiên). */
    fun setMicrophoneMute(mute: Boolean) = audio.setMicrophoneMute(mute)

    /** Giải phóng toàn bộ (gọi sau khi đã đóng mọi phiên). */
    fun dispose() {
        factory.dispose()
        audio.release()
        eglBase?.release()
    }

    companion object {
        @Volatile
        private var initialized = false

        /**
         * @param forViewer true ở app Xem: tạo EGL để giải mã phần cứng và vẽ thẳng lên màn hình.
         *   Máy camera đưa khung NV21 từ CameraX vào nên không cần EGL.
         */
        fun create(context: Context, log: LogSink, forViewer: Boolean): RtcEngine? = try {
            val app = context.applicationContext
            synchronized(this) {
                if (!initialized) {
                    PeerConnectionFactory.initialize(
                        PeerConnectionFactory.InitializationOptions.builder(app).createInitializationOptions(),
                    )
                    initialized = true
                }
            }
            val egl = if (forViewer) EglBase.create() else null
            val audio = JavaAudioDeviceModule.builder(app)
                // Khử vọng/lọc ồn phần cứng nếu máy có; không có thì WebRTC tự làm bằng phần mềm.
                .setUseHardwareAcousticEchoCanceler(JavaAudioDeviceModule.isBuiltInAcousticEchoCancelerSupported())
                .setUseHardwareNoiseSuppressor(JavaAudioDeviceModule.isBuiltInNoiseSuppressorSupported())
                .setAudioRecordErrorCallback(RecordErrors(log))
                .setAudioTrackErrorCallback(PlayErrors(log))
                .createAudioDeviceModule()
            val factory = PeerConnectionFactory.builder()
                .setAudioDeviceModule(audio)
                // Ưu tiên mã hoá/giải mã phần cứng (H.264/VP8), không có thì dùng phần mềm (VP8).
                .setVideoEncoderFactory(DefaultVideoEncoderFactory(egl?.eglBaseContext, true, true))
                .setVideoDecoderFactory(DefaultVideoDecoderFactory(egl?.eglBaseContext))
                .createPeerConnectionFactory()
            RtcEngine(factory, egl, audio)
        } catch (e: UnsatisfiedLinkError) {
            log.log(LogSink.Level.WARN, "Không nạp được thư viện WebRTC, dùng MJPEG", e)
            null
        } catch (e: RuntimeException) {
            log.log(LogSink.Level.WARN, "Không khởi tạo được WebRTC, dùng MJPEG", e)
            null
        }

        /**
         * Cấu hình phiên: không STUN/TURN (2 máy thấy nhau qua IP Tailscale), chỉ UDP, gom ứng viên một lần
         * rồi gửi cả SDP (không trickle) → bắt tay đúng 1 lượt lệnh/kết quả qua MQTT.
         */
        internal fun rtcConfiguration() = PeerConnection.RTCConfiguration(emptyList()).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.DISABLED
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_ONCE
            keyType = PeerConnection.KeyType.ECDSA
            // Máy camera cũ dễ nóng: cho WebRTC tự hạ độ phân giải khi CPU quá tải.
            enableCpuOveruseDetection = true
        }
    }

    private class RecordErrors(private val log: LogSink) : JavaAudioDeviceModule.AudioRecordErrorCallback {
        // Tham số kiểu String? : thư viện Java có thể truyền null.
        override fun onWebRtcAudioRecordInitError(message: String?) = warn("Micro: $message")

        override fun onWebRtcAudioRecordStartError(code: JavaAudioDeviceModule.AudioRecordStartErrorCode?, message: String?) =
            warn("Micro ($code): $message")

        override fun onWebRtcAudioRecordError(message: String?) = warn("Micro: $message")

        private fun warn(m: String) = log.log(LogSink.Level.WARN, m, null)
    }

    private class PlayErrors(private val log: LogSink) : JavaAudioDeviceModule.AudioTrackErrorCallback {
        override fun onWebRtcAudioTrackInitError(message: String?) = warn("Loa: $message")

        override fun onWebRtcAudioTrackStartError(code: JavaAudioDeviceModule.AudioTrackStartErrorCode?, message: String?) =
            warn("Loa ($code): $message")

        override fun onWebRtcAudioTrackError(message: String?) = warn("Loa: $message")

        private fun warn(m: String) = log.log(LogSink.Level.WARN, m, null)
    }
}
