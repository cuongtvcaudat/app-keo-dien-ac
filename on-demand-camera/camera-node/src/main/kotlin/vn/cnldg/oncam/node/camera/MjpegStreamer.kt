package vn.cnldg.oncam.node.camera

import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.IHTTPSession
import fi.iki.elonen.NanoHTTPD.Method
import fi.iki.elonen.NanoHTTPD.Response
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.concurrent.atomic.AtomicInteger
import vn.cnldg.oncam.shared.LogSink
import vn.cnldg.oncam.shared.protocol.StreamPaths
import vn.cnldg.oncam.shared.security.StreamAuth

/**
 * HTTP server phát MJPEG (NanoHTTPD) — chỉ bind trên [host] (IP Tailscale), KHÔNG bao giờ 0.0.0.0.
 *
 * - `GET /stream.mjpeg` → `multipart/x-mixed-replace`, mỗi phần là một khung JPEG kèm header
 *   [StreamPaths.TIMESTAMP_HEADER] (thời điểm chụp) để viewer ước lượng độ trễ.
 * - `GET /snapshot.jpg` → khung JPEG mới nhất.
 * - Mọi request phải có header [StreamAuth.HEADER] hợp lệ (quyết định D6), sai → 401.
 * - Tối đa [maxClients] người xem cùng lúc để máy cũ không quá tải, quá → 503.
 */
class MjpegStreamer(
    host: String,
    port: Int,
    private val auth: StreamAuth,
    private val hub: FrameHub,
    private val maxClients: Int = 3,
    private val log: LogSink = LogSink.NONE,
) : NanoHTTPD(host, port) {

    init {
        require(host != "0.0.0.0" && host != "::") { "Không được mở HTTP stream trên mọi giao diện mạng" }
    }

    private val clients = AtomicInteger(0)

    /** Số người đang xem luồng MJPEG. */
    val clientCount: Int get() = clients.get()

    /** Mở cổng (chặn luồng tới khi bind xong). @throws java.io.IOException nếu không bind được. */
    fun startServer() {
        start(SOCKET_READ_TIMEOUT, true)
        log.log(LogSink.Level.INFO, "MJPEG server đang nghe tại $hostname:$listeningPort", null)
    }

    /** Đóng server và mọi kết nối đang xem. */
    fun stopServer() {
        hub.close()
        stop()
        log.log(LogSink.Level.INFO, "Đã tắt MJPEG server", null)
    }

    override fun serve(session: IHTTPSession): Response {
        if (session.method != Method.GET) return text(Response.Status.METHOD_NOT_ALLOWED, "method not allowed")
        // NanoHTTPD chuyển tên header về chữ thường.
        if (!auth.isValid(session.headers[StreamAuth.HEADER.lowercase()])) {
            return text(Response.Status.UNAUTHORIZED, "unauthorized")
        }
        return when (session.uri) {
            StreamPaths.STREAM -> stream()
            StreamPaths.SNAPSHOT -> snapshot()
            else -> text(Response.Status.NOT_FOUND, "not found")
        }
    }

    private fun stream(): Response {
        if (clients.incrementAndGet() > maxClients) {
            clients.decrementAndGet()
            return text(Response.Status.SERVICE_UNAVAILABLE, "too many viewers")
        }
        val body = MjpegInputStream(hub) { clients.decrementAndGet() }
        return newChunkedResponse(Response.Status.OK, "multipart/x-mixed-replace; boundary=${StreamPaths.BOUNDARY}", body)
            .noCache()
    }

    private fun snapshot(): Response {
        val jpeg = hub.latest() ?: return text(Response.Status.SERVICE_UNAVAILABLE, "no frame")
        return newFixedLengthResponse(Response.Status.OK, JPEG, ByteArrayInputStream(jpeg), jpeg.size.toLong())
            .noCache()
    }

    private fun text(status: Response.Status, message: String): Response =
        newFixedLengthResponse(status, NanoHTTPD.MIME_PLAINTEXT, message)

    private fun Response.noCache(): Response = apply {
        addHeader("Cache-Control", "no-cache, no-store, must-revalidate")
        addHeader("Pragma", "no-cache")
    }

    private companion object {
        const val JPEG = "image/jpeg"
    }
}

/**
 * Luồng byte vô tận của MJPEG: mỗi lần hết dữ liệu thì chờ khung mới từ [hub].
 * Kết thúc (-1) khi hub đóng hoặc không có khung mới trong [FRAME_WAIT_MS] (camera treo).
 * [onClose] được gọi đúng một lần khi NanoHTTPD đóng luồng (người xem ngắt kết nối).
 */
internal class MjpegInputStream(
    private val hub: FrameHub,
    private val onClose: () -> Unit,
) : InputStream() {
    private var buffer = ByteArray(0)
    private var pos = 0
    private var lastSeq = -1L

    @Volatile
    private var closed = false

    override fun read(): Int {
        val one = ByteArray(1)
        return if (read(one, 0, 1) <= 0) -1 else one[0].toInt() and 0xFF
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (closed) return -1
        if (pos >= buffer.size) {
            val frame = hub.awaitAfter(lastSeq, FRAME_WAIT_MS) ?: return -1
            lastSeq = frame.seq
            buffer = part(frame.jpeg, frame.capturedAtMs)
            pos = 0
        }
        val n = minOf(len, buffer.size - pos)
        System.arraycopy(buffer, pos, b, off, n)
        pos += n
        return n
    }

    override fun close() {
        if (closed) return
        closed = true
        onClose()
    }

    private fun part(jpeg: ByteArray, capturedAtMs: Long): ByteArray {
        val header = (
            "--${StreamPaths.BOUNDARY}\r\nContent-Type: image/jpeg\r\nContent-Length: ${jpeg.size}\r\n" +
                "${StreamPaths.TIMESTAMP_HEADER}: $capturedAtMs\r\n\r\n"
            ).toByteArray(Charsets.US_ASCII)
        return header + jpeg + CRLF
    }

    private companion object {
        const val FRAME_WAIT_MS = 5_000L
        val CRLF = "\r\n".toByteArray(Charsets.US_ASCII)
    }
}
