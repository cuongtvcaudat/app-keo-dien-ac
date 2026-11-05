package vn.cnldg.oncam.shared

/**
 * Hằng số chung của dự án OnCam, dùng cho cả camera-node và viewer.
 */
object OnCam {
    /**
     * Phiên bản giao thức JSON giữa viewer và camera-node.
     * Tăng giá trị này khi đổi cấu trúc lệnh/trạng thái theo cách không tương thích ngược.
     */
    const val PROTOCOL_VERSION: Int = 1
}
