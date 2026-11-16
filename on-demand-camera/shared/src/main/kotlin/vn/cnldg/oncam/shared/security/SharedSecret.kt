package vn.cnldg.oncam.shared.security

import java.security.SecureRandom
import java.util.Base64

/**
 * Tiện ích cho token bí mật chung giữa viewer và camera-node.
 * Token không được hard-code: người dùng tạo/nhập ở màn hình cấu hình, lưu bằng EncryptedSharedPreferences.
 */
object SharedSecret {
    /** Độ dài tối thiểu chấp nhận được. */
    const val MIN_LENGTH: Int = 16

    private val random = SecureRandom()

    /** Sinh token ngẫu nhiên 32 byte (256 bit), dạng base64url không dấu '=' (43 ký tự). */
    fun generate(): String {
        val bytes = ByteArray(32).also(random::nextBytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    /** Token có đủ dài để dùng không. */
    fun isAcceptable(secret: String): Boolean = secret.trim().length >= MIN_LENGTH && secret == secret.trim()
}
