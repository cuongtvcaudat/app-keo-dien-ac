package vn.cnldg.oncam.node.core

import java.nio.ByteBuffer
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals

/** Bộ chuyển YUV nhanh phải ra đúng từng byte như cách đọc từng điểm ảnh (cách cũ) với mọi kiểu bố trí bộ nhớ. */
class YuvToNv21Test {

    private val w = 64
    private val h = 48
    private val rnd = Random(42)

    @Test
    fun `mat U V tach roi (I420) co dem cuoi hang`() = check(layout = Layout.PLANAR, yPad = 16, cPad = 8)

    @Test
    fun `U V xen ke kieu NV12 (U truoc)`() = check(layout = Layout.UV_INTERLEAVED, yPad = 0, cPad = 0)

    @Test
    fun `U V xen ke kieu NV21 (V truoc) co dem`() = check(layout = Layout.VU_INTERLEAVED, yPad = 32, cPad = 32)

    @Test
    fun `chuyen nhieu khung lien tiep dung lai mang tam`() {
        val conv = YuvToNv21()
        repeat(3) { check(Layout.UV_INTERLEAVED, 8, 8, conv) }
    }

    private enum class Layout { PLANAR, UV_INTERLEAVED, VU_INTERLEAVED }

    private fun check(layout: Layout, yPad: Int, cPad: Int, conv: YuvToNv21 = YuvToNv21()) {
        val yStride = w + yPad
        val y = YuvPlane(ByteBuffer.allocateDirect(yStride * h).also(::fill), yStride, 1)
        val (u, v) = when (layout) {
            Layout.PLANAR -> {
                val s = w / 2 + cPad
                YuvPlane(direct(s * h / 2), s, 1) to YuvPlane(direct(s * h / 2), s, 1)
            }
            else -> {
                // Như Android: một vùng nhớ xen kẽ, 2 mặt lệch nhau 1 byte, mỗi mặt thiếu byte cuối.
                val s = w + cPad
                val mem = direct(s * h / 2)
                val first = mem.duplicate().apply { limit(s * (h / 2 - 1) + w - 1) }.slice()
                val second = mem.duplicate().apply { position(1); limit(s * (h / 2 - 1) + w) }.slice()
                if (layout == Layout.UV_INTERLEAVED) {
                    YuvPlane(first, s, 2) to YuvPlane(second, s, 2)
                } else {
                    YuvPlane(second, s, 2) to YuvPlane(first, s, 2)
                }
            }
        }
        assertContentEquals(reference(y, u, v), conv.convert(y, u, v, w, h))
    }

    private fun direct(size: Int) = ByteBuffer.allocateDirect(size).also(::fill)

    private fun fill(b: ByteBuffer) {
        for (i in 0 until b.capacity()) b.put(i, rnd.nextInt(256).toByte())
    }

    /** Cách cũ (đọc từng byte) làm chuẩn so sánh. */
    private fun reference(y: YuvPlane, u: YuvPlane, v: YuvPlane): ByteArray {
        val out = ByteArray(w * h * 3 / 2)
        var o = 0
        for (row in 0 until h) for (col in 0 until w) out[o++] = y.buffer.get(row * y.rowStride + col * y.pixelStride)
        for (row in 0 until h / 2) {
            for (col in 0 until w / 2) {
                out[o++] = v.buffer.get(row * v.rowStride + col * v.pixelStride)
                out[o++] = u.buffer.get(row * u.rowStride + col * u.pixelStride)
            }
        }
        return out
    }
}
