// Phỏng theo tailscale-android (VPNServiceBuilder.kt), giấy phép BSD-3-Clause.
// Copyright (c) Tailscale Inc & AUTHORS. Xem on-demand-camera/vpn/NOTICE.
//
// Hai lớp bọc để libtailscale điều khiển VpnService của Android:
//  - OncamVpnServiceBuilder: bọc android VpnService.Builder (địa chỉ, route, DNS, MTU, lập đường hầm).
//  - OncamParcelFileDescriptor: bọc ParcelFileDescriptor để Go lấy fd tun.
package vn.cnldg.oncam.vpn

import android.net.IpPrefix
import android.net.VpnService
import android.os.Build
import java.net.InetAddress

/** Bọc [VpnService.Builder] để phía Go (libtailscale) dựng đường hầm. */
class OncamVpnServiceBuilder(
    private val builder: VpnService.Builder,
) : libtailscale.VPNServiceBuilder {

    override fun addAddress(addr: String, prefixLen: Int) {
        builder.addAddress(addr, prefixLen)
    }

    override fun addDNSServer(addr: String) {
        builder.addDnsServer(addr)
    }

    override fun addRoute(addr: String, prefixLen: Int) {
        builder.addRoute(addr, prefixLen)
    }

    override fun excludeRoute(addr: String, prefixLen: Int) {
        // excludeRoute chỉ có từ Android 13 (API 33).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            builder.excludeRoute(IpPrefix(InetAddress.getByName(addr), prefixLen))
        }
    }

    override fun addSearchDomain(domain: String) {
        builder.addSearchDomain(domain)
    }

    override fun setMTU(mtu: Int) {
        builder.setMtu(mtu)
    }

    override fun establish(): libtailscale.ParcelFileDescriptor? =
        builder.establish()?.let { OncamParcelFileDescriptor(it) }
}

/** Bọc [android.os.ParcelFileDescriptor]; Go gọi detach() để lấy fd tun thô. */
class OncamParcelFileDescriptor(
    private val fd: android.os.ParcelFileDescriptor,
) : libtailscale.ParcelFileDescriptor {
    override fun detach(): Int = fd.detachFd()
}
