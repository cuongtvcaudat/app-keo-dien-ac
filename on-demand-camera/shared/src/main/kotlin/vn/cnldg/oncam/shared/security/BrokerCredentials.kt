package vn.cnldg.oncam.shared.security

/**
 * Tài khoản đăng nhập broker MQTT nhúng trong camera-node (đề xuất P1).
 *
 * Mật khẩu được suy ra từ token bí mật chung bằng HMAC, nên người dùng chỉ cần nhập
 * 3 thứ ở viewer: địa chỉ broker, mã camera, token. Lộ mật khẩu broker không làm lộ token.
 */
object BrokerCredentials {
    /** Tên đăng nhập cố định của broker nhúng. */
    const val USERNAME: String = "oncam"

    /** Mật khẩu broker nhúng (32 ký tự hex = 128 bit) suy ra từ [secret]. */
    fun password(secret: String): String = Hmac(secret).hex("oncam-mqtt-password-v1").take(32)
}
