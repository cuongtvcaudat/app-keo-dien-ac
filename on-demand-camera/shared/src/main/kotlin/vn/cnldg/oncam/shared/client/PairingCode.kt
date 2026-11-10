package vn.cnldg.oncam.shared.client

import java.net.URLDecoder
import java.net.URLEncoder
import vn.cnldg.oncam.shared.protocol.DeviceIds
import vn.cnldg.oncam.shared.security.SharedSecret

/**
 * Nội dung mã QR ghép đôi: máy camera hiện QR, app Xem quét để thêm camera mà không phải gõ/gửi token.
 *
 * Dạng: `oncam://pair?v=1&d=<mã camera>&b=<địa chỉ broker>&t=<token>&n=<tên>[&u=<user>&p=<pass>]`.
 * QR chứa token bí mật → máy camera chỉ hiện khi bấm và tự ẩn sau ít phút.
 */
object PairingCode {
    private const val PREFIX = "oncam://pair?"
    private const val VERSION = "1"

    /** Thông tin trong mã QR. */
    data class Info(
        val deviceId: String,
        val brokerUri: String,
        val secret: String,
        val displayName: String? = null,
        val brokerUsername: String? = null,
        val brokerPassword: String? = null,
        /** Mã "chỉ xem" (0.13): [secret] là token khách — chỉ xem hình, không điều khiển/nói/xem vị trí. */
        val guest: Boolean = false,
    ) {
        /** Không in token/mật khẩu ra log. */
        override fun toString(): String = "PairingCode.Info(deviceId=$deviceId, brokerUri=$brokerUri, secret=***)"
    }

    /** Tạo chuỗi để vẽ QR. */
    fun encode(info: Info): String = buildString {
        append(PREFIX)
        append("v=").append(VERSION)
        append("&d=").append(enc(info.deviceId))
        append("&b=").append(enc(info.brokerUri))
        append("&t=").append(enc(info.secret))
        info.displayName?.takeIf { it.isNotBlank() }?.let { append("&n=").append(enc(it)) }
        info.brokerUsername?.takeIf { it.isNotBlank() }?.let { append("&u=").append(enc(it)) }
        info.brokerPassword?.takeIf { it.isNotBlank() }?.let { append("&p=").append(enc(it)) }
        if (info.guest) append("&g=1")
    }

    /** Đọc chuỗi từ QR; null nếu không phải mã OnCam hợp lệ. */
    fun decode(text: String): Info? {
        val raw = text.trim()
        if (!raw.startsWith(PREFIX)) return null
        val params = raw.removePrefix(PREFIX).split('&').mapNotNull { pair ->
            val i = pair.indexOf('=')
            if (i <= 0) null else pair.substring(0, i) to dec(pair.substring(i + 1))
        }.toMap()
        if (params["v"] != VERSION) return null
        val deviceId = params["d"] ?: return null
        val broker = params["b"]?.let(BrokerAddress::normalize) ?: return null
        val secret = params["t"] ?: return null
        if (!DeviceIds.isValid(deviceId) || !BrokerAddress.isValid(broker) || !SharedSecret.isAcceptable(secret)) return null
        return Info(
            deviceId = deviceId,
            brokerUri = broker,
            secret = secret,
            displayName = params["n"],
            brokerUsername = params["u"],
            brokerPassword = params["p"],
            guest = params["g"] == "1",
        )
    }

    // Dùng bản nhận tên bảng mã (chạy được từ Android 8); bản nhận Charset chỉ có từ Android 13.
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun dec(s: String) = try {
        URLDecoder.decode(s, "UTF-8")
    } catch (_: IllegalArgumentException) {
        ""
    }
}
