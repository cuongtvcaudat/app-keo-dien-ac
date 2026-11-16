package vn.cnldg.oncam.shared.client

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PairingAndAddressTest {

    @Test
    fun `chi go IP la du`() {
        assertEquals("tcp://100.101.102.103:1883", BrokerAddress.normalize("100.101.102.103"))
        assertEquals("tcp://100.101.102.103:1883", BrokerAddress.normalize(" 100.101.102.103 "))
        assertEquals("tcp://100.101.102.103:2000", BrokerAddress.normalize("100.101.102.103:2000"))
        assertEquals("ssl://broker.example:8883", BrokerAddress.normalize("ssl://broker.example:8883"))
        assertEquals("tcp://broker.example:1883", BrokerAddress.normalize("tcp://broker.example"))
        assertEquals("", BrokerAddress.normalize("  "))
    }

    @Test
    fun `chan dia chi sai`() {
        assertTrue(BrokerAddress.isValid("tcp://100.101.102.103:1883"))
        assertTrue(BrokerAddress.isValid("ssl://broker.example:8883"))
        // Lỗi đã gặp thực tế: chỉ gõ số cổng.
        assertFalse(BrokerAddress.isValid(BrokerAddress.normalize("1883")))
        assertFalse(BrokerAddress.isValid("tcp://100.101.102:1883"))
        assertFalse(BrokerAddress.isValid("tcp://100.101.102.300:1883"))
        assertFalse(BrokerAddress.isValid("tcp://100.101.102.103:70000"))
        assertFalse(BrokerAddress.isValid("http://100.101.102.103:1883"))
        assertFalse(BrokerAddress.isValid("tcp://100.101.102.103"))
    }

    @Test
    fun `ma QR ma hoa roi doc lai dung`() {
        val info = PairingCode.Info(
            deviceId = "san-nha",
            brokerUri = "tcp://100.101.102.103:1883",
            secret = "aB3+/=&?x-0123456789",
            displayName = "Sân nhà & cổng",
        )

        val text = PairingCode.encode(info)

        assertTrue(text.startsWith("oncam://pair?v=1&"))
        assertEquals(info, PairingCode.decode(text))
        assertEquals(info, PairingCode.decode("  $text\n"))
    }

    @Test
    fun `ma QR giu ca tai khoan broker rieng`() {
        val info = PairingCode.Info("cong-truoc", "ssl://broker.example:8883", "test-secret-0123456789", null, "u1", "p@ss")
        assertEquals(info, PairingCode.decode(PairingCode.encode(info)))
    }

    @Test
    fun `tu choi ma QR khong hop le`() {
        val good = PairingCode.encode(PairingCode.Info("san-nha", "tcp://100.101.102.103:1883", "test-secret-0123456789"))
        assertNull(PairingCode.decode("https://example.com"))
        assertNull(PairingCode.decode(good.replace("v=1", "v=2")))
        assertNull(PairingCode.decode(good.replace("&t=test-secret-0123456789", "&t=123")))
        assertNull(PairingCode.decode(good.replace("d=san-nha", "d=S%C3%A2n")))
        assertNull(PairingCode.decode(good.replace("&b=tcp%3A%2F%2F100.101.102.103%3A1883", "&b=1883")))
        assertNull(PairingCode.decode(good.replace("&d=san-nha", "")))
    }

    @Test
    fun `ma QR chi co IP van duoc chuan hoa`() {
        val decoded = PairingCode.decode("oncam://pair?v=1&d=san-nha&b=100.101.102.103&t=test-secret-0123456789")
        assertEquals("tcp://100.101.102.103:1883", decoded?.brokerUri)
    }

    @Test
    fun `khong in token ra log`() {
        val info = PairingCode.Info("san-nha", "tcp://100.101.102.103:1883", "test-secret-0123456789")
        assertFalse(info.toString().contains(info.secret))
    }

    @Test
    fun `do tre tru do lech gio`() {
        // Camera nhanh 2 giây; khung chụp lúc camera = 10_000, máy xem nhận lúc 8_300 → trễ thật 300 ms.
        assertEquals(300L, FrameLatency.of(nowMs = 8_300, capturedAtMs = 10_000, clockOffsetMs = 2_000))
        // Chưa đo lệch giờ: giữ cách tính cũ.
        assertEquals(500L, FrameLatency.of(10_500, 10_000, null))
        // Âm nhẹ do sai số đo → 0; âm nhiều hoặc quá 60 giây → không hiện.
        assertEquals(0L, FrameLatency.of(9_900, 10_000, 0))
        assertNull(FrameLatency.of(9_000, 10_000, 0))
        assertNull(FrameLatency.of(80_000, 10_000, 0))
    }
}
