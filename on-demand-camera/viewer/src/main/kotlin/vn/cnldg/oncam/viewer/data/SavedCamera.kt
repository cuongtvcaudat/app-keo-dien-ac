package vn.cnldg.oncam.viewer.data

import kotlinx.serialization.Serializable
import vn.cnldg.oncam.shared.client.CameraEndpoint
import vn.cnldg.oncam.shared.protocol.DeviceIds
import vn.cnldg.oncam.shared.security.SharedSecret

/**
 * Một camera đã thêm vào app Xem (lưu mã hoá).
 *
 * @property brokerUri địa chỉ broker, lấy từ màn hình máy camera, vd. `tcp://100.101.102.103:1883`.
 * @property label tên tự đặt (để trống thì dùng tên máy camera gửi về).
 * @property brokerUsername để trống khi dùng broker nhúng trong máy camera.
 */
@Serializable
data class SavedCamera(
    val deviceId: String,
    val brokerUri: String,
    val secret: String,
    val label: String = "",
    val brokerUsername: String = "",
    val brokerPassword: String = "",
) {
    /** Thông tin kết nối cho [vn.cnldg.oncam.shared.client.CameraClient]. */
    fun endpoint() = CameraEndpoint(
        deviceId = deviceId,
        brokerUri = brokerUri,
        secret = secret,
        brokerUsername = brokerUsername.ifBlank { null },
        brokerPassword = brokerPassword.ifBlank { null },
    )

    /** Lỗi nhập liệu; rỗng nghĩa là hợp lệ. */
    fun problems(): List<SavedCameraProblem> = buildList {
        if (!DeviceIds.isValid(deviceId)) add(SavedCameraProblem.INVALID_DEVICE_ID)
        if (!BROKER_URI.matches(brokerUri)) add(SavedCameraProblem.INVALID_BROKER_URI)
        if (!SharedSecret.isAcceptable(secret)) add(SavedCameraProblem.WEAK_SECRET)
    }

    /** Không in token/mật khẩu ra log. */
    override fun toString(): String = "SavedCamera(deviceId=$deviceId, brokerUri=$brokerUri, secret=***)"

    private companion object {
        val BROKER_URI = Regex("^(tcp|ssl)://[A-Za-z0-9.\\-]+:\\d{2,5}$")
    }
}

/** Lỗi nhập liệu khi thêm camera. */
enum class SavedCameraProblem { INVALID_DEVICE_ID, INVALID_BROKER_URI, WEAK_SECRET }
