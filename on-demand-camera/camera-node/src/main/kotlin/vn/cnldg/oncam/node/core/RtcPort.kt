package vn.cnldg.oncam.node.core

import kotlinx.coroutines.flow.StateFlow
import vn.cnldg.oncam.shared.protocol.StreamQuality

/** Kết quả trả lời SDP offer. */
sealed interface RtcAnswer {
    /** SDP answer gửi lại app Xem (CommandHandler sẽ ký). */
    data class Ok(val sdp: String) : RtcAnswer

    /** Không mở được phiên, [code] gửi về app Xem. */
    data class Failed(val code: String) : RtcAnswer
}

/**
 * Phiên WebRTC (Phase 7) mà [StreamManager] điều khiển. Cài đặt Android: `vn.cnldg.oncam.node.rtc.NodeRtc`
 * (bọc `CameraRtc` của module :rtc). Tách interface để test logic bật/tắt camera trên JVM bằng bản giả.
 */
interface RtcPort {
    /** Số phiên đang mở — tính là người xem (camera không tự tắt khi còn phiên). */
    val sessions: StateFlow<Int>

    /** Micro dùng được để gửi tiếng sân. */
    fun micReady(): Boolean

    /**
     * Trả lời offer của phiên [sessionId]; chỉ gọi khi camera đang phát.
     * @param audio false = phiên chỉ có hình (khách "chỉ xem"): không gửi tiếng sân, không nhận tiếng app Xem.
     */
    suspend fun answer(sessionId: String, offerSdp: String, quality: StreamQuality, audio: Boolean = true): RtcAnswer

    /** Đổi chất lượng cho mọi phiên. */
    fun setQuality(quality: StreamQuality)

    /** Đóng một phiên; false nếu không có. */
    fun hangup(sessionId: String): Boolean

    /** Đóng mọi phiên (camera tắt). */
    fun closeAll()
}
