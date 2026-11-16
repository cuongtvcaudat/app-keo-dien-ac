package vn.cnldg.oncam.shared.client

/** Tính độ trễ hình (từ lúc máy camera chụp đến lúc máy xem nhận) khi 2 máy có thể lệch giờ. */
object FrameLatency {
    /** Sai số cho phép của phép đo giờ (≈ nửa thời gian khứ hồi PING qua Tailscale). */
    const val CLOCK_TOLERANCE_MS: Long = 300

    /** Trễ hơn mức này coi là số đo sai (thường do chưa đo được độ lệch giờ). */
    const val MAX_PLAUSIBLE_MS: Long = 60_000

    /**
     * @param nowMs lúc nhận khung (giờ máy xem).
     * @param capturedAtMs lúc chụp khung (giờ máy camera, header `X-OnCam-Timestamp`).
     * @param clockOffsetMs giờ camera − giờ máy xem; null = chưa đo (coi như cùng giờ).
     * @return độ trễ ms; âm nhẹ (≤ [CLOCK_TOLERANCE_MS]) coi là 0; âm nhiều hoặc quá [MAX_PLAUSIBLE_MS] → null.
     */
    fun of(nowMs: Long, capturedAtMs: Long, clockOffsetMs: Long?): Long? {
        val d = nowMs - capturedAtMs + (clockOffsetMs ?: 0)
        return when {
            d in 0..MAX_PLAUSIBLE_MS -> d
            d >= -CLOCK_TOLERANCE_MS && d < 0 -> 0
            else -> null
        }
    }
}
