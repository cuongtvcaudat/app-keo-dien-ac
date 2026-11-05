package vn.cnldg.oncam.shared.mqtt

import io.moquette.broker.Server
import io.moquette.broker.config.IConfig
import io.moquette.broker.config.MemoryConfig
import io.moquette.broker.security.IAuthenticator
import java.net.ServerSocket
import java.util.Properties
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import vn.cnldg.oncam.shared.protocol.DeviceStatus
import vn.cnldg.oncam.shared.protocol.ProtocolJson
import vn.cnldg.oncam.shared.protocol.Topics

/**
 * Test tích hợp với broker MQTT thật (Moquette chạy trong JVM, chỉ nghe 127.0.0.1).
 */
class MqttClientWrapperTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val clients = mutableListOf<MqttClientWrapper>()
    private var broker: Server? = null
    private val port = freePort()

    @BeforeTest
    fun setUp() {
        broker = startBroker()
    }

    @AfterTest
    fun tearDown() {
        runBlocking { clients.forEach { it.stop() } }
        scope.cancel()
        broker?.stopServer()
    }

    @Test
    fun `ket noi va nhan duoc lenh QoS 1`() = runBlocking<Unit> {
        val node = client("node")
        val viewer = client("viewer")
        node.awaitConnected()
        viewer.awaitConnected()

        assertTrue(node.subscribe(Topics.command(DEVICE), Topics.QOS_COMMAND))
        assertTrue(viewer.publish(OutgoingMessage(Topics.command(DEVICE), "{\"lenh\":1}", Topics.QOS_COMMAND, false)))

        val msg = withTimeout(TIMEOUT_MS) { node.messages.first() }
        assertEquals(Topics.command(DEVICE), msg.topic)
        assertEquals("{\"lenh\":1}", msg.payload)
        assertFalse(msg.retained)
    }

    @Test
    fun `viewer vao sau van nhan trang thai retain`() = runBlocking<Unit> {
        val node = client("node")
        node.awaitConnected()
        val status = ProtocolJson.encodeStatus(DeviceStatus(deviceId = DEVICE, online = true, lastSeen = 1))
        assertTrue(node.publish(OutgoingMessage(Topics.status(DEVICE), status, Topics.QOS_STATUS, retained = true)))

        val viewer = client("viewer")
        viewer.awaitConnected()
        viewer.subscribe(Topics.ALL_STATUS, 1)

        val msg = withTimeout(TIMEOUT_MS) { viewer.messages.first() }
        // Không kiểm msg.retained: Moquette 0.17 không bật cờ retain khi gửi tin đã lưu.
        assertEquals(true, ProtocolJson.decodeStatus(msg.payload)?.online)
    }

    @Test
    fun `mat mang dot ngot thi broker phat LWT offline`() = runBlocking<Unit> {
        val viewer = client("viewer")
        viewer.awaitConnected()
        viewer.subscribe(Topics.status(DEVICE), 1)

        val node = client("node", will = offlineWill())
        node.awaitConnected()
        node.simulateNetworkLossForTest()

        val msg = withTimeout(TIMEOUT_MS) { viewer.messages.first() }
        val status = ProtocolJson.decodeStatus(msg.payload)
        assertEquals(false, status?.online)
        assertEquals(DEVICE, status?.deviceId)
    }

    @Test
    fun `stop em thi khong phat LWT`() = runBlocking<Unit> {
        val viewer = client("viewer")
        viewer.awaitConnected()
        viewer.subscribe(Topics.status(DEVICE), 1)

        val node = client("node", will = offlineWill())
        node.awaitConnected()
        node.stop()

        assertEquals(MqttConnectionState.Idle, node.state.value)
        assertNull(withTimeoutOrNull(2_000) { viewer.messages.first() })
    }

    @Test
    fun `broker khoi dong lai thi tu ket noi lai va subscribe lai`() = runBlocking<Unit> {
        val node = client("node")
        node.awaitConnected()
        node.subscribe(Topics.command(DEVICE), Topics.QOS_COMMAND)

        broker?.stopServer()
        withTimeout(TIMEOUT_MS) { node.state.first { it is MqttConnectionState.Disconnected } }
        assertFalse(node.publish(OutgoingMessage(Topics.status(DEVICE), "x", 0, false)))

        delay(1_500)
        broker = startBroker()
        val started = System.currentTimeMillis()
        node.reconnectNow()
        node.awaitConnected(timeoutMs = 20_000)
        val reconnectMs = System.currentTimeMillis() - started
        assertTrue(reconnectMs < 15_000, "Kết nối lại mất $reconnectMs ms")

        val viewer = client("viewer")
        viewer.awaitConnected()
        viewer.publish(OutgoingMessage(Topics.command(DEVICE), "sau-khi-ket-noi-lai", Topics.QOS_COMMAND, false))
        val msg = withTimeout(TIMEOUT_MS) { node.messages.first() }
        assertEquals("sau-khi-ket-noi-lai", msg.payload)
    }

    @Test
    fun `broker co mat khau thi tu choi sai mat khau va nhan dung mat khau`() = runBlocking<Unit> {
        broker?.stopServer()
        broker = startBroker(
            authenticator = { _, user, pass -> user == "oncam" && pass != null && String(pass, Charsets.UTF_8) == "mat-khau-dung" },
        )

        val wrong = client("sai", username = "oncam", password = "mat-khau-sai")
        val right = client("dung", username = "oncam", password = "mat-khau-dung")

        right.awaitConnected()
        val state = withTimeout(TIMEOUT_MS) { wrong.state.first { it is MqttConnectionState.Disconnected } }
        assertIs<MqttConnectionState.Disconnected>(state)
        assertEquals(MqttConnectionState.Connected, right.state.value)
    }

    @Test
    fun `cau hinh khong in mat khau ra log`() {
        val cfg = MqttConfig(serverUri = "tcp://127.0.0.1:1", clientId = "c", password = "bi-mat")

        assertFalse(cfg.toString().contains("bi-mat"))
    }

    // --- tiện ích ---

    private fun client(
        name: String,
        will: OutgoingMessage? = null,
        username: String? = null,
        password: String? = null,
    ): MqttClientWrapper {
        val config = MqttConfig(
            serverUri = "tcp://127.0.0.1:$port",
            clientId = "test-$name-${System.nanoTime()}",
            username = username,
            password = password,
            keepAliveSec = 5,
            connectTimeoutSec = 3,
            maxReconnectDelayMs = 2_000,
            will = will,
        )
        return MqttClientWrapper(config, scope).also {
            clients += it
            it.start()
        }
    }

    private fun offlineWill() = OutgoingMessage(
        topic = Topics.status(DEVICE),
        payload = ProtocolJson.encodeStatus(DeviceStatus.offline(DEVICE, "Sân nhà", 1)),
        qos = Topics.QOS_WILL,
        retained = true,
    )

    private suspend fun MqttClientWrapper.awaitConnected(timeoutMs: Long = TIMEOUT_MS) {
        withTimeout(timeoutMs) { state.first { it == MqttConnectionState.Connected } }
    }

    private fun startBroker(authenticator: IAuthenticator? = null): Server {
        val props = Properties().apply {
            setProperty(IConfig.HOST_PROPERTY_NAME, "127.0.0.1")
            setProperty(IConfig.PORT_PROPERTY_NAME, port.toString())
            setProperty(IConfig.WEB_SOCKET_PORT_PROPERTY_NAME, "disabled")
            setProperty(IConfig.PERSISTENCE_ENABLED_PROPERTY_NAME, "false")
            setProperty(IConfig.ALLOW_ANONYMOUS_PROPERTY_NAME, (authenticator == null).toString())
        }
        return Server().apply { startServer(MemoryConfig(props), emptyList(), null, authenticator, null) }
    }

    private companion object {
        const val DEVICE = "san-nha"
        const val TIMEOUT_MS = 10_000L

        fun freePort(): Int = ServerSocket(0).use { it.localPort }
    }
}
