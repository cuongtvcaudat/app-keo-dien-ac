package vn.cnldg.oncam.node.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NodeConfigAndAddressTest {

    private val ok = NodeConfig(deviceId = "san-nha", displayName = "Sân nhà", secret = "test-secret-0123456789")

    @Test
    fun `cau hinh hop le`() {
        assertEquals(emptyList(), ok.problems())
        assertEquals(
            emptyList(),
            ok.copy(brokerMode = BrokerMode.EXTERNAL, externalUri = "ssl://broker.example:8883").problems(),
        )
    }

    @Test
    fun `phat hien loi cau hinh`() {
        assertEquals(listOf(ConfigProblem.INVALID_DEVICE_ID), ok.copy(deviceId = "Sân Nhà").problems())
        assertEquals(listOf(ConfigProblem.EMPTY_DISPLAY_NAME), ok.copy(displayName = " ").problems())
        assertEquals(listOf(ConfigProblem.WEAK_SECRET), ok.copy(secret = "123").problems())
        assertEquals(listOf(ConfigProblem.INVALID_PORT), ok.copy(brokerPort = 80).problems())
        assertEquals(
            listOf(ConfigProblem.INVALID_EXTERNAL_URI),
            ok.copy(brokerMode = BrokerMode.EXTERNAL, externalUri = "http://x").problems(),
        )
        // Màn hình cấu hình chuẩn hoá trước khi kiểm: "100.80.1.2" → "tcp://100.80.1.2:1883".
        assertEquals(
            emptyList(),
            ok.copy(brokerMode = BrokerMode.EXTERNAL, externalUri = "tcp://100.80.1.2:1883").problems(),
        )
        assertEquals(
            listOf(ConfigProblem.INVALID_EXTERNAL_URI),
            ok.copy(brokerMode = BrokerMode.EXTERNAL, externalUri = "tcp://1883:1883").problems(),
        )
    }

    @Test
    fun `khong in token ra log`() {
        assertFalse(ok.toString().contains(ok.secret))
    }

    @Test
    fun `nhan dung dai IP Tailscale 100_64_0_0 tren 10`() {
        assertTrue(TailscaleIp.isTailscaleRange(ip(100, 64, 0, 1)))
        assertTrue(TailscaleIp.isTailscaleRange(ip(100, 127, 255, 254)))
        assertFalse(TailscaleIp.isTailscaleRange(ip(100, 63, 255, 255)))
        assertFalse(TailscaleIp.isTailscaleRange(ip(100, 128, 0, 1)))
        assertFalse(TailscaleIp.isTailscaleRange(ip(192, 168, 1, 10)))
    }

    @Test
    fun `chi chon IP tren giao dien tun va bo qua CGNAT cua nha mang`() {
        assertEquals(
            "100.101.102.103",
            TailscaleIp.pick(listOf("rmnet_data0" to ip(100, 70, 1, 2), "tun0" to ip(100, 101, 102, 103))),
        )
        assertNull(TailscaleIp.pick(listOf("rmnet_data0" to ip(100, 70, 1, 2))))
        assertNull(TailscaleIp.pick(listOf("tun0" to ip(10, 0, 0, 2))))
        assertNull(TailscaleIp.pick(emptyList()))
    }

    @Test
    fun `broker nhung tat telemetry va anonymous chi nghe IP duoc chi dinh`() {
        val props = vn.cnldg.oncam.node.broker.EmbeddedBroker(java.io.File("build/tmp")).properties("100.101.102.103", 1883)

        assertEquals("false", props.getProperty("telemetry_enabled"), "Moquette mặc định gửi IP công cộng ra ngoài")
        assertEquals("false", props.getProperty("allow_anonymous"))
        assertEquals("false", props.getProperty("persistence_enabled"))
        assertEquals("disabled", props.getProperty("websocket_port"))
        assertEquals("100.101.102.103", props.getProperty("host"))
    }

    private fun ip(a: Int, b: Int, c: Int, d: Int) = byteArrayOf(a.toByte(), b.toByte(), c.toByte(), d.toByte())
}
