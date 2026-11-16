package vn.cnldg.oncam.viewer.stream

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import vn.cnldg.oncam.shared.LogSink
import vn.cnldg.oncam.shared.client.FrameLatency
import vn.cnldg.oncam.shared.client.HttpStatusException
import vn.cnldg.oncam.shared.client.MjpegReader

/**
 * Địa chỉ luồng + header xác thực (tạo mới mỗi lần kết nối vì header chỉ dùng được 60 giây).
 * @property clockOffsetMs giờ camera − giờ máy xem (ms), đọc lại mỗi khung vì phép đo giờ có thể xong sau khi
 *   đã kết nối luồng; null = chưa đo (coi như 2 máy cùng giờ).
 */
class StreamSource(val url: String, val authHeader: String, val clockOffsetMs: () -> Long? = { null })

/** Giai đoạn của luồng xem trực tiếp. */
enum class StreamPhase { IDLE, WAITING, CONNECTING, PLAYING, RECONNECTING }

/**
 * Trạng thái hiển thị.
 * @property frameSeq tăng mỗi khung mới (bitmap được tái sử dụng nên cần số thứ tự để giao diện vẽ lại).
 * @property latencyMs độ trễ ước lượng = giờ máy xem − giờ chụp trên máy camera, đã trừ độ lệch đồng hồ 2 máy.
 */
data class StreamState(
    val phase: StreamPhase = StreamPhase.IDLE,
    val frame: Bitmap? = null,
    val jpeg: ByteArray? = null,
    val frameSeq: Long = 0,
    val fps: Int? = null,
    val latencyMs: Long? = null,
    val error: String? = null,
) {
    override fun equals(other: Any?): Boolean =
        other is StreamState && other.phase == phase && other.frameSeq == frameSeq && other.error == error &&
            other.fps == fps && other.latencyMs == latencyMs && other.frame === frame

    override fun hashCode(): Int = (phase.hashCode() * 31 + frameSeq.hashCode()) * 31 + (error?.hashCode() ?: 0)
}

/**
 * Đọc luồng MJPEG từ camera và giải mã thành Bitmap để hiển thị.
 *
 * - [play] chạy vòng lặp: chờ có nguồn (camera đang phát) → kết nối → đọc khung → mất kết nối thì thử lại sau 1 s.
 * - Giải mã RGB_565 và tái sử dụng 3 bitmap luân phiên: 720p chỉ tốn ~1,8 MB/khung, không tạo rác
 *   55 MB/giây như cách giải mã mới mỗi khung (máy xem đỡ giật, đỡ nóng).
 */
class StreamClient(
    private val scope: CoroutineScope,
    private val log: LogSink = LogSink.NONE,
) {
    private val _state = MutableStateFlow(StreamState())

    /** Trạng thái hiện tại. */
    val state: StateFlow<StreamState> = _state.asStateFlow()

    private var job: Job? = null

    @Volatile
    private var reader: MjpegReader? = null

    /** Bắt đầu xem; [source] được gọi lại mỗi lần (kết nối lại), trả về null khi camera chưa phát. */
    fun play(source: () -> StreamSource?) {
        stop()
        val pool = BitmapPool()
        job = scope.launch(Dispatchers.IO) {
            var seq = 0L
            var everPlayed = false
            val arrivals = ArrayDeque<Long>()
            while (isActive) {
                val src = source()
                if (src == null) {
                    _state.update { it.copy(phase = StreamPhase.WAITING) }
                    delay(WAIT_POLL_MS)
                    continue
                }
                _state.update { it.copy(phase = if (everPlayed) StreamPhase.RECONNECTING else StreamPhase.CONNECTING) }
                try {
                    val r = MjpegReader.open(src.url, src.authHeader)
                    reader = r
                    r.use {
                        while (isActive) {
                            val f = r.next() ?: break
                            val bmp = pool.decode(f.jpeg) ?: continue
                            val now = System.currentTimeMillis()
                            arrivals.addLast(now)
                            while (arrivals.isNotEmpty() && now - arrivals.first() > FPS_WINDOW_MS) arrivals.removeFirst()
                            everPlayed = true
                            _state.value = StreamState(
                                phase = StreamPhase.PLAYING,
                                frame = bmp,
                                jpeg = f.jpeg,
                                frameSeq = ++seq,
                                fps = arrivals.size,
                                latencyMs = f.capturedAtMs?.let { FrameLatency.of(now, it, src.clockOffsetMs()) },
                            )
                        }
                    }
                } catch (e: HttpStatusException) {
                    _state.update { it.copy(error = "HTTP ${e.code}") }
                } catch (e: IOException) {
                    if (isActive) log.log(LogSink.Level.WARN, "Luồng MJPEG lỗi: ${e.message}", null)
                    _state.update { it.copy(error = e.message) }
                } finally {
                    reader = null
                }
                if (isActive) {
                    _state.update { it.copy(phase = StreamPhase.RECONNECTING) }
                    delay(RETRY_MS)
                }
            }
        }
    }

    /** Dừng xem và đóng kết nối ngay (kể cả khi đang chờ khung). */
    fun stop() {
        job?.cancel()
        job = null
        reader?.close()
        reader = null
        _state.value = StreamState()
    }

    /** 3 bitmap luân phiên: khung đang hiển thị không bị ghi đè khi giải mã khung tiếp theo. */
    private class BitmapPool {
        private val buffers = arrayOfNulls<Bitmap>(3)
        private var index = 0

        fun decode(jpeg: ByteArray): Bitmap? {
            val options = BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.RGB_565
                inMutable = true
                inBitmap = buffers[index]
            }
            val bmp = try {
                BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, options)
            } catch (_: IllegalArgumentException) {
                // Bitmap cũ không vừa (vd. vừa đổi chất lượng) → giải mã vào bitmap mới.
                options.inBitmap = null
                BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, options)
            } ?: return null
            buffers[index] = bmp
            index = (index + 1) % buffers.size
            return bmp
        }
    }

    private companion object {
        const val WAIT_POLL_MS = 300L
        const val RETRY_MS = 1_000L
        const val FPS_WINDOW_MS = 1_000L
    }
}
