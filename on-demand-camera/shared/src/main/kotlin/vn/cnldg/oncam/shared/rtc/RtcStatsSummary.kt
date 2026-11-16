package vn.cnldg.oncam.shared.rtc

/**
 * Tóm tắt phiên WebRTC để hiện cho người dùng: chuẩn nén, độ phân giải, lượng dữ liệu đang nhận.
 *
 * @property codec tên chuẩn nén video, vd. "H264", "VP8".
 * @property kbps tổng dữ liệu nhận (hình + tiếng), kbit/s, tính giữa 2 lần đo.
 * @property totalBytes tổng byte đã nhận từ đầu phiên.
 */
data class RtcStatsSummary(
    val codec: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val kbps: Int? = null,
    val totalBytes: Long = 0,
) {
    companion object {
        /**
         * Đọc báo cáo getStats của WebRTC (đã chuyển sang Map thuần để test được trên JVM).
         *
         * @param stats id → (loại, các trường) của từng mục trong báo cáo.
         * @param previous lần đo trước (để tính kbit/s), null nếu là lần đầu.
         * @param elapsedMs thời gian giữa 2 lần đo.
         */
        fun from(stats: Map<String, Pair<String, Map<String, Any?>>>, previous: RtcStatsSummary?, elapsedMs: Long): RtcStatsSummary {
            val inbound = stats.values.filter { it.first == "inbound-rtp" }.map { it.second }
            val video = inbound.firstOrNull { it["kind"] == "video" }
            val total = inbound.sumOf { (it["bytesReceived"] as? Number)?.toLong() ?: 0L }
            val codec = (video?.get("codecId") as? String)
                ?.let { stats[it]?.second?.get("mimeType") as? String }
                ?.substringAfter('/')
            val kbps = if (previous != null && elapsedMs > 0 && total >= previous.totalBytes) {
                ((total - previous.totalBytes) * 8 / elapsedMs).toInt()
            } else {
                null
            }
            return RtcStatsSummary(
                codec = codec,
                width = (video?.get("frameWidth") as? Number)?.toInt(),
                height = (video?.get("frameHeight") as? Number)?.toInt(),
                kbps = kbps,
                totalBytes = total,
            )
        }
    }
}
