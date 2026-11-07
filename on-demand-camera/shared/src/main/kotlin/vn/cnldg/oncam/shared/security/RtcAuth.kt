package vn.cnldg.oncam.shared.security

import vn.cnldg.oncam.shared.protocol.DeviceIds

/**
 * Ký/kiểm SDP answer của phiên WebRTC (Phase 7).
 *
 * Offer của app Xem đã nằm trong lệnh RTC_OFFER có chữ ký; camera ký lại answer bằng cùng token, gắn với đúng
 * mã lệnh. Vì SDP chứa dấu vân tay chứng chỉ DTLS của mỗi bên, 2 chữ ký này giúp mã hoá đầu-cuối của WebRTC
 * xác thực được đúng máy camera ↔ đúng máy xem, kể cả khi ai đó đọc/ghi được broker (broker riêng bị lộ mật khẩu).
 */
class RtcAuth(secret: String, private val deviceId: String) {
    private val hmac = Hmac(secret)

    init {
        DeviceIds.requireValid(deviceId)
    }

    /** Chữ ký của camera cho answer [sdp] trả lời lệnh [commandId]. */
    fun signAnswer(commandId: String, sdp: String): String = hmac.hex(canonical(commandId, sdp))

    /** Answer có đúng do camera giữ token này ký cho lệnh [commandId] không. */
    fun verifyAnswer(commandId: String, sdp: String, signature: String?): Boolean =
        signature != null && hmac.matches(canonical(commandId, sdp), signature)

    private fun canonical(commandId: String, sdp: String) = "$PREFIX|$deviceId|$commandId|${sha256Hex(sdp)}"

    private companion object {
        const val PREFIX = "oncam-rtc-answer-v1"
    }
}
