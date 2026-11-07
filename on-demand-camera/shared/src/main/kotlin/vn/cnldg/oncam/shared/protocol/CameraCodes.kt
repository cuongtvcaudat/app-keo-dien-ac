package vn.cnldg.oncam.shared.protocol

/**
 * Mã lỗi/thông điệp camera dùng trong [DeviceStatus.error], [DeviceEvent.message] và kết quả lệnh.
 * Viewer đổi mã sang câu tiếng Việt; giữ dạng mã để không phụ thuộc ngôn ngữ.
 */
object CameraCodes {
    /** Service bị khởi động lại từ nền (Android 11+) → cần mở app trên máy camera một lần. */
    const val NEEDS_APP_OPEN: String = "CAMERA_NEEDS_APP_OPEN"

    /** Chưa cấp quyền Camera cho app camera-node. */
    const val NO_PERMISSION: String = "NO_CAMERA_PERMISSION"

    /** Máy không có camera trước/sau như yêu cầu. */
    const val NO_CAMERA: String = "NO_CAMERA"

    /** Chưa có IP Tailscale để mở HTTP stream (không bao giờ mở trên 0.0.0.0). */
    const val NO_TAILSCALE: String = "NO_TAILSCALE"

    /** Không mở được cổng HTTP stream. */
    const val HTTP_BIND_FAILED: String = "HTTP_BIND_FAILED"

    /** CameraX không mở được camera. */
    const val OPEN_FAILED: String = "CAMERA_OPEN_FAILED"

    /** Camera báo lỗi khi đang chạy (bị app khác chiếm, bị hệ thống tắt...). Dạng "CAMERA_ERROR:chi_tiết". */
    const val CAMERA_ERROR: String = "CAMERA_ERROR"

    /** Lệnh cần camera đang bật. */
    const val CAMERA_OFF: String = "CAMERA_OFF"

    /** Camera hiện tại không có đèn flash (thường là camera trước). */
    const val NO_FLASH: String = "NO_FLASH"

    /** Camera đang bật nhưng chưa có khung hình nào. */
    const val NO_FRAME: String = "NO_FRAME"

    /** IP Tailscale đổi khi đang phát → đã tắt stream, viewer cần bật lại. */
    const val ADDRESS_CHANGED: String = "ADDRESS_CHANGED"

    /** STOP_STREAM khi camera đã tắt. */
    const val ALREADY_OFF: String = "ALREADY_OFF"

    /** Lý do tắt (trong sự kiện CAMERA_STOPPED). */
    const val STOP_BY_VIEWER: String = "VIEWER"
    const val STOP_ON_DEVICE: String = "ON_DEVICE"
    const val STOP_SHUTDOWN: String = "SHUTDOWN"

    /** Máy camera chưa bật "Chia sẻ vị trí". */
    const val LOCATION_DISABLED: String = "LOCATION_DISABLED"

    /** Chưa cấp quyền vị trí, hoặc service chưa có loại "location" (cần mở app trên máy camera). */
    const val LOCATION_NO_PERMISSION: String = "LOCATION_NO_PERMISSION"

    /** Vị trí (GPS/mạng) đang tắt trong Cài đặt của máy camera. */
    const val LOCATION_OFF: String = "LOCATION_OFF"

    /** Không bắt được vị trí trong thời gian chờ (trong nhà kín, chưa có vệ tinh). */
    const val LOCATION_UNAVAILABLE: String = "LOCATION_UNAVAILABLE"

    /** Không bắt đầu được việc quay (camera không hỗ trợ quay song song với luồng xem, hết bộ nhớ…). */
    const val RECORDING_FAILED: String = "RECORDING_FAILED"

    /** Máy camera hết bộ nhớ trong để lưu video. */
    const val RECORDING_STORAGE_FULL: String = "RECORDING_STORAGE_FULL"

    /** Máy camera bản này chưa hỗ trợ quay video. */
    const val RECORDING_UNSUPPORTED: String = "RECORDING_UNSUPPORTED"

    /** Không tắt camera vì đang canh chuyển động (tắt canh trước bằng SET_MOTION false). */
    const val MOTION_ARMED: String = "MOTION_ARMED"

    /** Canh chuyển động tự tắt vì camera bị tắt (quá nhiệt, lỗi camera, dừng node...). */
    const val MOTION_STOPPED: String = "MOTION_STOPPED"

    /** Tiền tố thông điệp THERMAL_THROTTLE: "LOWERED:LOW:43.1" hoặc "STOPPED:45.2". */
    const val THERMAL_LOWERED: String = "LOWERED"
    const val THERMAL_STOPPED: String = "STOPPED"
}

/** Đường dẫn HTTP trên camera-node. */
object StreamPaths {
    /** Luồng MJPEG (multipart/x-mixed-replace). */
    const val STREAM: String = "/stream.mjpeg"

    /** Khung hình mới nhất dạng JPEG. */
    const val SNAPSHOT: String = "/snapshot.jpg"

    /** Ranh giới giữa các khung trong luồng MJPEG. */
    const val BOUNDARY: String = "oncamframe"

    /** Header trong mỗi phần MJPEG: thời điểm chụp khung (ms, đồng hồ máy camera) để viewer ước lượng độ trễ. */
    const val TIMESTAMP_HEADER: String = "X-OnCam-Timestamp"
}
