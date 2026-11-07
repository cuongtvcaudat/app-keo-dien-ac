package vn.cnldg.oncam.node.camera

import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import androidx.camera.core.ImageProxy
import java.io.ByteArrayOutputStream
import vn.cnldg.oncam.node.core.YuvPlane
import vn.cnldg.oncam.node.core.YuvToNv21

/**
 * Chuyển khung YUV_420_888 của CameraX sang NV21 ([YuvToNv21]) rồi JPEG (YuvImage của Android).
 */
internal object YuvJpeg {

    /** Khung NV21 (kích thước chẵn) — dùng chung cho JPEG (MJPEG) và WebRTC. */
    class Nv21(val data: ByteArray, val width: Int, val height: Int)

    /** Mã hoá [image] thành JPEG chất lượng [quality] (0-100). */
    fun encode(image: ImageProxy, quality: Int): ByteArray = encode(nv21(image, YuvToNv21()), quality)

    /** Mã hoá khung NV21 thành JPEG chất lượng [quality] (0-100). */
    fun encode(frame: Nv21, quality: Int): ByteArray {
        val out = ByteArrayOutputStream(frame.width * frame.height / 8)
        YuvImage(frame.data, ImageFormat.NV21, frame.width, frame.height, null)
            .compressToJpeg(Rect(0, 0, frame.width, frame.height), quality, out)
        return out.toByteArray()
    }

    /**
     * Chép [image] sang mảng NV21 mới (mỗi khung một mảng: WebRTC giữ mảng tới khi mã hoá xong).
     * [converter] giữ mảng tạm giữa các khung — mỗi luồng camera dùng một bộ riêng.
     */
    fun nv21(image: ImageProxy, converter: YuvToNv21): Nv21 {
        val width = image.width and 1.inv() // YuvImage và WebRTC cần kích thước chẵn
        val height = image.height and 1.inv()
        val p = image.planes
        val data = converter.convert(
            YuvPlane(p[0].buffer, p[0].rowStride, p[0].pixelStride),
            YuvPlane(p[1].buffer, p[1].rowStride, p[1].pixelStride),
            YuvPlane(p[2].buffer, p[2].rowStride, p[2].pixelStride),
            width,
            height,
        )
        return Nv21(data, width, height)
    }
}
