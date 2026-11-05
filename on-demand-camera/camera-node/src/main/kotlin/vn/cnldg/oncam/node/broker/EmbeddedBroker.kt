package vn.cnldg.oncam.node.broker

import io.moquette.broker.Server
import io.moquette.broker.config.IConfig
import io.moquette.broker.config.MemoryConfig
import io.moquette.broker.security.IAuthenticator
import java.io.File
import java.security.MessageDigest
import java.util.Properties
import vn.cnldg.oncam.shared.LogSink

/**
 * Broker MQTT Moquette chạy trong app camera-node (đề xuất P1).
 *
 * - Chỉ nghe trên [start] `host` được truyền vào (IP Tailscale) — không bao giờ 0.0.0.0.
 * - Bắt buộc đăng nhập (tắt anonymous), không lưu dữ liệu xuống đĩa.
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
     * Mở broker tại [host]:[port]. Nếu đang chạy thì tắt bản cũ trước.
     * @throws java.io.IOException nếu không bind được (sai IP, cổng bị chiếm...).
     */
    @Synchronized
    fun start(host: String, port: Int, username: String, password: String) {
        require(host != "0.0.0.0" && host != "::") { "Không được mở broker trên mọi giao diện mạng" }
        stopLocked()
        dataDir.mkdirs()
        val props = Properties().apply {
            setProperty(IConfig.HOST_PROPERTY_NAME, host)
            setProperty(IConfig.PORT_PROPERTY_NAME, port.toString())
            setProperty(IConfig.WEB_SOCKET_PORT_PROPERTY_NAME, DISABLED)
            setProperty(IConfig.PERSISTENCE_ENABLED_PROPERTY_NAME, "false")
            setProperty(IConfig.DATA_PATH_PROPERTY_NAME, dataDir.absolutePath)
            setProperty(IConfig.ALLOW_ANONYMOUS_PROPERTY_NAME, "false")
        }
        val expected = password.toByteArray(Charsets.UTF_8)
        val authenticator = IAuthenticator { _, user, pass ->
            user == username && pass != null && MessageDigest.isEqual(pass, expected)
        }
        val s = Server()
        s.startServer(MemoryConfig(props), emptyList(), null, authenticator, null)
        server = s
        log.log(LogSink.Level.INFO, "Broker nhúng đang nghe tại $host:$port", null)
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

    private companion object {
        const val DISABLED = "disabled"
    }
}
