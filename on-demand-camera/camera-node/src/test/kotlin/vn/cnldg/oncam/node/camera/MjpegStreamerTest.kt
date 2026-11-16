package vn.cnldg.oncam.node.camera

import java.net.ServerSocket
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import vn.cnldg.oncam.node.core.FakeCameraPort
import vn.cnldg.oncam.node.core.httpGet
import vn.cnldg.oncam.node.core.openMjpeg
import vn.cnldg.oncam.shared.protocol.StreamPaths
import vn.cnldg.oncam.shared.security.StreamAuth

class MjpegStreamerTest {

    private val port = ServerSocket(0).use { it.localPort }
    private val auth = StreamAuth("test-secret-0123456789", "san-nha")
    private val hub = FrameHub()
    private val server = MjpegStreamer("127.0.0.1", port, auth, hub, maxClients = 2).also { it.startServer() }
    private val base = "http://127.0.0.1:$port"

    @AfterTest
    fun tearDown() = server.stopServer()

    @Test
    fun `khong co hoac sai header xac thuc thi tra 401`() {
        assertEquals(401, httpGet("$base${StreamPaths.SNAPSHOT}", null).code)
        assertEquals(401, httpGet("$base${StreamPaths.SNAPSHOT}", "1700000000.deadbeef").code)
        val otherDevice = StreamAuth("test-secret-0123456789", "camera-khac").headerValue()
        assertEquals(401, httpGet("$base${StreamPaths.STREAM}", otherDevice).code)
    }

    @Test
    fun `duong dan la tra 404`() {
        assertEquals(404, httpGet("$base/admin", auth.headerValue()).code)
    }

    @Test
    fun `snapshot tra khung moi nhat hoac 503 khi chua co khung`() {
        assertEquals(503, httpGet("$base${StreamPaths.SNAPSHOT}", auth.headerValue()).code)

        hub.publish(FakeCameraPort.fakeJpeg(1))
        hub.publish(FakeCameraPort.fakeJpeg(2))
        val res = httpGet("$base${StreamPaths.SNAPSHOT}", auth.headerValue())

        assertEquals(200, res.code)
        assertEquals("image/jpeg", res.contentType)
        assertContentEquals(FakeCameraPort.fakeJpeg(2), res.body)
    }

    @Test
    fun `luong MJPEG phat nhieu khung dem nguoi xem va gioi han so nguoi`() {
        val feeder = Thread {
            var i = 0
            while (!Thread.currentThread().isInterrupted) {
                hub.publish(FakeCameraPort.fakeJpeg(i++))
                try {
                    Thread.sleep(20)
                } catch (_: InterruptedException) {
                    return@Thread
                }
            }
        }.apply { start() }
        try {
            val (a, partsA) = openMjpeg("127.0.0.1", port, auth.headerValue(), parts = 3)
            val (b, partsB) = openMjpeg("127.0.0.1", port, auth.headerValue(), parts = 3)
            assertEquals(3, partsA)
            assertEquals(3, partsB)
            assertEquals(2, server.clientCount)

            // Người thứ 3 vượt giới hạn 2 → 503.
            assertEquals(503, httpGet("$base${StreamPaths.STREAM}", auth.headerValue()).code)

            // Người xem tắt app → server phát hiện và giảm bộ đếm.
            a.close()
            b.close()
            val deadline = System.currentTimeMillis() + 5_000
            while (server.clientCount > 0 && System.currentTimeMillis() < deadline) Thread.sleep(50)
            assertEquals(0, server.clientCount)
        } finally {
            feeder.interrupt()
        }
    }

    @Test
    fun `khong cho mo tren moi giao dien mang`() {
        assertFailsWith<IllegalArgumentException> { MjpegStreamer("0.0.0.0", port, auth, FrameHub()) }
    }

    @Test
    fun `hub cho khung moi va tra null khi dong`() {
        val h = FrameHub()
        assertNull(h.awaitAfter(-1, 50))
        h.publish(FakeCameraPort.fakeJpeg(7))
        val f = h.awaitAfter(-1, 50)!!
        assertNull(h.awaitAfter(f.seq, 50)) // chưa có khung mới hơn
        Thread { Thread.sleep(50); h.close() }.start()
        assertNull(h.awaitAfter(f.seq, 2_000))
        assertTrue(h.isClosed)
    }
}
