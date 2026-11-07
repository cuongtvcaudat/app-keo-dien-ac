package vn.cnldg.oncam.node.core

import vn.cnldg.oncam.shared.OnCam
import vn.cnldg.oncam.shared.client.BrokerAddress
import vn.cnldg.oncam.shared.protocol.DeviceIds
import vn.cnldg.oncam.shared.security.SharedSecret

/** Nơi đặt MQTT broker. */
enum class BrokerMode {
    /** Broker Moquette chạy ngay trong app camera-node (đề xuất P1). */
    EMBEDDED,

    /** Broker riêng (Mosquitto, broker cloud...). */
    EXTERNAL,
}

/**
 * Cấu hình của camera-node, nhập ở màn hình Setup và lưu mã hoá (EncryptedSharedPreferences).
 *
 * @property secret token bí mật chung với viewer (ký lệnh + suy ra mật khẩu broker nhúng).
 * @property brokerPort cổng broker nhúng (chỉ dùng khi [brokerMode] = EMBEDDED).
 * @property externalUri địa chỉ broker ngoài, vd. `tcp://100.x.y.z:1883` hoặc `ssl://host:8883`.
 * @property orientation hướng đặt máy để xoay ảnh cho đứng.
 * @property shareLocation gửi vị trí máy (GPS/mạng) cho app Xem. Mặc định tắt.
 * @property locationIntervalMin chu kỳ tự cập nhật vị trí (phút): 15, 60, hoặc 0 = tắt (GPS chỉ chạy khi app Xem
 *   bấm "Cập nhật"/"Theo dõi").
 */
data class NodeConfig(
    val deviceId: String,
    val displayName: String,
    val secret: String,
    val brokerMode: BrokerMode = BrokerMode.EMBEDDED,
    val brokerPort: Int = OnCam.DEFAULT_MQTT_PORT,
    val externalUri: String = "",
    val externalUsername: String = "",
    val externalPassword: String = "",
    val orientation: MountOrientation = MountOrientation.UP,
    val shareLocation: Boolean = false,
    val locationIntervalMin: Int = 15,
) {
    /** Danh sách lỗi cấu hình; rỗng nghĩa là hợp lệ. */
    fun problems(): List<ConfigProblem> = buildList {
        if (!DeviceIds.isValid(deviceId)) add(ConfigProblem.INVALID_DEVICE_ID)
        if (displayName.isBlank()) add(ConfigProblem.EMPTY_DISPLAY_NAME)
        if (!SharedSecret.isAcceptable(secret)) add(ConfigProblem.WEAK_SECRET)
        if (brokerMode == BrokerMode.EMBEDDED && brokerPort !in 1024..65535) add(ConfigProblem.INVALID_PORT)
        if (brokerMode == BrokerMode.EXTERNAL && !BrokerAddress.isValid(externalUri)) add(ConfigProblem.INVALID_EXTERNAL_URI)
    }

    /** Không in token/mật khẩu ra log. */
    override fun toString(): String =
        "NodeConfig(deviceId=$deviceId, displayName=$displayName, brokerMode=$brokerMode, " +
            "brokerPort=$brokerPort, externalUri=$externalUri, secret=***)"
}

/** Lỗi cấu hình (giao diện đổi thành câu tiếng Việt qua string resource). */
enum class ConfigProblem {
    INVALID_DEVICE_ID,
    EMPTY_DISPLAY_NAME,
    WEAK_SECRET,
    INVALID_PORT,
    INVALID_EXTERNAL_URI,
}
