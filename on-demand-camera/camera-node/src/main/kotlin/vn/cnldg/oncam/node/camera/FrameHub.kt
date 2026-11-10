package vn.cnldg.oncam.node.camera

import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Giữ khung JPEG mới nhất và đánh thức các luồng HTTP đang chờ khung tiếp theo.
 *
 * Camera chỉ mã hoá JPEG một lần cho mỗi khung, dù có nhiều người xem. Người xem chậm sẽ bỏ qua khung cũ
 * (luôn lấy khung mới nhất), không làm chậm camera.
 */
class FrameHub {
    private val lock = ReentrantLock()
    private val newFrame = lock.newCondition()
    private var frame: ByteArray? = null
    private var frameAtMs = 0L
    private var seq = 0L
    private var closed = false

    /** Một khung kèm số thứ tự và thời điểm chụp (ms). */
    class Frame(val seq: Long, val jpeg: ByteArray, val capturedAtMs: Long)

    /** Đưa khung mới vào (luồng camera). */
    fun publish(jpeg: ByteArray) = lock.withLock {
        if (closed) return@withLock
        frame = jpeg
        frameAtMs = System.currentTimeMillis()
        seq++
        newFrame.signalAll()
    }

    /** Khung mới nhất, null nếu chưa có. */
    fun latest(): ByteArray? = lock.withLock { frame }

    /**
     * Chờ khung có số thứ tự lớn hơn [afterSeq] tối đa [timeoutMs].
     * @return null nếu hết thời gian hoặc hub đã đóng.
     */
    fun awaitAfter(afterSeq: Long, timeoutMs: Long): Frame? = lock.withLock {
        var remainingNs = TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (!closed && seq <= afterSeq) {
            if (remainingNs <= 0) return null
            remainingNs = newFrame.awaitNanos(remainingNs)
        }
        val current = frame
        if (closed || current == null) null else Frame(seq, current, frameAtMs)
    }

    /** Đóng hub: các luồng đang chờ được đánh thức và nhận null. */
    fun close() = lock.withLock {
        closed = true
        frame = null
        newFrame.signalAll()
    }

    /** Hub đã đóng chưa. */
    val isClosed: Boolean get() = lock.withLock { closed }
}
