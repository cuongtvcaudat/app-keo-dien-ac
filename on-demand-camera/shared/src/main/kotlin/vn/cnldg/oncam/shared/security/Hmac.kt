package vn.cnldg.oncam.shared.security

import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * HMAC-SHA256 dạng hex với khóa là token bí mật chung giữa 2 app.
 * Mac không an toàn đa luồng nên tạo mới mỗi lần gọi (chi phí rất nhỏ).
 */
internal class Hmac(secret: String) {
    private val key = SecretKeySpec(secret.toByteArray(Charsets.UTF_8), ALGORITHM)

    fun hex(message: String): String {
        val mac = Mac.getInstance(ALGORITHM)
        mac.init(key)
        return toHex(mac.doFinal(message.toByteArray(Charsets.UTF_8)))
    }

    /** So sánh 2 chữ ký trong thời gian cố định (chống đoán dần theo thời gian phản hồi). */
    fun matches(message: String, signatureHex: String): Boolean =
        MessageDigest.isEqual(
            hex(message).toByteArray(Charsets.US_ASCII),
            signatureHex.lowercase().toByteArray(Charsets.US_ASCII),
        )

    private companion object {
        const val ALGORITHM = "HmacSHA256"
        val HEX = "0123456789abcdef".toCharArray()

        fun toHex(bytes: ByteArray): String {
            val out = CharArray(bytes.size * 2)
            bytes.forEachIndexed { i, b ->
                val v = b.toInt() and 0xFF
                out[i * 2] = HEX[v ushr 4]
                out[i * 2 + 1] = HEX[v and 0x0F]
            }
            return String(out)
        }
    }
}
