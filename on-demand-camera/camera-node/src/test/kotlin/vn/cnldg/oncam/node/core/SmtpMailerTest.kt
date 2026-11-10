package vn.cnldg.oncam.node.core

import java.net.ServerSocket
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Gửi email qua máy chủ SMTP giả (nói đúng giao thức như Gmail, không TLS). */
class SmtpMailerTest {

    private val server = ServerSocket(0)
    private val commands = CopyOnWriteArrayList<String>()
    private val data = CopyOnWriteArrayList<String>()

    @Volatile
    private var acceptPassword = "abcd efgh ijkl mnop"

    init {
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val s = runCatching { server.accept() }.getOrNull() ?: break
                s.use { sock ->
                    val r = sock.getInputStream().bufferedReader(Charsets.US_ASCII)
                    val w = sock.getOutputStream().bufferedWriter(Charsets.US_ASCII)
                    fun reply(line: String) {
                        w.write(line + "\r\n")
                        w.flush()
                    }
                    reply("220 smtp.fake ESMTP")
                    while (true) {
                        val line = r.readLine() ?: break
                        commands += line
                        when {
                            line.startsWith("EHLO") -> {
                                reply("250-smtp.fake")
                                reply("250-SIZE 35882577")
                                reply("250 AUTH LOGIN PLAIN")
                            }
                            line.startsWith("AUTH PLAIN ") -> {
                                val parts = String(Base64.getDecoder().decode(line.removePrefix("AUTH PLAIN "))).split('\u0000')
                                reply(if (parts.getOrNull(2) == acceptPassword) "235 2.7.0 Accepted" else "535 5.7.8 Bad credentials")
                            }
                            line.startsWith("MAIL FROM:") || line.startsWith("RCPT TO:") -> reply("250 OK")
                            line == "DATA" -> {
                                reply("354 Go ahead")
                                val sb = StringBuilder()
                                while (true) {
                                    val l = r.readLine() ?: break
                                    if (l == ".") break
                                    sb.append(l).append('\n')
                                }
                                data += sb.toString()
                                reply("250 2.0.0 OK queued")
                            }
                            line == "QUIT" -> {
                                reply("221 bye")
                                break
                            }
                            else -> reply("502 unknown")
                        }
                    }
                }
            }
        }
    }

    @AfterTest
    fun tearDown() = server.close()

    private fun mailer(password: String = acceptPassword, to: List<String> = listOf("cuong@example.com", "vo@example.com")) =
        SmtpMailer(
            MailAccount(username = "camera.nha@gmail.com", password = password, to = to, host = "127.0.0.1", port = server.localPort),
            tls = false,
            now = { ZonedDateTime.of(2026, 11, 7, 14, 3, 0, 0, ZoneOffset.ofHours(7)) },
        )

    @Test
    fun `gui email co anh dinh kem, tieu de tieng Viet`() {
        val jpeg = ByteArray(5_000) { (it * 31).toByte() }
        mailer().send(MailMessage("[OnCam] Sân nhà: Có chuyển động lúc 14:03 07/11", "Camera: Sân nhà\nLúc: 14:03", "anh.jpg", jpeg))

        assertTrue(commands.contains("MAIL FROM:<camera.nha@gmail.com>"))
        assertEquals(listOf("RCPT TO:<cuong@example.com>", "RCPT TO:<vo@example.com>"), commands.filter { it.startsWith("RCPT") })
        assertEquals("QUIT", commands.last())
        val mail = data.single()
        assertEquals("[OnCam] Sân nhà: Có chuyển động lúc 14:03 07/11", subject(mail))
        assertTrue(mail.contains("Date: Sat, 7 Nov 2026 14:03:00 +0700"), mail.lines().take(6).joinToString("\n"))
        assertContentEquals(jpeg, attachment(mail))
        assertTrue(mail.lines().all { it.all { c -> c.code < 128 } && it.length <= 998 }, "mọi dòng ASCII, ≤ 998 ký tự")
    }

    @Test
    fun `sai mat khau ung dung thi bao ma 535 va khong gui`() {
        val e = assertFailsWith<SmtpException> { mailer(password = "sai").send(MailMessage("x", "y")) }
        assertEquals(535, e.code)
        assertTrue(data.isEmpty())
        assertTrue(commands.none { it.startsWith("MAIL FROM") })
        assertTrue(e.message!!.contains("AUTH ***") || !e.message!!.contains("sai"), "không lộ mật khẩu trong lỗi")
    }

    @Test
    fun `dia chi co ky tu xuong dong bi tu choi - khong chen lenh SMTP duoc`() {
        assertFailsWith<IllegalArgumentException> { mailer(to = listOf("a@example.com>\r\nRCPT TO:<x@evil.com")) }
        assertFailsWith<IllegalArgumentException> { mailer(to = emptyList()) }
        assertFailsWith<IllegalArgumentException> { mailer(to = List(4) { "n$it@example.com" }) }
        assertEquals(listOf("a@x.com", "b@y.vn"), MailAccount.parseRecipients(" a@x.com; b@y.vn ,a@x.com"))
        assertTrue("MATKHAU123" !in MailAccount("u@gmail.com", "MATKHAU123", listOf("a@b.com")).toString(), "không in mật khẩu")
    }

    /** Ghép tiêu đề gập dòng rồi giải mã các đoạn =?UTF-8?B?...?=. */
    private fun subject(mail: String): String {
        val lines = mail.lines()
        val start = lines.indexOfFirst { it.startsWith("Subject: ") }
        val folded = buildString {
            append(lines[start].removePrefix("Subject: "))
            var i = start + 1
            while (lines[i].startsWith(" ")) append(lines[i++].trim())
        }
        return Regex("=\\?UTF-8\\?B\\?([^?]*)\\?=").findAll(folded)
            .joinToString("") { String(Base64.getDecoder().decode(it.groupValues[1]), Charsets.UTF_8) }
            .ifEmpty { folded }
    }

    private fun attachment(mail: String): ByteArray {
        val lines = mail.lines()
        val at = lines.indexOfFirst { it.startsWith("Content-Disposition: attachment") }
        var i = at
        while (lines[i].isNotEmpty()) i++
        val b64 = lines.drop(i + 1).takeWhile { !it.startsWith("--") }.joinToString("")
        return Base64.getDecoder().decode(b64)
    }
}
