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
 * @property allowRecording cho app Xem quay video và bật canh chuyển động (0.12; tắt → app Xem không thấy 2 nút này).
 * @property allowAudio cho app Xem nghe tiếng sân / nói ra loa (tắt → xem trực tiếp không tiếng, clip quay không tiếng).
 * @property alertMotion báo khi có chuyển động (lúc đang canh).
 * @property alertPower báo mất điện / có điện lại / pin yếu.
 * @property alertHeat báo máy quá nóng.
 * @property alertEmail gửi thêm email cho mỗi cảnh báo (dự phòng khi máy xem tắt app/mất mạng).
 * @property emailFrom địa chỉ Gmail dùng để gửi.
 * @property emailPassword **mật khẩu ứng dụng** Gmail (không phải mật khẩu Gmail); lưu mã hoá, không in ra log.
 * @property emailTo người nhận, cách nhau dấu phẩy (tối đa 3).
 * @property guestEnabled cho tạo mã QR "chỉ xem" (0.13; chỉ với broker nhúng).
 * @property guestSecret token của khách chỉ xem (khác [secret]); đổi token = thu hồi mọi mã QR chỉ xem đã phát.
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
    val allowRecording: Boolean = true,
    val allowAudio: Boolean = true,
    val alertMotion: Boolean = true,
    val alertPower: Boolean = true,
    val alertHeat: Boolean = true,
    val alertEmail: Boolean = false,
    val emailFrom: String = "",
    val emailPassword: String = "",
    val emailTo: String = "",
    val guestEnabled: Boolean = false,
    val guestSecret: String = "",
) {
    /** Token khách đang có hiệu lực; null nếu không bật quyền chỉ xem. */
    val activeGuestSecret: String? get() = guestSecret.takeIf { guestEnabled && brokerMode == BrokerMode.EMBEDDED }

    /** Loại cảnh báo đang bật. */
    val alertSettings: AlertSettings get() = AlertSettings(motion = alertMotion, power = alertPower, heat = alertHeat)

    /** Tài khoản gửi email cảnh báo; null nếu không bật hoặc cấu hình chưa hợp lệ. */
    fun mailAccount(): MailAccount? = if (alertEmail) MailAccount.fromInput(emailFrom, emailPassword, emailTo) else null

    /** Danh sách lỗi cấu hình; rỗng nghĩa là hợp lệ. */
    fun problems(): List<ConfigProblem> = buildList {
        if (!DeviceIds.isValid(deviceId)) add(ConfigProblem.INVALID_DEVICE_ID)
        if (displayName.isBlank()) add(ConfigProblem.EMPTY_DISPLAY_NAME)
        if (!SharedSecret.isAcceptable(secret)) add(ConfigProblem.WEAK_SECRET)
        if (brokerMode == BrokerMode.EMBEDDED && brokerPort !in 1024..65535) add(ConfigProblem.INVALID_PORT)
        if (brokerMode == BrokerMode.EXTERNAL && !BrokerAddress.isValid(externalUri)) add(ConfigProblem.INVALID_EXTERNAL_URI)
        if (alertEmail && mailAccount() == null) add(ConfigProblem.INVALID_EMAIL)
        if (guestEnabled && (!SharedSecret.isAcceptable(guestSecret) || guestSecret == secret)) add(ConfigProblem.INVALID_GUEST)
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
    INVALID_EMAIL,
    INVALID_GUEST,
}
