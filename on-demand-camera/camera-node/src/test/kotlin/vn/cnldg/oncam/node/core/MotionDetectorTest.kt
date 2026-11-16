package vn.cnldg.oncam.node.core

import java.nio.ByteBuffer
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import vn.cnldg.oncam.shared.protocol.MotionSensitivity

/** Phát hiện chuyển động trên khung giả 640×480 (có nhiễu cảm biến như ảnh thật). */
class MotionDetectorTest {

    private val w = 640
    private val h = 480
    private val random = Random(42)
    private var t = 0L

    /** Khung Y: nền xám 100 ± nhiễu 8, [paint] vẽ thêm (trả độ sáng mới cho điểm x,y hoặc null). */
    private fun frame(stride: Int = w, paint: (Int, Int, Int) -> Int? = { _, _, _ -> null }): ByteBuffer {
        val buf = ByteArray(stride * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val base = 100 + random.nextInt(-8, 9)
                buf[y * stride + x] = (paint(x, y, base) ?: base).coerceIn(0, 255).toByte()
            }
        }
        return ByteBuffer.wrap(buf)
    }

    private fun MotionDetector.feed(buf: ByteBuffer, stride: Int = w): MotionResult? {
        t += 300
        return analyze(buf, stride, w, h, t)
    }

    private fun MotionDetector.warm(n: Int = 6) = repeat(n) { feed(frame()) }

    @Test
    fun `canh dung yen co nhieu khong bao chuyen dong`() {
        val d = MotionDetector(MotionSensitivity.HIGH)
        repeat(40) { assertFalse(assertNotNullResult(d.feed(frame())).motion, "khung $it") }
    }

    @Test
    fun `nguoi di ngang san thi bao sau 2 khung`() {
        val d = MotionDetector(MotionSensitivity.MEDIUM)
        d.warm()
        // Khối 80×120 (≈ 3 % khung, người ở xa ~10 m) sáng hơn nền, đi ngang 40 px mỗi khung.
        val results = (0 until 6).map { i ->
            val left = 100 + i * 40
            assertNotNullResult(d.feed(frame { x, y, _ -> if (x in left until left + 80 && y in 200 until 320) 170 else null }))
        }
        assertFalse(results[0].motion, "1 khung chưa đủ (lọc nhiễu)")
        assertTrue(results[1].motion, "khung thứ 2 liên tiếp → có chuyển động")
        assertTrue(results.drop(1).all { it.motion })
        assertTrue(results[1].changedPercent >= MotionSensitivity.MEDIUM.areaPercent)
    }

    @Test
    fun `camera tu chinh phoi sang ca khung khong tinh la chuyen dong`() {
        val d = MotionDetector(MotionSensitivity.HIGH)
        d.warm()
        repeat(5) { i ->
            val r = assertNotNullResult(d.feed(frame { _, _, base -> base + 10 * (i + 1) }))
            assertFalse(r.motion, "sáng dần +${10 * (i + 1)}")
        }
    }

    @Test
    fun `bat den khong deu ca khung la doi anh sang - hoc lai nen khong bao`() {
        val d = MotionDetector(MotionSensitivity.MEDIUM)
        d.warm()
        val lit = { x: Int, _: Int, base: Int -> base + if (x < w / 2) 110 else 30 }
        val r = assertNotNullResult(d.feed(frame(paint = lit)))
        assertFalse(r.motion)
        assertTrue(r.changedPercent >= 70.0)
        // Đèn vẫn sáng như thế: nền đã học lại → các khung sau yên.
        repeat(4) { assertFalse(assertNotNullResult(d.feed(frame(paint = lit))).motion) }
    }

    @Test
    fun `vat nho mot o khong bao`() {
        val d = MotionDetector(MotionSensitivity.HIGH)
        d.warm()
        repeat(5) { i ->
            val on = i % 2 == 0
            val r = assertNotNullResult(d.feed(frame { x, y, _ -> if (on && x in 300 until 320 && y in 200 until 220) 250 else null }))
            assertFalse(r.motion, "con côn trùng 20×20 px")
        }
    }

    @Test
    fun `do nhay thap bo qua vat vua - do nhay cao thi bao`() {
        // Khối 70×70 ≈ 1,6 % khung: vượt ngưỡng HIGH (1 %) nhưng dưới LOW (5 %).
        fun run(s: MotionSensitivity): Boolean {
            val d = MotionDetector(s)
            d.warm()
            return (0 until 4).map { i ->
                val left = 100 + i * 80
                assertNotNullResult(d.feed(frame { x, y, _ -> if (x in left until left + 70 && y in 200 until 270) 200 else null })).motion
            }.any { it }
        }
        assertTrue(run(MotionSensitivity.HIGH))
        assertFalse(run(MotionSensitivity.LOW))
    }

    @Test
    fun `hang co dem byte cuoi hang van dung`() {
        val stride = 704
        val d = MotionDetector(MotionSensitivity.MEDIUM)
        repeat(6) { d.feed(frame(stride), stride) }
        repeat(4) { assertFalse(assertNotNullResult(d.feed(frame(stride), stride)).motion) }
        val moving = (0 until 3).map { i ->
            val left = 100 + i * 50
            d.feed(frame(stride) { x, y, _ -> if (x in left until left + 80 && y in 150 until 330) 30 else null }, stride)!!.motion
        }
        assertEquals(listOf(false, true, true), moving)
    }

    @Test
    fun `chi phan tich 4 khung moi giay`() {
        val d = MotionDetector()
        val f = frame()
        assertTrue(d.analyze(f, w, w, h, 1_000) != null)
        assertNull(d.analyze(f, w, w, h, 1_100))
        assertNull(d.analyze(f, w, w, h, 1_249))
        assertTrue(d.analyze(f, w, w, h, 1_250) != null)
    }

    private fun assertNotNullResult(r: MotionResult?): MotionResult = checkNotNull(r) { "khung bị bỏ qua" }
}
