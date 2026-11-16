package vn.cnldg.oncam.shared.security

import vn.cnldg.oncam.shared.OnCam
import vn.cnldg.oncam.shared.protocol.DeviceIds

/**
 * Xác thực truy cập HTTP `/stream.mjpeg` và `/snapshot.jpg` (quyết định D6).
 *
 * Viewer gửi header [HEADER] = `"{timestamp}.{hmac}"`, với
 * hmac = HMAC-SHA256(secret, "oncam-stream-v1|{deviceId}|{timestamp}").
 * Token bí mật không bao giờ đi trên mạng; header chỉ dùng được trong ±[maxSkewSec] giây.
 */
class StreamAuth(
    secret: String,
    private val deviceId: String,
    private val clock: EpochClock = EpochClock.SYSTEM,
    private val maxSkewSec: Long = OnCam.MAX_CLOCK_SKEW_SEC,
) {
    private val hmac = Hmac(secret)

    init {
        DeviceIds.requireValid(deviceId)
    }

    /** Giá trị header cho một request mới (viewer). */
    fun headerValue(timestamp: Long = clock.nowSec()): String = "$timestamp.${hmac.hex(message(timestamp))}"

    /** Kiểm tra giá trị header nhận được (camera-node). */
    fun isValid(headerValue: String?): Boolean {
        if (headerValue == null) return false
        val dot = headerValue.indexOf('.')
        if (dot <= 0 || dot == headerValue.lastIndex) return false
        val timestamp = headerValue.substring(0, dot).toLongOrNull() ?: return false
        if (kotlin.math.abs(clock.nowSec() - timestamp) > maxSkewSec) return false
        return hmac.matches(message(timestamp), headerValue.substring(dot + 1))
    }

    private fun message(timestamp: Long) = "oncam-stream-v1|$deviceId|$timestamp"

    companion object {
        /** Tên header HTTP. */
        const val HEADER: String = "X-OnCam-Auth"
    }
}
