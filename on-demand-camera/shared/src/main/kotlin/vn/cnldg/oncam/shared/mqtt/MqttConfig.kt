package vn.cnldg.oncam.shared.mqtt

/**
 * Cấu hình kết nối tới MQTT broker.
 *
 * @property serverUri `tcp://host:1883` (trong mạng Tailscale) hoặc `ssl://host:8883` (qua Internet).
 * @property keepAliveSec chu kỳ ping. Broker coi client đã mất sau 1,5 × keepAlive
 *   (15s → 22,5s) rồi phát LWT → đáp ứng tiêu chí "offline trong 30s".
 * @property maxReconnectDelayMs thời gian chờ tối đa giữa 2 lần thử kết nối lại (lùi dần 1s, 2s, 4s, 8s, 10s...).
 * @property will tin nhắn LWT broker tự phát khi client mất kết nối đột ngột.
 */
data class MqttConfig(
    val serverUri: String,
    val clientId: String,
    val username: String? = null,
    val password: String? = null,
    val keepAliveSec: Int = 15,
    val connectTimeoutSec: Int = 10,
    val maxReconnectDelayMs: Long = 10_000,
    val will: OutgoingMessage? = null,
) {
    init {
        require(serverUri.startsWith("tcp://") || serverUri.startsWith("ssl://")) {
            "serverUri phải bắt đầu bằng tcp:// hoặc ssl://"
        }
        require(clientId.isNotBlank()) { "clientId không được rỗng" }
        require(keepAliveSec in 5..300) { "keepAliveSec phải trong khoảng 5-300" }
        require(maxReconnectDelayMs >= 1_000) { "maxReconnectDelayMs tối thiểu 1000" }
    }

    /** true nếu kết nối có TLS. */
    val isTls: Boolean get() = serverUri.startsWith("ssl://")

    /** Không in mật khẩu ra log. */
    override fun toString(): String =
        "MqttConfig(serverUri=$serverUri, clientId=$clientId, username=$username, " +
            "password=${if (password.isNullOrEmpty()) "<none>" else "***"}, keepAliveSec=$keepAliveSec)"
}

/** Tin nhắn gửi đi. */
data class OutgoingMessage(
    val topic: String,
    val payload: String,
    val qos: Int,
    val retained: Boolean,
) {
    init {
        require(qos in 0..2) { "qos phải là 0, 1 hoặc 2" }
    }
}

/**
 * Tin nhắn nhận được.
 *
 * @property retained cờ retain do broker gửi kèm. KHÔNG dựa vào cờ này để xử lý:
 *   Moquette 0.17 không bật cờ khi gửi tin đã lưu cho người subscribe sau (đã kiểm chứng bằng test).
 */
data class IncomingMessage(
    val topic: String,
    val payload: String,
    val qos: Int,
    val retained: Boolean,
)

/** Trạng thái kết nối của [MqttClientWrapper]. */
sealed interface MqttConnectionState {
    /** Chưa start hoặc đã stop. */
    data object Idle : MqttConnectionState

    /** Đang thử kết nối. */
    data object Connecting : MqttConnectionState

    /** Đã kết nối và đã subscribe lại các topic. */
    data object Connected : MqttConnectionState

    /**
     * Mất kết nối / kết nối thất bại; sẽ thử lại sau [retryInMs] ms.
     * @property reason mô tả lỗi gần nhất (nếu có).
     */
    data class Disconnected(val reason: String?, val retryInMs: Long) : MqttConnectionState
}
