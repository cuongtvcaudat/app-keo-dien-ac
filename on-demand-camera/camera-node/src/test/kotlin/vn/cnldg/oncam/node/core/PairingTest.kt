package vn.cnldg.oncam.node.core

import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import vn.cnldg.oncam.shared.client.PairingCode
import vn.cnldg.oncam.shared.security.SharedSecret

class PairingTest {

    // Token thật do app sinh (43 ký tự) để kiểm kích thước mã QR đúng thực tế.
    private val config = NodeConfig(deviceId = "san-nha", displayName = "Sân nhà", secret = SharedSecret.generate())
    private val uri = "tcp://100.101.102.103:1883"

    @Test
    fun `chua co IP Tailscale thi khong tao ma`() {
        assertNull(config.pairingCode(null))
    }

    @Test
    fun `broker nhung khong dua mat khau broker vao QR`() {
        val info = PairingCode.decode(config.pairingCode(uri)!!)!!

        assertEquals(PairingCode.Info("san-nha", uri, config.secret, "Sân nhà"), info)
    }

    @Test
    fun `broker rieng kem tai khoan broker`() {
        val ext = config.copy(
            brokerMode = BrokerMode.EXTERNAL,
            externalUri = "ssl://broker.example:8883",
            externalUsername = "cam1",
            externalPassword = "p@ss word",
        )
        val info = PairingCode.decode(ext.pairingCode("ssl://broker.example:8883")!!)!!

        assertEquals("cam1", info.brokerUsername)
        assertEquals("p@ss word", info.brokerPassword)
    }

    @Test
    fun `ve QR roi doc lai bang zxing ra dung noi dung`() {
        val text = config.pairingCode(uri)!!
        val matrix = QrCodes.encode(text)

        // Phóng to 4 lần như khi hiện trên màn hình rồi cho bộ đọc QR tìm và giải mã.
        val scale = 4
        val w = matrix.width * scale
        val h = matrix.height * scale
        val pixels = IntArray(w * h) { i ->
            if (matrix[(i % w) / scale, (i / w) / scale]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        }
        val decoded = QRCodeReader().decode(BinaryBitmap(HybridBinarizer(RGBLuminanceSource(w, h, pixels)))).text

        assertEquals(text, decoded)
        // ~140 ký tự → QR phiên bản ≤ 8 (49 ô): quét dễ bằng camera điện thoại cách 20–30 cm.
        assertTrue(matrix.width <= 49 + 4, "QR ${matrix.width} ô cho ${text.length} ký tự")
    }
}
