package vn.cnldg.oncam.node.camera

import fi.iki.elonen.NanoHTTPD
import java.io.FileInputStream
import vn.cnldg.oncam.node.core.RecordingStore
import vn.cnldg.oncam.shared.LogSink
import vn.cnldg.oncam.shared.protocol.ProtocolJson
import vn.cnldg.oncam.shared.protocol.Recordings
import vn.cnldg.oncam.shared.security.StreamAuth

/**
 * Máy chủ tải video (NanoHTTPD) — chỉ bind trên [host] (IP Tailscale), KHÔNG bao giờ 0.0.0.0; chỉ mở khi cần
 * (vừa quay xong, hoặc app Xem yêu cầu) rồi tự đóng.
 *
 * - `GET /recordings` → JSON danh sách clip (mới nhất trước).
 * - `GET /recordings/<tên>` → file MP4 (tên phải đúng mẫu do máy camera đặt — chặn đọc file khác).
 * - Mọi request phải có header [StreamAuth.HEADER] hợp lệ (cùng chữ ký với luồng hình), sai → 401.
 */
class RecordingServer(
    host: String,
    port: Int,
    private val auth: StreamAuth,
    private val store: RecordingStore,
    private val log: LogSink = LogSink.NONE,
) : NanoHTTPD(host, port) {

    init {
        require(host != "0.0.0.0" && host != "::") { "Không được mở cổng tải video trên mọi giao diện mạng" }
    }

    /** Mở cổng. @throws java.io.IOException nếu không bind được. */
    fun startServer() {
        start(SOCKET_READ_TIMEOUT, true)
        log.log(LogSink.Level.INFO, "Cổng tải video mở tại $hostname:$listeningPort", null)
    }

    /** Đóng cổng. */
    fun stopServer() {
        stop()
        log.log(LogSink.Level.INFO, "Đã đóng cổng tải video", null)
    }

    override fun serve(session: IHTTPSession): Response {
        if (session.method != Method.GET) return text(Response.Status.METHOD_NOT_ALLOWED, "method not allowed")
        if (!auth.isValid(session.headers[StreamAuth.HEADER.lowercase()])) {
            return text(Response.Status.UNAUTHORIZED, "unauthorized")
        }
        val uri = session.uri
        return when {
            uri == Recordings.LIST_PATH ->
                newFixedLengthResponse(Response.Status.OK, "application/json", ProtocolJson.encodeRecordings(store.list()))
            uri.startsWith(Recordings.LIST_PATH + "/") -> {
                val file = store.file(uri.removePrefix(Recordings.LIST_PATH + "/"))
                    ?: return text(Response.Status.NOT_FOUND, "not found")
                newFixedLengthResponse(Response.Status.OK, "video/mp4", FileInputStream(file), file.length()).apply {
                    addHeader("Content-Disposition", "attachment; filename=\"${file.name}\"")
                }
            }
            else -> text(Response.Status.NOT_FOUND, "not found")
        }
    }

    private fun text(status: Response.Status, message: String): Response =
        newFixedLengthResponse(status, MIME_PLAINTEXT, message)
}
