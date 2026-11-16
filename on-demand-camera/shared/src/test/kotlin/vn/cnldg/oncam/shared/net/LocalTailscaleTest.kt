package vn.cnldg.oncam.shared.net

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Nhận diện Tailscale đang bật: chỉ tin địa chỉ Tailscale trên giao diện tun* (không nhầm với 4G CGNAT). */
class LocalTailscaleTest {

    @Test
    fun `co dia chi Tailscale tren tun thi coi la da bat`() {
        assertTrue(LocalTailscale.hasAddress(listOf("tun0" to "100.101.102.103")))
        assertTrue(LocalTailscale.hasAddress(listOf("tun0" to "fd7a:115c:a1e0::1")))
        assertTrue(LocalTailscale.hasAddress(listOf("wlan0" to "192.168.1.5", "tun0" to "100.64.0.9")))
    }

    @Test
    fun `khong co tun Tailscale thi coi la chua bat`() {
        assertFalse(LocalTailscale.hasAddress(emptyList()))
        assertFalse(LocalTailscale.hasAddress(listOf("wlan0" to "192.168.1.5")))
        // 4G CGNAT cùng dải 100.64/10 nhưng KHÔNG phải tun → không tính là Tailscale.
        assertFalse(LocalTailscale.hasAddress(listOf("rmnet0" to "100.70.1.2")))
        // Giao diện tun nhưng địa chỉ ngoài dải Tailscale.
        assertFalse(LocalTailscale.hasAddress(listOf("tun0" to "10.0.0.1")))
    }
}
