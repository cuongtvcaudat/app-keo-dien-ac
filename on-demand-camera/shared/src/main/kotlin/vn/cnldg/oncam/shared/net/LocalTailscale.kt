package vn.cnldg.oncam.shared.net

import java.net.NetworkInterface
import vn.cnldg.oncam.shared.rtc.TailscaleAddress

/**
 * Kiểm tra máy này đã bật Tailscale chưa (có địa chỉ Tailscale trên giao diện VPN `tun*`).
 *
 * App Xem dùng để nhắc người dùng bật Tailscale khi chưa bật (camera sẽ không kết nối được nếu thiếu).
 * Chỉ nhận giao diện `tun*` vì vài nhà mạng 4G cũng cấp IP dải 100.64.0.0/10 (CGNAT) cho giao diện di động.
 */
object LocalTailscale {

    /**
     * Trong danh sách (tên giao diện, địa chỉ IP) có địa chỉ Tailscale trên giao diện tun* không.
     * Tách riêng để test không cần mạng thật.
     */
    fun hasAddress(candidates: List<Pair<String, String>>): Boolean =
        candidates.any { (name, ip) -> name.startsWith("tun") && TailscaleAddress.isTailscale(ip) }

    /** Máy hiện tại đã bật Tailscale chưa. Lỗi đọc giao diện mạng → coi như chưa chắc (trả về true, không nhắc nhầm). */
    fun isUp(): Boolean {
        val candidates = try {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
                .filter { runCatching { it.isUp }.getOrDefault(false) }
                .flatMap { nif -> nif.inetAddresses.toList().map { nif.name to it.hostAddress.orEmpty().substringBefore('%') } }
        } catch (_: Exception) {
            return true
        }
        return hasAddress(candidates)
    }
}
