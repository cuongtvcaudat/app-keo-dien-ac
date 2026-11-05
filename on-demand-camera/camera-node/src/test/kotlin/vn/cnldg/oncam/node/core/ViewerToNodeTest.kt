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
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import vn.cnldg.oncam.node.broker.EmbeddedBroker
import vn.cnldg.oncam.shared.client.CameraClient
import vn.cnldg.oncam.shared.client.CameraEndpoint
import vn.cnldg.oncam.shared.client.CommandResult
import vn.cnldg.oncam.shared.client.MjpegReader
import vn.cnldg.oncam.shared.mqtt.MqttConnectionState
import vn.cnldg.oncam.shared.protocol.Action
import vn.cnldg.oncam.shared.protocol.CameraCodes
import vn.cnldg.oncam.shared.protocol.CameraFacing
import vn.cnldg.oncam.shared.protocol.CameraState
import vn.cnldg.oncam.shared.protocol.CommandParams
import vn.cnldg.oncam.shared.protocol.NetworkType
import vn.cnldg.oncam.shared.protocol.StreamQuality
import vn.cnldg.oncam.shared.security.StreamAuth

/**
 * Đầu-cuối: CameraClient (phía app Xem, module shared) ↔ camera node thật (broker nhúng + StreamManager
 * với camera giả). Đi theo các tiêu chí nghiệm thu ở spec mục 9.
 */
class ViewerToNodeTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mqttPort = ServerSocket(0).use { it.localPort }
    private val httpPort = ServerSocket(0).use { it.localPort }
    private val config = NodeConfig(deviceId = DEVICE, displayName = "Sân nhà", secret = SECRET, brokerPort = mqttPort)
    private val fake = FakeCameraPort(scope)
    private val streams = StreamManager(
        camera = fake,
        auth = StreamAuth(SECRET, DEVICE),
        scope = scope,
        addressProvider = { "127.0.0.1" },
        cameraAllowed = { true },
        deviceInfo = { DeviceSnapshot(76, true, 33.5, NetworkType.WIFI) },
        settings = StreamManager.Settings(httpPort = httpPort, checkIntervalMs = 50, msPerIdleSecond = 10),
    )
    private val node = NodeController(
        config = config,
        scope = scope,
        deviceInfo = { DeviceSnapshot(76, true, 33.5, NetworkType.WIFI) },
        addressProvider = { "127.0.0.1" },
        broker = EmbeddedBroker(createTempDirectory("moquette").toFile()),
        timing = NodeController.Timing(addressCheckMs = 200, keepAliveSec = 5, maxReconnectDelayMs = 1_000),
        camera = streams,
    )
    private val viewer = CameraClient(
        endpoint = CameraEndpoint(DEVICE, "tcp://127.0.0.1:$mqttPort", SECRET),
        clientId = "viewer-test",
        scope = scope,
        keepAliveSec = 5,
    )

    @AfterTest
    fun tearDown() = runBlocking<Unit> {
        viewer.stop()
        node.stop()
        scope.cancel()
    }

    private suspend fun connect() {
        node.start()
        viewer.start()
        withTimeout(TIMEOUT_MS) { viewer.view.first { it.online } }
    }

    @Test
    fun `viewer thay camera online voi pin nhiet do mang`() = runBlocking<Unit> {
        connect()
        val s = viewer.view.value.status!!

        assertEquals("Sân nhà", s.displayName)
        assertEquals(76, s.batteryPercent)
        assertEquals(33.5, s.temperatureC)
        assertEquals(NetworkType.WIFI, s.network)
        assertEquals(true, s.cameraReady)
        assertEquals(CameraState.OFF, s.cameraState)
    }

    @Test
    fun `bat camera xem snapshot doi camera flash chat luong roi tat`() = runBlocking<Unit> {
        connect()

        val start = viewer.send(Action.START_STREAM)
        assertIs<CommandResult.Ok>(start)
        withTimeout(TIMEOUT_MS) { viewer.view.first { it.status?.cameraState == CameraState.STREAMING } }

        delay(150)
        val snap = httpGet(viewer.snapshotUrl()!!, viewer.streamAuthHeader())
        assertEquals(200, snap.code)
        assertIs<CommandResult.Ok>(viewer.send(Action.SNAPSHOT))

        assertIs<CommandResult.Ok>(viewer.send(Action.SET_FLASH, CommandParams(flash = true)))
        withTimeout(TIMEOUT_MS) { viewer.view.first { it.status?.flashOn == true } }

        assertIs<CommandResult.Ok>(viewer.send(Action.SWITCH_CAMERA))
        withTimeout(TIMEOUT_MS) { viewer.view.first { it.status?.currentCamera == CameraFacing.FRONT } }
        // Camera trước không có flash.
        assertEquals(CommandResult.Rejected(CameraCodes.NO_FLASH), viewer.send(Action.SET_FLASH, CommandParams(flash = true)))

        assertIs<CommandResult.Ok>(viewer.send(Action.SET_QUALITY, CommandParams(quality = StreamQuality.LOW)))
        withTimeout(TIMEOUT_MS) { viewer.view.first { it.status?.quality == StreamQuality.LOW } }
        assertEquals(CameraFacing.FRONT to StreamQuality.LOW, fake.opened)

        assertIs<CommandResult.Ok>(viewer.send(Action.STOP_STREAM))
        withTimeout(TIMEOUT_MS) { viewer.view.first { it.status?.cameraState == CameraState.OFF } }
        assertEquals(null, fake.opened, "Tắt camera phải nhả camera")
    }

    @Test
    fun `xem truc tiep MJPEG do tre dem nguoi xem va khong tu tat khi dang xem`() = runBlocking<Unit> {
        connect()
        assertIs<CommandResult.Ok>(viewer.send(Action.START_STREAM, CommandParams(idleTimeoutSec = 30)))
        val url = withTimeout(TIMEOUT_MS) {
            viewer.view.first { it.status?.cameraState == CameraState.STREAMING }.status!!.streamUrl!!
        }

        val reader = withContext(Dispatchers.IO) { MjpegReader.open(url, viewer.streamAuthHeader()) }
        try {
            val startedMs = System.currentTimeMillis()
            var frames = 0
            var maxLatencyMs = 0L
            withContext(Dispatchers.IO) {
                // Xem ~1 giây: gấp hơn 3 lần thời gian chờ tự tắt (0,3 s trong test).
                while (System.currentTimeMillis() - startedMs < 1_000) {
                    val f = reader.next()!!
                    assertEquals(0xD8.toByte(), f.jpeg[1])
                    maxLatencyMs = maxOf(maxLatencyMs, System.currentTimeMillis() - f.capturedAtMs!!)
                    frames++
                }
            }
            assertTrue(frames >= 10, "Chỉ nhận $frames khung trong 1 giây")
            assertTrue(maxLatencyMs < 2_000, "Độ trễ $maxLatencyMs ms (yêu cầu < 2 s)")
            withTimeout(TIMEOUT_MS) { viewer.view.first { it.status?.viewers == 1 } }
            assertEquals(CameraState.STREAMING, viewer.view.value.status?.cameraState)
        } finally {
            reader.close()
        }

        // Rời màn hình xem → 0 người xem → hết thời gian chờ thì camera tự tắt.
        withTimeout(TIMEOUT_MS) { viewer.view.first { it.status?.cameraState == CameraState.OFF } }
    }

    @Test
    fun `khong ai xem thi camera tu tat va viewer thay dung trang thai`() = runBlocking<Unit> {
        connect()

        assertIs<CommandResult.Ok>(viewer.send(Action.START_STREAM, CommandParams(idleTimeoutSec = 30)))
        withTimeout(TIMEOUT_MS) { viewer.view.first { it.status?.cameraState == CameraState.STREAMING } }

        // 30 "giây" = 0,3 s trong test.
        withTimeout(TIMEOUT_MS) { viewer.view.first { it.status?.cameraState == CameraState.OFF } }
        assertEquals(null, fake.opened)
    }

    @Test
    fun `may camera dung thi viewer thay offline trong 30 giay`() = runBlocking<Unit> {
        connect()
        val startedMs = System.currentTimeMillis()

        // Broker nhúng tắt cùng camera node. (Trường hợp mất mạng đột ngột dựa vào keepalive 10 s
        // của viewer — lý thuyết ≤ 20 s — cần kiểm trên máy thật.)
        node.stop()

        withTimeout(30_000) { viewer.view.first { !it.online } }
        val elapsed = System.currentTimeMillis() - startedMs
        assertTrue(elapsed < 30_000, "Phát hiện offline mất $elapsed ms")
        withTimeout(30_000) { viewer.view.first { it.connection != MqttConnectionState.Connected } }
        assertEquals(CommandResult.NotConnected, viewer.send(Action.PING))
    }

    @Test
    fun `camera khoi dong lai thi viewer tu ket noi lai`() = runBlocking<Unit> {
        connect()
        node.stop()
        withTimeout(30_000) { viewer.view.first { !it.online } }

        node.start()
        viewer.reconnectNow()

        withTimeout(30_000) { viewer.view.first { it.online } }
        assertEquals(CommandResult.Ok("PONG"), viewer.send(Action.PING))
    }

    private companion object {
        const val DEVICE = "san-nha"
        const val SECRET = "test-secret-0123456789"
        const val TIMEOUT_MS = 10_000L
    }
}
