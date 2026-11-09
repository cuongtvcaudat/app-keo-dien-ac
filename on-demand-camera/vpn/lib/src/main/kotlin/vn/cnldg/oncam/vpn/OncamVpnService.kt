// Phỏng theo tailscale-android (IPNService.kt), giấy phép BSD-3-Clause.
// Copyright (c) Tailscale Inc & AUTHORS. Xem on-demand-camera/vpn/NOTICE.
//
// VpnService của OnCam, đồng thời là libtailscale.IPNService để lõi Tailscale (Go)
// lập đường hầm trên máy này. Nhờ đó app chỉ cần 1 cái — không cài Tailscale rời.
package vn.cnldg.oncam.vpn

import android.content.Intent
import android.net.VpnService
import android.util.Log

/**
 * Dịch vụ VPN: khi chạy sẽ bảo lõi Tailscale (Go) dựng đường hầm qua chính service này.
 *
 * Vòng đời: [OncamTailscale.start] gọi `Libtailscale.start(...)` để khởi động lõi; lõi gọi
 * `RequestVPN(this)` → Android hiện hộp thoại xin quyền VPN (1 lần) → [onStartCommand] chạy →
 * Go gọi [newBuilder]/[establish] để lấy fd tun.
 */
class OncamVpnService : VpnService(), libtailscale.IPNService {

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "OncamVpnService onStartCommand")
        // Bảo lõi Tailscale dựng đường hầm qua service này.
        libtailscale.Libtailscale.requestVPN(this)
        return START_STICKY
    }

    override fun onDestroy() {
        try {
            libtailscale.Libtailscale.serviceDisconnect(this)
        } catch (e: Exception) {
            Log.w(TAG, "serviceDisconnect lỗi: $e")
        }
        super.onDestroy()
    }

    override fun onRevoke() {
        // Người dùng tắt VPN trong Cài đặt Android.
        try {
            libtailscale.Libtailscale.serviceDisconnect(this)
        } catch (_: Exception) {}
        stopSelf()
        super.onRevoke()
    }

    // --- libtailscale.IPNService ---

    /** Mã định danh phiên VPN (Go dùng để phân biệt các lần bật/tắt). */
    override fun id(): String = System.identityHashCode(this).toString()

    /** Bảo vệ 1 socket khỏi bị lặp vào chính đường hầm (đi thẳng mạng gốc). */
    override fun protect(fd: Int): Boolean = super<VpnService>.protect(fd)

    /** Tạo builder để Go cấu hình đường hầm. */
    override fun newBuilder(): libtailscale.VPNServiceBuilder =
        OncamVpnServiceBuilder(Builder())

    override fun close() {
        stopSelf()
    }

    override fun disconnectVPN() {
        stopSelf()
    }

    override fun updateVpnStatus(up: Boolean) {
        Log.d(TAG, "VPN status: ${if (up) "đang chạy" else "đã dừng"}")
    }

    companion object {
        private const val TAG = "OncamTailscale"
    }
}
