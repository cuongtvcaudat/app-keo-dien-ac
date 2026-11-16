package vn.cnldg.oncam.shared.security

/**
 * Ghi nhớ id lệnh đã xử lý trong [ttlSec] giây để từ chối lệnh bị gửi lại (replay).
 *
 * Giới hạn [maxEntries] để không tốn bộ nhớ vô hạn; khi đầy thì bỏ id cũ nhất.
 * An toàn đa luồng.
 */
class ReplayGuard(
    private val ttlSec: Long,
    private val maxEntries: Int = 1024,
) {
    // id → thời điểm hết hạn; LinkedHashMap giữ thứ tự chèn để bỏ id cũ nhất trước.
    private val seen = LinkedHashMap<String, Long>()

    /**
     * Trả về true nếu [id] chưa thấy trong cửa sổ thời gian (đồng thời ghi nhận nó),
     * false nếu là lệnh lặp lại.
     */
    @Synchronized
    fun firstSeen(id: String, nowSec: Long): Boolean {
        purgeExpired(nowSec)
        if (seen.containsKey(id)) return false
        if (seen.size >= maxEntries) {
            seen.remove(seen.keys.first())
        }
        seen[id] = nowSec + ttlSec
        return true
    }

    /** Số id đang được ghi nhớ (phục vụ test/giám sát). */
    @Synchronized
    fun size(): Int = seen.size

    private fun purgeExpired(nowSec: Long) {
        val it = seen.entries.iterator()
        while (it.hasNext()) {
            if (it.next().value <= nowSec) it.remove()
        }
    }
}
