package vn.cnldg.oncam.shared.protocol

/**
 * Tên topic MQTT và mức QoS dùng trong OnCam.
 *
 * Cấu trúc: `camera/{deviceId}/cmd | status | event | alert`.
 */
object Topics {
    const val ROOT: String = "camera"

    /** Viewer subscribe topic này để thấy mọi camera (trạng thái được retain). */
    const val ALL_STATUS: String = "$ROOT/+/status"

    /** QoS 1 cho lệnh: đảm bảo camera-node nhận được ít nhất 1 lần. */
    const val QOS_COMMAND: Int = 1

    /**
     * QoS 1 cho trạng thái (khác spec gốc là QoS 0 — quyết định D9).
     * Chuẩn MQTT 3.1.1 cho phép broker bỏ tin retain gửi bằng QoS 0; Moquette đúng là xoá luôn
     * (đã kiểm chứng bằng test). QoS 1 đảm bảo viewer mở app là thấy trạng thái mới nhất trên mọi broker.
     */
    const val QOS_STATUS: Int = 1

    /** QoS 0 cho sự kiện: nhẹ, không retain; trạng thái thật luôn nằm ở topic status. */
    const val QOS_EVENT: Int = 0

    /** QoS 1 cho cảnh báo (0.12): không retain, nhưng không để rơi khi mạng chập chờn. */
    const val QOS_ALERT: Int = 1

    /** QoS 1 cho LWT để viewer chắc chắn nhận được thông báo offline. */
    const val QOS_WILL: Int = 1

    /** Topic lệnh của một camera. */
    fun command(deviceId: String): String = build(deviceId, Kind.COMMAND)

    /** Topic trạng thái của một camera. */
    fun status(deviceId: String): String = build(deviceId, Kind.STATUS)

    /** Topic sự kiện của một camera. */
    fun event(deviceId: String): String = build(deviceId, Kind.EVENT)

    /** Topic cảnh báo của một camera. */
    fun alert(deviceId: String): String = build(deviceId, Kind.ALERT)

    /** Phân tích topic thành (deviceId, loại). Trả về null nếu không đúng cấu trúc OnCam. */
    fun parse(topic: String): Parsed? {
        val parts = topic.split('/')
        if (parts.size != 3 || parts[0] != ROOT || !DeviceIds.isValid(parts[1])) return null
        val kind = Kind.entries.firstOrNull { it.suffix == parts[2] } ?: return null
        return Parsed(parts[1], kind)
    }

    private fun build(deviceId: String, kind: Kind): String {
        DeviceIds.requireValid(deviceId)
        return "$ROOT/$deviceId/${kind.suffix}"
    }

    /** Loại topic. */
    enum class Kind(val suffix: String) {
        COMMAND("cmd"),
        STATUS("status"),
        EVENT("event"),
        ALERT("alert"),
    }

    /** Kết quả [parse]. */
    data class Parsed(val deviceId: String, val kind: Kind)
}

/**
 * Quy tắc đặt deviceId: chữ thường a-z, số, `-`, `_`; 1-32 ký tự; bắt đầu bằng chữ/số.
 *
 * Không cho dấu tiếng Việt vì bàn phím 2 máy có thể sinh 2 dạng Unicode khác nhau
 * (dựng sẵn / tổ hợp) → topic không khớp. Tên có dấu để ở [DeviceStatus.displayName].
 */
object DeviceIds {
    private val PATTERN = Regex("^[a-z0-9][a-z0-9_-]{0,31}$")

    /** deviceId có hợp lệ không. */
    fun isValid(deviceId: String): Boolean = PATTERN.matches(deviceId)

    /** Ném [IllegalArgumentException] nếu deviceId không hợp lệ. */
    fun requireValid(deviceId: String) {
        require(isValid(deviceId)) { "deviceId không hợp lệ: '$deviceId' (chỉ a-z, 0-9, -, _; tối đa 32 ký tự)" }
    }
}
