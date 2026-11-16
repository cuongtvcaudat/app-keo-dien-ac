package vn.cnldg.oncam.node.core

import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetSocketAddress
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.Locale
import java.util.UUID
import javax.net.SocketFactory
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * Tài khoản gửi email cảnh báo (mặc định Gmail, cổng 465 mã hoá TLS ngay từ đầu).
 *
 * @property username địa chỉ Gmail gửi đi.
 * @property password **mật khẩu ứng dụng** Gmail (16 ký tự, tạo ở myaccount.google.com/apppasswords) — không
 *   phải mật khẩu Gmail. Không in ra log.
 * @property to người nhận (tối đa [MAX_RECIPIENTS]).
 */
data class MailAccount(
    val username: String,
    val password: String,
    val to: List<String>,
    val host: String = "smtp.gmail.com",
    val port: Int = 465,
) {
    override fun toString(): String = "MailAccount(username=$username, to=$to, host=$host:$port, password=***)"

    companion object {
        /** Gửi tối đa chừng này người nhận. */
        const val MAX_RECIPIENTS: Int = 3

        private val EMAIL = Regex("^[A-Za-z0-9._%+-]{1,64}@[A-Za-z0-9.-]{1,253}\\.[A-Za-z]{2,24}$")

        /** Địa chỉ email hợp lệ (chặn ký tự xuống dòng/dấu <> để không chèn được lệnh SMTP). */
        fun isValidAddress(address: String): Boolean = EMAIL.matches(address)

        /** Tách danh sách người nhận "a@x.com, b@y.com" (bỏ khoảng trắng, dấu ; cũng được). */
        fun parseRecipients(text: String): List<String> =
            text.split(',', ';', ' ', '\n').map { it.trim() }.filter { it.isNotEmpty() }.distinct()

        /**
         * Tài khoản từ 3 ô nhập (Gmail gửi, mật khẩu ứng dụng — Google hiện dạng "abcd efgh ijkl mnop" nên bỏ
         * khoảng trắng —, danh sách người nhận); null nếu ô nào chưa hợp lệ.
         */
        fun fromInput(from: String, password: String, to: String): MailAccount? {
            val recipients = parseRecipients(to)
            val user = from.trim()
            val pass = password.replace(" ", "")
            val ok = isValidAddress(user) && pass.isNotEmpty() &&
                recipients.isNotEmpty() && recipients.size <= MAX_RECIPIENTS && recipients.all(::isValidAddress)
            return if (ok) MailAccount(username = user, password = pass, to = recipients) else null
        }
    }
}

/** Một email: tiêu đề, nội dung chữ, ảnh đính kèm (tuỳ chọn). */
data class MailMessage(
    val subject: String,
    val body: String,
    val attachmentName: String? = null,
    val attachment: ByteArray? = null,
    val attachmentType: String = "image/jpeg",
)

/** Máy chủ SMTP trả mã lỗi (vd. 535 = sai mật khẩu ứng dụng). */
class SmtpException(val code: Int, message: String) : IOException(message)

/**
 * Gửi email qua SMTP (đủ cho Gmail: TLS cổng 465, AUTH PLAIN), viết tay để không thêm thư viện.
 * Chặn luồng — gọi trên Dispatchers.IO.
 *
 * @param tls true (Gmail 465): bọc TLS ngay sau khi nối (có SNI + kiểm tên máy chủ); test đặt false để nói chuyện
 *   với máy chủ SMTP giả bằng socket thường.
 */
