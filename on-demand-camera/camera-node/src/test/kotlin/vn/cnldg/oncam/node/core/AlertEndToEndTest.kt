package vn.cnldg.oncam.node.core

import java.net.ServerSocket
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import vn.cnldg.oncam.node.broker.EmbeddedBroker
import vn.cnldg.oncam.shared.client.CameraClient
import vn.cnldg.oncam.shared.client.CameraEndpoint
import vn.cnldg.oncam.shared.mqtt.MqttClientWrapper
import vn.cnldg.oncam.shared.mqtt.MqttConfig
import vn.cnldg.oncam.shared.mqtt.MqttConnectionState
import vn.cnldg.oncam.shared.mqtt.OutgoingMessage
import vn.cnldg.oncam.shared.protocol.AlertType
import vn.cnldg.oncam.shared.protocol.DeviceAlert
import vn.cnldg.oncam.shared.protocol.NetworkType
import vn.cnldg.oncam.shared.protocol.ProtocolJson
import vn.cnldg.oncam.shared.protocol.Topics
import vn.cnldg.oncam.shared.security.AlertAuth
import vn.cnldg.oncam.shared.security.BrokerCredentials

/**
 * Cảnh báo đi đầu-cuối: AlertManager → NodeController → broker nhúng thật → CameraClient của app Xem.
 * Ảnh kèm ~60 KB để chắc broker không cắt tin lớn.
 */
class AlertEndToEndTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mqttPort = ServerSocket(0).use { it.localPort }
    private val config = NodeConfig(deviceId = DEVICE, displayName = "Sân nhà", secret = SECRET, brokerPort = mqttPort)
    private val jpeg = ByteArray(60_000) { (it * 13 + it / 251).toByte() }

    @Volatile private var device = DeviceSnapshot(80, true, 33.0, NetworkType.WIFI, plugged = true)

    private val alerts = AlertManager(
        deviceId = DEVICE,
        auth = AlertAuth(SECRET, DEVICE),
        scope = scope,
        settings = { config.alertSettings },
        cameraName = { config.displayName },
        snapshot = { jpeg },
        policy = AlertManager.Policy(motionSnapshotDelayMs = 10, powerLostAfterMs = 150),
    )
    private val node = NodeController(
        config = config,
        scope = scope,
        deviceInfo = { device },
        addressProvider = { "127.0.0.1" },
        broker = EmbeddedBroker(createTempDirectory("moquette").toFile()),
        timing = NodeController.Timing(addressCheckMs = 200, keepAliveSec = 5, maxReconnectDelayMs = 1_000, alertCheckMs = 50),
        alerts = alerts,
    )
    private val viewer = CameraClient(
        endpoint = CameraEndpoint(DEVICE, "tcp://127.0.0.1:$mqttPort", SECRET),
        clientId = "viewer-test",
        scope = scope,
        keepAliveSec = 5,
    )
    private val received = CopyOnWriteArrayList<DeviceAlert>()

    init {
        scope.launch { viewer.alerts.collect { received += it } }
    }

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
        delay(200) // chờ đăng ký topic alert xong
    }

    private suspend fun await(count: Int) = withTimeout(TIMEOUT_MS) { while (received.size < count) delay(20) }

    @Test
    fun `canh bao chuyen dong kem anh 60 KB toi app Xem nguyen ven`() = runBlocking<Unit> {
        connect()
        alerts.onMotion("oncam-20270115-010000.mp4")
        await(1)
        val a = received.single()
        assertEquals(AlertType.MOTION, a.type)
        assertEquals("Sân nhà", a.cameraName)
        assertEquals("oncam-20270115-010000.mp4", a.clip)
        assertContentEquals(jpeg, Base64.getDecoder().decode(a.snapshot))
    }

    @Test
    fun `rut nguon thi app Xem nhan mat dien, cam lai nhan co dien`() = runBlocking<Unit> {
        connect()
        device = device.copy(charging = false, plugged = false, batteryPercent = 78)
        await(1)
        assertEquals(AlertType.POWER_LOST, received[0].type)
        assertEquals(78, received[0].batteryPercent)
        device = device.copy(charging = true, plugged = true)
        await(2)
        assertEquals(AlertType.POWER_RESTORED, received[1].type)
    }

    @Test
    fun `canh bao gia - sai chu ky hoac qua cu - bi app Xem bo`() = runBlocking<Unit> {
        connect()
        // Kẻ biết mật khẩu broker (nhưng không có token) tự publish cảnh báo.
        val intruder = MqttClientWrapper(
            MqttConfig(
                serverUri = "tcp://127.0.0.1:$mqttPort",
                clientId = "intruder",
                username = BrokerCredentials.USERNAME,
                password = BrokerCredentials.password(SECRET),
            ),
            scope,
        )
        intruder.start()
        withTimeout(TIMEOUT_MS) { intruder.state.first { it == MqttConnectionState.Connected } }
        val topic = Topics.alert(DEVICE)
        val now = System.currentTimeMillis()
        val forged = DeviceAlert(AlertType.MOTION, now, cameraName = "Sân nhà", signature = "00".repeat(32))
        val wrongKey = AlertAuth("token-khac-0123456789", DEVICE).sign(DeviceAlert(AlertType.POWER_LOST, now))
        val stale = AlertAuth(SECRET, DEVICE).sign(DeviceAlert(AlertType.POWER_LOST, now - 11 * 60_000))
        val genuine = AlertAuth(SECRET, DEVICE).sign(DeviceAlert(AlertType.OVERHEAT, now, temperatureC = 43.0))
        for (a in listOf(forged, wrongKey, stale, genuine, genuine)) {
            assertTrue(intruder.publish(OutgoingMessage(topic, ProtocolJson.encodeAlert(a), Topics.QOS_ALERT, false)))
        }
        await(1)
        delay(300)
        assertEquals(listOf(AlertType.OVERHEAT), received.map { it.type }, "chỉ nhận cảnh báo ký đúng, còn mới, không trùng")
        intruder.stop()
    }

    private companion object {
        const val DEVICE = "san-nha"
        const val SECRET = "test-secret-0123456789"
        const val TIMEOUT_MS = 10_000L
    }
}
