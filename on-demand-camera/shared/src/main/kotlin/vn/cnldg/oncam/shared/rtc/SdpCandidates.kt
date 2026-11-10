package vn.cnldg.oncam.shared.rtc

/**
 * Xử lý dòng ứng viên ICE (`a=candidate:`) trong SDP.
 *
 * OnCam không dùng STUN/TURN: 2 máy đã thấy nhau qua IP Tailscale. Bỏ mọi ứng viên khác (IP Wi-Fi nhà, IP 4G)
 * để WebRTC chỉ đi trong đường hầm Tailscale và không lộ IP mạng nhà ra phía bên kia.
 */
object SdpCandidates {

    /** Địa chỉ của mọi dòng ứng viên trong [sdp] (theo thứ tự xuất hiện). */
    fun addresses(sdp: String): List<String> = sdp.lineSequence().mapNotNull(::addressOf).toList()

    /** Giữ lại các dòng ứng viên có địa chỉ thoả [keep]; giữ nguyên các dòng khác và kiểu xuống dòng. */
    fun filter(sdp: String, keep: (String) -> Boolean): String {
        val sep = if (sdp.contains("\r\n")) "\r\n" else "\n"
        return sdp.split(sep).filter { line -> addressOf(line)?.let(keep) ?: true }.joinToString(sep)
    }

    /**
     * Địa chỉ trong một dòng `a=candidate:<foundation> <component> <udp|tcp> <priority> <địa chỉ> <cổng> typ ...`,
     * null nếu không phải dòng ứng viên.
     */
    fun addressOf(line: String): String? {
        if (!line.startsWith(PREFIX)) return null
        val parts = line.trim().split(' ')
        return parts.getOrNull(4)
    }

    private const val PREFIX = "a=candidate:"
}

/** Nhận diện địa chỉ Tailscale: IPv4 `100.64.0.0/10` (CGNAT) hoặc IPv6 `fd7a:115c:a1e0::/48`. */
object TailscaleAddress {
    private val IPV4 = Regex("^(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})$")

    /** [ip] có thuộc dải Tailscale không. */
    fun isTailscale(ip: String): Boolean {
        IPV4.matchEntire(ip)?.let { m ->
            val a = m.groupValues[1].toInt()
            val b = m.groupValues[2].toInt()
            return a == 100 && b in 64..127 && m.groupValues.drop(1).all { it.toInt() in 0..255 }
        }
        return ip.lowercase().startsWith("fd7a:115c:a1e0:")
    }
}
