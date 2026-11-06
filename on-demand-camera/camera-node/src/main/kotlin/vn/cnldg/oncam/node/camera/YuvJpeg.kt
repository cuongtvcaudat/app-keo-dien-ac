package vn.cnldg.oncam.node.camera

import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import androidx.camera.core.ImageProxy
import java.io.ByteArrayOutputStream

/**
 * Chuyển khung YUV_420_888 của CameraX sang JPEG (qua NV21 + YuvImage của Android).
 * Xử lý đúng rowStride/pixelStride vì mỗi hãng máy bố trí bộ nhớ khác nhau.
 */
internal object YuvJpeg {

    /** Khung NV21 (kích thước chẵn) — dùng chung cho JPEG (MJPEG) và WebRTC. */
    class Nv21(val data: ByteArray, val width: Int, val height: Int)

    /** Mã hoá [image] thành JPEG chất lượng [quality] (0-100). */
    fun encode(image: ImageProxy, quality: Int): ByteArray = encode(nv21(image), quality)

    /** Mã hoá khung NV21 thành JPEG chất lượng [quality] (0-100). */
    fun encode(frame: Nv21, quality: Int): ByteArray {
        val out = ByteArrayOutputStream(frame.width * frame.height / 8)
        YuvImage(frame.data, ImageFormat.NV21, frame.width, frame.height, null)
            .compressToJpeg(Rect(0, 0, frame.width, frame.height), quality, out)
        return out.toByteArray()
    }

    /** Chép [image] sang mảng NV21 mới (mỗi khung một mảng: WebRTC giữ mảng tới khi mã hoá xong). */
    fun nv21(image: ImageProxy): Nv21 {
        val width = image.width and 1.inv() // YuvImage và WebRTC cần kích thước chẵn
        val height = image.height and 1.inv()
        return Nv21(toNv21(image, width, height), width, height)
    }

    private fun toNv21(image: ImageProxy, width: Int, height: Int): ByteArray {
        val out = ByteArray(width * height * 3 / 2)
        val yPlane = image.planes[0]
        val yBuf = yPlane.buffer
        val yRow = yPlane.rowStride
        val yPix = yPlane.pixelStride
        var o = 0
        if (yPix == 1) {
            for (row in 0 until height) {
                yBuf.position(row * yRow)
                yBuf.get(out, o, width)
                o += width
            }
        } else {
            for (row in 0 until height) {
                val base = row * yRow
                for (col in 0 until width) out[o++] = yBuf.get(base + col * yPix)
            }
        }

        val uPlane = image.planes[1]
        val vPlane = image.planes[2]
        val uBuf = uPlane.buffer
        val vBuf = vPlane.buffer
        val uRow = uPlane.rowStride
        val vRow = vPlane.rowStride
        val uPix = uPlane.pixelStride
        val vPix = vPlane.pixelStride
        // NV21: sau mặt Y là các cặp V,U xen kẽ, độ phân giải 1/2 mỗi chiều.
        for (row in 0 until height / 2) {
            val uBase = row * uRow
            val vBase = row * vRow
            for (col in 0 until width / 2) {
                out[o++] = vBuf.get(vBase + col * vPix)
                out[o++] = uBuf.get(uBase + col * uPix)
            }
        }
        return out
    }
}
