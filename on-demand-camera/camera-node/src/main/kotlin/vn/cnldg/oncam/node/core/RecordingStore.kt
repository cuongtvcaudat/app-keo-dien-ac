package vn.cnldg.oncam.node.core

import java.io.File
import vn.cnldg.oncam.shared.protocol.RecordingInfo
import vn.cnldg.oncam.shared.protocol.Recordings

/**
 * Thư mục video trên máy camera: đặt tên file, liệt kê, giữ tổng dung lượng ≤ [maxBytes] (xoá clip cũ nhất trước).
 * Thư mục riêng của app (không cần quyền bộ nhớ, gỡ app là xoá theo).
 */
class RecordingStore(
    val dir: File,
    private val maxBytes: Long = 2L * 1024 * 1024 * 1024,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    init {
        dir.mkdirs()
    }

    /** File mới cho clip sắp quay (tên theo giờ UTC, không trùng). */
    @Synchronized
    fun newFile(): File {
        val now = nowMs()
        var i = 0
        while (true) {
            val f = File(dir, Recordings.nameAt(now, i))
            if (!f.exists()) return f
            i++
        }
    }

    /** Các clip đã quay xong (mới nhất trước); bỏ file rỗng/đang ghi dở có tên không hợp lệ. */
    fun list(): List<RecordingInfo> =
        (dir.listFiles() ?: emptyArray())
            .filter { it.isFile && Recordings.isValidName(it.name) && it.length() > 0 }
            .sortedByDescending { it.lastModified() }
            .map { RecordingInfo(name = it.name, sizeBytes = it.length(), createdAt = it.lastModified() / 1000) }

    /** File của clip [name]; null nếu tên không hợp lệ (chặn "../") hoặc không có. */
    fun file(name: String): File? =
        if (Recordings.isValidName(name)) File(dir, name).takeIf { it.isFile } else null

    /** Xoá clip cũ nhất cho tới khi tổng dung lượng ≤ giới hạn; giữ lại [keep] (clip vừa quay). */
    @Synchronized
    fun enforceLimit(keep: File? = null): Int {
        val files = (dir.listFiles() ?: emptyArray()).filter { it.isFile && Recordings.isValidName(it.name) }
            .sortedBy { it.lastModified() }
        var total = files.sumOf { it.length() }
        var deleted = 0
        for (f in files) {
            if (total <= maxBytes) break
            if (f == keep) continue
            val len = f.length()
            if (f.delete()) {
                total -= len
                deleted++
            }
        }
        return deleted
    }
}
