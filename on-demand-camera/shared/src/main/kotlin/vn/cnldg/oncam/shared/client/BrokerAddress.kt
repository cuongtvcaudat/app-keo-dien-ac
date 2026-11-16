package vn.cnldg.oncam.shared.client

import vn.cnldg.oncam.shared.OnCam

/**
 * Chuẩn hoá địa chỉ broker người dùng gõ: chỉ cần IP `100.x.y.z` là đủ,
 * tự thêm `tcp://` và cổng 1883 nếu thiếu.
 */
object BrokerAddress {
    private val FULL = Regex("^(tcp|ssl)://([A-Za-z0-9.\\-]+):(\\d{1,5})$")
    private val IPV4 = Regex("^(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})$")

    /** `100.1.2.3` → `tcp://100.1.2.3:1883`; `100.1.2.3:2000` → `tcp://100.1.2.3:2000`; đầy đủ thì giữ nguyên. */
    fun normalize(input: String, defaultPort: Int = OnCam.DEFAULT_MQTT_PORT): String {
        var s = input.trim().replace(" ", "")
        if (s.isEmpty()) return s
        if (!s.contains("://")) s = "tcp://$s"
        if (!s.substringAfter("://").contains(':')) s = "$s:$defaultPort"
        return s
    }

    /**
     * Địa chỉ có hợp lệ không: `tcp://` hoặc `ssl://`, host là IPv4 đủ 4 số (0–255) hoặc tên miền có chữ cái,
     * cổng 1–65535. Chặn trường hợp chỉ gõ số cổng (vd. `1883`).
     */
    fun isValid(uri: String): Boolean {
        val m = FULL.matchEntire(uri) ?: return false
        val host = m.groupValues[2]
        val port = m.groupValues[3].toIntOrNull() ?: return false
        if (port !in 1..65535) return false
        val ip = IPV4.matchEntire(host)
        return if (ip != null) {
            ip.groupValues.drop(1).all { it.toInt() in 0..255 }
        } else {
            host.any { it.isLetter() } && !host.startsWith('.') && !host.endsWith('.')
        }
    }
}
