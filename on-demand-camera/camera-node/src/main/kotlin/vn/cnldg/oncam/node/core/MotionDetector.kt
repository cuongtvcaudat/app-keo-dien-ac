package vn.cnldg.oncam.node.core

import java.nio.ByteBuffer
import kotlin.math.abs
import vn.cnldg.oncam.shared.protocol.MotionSensitivity

/** Kết quả phân tích một khung: [changedPercent] % ô lưới đổi; [motion] = có chuyển động (đã lọc nhiễu). */
data class MotionResult(val changedPercent: Double, val motion: Boolean)

/**
 * Phát hiện chuyển động trên kênh độ sáng (Y) của khung camera, rất nhẹ (~20 nghìn phép đọc mỗi khung 640×480):
 *
 * 1. Chia khung thành lưới [gridW]×[gridH] ô, lấy độ sáng trung bình mỗi ô (đọc 1 điểm mỗi [step] điểm ảnh).
 * 2. So với ảnh nền (trung bình trượt, tự thích nghi khi trời sáng/tối dần), sau khi trừ chênh độ sáng chung
 *    của cả khung (camera tự chỉnh phơi sáng không bị tính là chuyển động).
 * 3. Ô đổi > [MotionSensitivity.cellDelta] tính là "đổi"; ≥ [MotionSensitivity.areaPercent] % ô đổi trong
 *    [confirmFrames] khung liên tiếp → có chuyển động (lọc 1 khung nhiễu).
 * 4. Hơn [lightingPercent] % ô đổi cùng lúc (bật/tắt đèn, mây che nắng) → coi là đổi ánh sáng: học lại nền, không báo.
 *
 * Chỉ phân tích tối đa 1 khung mỗi [minIntervalMs]. [analyze] gọi từ một luồng (luồng camera); [reset] và
 * [sensitivity] đổi được từ luồng khác.
 */
class MotionDetector(
    @Volatile var sensitivity: MotionSensitivity = MotionSensitivity.MEDIUM,
    private val gridW: Int = 32,
    private val gridH: Int = 24,
    private val step: Int = 4,
    private val minIntervalMs: Long = 250,
    private val warmupFrames: Int = 4,
    private val confirmFrames: Int = 2,
    private val lightingPercent: Double = 70.0,
    private val learnRate: Float = 0.05f,
) {
    private val cells = FloatArray(gridW * gridH)
    private var background: FloatArray? = null
    private var learned = 0
    private var consecutive = 0
    private var lastMs = Long.MIN_VALUE

    @Volatile
    private var resetRequested = false

    /** Quên nền (đổi camera/độ phân giải, bật lại canh) — áp dụng ở khung kế tiếp. */
    fun reset() {
        resetRequested = true
    }

    /**
     * Phân tích kênh Y ([y] bắt đầu ở vị trí 0, mỗi hàng [rowStride] byte, điểm ảnh liền nhau).
     * @return null nếu bỏ qua khung (chưa đủ [minIntervalMs] từ lần trước, hoặc khung quá nhỏ).
     */
    fun analyze(y: ByteBuffer, rowStride: Int, width: Int, height: Int, nowMs: Long): MotionResult? {
        if (resetRequested) {
            resetRequested = false
            background = null
            learned = 0
            consecutive = 0
            lastMs = Long.MIN_VALUE
        }
        if (lastMs != Long.MIN_VALUE && nowMs - lastMs < minIntervalMs) return null
        val cellW = width / gridW
        val cellH = height / gridH
        if (cellW < 1 || cellH < 1) return null
        lastMs = nowMs
        measure(y, rowStride, cellW, cellH)

        val bg = background
        if (bg == null || bg.size != cells.size) {
            background = cells.copyOf()
            learned = 1
            consecutive = 0
            return MotionResult(0.0, false)
        }
        if (learned < warmupFrames) {
            learn(bg, 0.5f)
            learned++
            return MotionResult(0.0, false)
        }

        // Chênh độ sáng chung (camera tự chỉnh phơi sáng) — trừ đi trước khi so từng ô.
        var shift = 0f
        for (i in cells.indices) shift += cells[i] - bg[i]
        shift /= cells.size
        val delta = sensitivity.cellDelta
        var changed = 0
        for (i in cells.indices) if (abs(cells[i] - bg[i] - shift) > delta) changed++
        val percent = changed * 100.0 / cells.size

        if (percent >= lightingPercent) {
            // Bật đèn / mây che: học lại nền ngay, không báo.
            background = cells.copyOf()
            learned = warmupFrames
            consecutive = 0
            return MotionResult(percent, false)
        }
        val above = percent >= sensitivity.areaPercent
        consecutive = if (above) consecutive + 1 else 0
        // Đang có chuyển động thì học chậm hơn (vật dừng lại lâu, ví dụ xe đỗ, vẫn dần thành nền).
        learn(bg, if (above) learnRate / 3 else learnRate)
        return MotionResult(percent, consecutive >= confirmFrames)
    }

    private fun measure(y: ByteBuffer, rowStride: Int, cellW: Int, cellH: Int) {
        val limit = y.limit()
        for (gy in 0 until gridH) {
            for (gx in 0 until gridW) {
                var sum = 0
                var n = 0
                var py = gy * cellH + step / 2
                val yEnd = (gy + 1) * cellH
                while (py < yEnd) {
                    val row = py * rowStride
                    var px = gx * cellW + step / 2
                    val xEnd = (gx + 1) * cellW
                    while (px < xEnd) {
                        val idx = row + px
                        if (idx < limit) {
                            sum += y.get(idx).toInt() and 0xFF
                            n++
                        }
                        px += step
                    }
                    py += step
                }
                cells[gy * gridW + gx] = if (n > 0) sum.toFloat() / n else 0f
            }
        }
    }

    private fun learn(bg: FloatArray, rate: Float) {
        for (i in bg.indices) bg[i] += (cells[i] - bg[i]) * rate
    }
}
