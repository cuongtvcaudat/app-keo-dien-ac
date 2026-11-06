package vn.cnldg.oncam.shared.rtc

/**
 * Thứ tự ưu tiên chuẩn nén video cho máy camera (Phase 7, tối ưu hiệu năng).
 *
 * Nén bằng chip phần cứng nhẹ hơn CPU rất nhiều (máy camera đỡ nóng, đỡ hao pin) → ưu tiên chuẩn mà máy camera
 * có bộ nén phần cứng: H.264 (gần như máy nào cũng có) → VP8 → VP9/AV1/H.265. Không có phần cứng thì dùng VP8
 * phần mềm (nhẹ nhất trong các bộ nén phần mềm của WebRTC). Các mục phụ (rtx: gửi lại gói mất, red/ulpfec: sửa lỗi)
 * giữ nguyên ở cuối.
 */
object CodecPreference {
    private val ORDER = listOf("H264", "VP8", "VP9", "AV1", "H265")
    private val AUX = setOf("RTX", "RED", "ULPFEC", "FLEXFEC-03")

    /**
     * @param names tên codec theo thứ tự WebRTC liệt kê (có thể trùng: mỗi profile H.264 một mục).
     * @param hardware tên các chuẩn có bộ nén phần cứng trên máy (vd. {"H264", "VP8"}).
     * @return chỉ số của [names] theo thứ tự ưu tiên mới (sắp xếp ổn định: cùng hạng giữ thứ tự cũ).
     */
    fun order(names: List<String>, hardware: Set<String>): List<Int> {
        val hw = hardware.map { it.uppercase() }.toSet()
        return names.indices.sortedBy { rank(names[it].uppercase(), hw) }
    }

    /** Có phải mục phụ (không phải chuẩn nén thật) không. */
    fun isAuxiliary(name: String): Boolean = name.uppercase() in AUX

    private fun rank(name: String, hw: Set<String>): Int {
        val known = ORDER.indexOf(name).let { if (it < 0) ORDER.size else it }
        return when {
            name in AUX -> 1_000
            name in hw -> known // phần cứng: H264 → VP8 → …
            name == "VP8" -> 100 // phần mềm nhẹ nhất
            else -> 200 + known
        }
    }
}
