package vn.cnldg.oncam.node.broker

import io.moquette.broker.Server
import io.moquette.broker.config.IConfig
import io.moquette.broker.config.MemoryConfig
import io.moquette.broker.security.IAuthenticator
import io.moquette.broker.security.IAuthorizatorPolicy
import io.moquette.broker.subscriptions.Topic
import java.io.File
import java.security.MessageDigest
import java.util.Properties
import vn.cnldg.oncam.shared.LogSink
import vn.cnldg.oncam.shared.protocol.Topics
import vn.cnldg.oncam.shared.security.BrokerCredentials

/**
 * Broker MQTT Moquette chạy trong app camera-node (đề xuất P1).
 *
 * - Chỉ nghe trên [start] `host` được truyền vào (IP Tailscale) — không bao giờ 0.0.0.0.
 * - Bắt buộc đăng nhập (tắt anonymous), không lưu dữ liệu xuống đĩa, tắt telemetry gửi ra ngoài.
 * - Các hàm chặn luồng (bind cổng, tắt Netty ~2 giây) → gọi từ Dispatchers.IO.
 */
class EmbeddedBroker(
    private val dataDir: File,
    private val log: LogSink = LogSink.NONE,
) {
    private var server: Server? = null

    /** Broker có đang chạy không. */
    val isRunning: Boolean
        @Synchronized get() = server != null

    /**
     * Tài khoản khách "chỉ xem" (0.13) của camera [deviceId]: đăng nhập bằng [BrokerCredentials.GUEST_USERNAME] +
     * [password], chỉ được ĐỌC `camera/{id}/guest/status|event` và GHI `camera/{id}/guest/cmd` — không đọc được
     * trạng thái đầy đủ (có vị trí), cảnh báo, hay lệnh của chủ; không đăng ký được topic đại diện (`#`, `+`).
     */
    data class GuestAccess(val deviceId: String, val password: String) {
        override fun toString(): String = "GuestAccess(deviceId=$deviceId, password=***)"
    }

    /**
     * Mở broker tại [host]:[port]. Nếu đang chạy thì tắt bản cũ trước.
     * @param guest tài khoản khách chỉ xem; null = không có khách.
     * @throws java.io.IOException nếu không bind được (sai IP, cổng bị chiếm...).
     */
    @Synchronized
    fun start(host: String, port: Int, username: String, password: String, guest: GuestAccess? = null) {
        require(host != "0.0.0.0" && host != "::") { "Không được mở broker trên mọi giao diện mạng" }
        stopLocked()
        dataDir.mkdirs()
        val props = properties(host, port)
        val expected = password.toByteArray(Charsets.UTF_8)
        val guestExpected = guest?.password?.toByteArray(Charsets.UTF_8)
        val authenticator = IAuthenticator { _, user, pass ->
            when {
                pass == null -> false
                user == username -> MessageDigest.isEqual(pass, expected)
                user == BrokerCredentials.GUEST_USERNAME && guestExpected != null -> MessageDigest.isEqual(pass, guestExpected)
                else -> false
            }
        }
        val s = Server()
        s.startServer(MemoryConfig(props), emptyList(), null, authenticator, policy(username, guest))
        server = s
        log.log(LogSink.Level.INFO, "Broker nhúng đang nghe tại $host:$port", null)
    }

    /**
     * Cấu hình Moquette. Tách riêng để test khoá cứng các giá trị an toàn.
     *
     * QUAN TRỌNG: Moquette 0.17 mặc định bật telemetry — mỗi lần khởi động gửi hệ điều hành, phiên bản,
     * UUID và **IP công cộng của mạng nhà** (lấy qua whatismyip.akamai.com) về telemetry.moquette.io.
     * Phát hiện qua log chặn của proxy khi chạy test → luôn tắt.
     */
    internal fun properties(host: String, port: Int) = Properties().apply {
        setProperty(IConfig.HOST_PROPERTY_NAME, host)
        setProperty(IConfig.PORT_PROPERTY_NAME, port.toString())
        setProperty(IConfig.WEB_SOCKET_PORT_PROPERTY_NAME, DISABLED)
        setProperty(IConfig.PERSISTENCE_ENABLED_PROPERTY_NAME, "false")
        setProperty(IConfig.DATA_PATH_PROPERTY_NAME, dataDir.absolutePath)
        setProperty(IConfig.ALLOW_ANONYMOUS_PROPERTY_NAME, "false")
        setProperty(IConfig.ENABLE_TELEMETRY_NAME, "false")
        // Mặc định Moquette bỏ tin > 8 092 byte; lệnh RTC_OFFER mang SDP 4–10 KB (Phase 7), cảnh báo kèm ảnh
        // JPEG ≤ 200 KB (≈ 270 KB sau base64, bản 0.12).
        setProperty(IConfig.NETTY_MAX_BYTES_PROPERTY_NAME, MAX_MESSAGE_BYTES.toString())
    }

    /** Quyền theo topic: chủ được tất cả; khách chỉ đúng 3 topic khách của camera này (so khớp nguyên văn). */
    internal fun policy(owner: String, guest: GuestAccess?): IAuthorizatorPolicy {
        val guestRead = guest?.let { setOf(Topics.guestStatus(it.deviceId), Topics.guestEvent(it.deviceId)) }.orEmpty()
        val guestWrite = guest?.let { setOf(Topics.guestCommand(it.deviceId)) }.orEmpty()
        return object : IAuthorizatorPolicy {
            override fun canWrite(topic: Topic, user: String?, client: String?): Boolean =
                user == owner || (user == BrokerCredentials.GUEST_USERNAME && topic.toString() in guestWrite)

            override fun canRead(topic: Topic, user: String?, client: String?): Boolean =
                user == owner || (user == BrokerCredentials.GUEST_USERNAME && topic.toString() in guestRead)
        }
    }

    /** Tắt broker (chặn luồng tới khi Netty tắt xong). */
    @Synchronized
    fun stop() = stopLocked()

    private fun stopLocked() {
        val s = server ?: return
        server = null
        try {
            s.stopServer()
            log.log(LogSink.Level.INFO, "Đã tắt broker nhúng", null)
        } catch (e: Exception) {
            log.log(LogSink.Level.WARN, "Lỗi khi tắt broker nhúng", e)
        }
    }

    internal companion object {
        private const val DISABLED = "disabled"

        /**
         * Tin MQTT lớn nhất broker nhận: 512 KB — đủ cho cảnh báo kèm ảnh (MAX_ALERT_SNAPSHOT_BYTES = 200 KB,
         * base64 ≈ 270 KB) + JSON. Broker bắt buộc đăng nhập nên người ngoài không gửi được tin lớn.
         */
        const val MAX_MESSAGE_BYTES: Int = 512 * 1024
    }
}
