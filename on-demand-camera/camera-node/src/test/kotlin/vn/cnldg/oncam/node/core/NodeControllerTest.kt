package vn.cnldg.oncam.node.core

import java.net.ServerSocket
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import vn.cnldg.oncam.node.broker.EmbeddedBroker
import vn.cnldg.oncam.node.mqtt.CommandHandler
import vn.cnldg.oncam.shared.mqtt.IncomingMessage
import vn.cnldg.oncam.shared.mqtt.MqttClientWrapper
import vn.cnldg.oncam.shared.mqtt.MqttConfig
import vn.cnldg.oncam.shared.mqtt.MqttConnectionState
import vn.cnldg.oncam.shared.mqtt.OutgoingMessage
import vn.cnldg.oncam.shared.protocol.Action
import vn.cnldg.oncam.shared.protocol.DeviceEvent
import vn.cnldg.oncam.shared.protocol.DeviceStatus
import vn.cnldg.oncam.shared.protocol.EventType
import vn.cnldg.oncam.shared.protocol.NetworkType
import vn.cnldg.oncam.shared.protocol.ProtocolJson
import vn.cnldg.oncam.shared.protocol.RejectReason
import vn.cnldg.oncam.shared.protocol.Topics
import vn.cnldg.oncam.shared.security.BrokerCredentials
import vn.cnldg.oncam.shared.security.CommandSigner
import vn.cnldg.oncam.shared.protocol.CameraState
import vn.cnldg.oncam.shared.protocol.StreamPaths
import vn.cnldg.oncam.shared.security.StreamAuth

/**
 * Test đầu-cuối của camera node trên JVM: broker Moquette nhúng thật + client MQTT thật,
 * "IP Tailscale" giả lập bằng địa chỉ loopback.
 */
class NodeControllerTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val port = ServerSocket(0).use { it.localPort }
    private val config = NodeConfig(deviceId = DEVICE, displayName = "Sân nhà", secret = SECRET, brokerPort = port)
    private val device = DeviceInfoSource { DeviceSnapshot(80, true, 31.5, NetworkType.WIFI) }

    @Volatile
    private var address: String? = "127.0.0.1"
    private val controller = NodeController(
        config = config,
        scope = scope,
        deviceInfo = device,
        addressProvider = { address },
        broker = EmbeddedBroker(createTempDirectory("moquette").toFile()),
        timing = NodeController.Timing(addressCheckMs = 200, keepAliveSec = 5, maxReconnectDelayMs = 1_000),
    )
    private val viewers = mutableListOf<MqttClientWrapper>()

    @AfterTest
    fun tearDown() = runBlocking<Unit> {
        viewers.forEach { it.stop() }
        controller.stop()
        scope.cancel()
    }

    @Test
    fun `mo broker tren IP Tailscale va tu kiem tra PING thanh cong`() = runBlocking<Unit> {
        controller.start()
        awaitNodeConnected()

        val state = controller.state.value
        assertEquals(BrokerState.Running("127.0.0.1", port), state.broker)
        assertEquals("tcp://127.0.0.1:$port", state.brokerUri)

        // Nới trần thời gian cho PING khứ hồi: runner CI lúc tải nặng đôi khi chậm,
        // làm selfTest trả Failed dù broker vẫn thông (test flaky). Vẫn chứng minh PING chạy.
        val result = controller.selfTest(timeoutMs = TIMEOUT_MS)
        assertIs<SelfTestResult.Passed>(result)
        assertTrue(result.roundTripMs < TIMEOUT_MS)
        assertEquals(Action.PING, controller.state.value.lastCommand?.action)
        assertEquals(true, controller.state.value.lastCommand?.success)
    }

    @Test
    fun `viewer thay trang thai online va nhan ket qua lenh`() = runBlocking<Unit> {
        controller.start()
        awaitNodeConnected()
        val viewer = viewer("127.0.0.1", BrokerCredentials.password(SECRET))
        viewer.awaitConnected()
        viewer.subscribe(Topics.status(DEVICE), 1)
        viewer.subscribe(Topics.event(DEVICE), 1)

        val status = ProtocolJson.decodeStatus(viewer.next(Topics.status(DEVICE)).payload)!!
        assertEquals(true, status.online)
        assertEquals("Sân nhà", status.displayName)
        assertEquals(80, status.batteryPercent)
        assertEquals(NetworkType.WIFI, status.network)

        val signer = CommandSigner(SECRET)
        val ping = signer.sign(DEVICE, Action.PING)
        viewer.sendCommand(ProtocolJson.encodeCommand(ping))
        val pong = viewer.nextEvent()
        assertEquals(EventType.COMMAND_RESULT, pong.event)
        assertEquals(ping.id, pong.commandId)
        assertEquals(true, pong.success)
        assertEquals(CommandHandler.PONG, pong.message)

        // Lệnh camera chưa có ở Phase 2 → báo chưa hỗ trợ, không im lặng.
        viewer.sendCommand(ProtocolJson.encodeCommand(signer.sign(DEVICE, Action.START_STREAM)))
        assertEquals(RejectReason.UNSUPPORTED.name, viewer.nextEvent().message)

        // Lệnh ký bằng token khác → bị từ chối.
        val forged = CommandSigner("token-gia-mao-0123456789").sign(DEVICE, Action.PING)
        viewer.sendCommand(ProtocolJson.encodeCommand(forged))
        val rejected = viewer.nextEvent()
        assertEquals(false, rejected.success)
        assertEquals(RejectReason.BAD_SIGNATURE.name, rejected.message)
    }

    @Test
    fun `sai token thi khong dang nhap duoc broker nhung`() = runBlocking<Unit> {
        controller.start()
        awaitNodeConnected()

        val intruder = viewer("127.0.0.1", BrokerCredentials.password("token-khac-0123456789"))

        withTimeout(TIMEOUT_MS) { intruder.state.first { it is MqttConnectionState.Disconnected } }
        delay(500)
        assertTrue(intruder.state.value != MqttConnectionState.Connected)
    }

    @Test
    fun `dung em thi viewer thay trang thai offline`() = runBlocking<Unit> {
        controller.start()
        awaitNodeConnected()
        val viewer = viewer("127.0.0.1", BrokerCredentials.password(SECRET))
        viewer.awaitConnected()
        viewer.subscribe(Topics.status(DEVICE), 1)
        assertEquals(true, viewer.nextStatus().online)

        controller.stop()

        assertEquals(false, viewer.nextStatus().online)
        assertEquals(false, controller.state.value.running)
    }

    @Test
    fun `chua co IP Tailscale thi cho va co IP la mo broker`() = runBlocking<Unit> {
        address = null
        controller.start()
        delay(600)
        assertEquals(BrokerState.WaitingForAddress, controller.state.value.broker)
        assertEquals(MqttConnectionState.Idle, controller.state.value.mqtt)

        address = "127.0.0.1"
        controller.onNetworkChanged()

        awaitNodeConnected()
        assertEquals(BrokerState.Running("127.0.0.1", port), controller.state.value.broker)
    }

    @Test
    fun `IP Tailscale doi thi mo lai broker tren IP moi`() = runBlocking<Unit> {
        controller.start()
        awaitNodeConnected()

        address = "127.0.0.2" // Linux nhận mọi địa chỉ 127.x.x.x là loopback
        controller.onNetworkChanged()

        withTimeout(TIMEOUT_MS) {
            controller.state.first {
                it.broker == BrokerState.Running("127.0.0.2", port) && it.mqtt == MqttConnectionState.Connected
            }
        }
        val result = controller.selfTest()
        assertIs<SelfTestResult.Passed>(result)
    }

    @Test
    fun `viewer bat camera qua MQTT xem snapshot roi tat camera`() = runBlocking<Unit> {
        val httpPort = ServerSocket(0).use { it.localPort }
        val streamAuth = StreamAuth(SECRET, DEVICE)
        val camScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val streams = StreamManager(
            camera = FakeCameraPort(camScope),
            auth = streamAuth,
            scope = camScope,
            addressProvider = { address },
            cameraAllowed = { true },
            deviceInfo = device,
            settings = StreamManager.Settings(httpPort = httpPort, checkIntervalMs = 50),
        )
        val node = NodeController(
            config = config,
            scope = scope,
            deviceInfo = device,
            addressProvider = { address },
            broker = EmbeddedBroker(createTempDirectory("moquette").toFile()),
            timing = NodeController.Timing(addressCheckMs = 200, keepAliveSec = 5, maxReconnectDelayMs = 1_000),
            camera = streams,
        )
        try {
            node.start()
            withTimeout(TIMEOUT_MS) { node.state.first { it.mqtt == MqttConnectionState.Connected } }
            val viewer = viewer("127.0.0.1", BrokerCredentials.password(SECRET))
            viewer.awaitConnected()
            viewer.subscribe(Topics.status(DEVICE), 1)
            viewer.subscribe(Topics.event(DEVICE), 1)
            assertEquals(CameraState.OFF, viewer.nextStatus().cameraState)

            val signer = CommandSigner(SECRET)
            viewer.sendCommand(ProtocolJson.encodeCommand(signer.sign(DEVICE, Action.START_STREAM)))

            val url = "http://127.0.0.1:$httpPort${StreamPaths.STREAM}"
            val streaming = withTimeout(TIMEOUT_MS) {
                var s: DeviceStatus
                do { s = viewer.nextStatus() } while (s.cameraState != CameraState.STREAMING)
                s
            }
            assertEquals(url, streaming.streamUrl)
            assertEquals(true, streaming.cameraReady)

            delay(150)
            val snap = httpGet("http://127.0.0.1:$httpPort${StreamPaths.SNAPSHOT}", streamAuth.headerValue())
            assertEquals(200, snap.code)

            viewer.sendCommand(ProtocolJson.encodeCommand(signer.sign(DEVICE, Action.STOP_STREAM)))
            withTimeout(TIMEOUT_MS) {
                var s: DeviceStatus
                do { s = viewer.nextStatus() } while (s.cameraState != CameraState.OFF)
            }
            assertEquals(CameraState.OFF, node.state.value.camera.state)
        } finally {
            node.stop()
            camScope.cancel()
        }
    }

    // --- tiện ích ---

    private suspend fun awaitNodeConnected() {
        withTimeout(TIMEOUT_MS) { controller.state.first { it.mqtt == MqttConnectionState.Connected } }
    }

    private fun viewer(host: String, password: String): MqttClientWrapper =
        MqttClientWrapper(
            MqttConfig(
                serverUri = "tcp://$host:$port",
                clientId = "viewer-${System.nanoTime()}",
                username = BrokerCredentials.USERNAME,
                password = password,
                keepAliveSec = 5,
                connectTimeoutSec = 3,
                maxReconnectDelayMs = 1_000,
            ),
            scope,
        ).also {
            viewers += it
            it.start()
        }

    private suspend fun MqttClientWrapper.awaitConnected() {
        withTimeout(TIMEOUT_MS) { state.first { it == MqttConnectionState.Connected } }
    }

    private suspend fun MqttClientWrapper.next(topic: String): IncomingMessage =
        withTimeout(TIMEOUT_MS) { messages.first { it.topic == topic } }

    private suspend fun MqttClientWrapper.nextEvent(): DeviceEvent =
        ProtocolJson.decodeEvent(next(Topics.event(DEVICE)).payload)!!

    private suspend fun MqttClientWrapper.nextStatus(): DeviceStatus =
        ProtocolJson.decodeStatus(next(Topics.status(DEVICE)).payload)!!

    private suspend fun MqttClientWrapper.sendCommand(json: String) {
        assertTrue(publish(OutgoingMessage(Topics.command(DEVICE), json, Topics.QOS_COMMAND, false)))
    }

    private companion object {
        const val DEVICE = "san-nha"
        const val SECRET = "test-secret-0123456789"
        // Trần thời gian chờ sự kiện qua broker MQTT thật. Để rộng (60s) vì runner CI
        // tải nặng đôi khi chậm; đây là test đúng/sai, không đo tốc độ, nên nới không làm yếu test.
        const val TIMEOUT_MS = 60_000L
    }
}
