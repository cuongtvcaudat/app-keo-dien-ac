package vn.cnldg.oncam.node.core

import java.io.InputStream
import java.net.HttpURLConnection
import java.net.Socket
import java.net.URI
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import vn.cnldg.oncam.shared.protocol.CameraCodes
import vn.cnldg.oncam.shared.protocol.CameraFacing
import vn.cnldg.oncam.shared.protocol.StreamPaths
import vn.cnldg.oncam.shared.protocol.StreamQuality
import vn.cnldg.oncam.shared.security.StreamAuth

/** Camera giả: phát khung "JPEG" nhỏ mỗi 30 ms để test logic stream trên JVM. */
class FakeCameraPort(private val scope: CoroutineScope) : CameraPort {
    var available = setOf(CameraFacing.BACK, CameraFacing.FRONT)
    var flashOn = setOf(CameraFacing.BACK)
    var failOpenWith: String? = null

    @Volatile var opened: Pair<CameraFacing, StreamQuality>? = null
    @Volatile var openCount = 0
    @Volatile var torch = false

    private var producer: Job? = null
    val errorFlow = MutableSharedFlow<String>(extraBufferCapacity = 4)
    override val errors = errorFlow

    override suspend fun hasCamera(facing: CameraFacing) = facing in available

    override suspend fun open(facing: CameraFacing, quality: StreamQuality, sink: (ByteArray) -> Unit): OpenResult {
        failOpenWith?.let { return OpenResult.Failed(it) }
        if (facing !in available) return OpenResult.Failed(CameraCodes.NO_CAMERA)
        producer?.cancel()
        opened = facing to quality
        openCount++
        torch = false
        producer = scope.launch {
            var i = 0
            while (isActive) {
                sink(fakeJpeg(i++))
                delay(30)
            }
        }
        return OpenResult.Opened(hasFlash = facing in flashOn)
    }

    override suspend fun setTorch(on: Boolean): Boolean {
        val current = opened ?: return false
        if (on && current.first !in flashOn) return false
        torch = on
        return true
    }

    // --- quay video giả: ghi 1 KB rồi báo xong khi dừng / hết giờ / đóng camera ---
    var recordingSupported = true
    @Volatile var recordingFile: java.io.File? = null
    @Volatile var recordingAudio = false
    private var recordingCallback: ((RecordingOutcome) -> Unit)? = null

    override suspend fun startRecording(
        file: java.io.File,
        maxMs: Long,
        withAudio: Boolean,
        onFinished: (RecordingOutcome) -> Unit,
    ): Boolean {
        if (!recordingSupported || opened == null) return false
        file.writeBytes(ByteArray(1024) { 7 })
        recordingFile = file
        recordingAudio = withAudio
        recordingCallback = onFinished
        scope.launch {
            delay(maxMs)
            finishRecording(file)
        }
        return true
    }

    override suspend fun stopRecording() {
        recordingFile?.let { finishRecording(it) }
    }

    @Synchronized
    private fun finishRecording(file: java.io.File) {
        if (recordingFile != file) return
        val cb = recordingCallback
        recordingFile = null
        recordingCallback = null
        cb?.invoke(RecordingOutcome.Saved(file, file.length(), recordingAudio))
    }

    override suspend fun close() {
        recordingFile?.let { finishRecording(it) } // CameraX đóng camera cũng hoàn tất clip
        producer?.cancel()
        producer = null
        opened = null
        torch = false
    }

    companion object {
        /** "JPEG" giả: có đúng mã đầu FFD8 và mã cuối FFD9. */
        fun fakeJpeg(i: Int) = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), i.toByte(), 0xFF.toByte(), 0xD9.toByte())
    }
}

/** Kết quả một request HTTP đơn giản. */
data class HttpResult(val code: Int, val body: ByteArray, val contentType: String?)

/** GET [url], kèm header xác thực nếu có. */
fun httpGet(url: String, authHeader: String?): HttpResult {
    val conn = URI(url).toURL().openConnection() as HttpURLConnection
    conn.connectTimeout = 3_000
    conn.readTimeout = 3_000
    authHeader?.let { conn.setRequestProperty(StreamAuth.HEADER, it) }
    return try {
        val code = conn.responseCode
        val stream: InputStream? = if (code < 400) conn.inputStream else conn.errorStream
        HttpResult(code, stream?.use { it.readBytes() } ?: ByteArray(0), conn.contentType)
    } finally {
        conn.disconnect()
    }
}

/**
 * Mở luồng MJPEG bằng socket thô (để chủ động ngắt như người xem tắt app),
 * đọc tới khi thấy [parts] phần khung hình. Trả về socket còn mở + số phần đã thấy.
 */
fun openMjpeg(host: String, port: Int, authHeader: String, parts: Int): Pair<Socket, Int> {
    val socket = Socket(host, port)
    socket.soTimeout = 5_000
    val request = "GET ${StreamPaths.STREAM} HTTP/1.1\r\nHost: $host\r\n${StreamAuth.HEADER}: $authHeader\r\n\r\n"
    socket.getOutputStream().apply {
        write(request.toByteArray(Charsets.US_ASCII))
        flush()
    }
    val marker = "--${StreamPaths.BOUNDARY}".toByteArray(Charsets.US_ASCII)
    val input = socket.getInputStream()
    val seen = StringBuilder()
    var found = 0
    val buf = ByteArray(4096)
    while (found < parts) {
        val n = input.read(buf)
        if (n <= 0) break
        seen.append(String(buf, 0, n, Charsets.ISO_8859_1))
        found = Regex(Regex.escape(String(marker, Charsets.ISO_8859_1))).findAll(seen).count()
    }
    return socket to found
}