class SmtpMailer(
    private val account: MailAccount,
    private val tls: Boolean = true,
    private val timeoutMs: Int = 20_000,
    private val now: () -> ZonedDateTime = ZonedDateTime::now,
) {
    init {
        require(isValidAddress(account.username)) { "Địa chỉ gửi không hợp lệ" }
        require(account.to.isNotEmpty() && account.to.size <= MailAccount.MAX_RECIPIENTS && account.to.all(::isValidAddress)) {
            "Người nhận không hợp lệ"
        }
        require(account.password.isNotBlank()) { "Chưa có mật khẩu ứng dụng" }
    }

    /** Gửi [message]; lỗi mạng/máy chủ → IOException (SmtpException kèm mã). */
    fun send(message: MailMessage) {
        val plainSocket = SocketFactory.getDefault().createSocket()
        plainSocket.soTimeout = timeoutMs
        plainSocket.connect(InetSocketAddress(account.host, account.port), timeoutMs)
        val connected = if (tls) {
            try {
                val ssl = (SSLSocketFactory.getDefault() as SSLSocketFactory)
                    .createSocket(plainSocket, account.host, account.port, true) as SSLSocket
                // Kiểm tên máy chủ trong chứng chỉ (mặc định chỉ kiểm chuỗi tin cậy) → chống giả máy chủ Gmail.
                ssl.sslParameters = ssl.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
                ssl.startHandshake()
                ssl
            } catch (e: IOException) {
                plainSocket.close()
                throw e
            }
        } else {
            plainSocket
        }
        connected.use { socket ->
            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.US_ASCII))
            val out = socket.getOutputStream()
            expect(reader, 220)
            command(out, reader, "EHLO oncam", 250)
            val plain = "\u0000${account.username}\u0000${account.password}"
            command(out, reader, "AUTH PLAIN " + b64(plain.toByteArray(Charsets.UTF_8)), 235, secret = true)
            command(out, reader, "MAIL FROM:<${account.username}>", 250)
            account.to.forEach { command(out, reader, "RCPT TO:<$it>", 250, 251) }
            command(out, reader, "DATA", 354)
            val data = mime(message).lineSequence().joinToString("\r\n") { if (it.startsWith(".")) ".$it" else it }
            out.write((data + "\r\n.\r\n").toByteArray(Charsets.US_ASCII))
            out.flush()
            expect(reader, 250)
            runCatching { command(out, reader, "QUIT", 221) }
        }
    }

    /** Nội dung email theo MIME: chữ UTF-8 (base64) + ảnh đính kèm. Mọi dòng là ASCII. */
    internal fun mime(m: MailMessage): String {
        val boundary = "oncam-" + UUID.randomUUID().toString().replace("-", "")
        val date = now().format(DateTimeFormatter.RFC_1123_DATE_TIME.withLocale(Locale.US))
        return buildString {
            append("From: OnCam <${account.username}>\n")
            append("To: ${account.to.joinToString(", ")}\n")
            append("Subject: ${encodeHeader(m.subject)}\n")
            append("Date: $date\n")
            append("Message-ID: <${UUID.randomUUID()}@oncam>\n")
            append("MIME-Version: 1.0\n")
            append("Content-Type: multipart/mixed; boundary=\"$boundary\"\n")
            append("\n")
            append("--$boundary\n")
            append("Content-Type: text/plain; charset=UTF-8\n")
            append("Content-Transfer-Encoding: base64\n\n")
            append(wrap(b64(m.body.toByteArray(Charsets.UTF_8)))).append('\n')
            if (m.attachment != null) {
                val name = (m.attachmentName ?: "anh.jpg").filter { it.isLetterOrDigit() || it in "._-" }.ifEmpty { "anh.jpg" }
                append("--$boundary\n")
                append("Content-Type: ${m.attachmentType}; name=\"$name\"\n")
                append("Content-Disposition: attachment; filename=\"$name\"\n")
                append("Content-Transfer-Encoding: base64\n\n")
                append(wrap(b64(m.attachment))).append('\n')
            }
            append("--$boundary--")
        }
    }

    private fun command(out: OutputStream, reader: BufferedReader, line: String, vararg ok: Int, secret: Boolean = false) {
        out.write("$line\r\n".toByteArray(Charsets.UTF_8))
        out.flush()
        expect(reader, *ok, sent = if (secret) line.substringBefore(' ') + " ***" else line)
    }

    /** Đọc trả lời (có thể nhiều dòng "250-...") và kiểm mã. */
    private fun expect(reader: BufferedReader, vararg ok: Int, sent: String? = null) {
        var line: String
        do {
            line = reader.readLine() ?: throw IOException("Máy chủ email đóng kết nối")
        } while (line.length >= 4 && line[3] == '-')
        val code = line.take(3).toIntOrNull() ?: throw IOException("Trả lời SMTP lạ")
        if (code !in ok) throw SmtpException(code, "SMTP ${sent?.substringBefore(':') ?: "chào"} → $code")
    }

    private companion object {
        fun isValidAddress(a: String) = MailAccount.isValidAddress(a)

        fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

        fun wrap(s: String): String = s.chunked(76).joinToString("\n")

        /** Tiêu đề tiếng Việt theo RFC 2047; cắt từng đoạn ≤ 45 byte để mỗi phần ≤ 75 ký tự. */
        fun encodeHeader(text: String): String {
            val clean = text.replace('\r', ' ').replace('\n', ' ')
            if (clean.all { it.code in 32..126 }) return clean
            val parts = mutableListOf<String>()
            val sb = StringBuilder()
            for (ch in clean) {
                if ((sb.toString() + ch).toByteArray(Charsets.UTF_8).size > 45) {
                    parts += sb.toString()
                    sb.clear()
                }
                sb.append(ch)
            }
            if (sb.isNotEmpty()) parts += sb.toString()
            return parts.joinToString("\n ") { "=?UTF-8?B?${b64(it.toByteArray(Charsets.UTF_8))}?=" }
        }
    }
}
