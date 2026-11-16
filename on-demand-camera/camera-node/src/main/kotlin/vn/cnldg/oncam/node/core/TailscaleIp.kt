package vn.cnldg.oncam.node.core

import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Tìm IP Tailscale của máy để bind broker/HTTP server (spec: KHÔNG bind 0.0.0.0).
 *
 * Tailscale cấp IPv4 trong dải 100.64.0.0/10 trên giao diện VPN `tunN`. Chỉ nhận giao diện `tun*`
 * vì một số nhà mạng di động cũng dùng dải 100.64.0.0/10 (CGNAT) cho giao diện 4G.
 */
object TailscaleIp {

    /** Địa chỉ 4 byte có thuộc dải 100.64.0.0/10 không. */
    fun isTailscaleRange(address: ByteArray): Boolean =
        address.size == 4 &&
            (address[0].toInt() and 0xFF) == 100 &&
            (address[1].toInt() and 0xC0) == 64

    /**
     * Chọn IP Tailscale từ danh sách (tên giao diện, IPv4).
     * Tách riêng khỏi [find] để test được không cần mạng thật.
     */
    fun pick(candidates: List<Pair<String, ByteArray>>): String? =
        candidates
            .firstOrNull { (name, addr) -> name.startsWith("tun") && isTailscaleRange(addr) }
            ?.second
            ?.joinToString(".") { (it.toInt() and 0xFF).toString() }

    /** Tìm IP Tailscale trên máy hiện tại; null nếu Tailscale chưa bật. */
    fun find(): String? {
        val interfaces = try {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
        } catch (_: Exception) {
            emptyList()
        }
        val candidates = interfaces
            .filter { runCatching { it.isUp }.getOrDefault(false) }
            .flatMap { nif ->
                nif.inetAddresses.toList()
                    .filterIsInstance<Inet4Address>()
                    .map { nif.name to it.address }
            }
        return pick(candidates)
    }
}
