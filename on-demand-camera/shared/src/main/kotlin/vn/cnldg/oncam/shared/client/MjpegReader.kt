package vn.cnldg.oncam.shared.client

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import vn.cnldg.oncam.shared.protocol.StreamPaths
import vn.cnldg.oncam.shared.security.StreamAuth

/**
 * Đọc luồng MJPEG (`multipart/x-mixed-replace`) từ camera-node, từng khung JPEG một.
 *
 * Chặn luồng (blocking) — gọi trên luồng nền. Không phụ thuộc Android nên test được trên JVM
 * với MJPEG server thật của camera-node.
 */
class MjpegReader private constructor(
    private val connection: HttpURLConnection?,
    input: InputStream,
    boundary: String,
) : Closeable {

    /** Một khung: dữ liệu JPEG và thời điểm chụp theo đồng hồ máy camera (nếu có). */
    class Frame(val jpeg: ByteArray, val capturedAtMs: Long?)

    private val input = BufferedInputStream(input, BUFFER_SIZE)
    private val delimiter = "--$boundary"

    @Volatile
    private var closed = false

    /**
     * Đọc khung tiếp theo.
     * @return null khi luồng kết thúc (server đóng, hoặc gặp ranh giới cuối).
     * @throws IOException khi mất kết nối / quá thời gian đọc / dữ liệu sai định dạng.
     */
    fun next(): Frame? {
        // Bỏ qua dòng trống / phần mở đầu cho tới ranh giới.
        while (true) {
            val line = readLine() ?: return null
            if (line == "$delimiter--") return null
            if (line == delimiter) break
        }
        var length = -1
        var capturedAt: Long? = null
        while (true) {
            val line = readLine() ?: return null
            if (line.isEmpty()) break
            val colon = line.indexOf(':')
            if (colon <= 0) continue
            val name = line.substring(0, colon).trim()
            val value = line.substring(colon + 1).trim()
            when {
                name.equals("Content-Length", ignoreCase = true) -> length = value.toIntOrNull() ?: -1
                name.equals(StreamPaths.TIMESTAMP_HEADER, ignoreCase = true) -> capturedAt = value.toLongOrNull()
            }
        }
        val jpeg = if (length in 0..MAX_FRAME_BYTES) readExactly(length) else readUntilJpegEnd()
        return Frame(jpeg, capturedAt)
    }

    /** Đóng kết nối (gọi được từ luồng khác để dừng [next] đang chờ). */
    override fun close() {
        if (closed) return
        closed = true
        try {
            input.close()
        } catch (_: IOException) {
            // Bỏ qua khi đóng.
        }
        connection?.disconnect()
    }

    private fun readLine(): String? {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(b.toChar())
            if (sb.length > MAX_LINE) throw IOException("Dòng header MJPEG quá dài")
        }
    }

    private fun readExactly(n: Int): ByteArray {
        val out = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = input.read(out, off, n - off)
            if (r < 0) throw EOFException("Luồng MJPEG bị cắt giữa khung")
            off += r
        }
        return out
    }

    /** Dự phòng khi server không gửi Content-Length: đọc tới mã kết thúc JPEG FF D9. */
    private fun readUntilJpegEnd(): ByteArray {
        val out = ByteArrayOutputStream()
        var prev = -1
        while (true) {
            val b = input.read()
            if (b < 0) throw EOFException("Luồng MJPEG bị cắt giữa khung")
            out.write(b)
            if (prev == 0xFF && b == 0xD9) return out.toByteArray()
            if (out.size() > MAX_FRAME_BYTES) throw IOException("Khung MJPEG quá lớn")
            prev = b
        }
    }

    companion object {
        private const val BUFFER_SIZE = 64 * 1024
        private const val MAX_LINE = 1024
        private const val MAX_FRAME_BYTES = 8 * 1024 * 1024

        /**
         * Mở luồng [url] với header xác thực [authHeader] ([StreamAuth.headerValue]).
         * @throws HttpStatusException nếu camera trả mã khác 200 (401 sai xác thực, 503 quá nhiều người xem...).
         * @throws IOException khi không kết nối được.
         */
        fun open(url: String, authHeader: String, connectTimeoutMs: Int = 5_000, readTimeoutMs: Int = 10_000): MjpegReader {
            val conn = URI(url).toURL().openConnection() as HttpURLConnection
            conn.connectTimeout = connectTimeoutMs
            conn.readTimeout = readTimeoutMs
            conn.useCaches = false
            conn.setRequestProperty(StreamAuth.HEADER, authHeader)
            try {
                val code = conn.responseCode
                if (code != HttpURLConnection.HTTP_OK) throw HttpStatusException(code)
                val boundary = boundaryOf(conn.contentType) ?: throw IOException("Không phải luồng MJPEG: ${conn.contentType}")
                return MjpegReader(conn, conn.inputStream, boundary)
            } catch (e: IOException) {
                conn.disconnect()
                throw e
            }
        }

        /** Đọc từ luồng có sẵn (dùng trong test). */
        fun fromStream(input: InputStream, boundary: String): MjpegReader = MjpegReader(null, input, boundary)

        /** Lấy boundary từ Content-Type `multipart/x-mixed-replace; boundary=...`. */
        fun boundaryOf(contentType: String?): String? {
            if (contentType == null || !contentType.startsWith("multipart/", ignoreCase = true)) return null
            return contentType.split(';')
                .map { it.trim() }
                .firstOrNull { it.startsWith("boundary=", ignoreCase = true) }
                ?.substringAfter('=')
                ?.trim('"')
                ?.removePrefix("--")
                ?.takeIf { it.isNotEmpty() }
        }
    }
}

/** Camera trả mã HTTP khác 200 khi mở luồng. */
class HttpStatusException(val code: Int) : IOException("HTTP $code")
