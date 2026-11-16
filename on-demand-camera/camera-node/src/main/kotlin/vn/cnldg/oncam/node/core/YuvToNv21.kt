package vn.cnldg.oncam.node.core

import java.nio.ByteBuffer

/** Một mặt (plane) của ảnh YUV_420_888: dữ liệu + bước hàng (byte) + bước điểm ảnh (byte). */
class YuvPlane(val buffer: ByteBuffer, val rowStride: Int, val pixelStride: Int)

/**
 * Chuyển khung YUV_420_888 (CameraX) sang NV21 cho JPEG và WebRTC — chạy mỗi khung nên phải nhanh.
 *
 * Cách cũ đọc từng byte bằng `ByteBuffer.get(index)` (~460 nghìn lần/khung 720p, chậm trên bộ nhớ của camera).
 * Cách này chép nguyên hàng một lần (memcpy) vào mảng tạm rồi xếp lại trên mảng Kotlin.
 * Xử lý đúng rowStride/pixelStride vì mỗi hãng bố trí bộ nhớ khác nhau (có hàng thừa đệm, U/V xen kẽ hoặc tách rời).
 */
class YuvToNv21 {
    private var uRow = ByteArray(0)
    private var vRow = ByteArray(0)

    /**
     * @param width chiều rộng (số chẵn).
     * @param height chiều cao (số chẵn).
     * @return mảng NV21 mới (Y rồi V,U xen kẽ), dài width × height × 3/2.
     */
    fun convert(y: YuvPlane, u: YuvPlane, v: YuvPlane, width: Int, height: Int): ByteArray {
        val out = ByteArray(width * height * 3 / 2)
        copyLuma(y, width, height, out)
        copyChroma(u, v, width, height, out, width * height)
        return out
    }

    private fun copyLuma(p: YuvPlane, width: Int, height: Int, out: ByteArray) {
        val buf = p.buffer.duplicate()
        if (p.pixelStride == 1) {
            for (row in 0 until height) {
                buf.position(row * p.rowStride)
                buf.get(out, row * width, width)
            }
            return
        }
        val line = ByteArray((width - 1) * p.pixelStride + 1)
        for (row in 0 until height) {
            readRow(buf, row * p.rowStride, line)
            var o = row * width
            for (col in 0 until width) out[o++] = line[col * p.pixelStride]
        }
    }

    private fun copyChroma(u: YuvPlane, v: YuvPlane, width: Int, height: Int, out: ByteArray, start: Int) {
        val cw = width / 2
        val uLen = (cw - 1) * u.pixelStride + 1
        val vLen = (cw - 1) * v.pixelStride + 1
        if (uRow.size < uLen) uRow = ByteArray(uLen)
        if (vRow.size < vLen) vRow = ByteArray(vLen)
        val ub = u.buffer.duplicate()
        val vb = v.buffer.duplicate()
        var o = start
        for (row in 0 until height / 2) {
            readRow(ub, row * u.rowStride, uRow, uLen)
            readRow(vb, row * v.rowStride, vRow, vLen)
            var ui = 0
            var vi = 0
            for (col in 0 until cw) {
                out[o++] = vRow[vi]
                out[o++] = uRow[ui]
                ui += u.pixelStride
                vi += v.pixelStride
            }
        }
    }

    /** Chép [len] byte từ vị trí [pos] (hàng cuối có thể ngắn hơn do bộ đệm không có phần đệm cuối). */
    private fun readRow(buf: ByteBuffer, pos: Int, dst: ByteArray, len: Int = dst.size) {
        buf.position(pos)
        buf.get(dst, 0, minOf(len, buf.limit() - pos))
    }
}
