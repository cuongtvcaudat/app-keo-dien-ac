package vn.cnldg.oncam.node

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.nio.ByteBuffer
import kotlin.test.assertContentEquals
import org.junit.Test
import org.junit.runner.RunWith
import vn.cnldg.oncam.node.core.YuvPlane
import vn.cnldg.oncam.node.core.YuvToNv21

/**
 * Đo chuyển YUV→NV21 trên ART (Android) ở 1280×720, U/V xen kẽ như CameraX: cách chép cả hàng ([YuvToNv21])
 * so với đọc từng byte (cách cũ). In kết quả ra logcat (thẻ OnCamBench) để so trên emulator CI.
 */
@RunWith(AndroidJUnit4::class)
class YuvToNv21OnDeviceTest {

    @Test
    fun doTocDoChuyenMau720p() {
        val w = 1280
        val h = 720
        val s = w
        val y = YuvPlane(ByteBuffer.allocateDirect(s * h).also(::fill), s, 1)
        val mem = ByteBuffer.allocateDirect(s * h / 2).also(::fill)
        val u = YuvPlane(mem.duplicate().apply { limit(s * (h / 2 - 1) + w - 1) }.slice(), s, 2)
        val v = YuvPlane(mem.duplicate().apply { position(1); limit(s * (h / 2 - 1) + w) }.slice(), s, 2)
        val conv = YuvToNv21()

        assertContentEquals(perByte(y, u, v, w, h), conv.convert(y, u, v, w, h), "kết quả phải giống hệt cách cũ")

        repeat(30) { perByte(y, u, v, w, h); conv.convert(y, u, v, w, h) }
        val n = 60
        var t = System.nanoTime()
        repeat(n) { perByte(y, u, v, w, h) }
        val oldMs = (System.nanoTime() - t) / 1e6 / n
        t = System.nanoTime()
        repeat(n) { conv.convert(y, u, v, w, h) }
        val newMs = (System.nanoTime() - t) / 1e6 / n
        Log.i("OnCamBench", "YUV→NV21 720p trên ART: đọc từng byte %.2f ms/khung, chép cả hàng %.2f ms/khung (x%.1f)".format(oldMs, newMs, oldMs / newMs))
    }

    private fun fill(b: ByteBuffer) {
        for (i in 0 until b.capacity()) b.put(i, (i * 31).toByte())
    }

    /** Cách cũ: đọc từng byte bằng ByteBuffer.get(index). */
    private fun perByte(y: YuvPlane, u: YuvPlane, v: YuvPlane, w: Int, h: Int): ByteArray {
        val out = ByteArray(w * h * 3 / 2)
        var o = 0
        val yb = y.buffer.duplicate()
        for (row in 0 until h) {
            yb.position(row * y.rowStride)
            yb.get(out, o, w)
            o += w
        }
        for (row in 0 until h / 2) {
            for (col in 0 until w / 2) {
                out[o++] = v.buffer.get(row * v.rowStride + col * v.pixelStride)
                out[o++] = u.buffer.get(row * u.rowStride + col * u.pixelStride)
            }
        }
        return out
    }
}
