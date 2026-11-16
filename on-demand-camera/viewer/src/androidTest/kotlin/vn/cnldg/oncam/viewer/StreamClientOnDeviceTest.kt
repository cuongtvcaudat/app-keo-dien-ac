package vn.cnldg.oncam.viewer

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.ByteArrayOutputStream
import java.net.ServerSocket
import kotlin.concurrent.thread
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import vn.cnldg.oncam.shared.protocol.StreamPaths
import vn.cnldg.oncam.viewer.stream.StreamClient
import vn.cnldg.oncam.viewer.stream.StreamPhase
import vn.cnldg.oncam.viewer.stream.StreamSource

/**
 * StreamClient trên Android thật/emulator: HttpURLConnection của Android (đã cho phép cleartext),
 * giải mã RGB_565 tái sử dụng bitmap, tính FPS/độ trễ, và đổi độ phân giải giữa chừng (đổi chất lượng).
 */
@RunWith(AndroidJUnit4::class)
class StreamClientOnDeviceTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val server = ServerSocket(0)

    @After
    fun tearDown() {
        server.close()
        scope.cancel()
    }

    @Test
    fun xemLuongMjpegVaDoiDoPhanGiaiGiuaChung() = runBlocking<Unit> {
        val small = jpeg(320, 240)
        val large = jpeg(640, 480)
        thread(isDaemon = true) {
            runCatching {
                server.accept().use { socket ->
                    // Đọc hết header request.
                    val input = socket.getInputStream().bufferedReader()
                    while (input.readLine()?.isNotEmpty() == true) Unit
                    val out = socket.getOutputStream()
                    out.write(
                        ("HTTP/1.1 200 OK\r\nContent-Type: multipart/x-mixed-replace; boundary=${StreamPaths.BOUNDARY}\r\n" +
                            "Connection: close\r\n\r\n").toByteArray(),
                    )
                    repeat(60) { i ->
                        val frame = if (i < 20) small else large
                        out.write(
                            ("--${StreamPaths.BOUNDARY}\r\nContent-Type: image/jpeg\r\nContent-Length: ${frame.size}\r\n" +
                                "${StreamPaths.TIMESTAMP_HEADER}: ${System.currentTimeMillis()}\r\n\r\n").toByteArray(),
                        )
                        out.write(frame)
                        out.write("\r\n".toByteArray())
                        out.flush()
                        Thread.sleep(30)
                    }
                }
            }
        }

        val client = StreamClient(scope)
        client.play { StreamSource("http://127.0.0.1:${server.localPort}${StreamPaths.STREAM}", "khong-can") }

        val playing = withTimeout(10_000) { client.state.first { it.phase == StreamPhase.PLAYING && it.frame != null } }
        assertEquals(320, playing.frame!!.width)
        assertEquals(Bitmap.Config.RGB_565, playing.frame!!.config)

        // Sau khung thứ 20 server đổi sang 640×480 (như khi đổi chất lượng): vẫn hiển thị đúng.
        val bigger = withTimeout(10_000) { client.state.first { it.frame?.width == 640 } }
        assertEquals(480, bigger.frame!!.height)
        assertNotNull(bigger.jpeg)
        assertTrue((bigger.fps ?: 0) > 0)
        assertTrue((bigger.latencyMs ?: 0) < 2_000, "Độ trễ ${bigger.latencyMs} ms")

        client.stop()
        assertEquals(StreamPhase.IDLE, client.state.value.phase)
    }

    private fun jpeg(w: Int, h: Int): ByteArray {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(30, 120, 200)) }
        return ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, 80, it) }.toByteArray()
    }
}
