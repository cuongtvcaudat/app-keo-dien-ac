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

    /** Thời gian chờ mặc định (giây) trước khi tự tắt camera khi không còn ai xem. */
    const val DEFAULT_IDLE_TIMEOUT_SEC: Int = 180

    /** Giới hạn dưới/trên cho thời gian chờ tự tắt (giây). */
    const val MIN_IDLE_TIMEOUT_SEC: Int = 30
    const val MAX_IDLE_TIMEOUT_SEC: Int = 3600

    /** Độ lệch đồng hồ tối đa (giây) giữa 2 máy mà lệnh vẫn được chấp nhận. */
    const val MAX_CLOCK_SKEW_SEC: Long = 60

    /** Cổng mặc định của MQTT broker (không TLS) và HTTP stream. */
    const val DEFAULT_MQTT_PORT: Int = 1883
    const val DEFAULT_HTTP_PORT: Int = 8080
}
