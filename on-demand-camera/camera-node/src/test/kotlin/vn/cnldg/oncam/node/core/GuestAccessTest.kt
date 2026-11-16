package vn.cnldg.oncam.node.core

import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URI
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import vn.cnldg.oncam.node.broker.EmbeddedBroker
import vn.cnldg.oncam.shared.client.CameraClient
import vn.cnldg.oncam.shared.client.CameraEndpoint
import vn.cnldg.oncam.shared.client.CommandResult
import vn.cnldg.oncam.shared.client.PairingCode
import vn.cnldg.oncam.shared.client.RtcAnswerResult
import vn.cnldg.oncam.shared.mqtt.IncomingMessage
import vn.cnldg.oncam.shared.mqtt.MqttClientWrapper
import vn.cnldg.oncam.shared.mqtt.MqttConfig
import vn.cnldg.oncam.shared.mqtt.MqttConnectionState
import vn.cnldg.oncam.shared.mqtt.OutgoingMessage
import vn.cnldg.oncam.shared.protocol.Action
import vn.cnldg.oncam.shared.protocol.CameraCodes
import vn.cnldg.oncam.shared.protocol.CameraFacing
import vn.cnldg.oncam.shared.protocol.CameraState
import vn.cnldg.oncam.shared.protocol.CommandParams
import vn.cnldg.oncam.shared.protocol.NetworkType
import vn.cnldg.oncam.shared.protocol.ProtocolJson
import vn.cnldg.oncam.shared.protocol.StreamPaths
import vn.cnldg.oncam.shared.protocol.StreamQuality
import vn.cnldg.oncam.shared.protocol.Topics
import vn.cnldg.oncam.shared.security.BrokerCredentials
import vn.cnldg.oncam.shared.security.CommandSigner
import vn.cnldg.oncam.shared.security.StreamAuth

/**
 * Quyền "chỉ xem" (0.13) đầu-cuối với broker nhúng thật: khách xem được hình, KHÔNG điều khiển, KHÔNG thấy vị trí,
 * KHÔNG nghe/nói, KHÔNG đọc được topic của chủ; đổi token khách là thu hồi.
 */
class GuestAccessTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mqttPort = ServerSocket(0).use { it.localPort }
    private val httpPort = ServerSocket(0).use { it.localPort }
    private val recPort = ServerSocket(0).use { it.localPort }
    private val config = NodeConfig(
        deviceId = DEVICE,
        displayName = "Sân nhà",
        secret = SECRET,
        brokerPort = mqttPort,
        guestEnabled = true,
        guestSecret = GUEST_SECRET,
    )
    private val fake = FakeCameraPort(scope)
    private val rtc = RecordingRtc()
    private val streams = StreamManager(
        camera = fake,
        auth = StreamAuth(SECRET, DEVICE),
        scope = scope,
        addressProvider = { "127.0.0.1" },
        cameraAllowed = { true },
        deviceInfo = { DeviceSnapshot(76, true, 33.5, NetworkType.WIFI) },
        settings = StreamManager.Settings(httpPort = httpPort, recordingPort = recPort, checkIntervalMs = 50, msPerIdleSecond = 10),
        rtc = rtc,
        recordings = RecordingStore(createTempDirectory("oncam-guest-rec").toFile()),
        guestAuth = StreamAuth(GUEST_SECRET, DEVICE),
    )
    private val node = NodeController(
        config = config,
        scope = scope,
        deviceInfo = { DeviceSnapshot(76, true, 33.5, NetworkType.WIFI) },
        addressProvider = { "127.0.0.1" },
        broker = EmbeddedBroker(createTempDirectory("moquette").toFile()),
        timing = NodeController.Timing(addressCheckMs = 200, keepAliveSec = 5, maxReconnectDelayMs = 1_000),
        camera = streams,
        location = LocationTracker({ LocationFix.Ok(HOME) }, scope),
    )
    private val owner = client(SECRET, guest = false, id = "owner")
    private val guest = client(GUEST_SECRET, guest = true, id = "guest")

    private fun client(secret: String, guest: Boolean, id: String) = CameraClient(
        endpoint = CameraEndpoint(DEVICE, "tcp://127.0.0.1:$mqttPort", secret, guest = guest),
        clientId = "viewer-$id",
        scope = scope,
        keepAliveSec = 5,
    )

    @AfterTest
    fun tearDown() = runBlocking<Unit> {
        guest.stop()
        owner.stop()
        node.stop()
        scope.cancel()
    }

    private suspend fun connect() {
        node.start()
        owner.start()
        guest.start()
        withTimeout(TIMEOUT_MS) { owner.view.first { it.online && it.status?.location?.point == HOME } }
        withTimeout(TIMEOUT_MS) { guest.view.first { it.online } }
    }

    @Test
    fun `khach thay camera online nhung khong thay vi tri, quay video, canh chuyen dong`() = runBlocking<Unit> {
        connect()
        val s = guest.view.value.status!!
        assertEquals("Sân nhà", s.displayName)
        assertEquals(76, s.batteryPercent)
        assertNull(s.location, "khách không được thấy vị trí")
        assertNull(s.recording)
        assertNull(s.motion)
        assertEquals(false, s.micReady)
        assertNull(guest.view.value.lastLocation)
        // Chủ vẫn thấy đủ.
        assertEquals(HOME, owner.view.value.status!!.location!!.point)
        assertNotNull(owner.view.value.status!!.recording)
    }

    @Test
    fun `khach bat camera de xem duoc, moi lenh dieu khien deu bi tu choi`() = runBlocking<Unit> {
        connect()
        // Khách cố nhét tham số điều khiển vào lệnh bật: camera trước, đèn, chất lượng cao, chờ 1 giờ.
        val started = guest.send(
            Action.START_STREAM,
            CommandParams(camera = CameraFacing.FRONT, flash = true, quality = StreamQuality.HIGH, idleTimeoutSec = 3_600),
        )
        assertIs<CommandResult.Ok>(started)
        val st = streams.status.value
        assertEquals(CameraState.STREAMING, st.state)
        assertEquals(CameraFacing.BACK, st.facing, "khách không đổi được camera")
        assertEquals(StreamQuality.MEDIUM, st.quality, "khách không đổi được chất lượng")
        assertFalse(st.flashOn, "khách không bật được đèn")
        assertEquals(180, st.idleTimeoutSec, "khách không kéo dài được thời gian chờ")

        val denied = CommandResult.Rejected(CameraCodes.GUEST_NOT_ALLOWED)
        assertEquals(denied, guest.send(Action.STOP_STREAM))
        assertEquals(denied, guest.send(Action.SWITCH_CAMERA))
        assertEquals(denied, guest.send(Action.SET_FLASH, CommandParams(flash = true)))
        assertEquals(denied, guest.send(Action.SET_QUALITY, CommandParams(quality = StreamQuality.LOW)))
        assertEquals(denied, guest.send(Action.START_RECORDING))
        assertEquals(denied, guest.send(Action.OPEN_RECORDINGS))
        assertEquals(denied, guest.send(Action.SET_MOTION, CommandParams(motion = true)))
        assertEquals(denied, guest.send(Action.UPDATE_LOCATION))
        assertEquals(CameraState.STREAMING, streams.status.value.state)
        assertFalse(streams.status.value.recording.active)

        // Xem MJPEG / ảnh chụp bằng xác thực của khách thì được; tải video quay thì không.
        assertEquals(200, http("http://127.0.0.1:$httpPort${StreamPaths.SNAPSHOT}", guest.streamAuthHeader()))
        assertEquals(401, http("http://127.0.0.1:$httpPort${StreamPaths.SNAPSHOT}", "sai"))
        val base = assertNotNull(owner.openRecordings())
        assertEquals(200, http("$base/recordings", owner.streamAuthHeader()))
        assertEquals(401, http("$base/recordings", guest.streamAuthHeader()))
    }

    @Test
    fun `khach xem WebRTC khong co tieng, chu thi co`() = runBlocking<Unit> {
        connect()
        val answer = guest.rtcOffer(SESSION_GUEST, OFFER, idleTimeoutSec = 60)
        assertIs<RtcAnswerResult.Answer>(answer)
        assertEquals(listOf(false), rtc.audioFlags.toList(), "phiên của khách: không tiếng 2 chiều")

        assertIs<RtcAnswerResult.Answer>(owner.rtcOffer(SESSION_OWNER, OFFER, idleTimeoutSec = 60))
        assertEquals(listOf(false, true), rtc.audioFlags.toList())
    }

    @Test
    fun `tai khoan khach khong doc duoc topic cua chu va khong gui duoc lenh vao topic chu`() = runBlocking<Unit> {
        connect()
        val seen = CopyOnWriteArrayList<IncomingMessage>()
        val raw = MqttClientWrapper(
            MqttConfig(
                serverUri = "tcp://127.0.0.1:$mqttPort",
                clientId = "khach-to-mo",
                username = BrokerCredentials.GUEST_USERNAME,
                password = BrokerCredentials.password(GUEST_SECRET),
            ),
            scope,
        )
        scope.launch { raw.messages.collect { seen += it } }
        // Đăng ký mọi kiểu: topic chủ, cảnh báo, đại diện.
        for (t in listOf(Topics.status(DEVICE), Topics.event(DEVICE), Topics.alert(DEVICE), Topics.command(DEVICE), "#", "camera/+/status")) {
            raw.subscribe(t, 1)
        }
        raw.subscribe(Topics.guestStatus(DEVICE), 1)
        raw.start()
        withTimeout(TIMEOUT_MS) { raw.state.first { it == MqttConnectionState.Connected } }

        // Khách (có token khách) ký lệnh hợp lệ nhưng gửi vào topic lệnh của CHỦ → broker chặn, camera không nhận.
        val guestSigned = CommandSigner(GUEST_SECRET).sign(DEVICE, Action.START_STREAM)
        raw.publish(OutgoingMessage(Topics.command(DEVICE), ProtocolJson.encodeCommand(guestSigned), 1, false))
        // Chủ làm vài việc để có tin trên topic chủ.
        assertEquals(CommandResult.Ok(null), owner.send(Action.UPDATE_LOCATION))
        // Chờ tới khi khách nhận được trạng thái (retained của topic khách) thay vì chờ cứng — tránh chập chờn khi
        // máy CI tải nặng; rồi thêm chút để nếu lệnh giả có lọt vào thì trạng thái cũng kịp đổi (lúc đó test sẽ bắt).
        withTimeout(TIMEOUT_MS) { while (seen.isEmpty()) delay(20) }
        delay(300)

        assertEquals(CameraState.OFF, streams.status.value.state, "lệnh khách vào topic chủ không được thực hiện")
        assertTrue(seen.all { it.topic == Topics.guestStatus(DEVICE) }, "khách thấy: ${seen.map { it.topic }.distinct()}")
        assertTrue(seen.none { it.payload.contains("\"location\"") }, "không lộ vị trí")
        raw.stop()
    }

    @Test
    fun `ma chi xem bi thu hoi hoac sai thi khong ket noi duoc`() = runBlocking<Unit> {
        connect()
        val revoked = client("token-khach-cu-0123456789", guest = true, id = "revoked")
        revoked.start()
        delay(2_000)
        assertFalse(revoked.view.value.online)
        assertTrue(revoked.view.value.connection !is MqttConnectionState.Connected)
        revoked.stop()

        // Mã QR chỉ xem mang cờ g=1 và token khách (không phải token chủ).
        val code = PairingCode.encode(PairingCode.Info(DEVICE, "tcp://100.64.0.1:1883", GUEST_SECRET, "Sân nhà", guest = true))
        val info = assertNotNull(PairingCode.decode(code))
        assertTrue(info.guest)
        assertEquals(GUEST_SECRET, info.secret)
        assertFalse(code.contains(SECRET))
        assertFalse(assertNotNull(PairingCode.decode(PairingCode.encode(info.copy(guest = false)))).guest)
    }

    @Test
    fun `token khach phai khac token chu va du manh`() {
        assertEquals(listOf(ConfigProblem.INVALID_GUEST), config.copy(guestSecret = SECRET).problems())
        assertEquals(listOf(ConfigProblem.INVALID_GUEST), config.copy(guestSecret = "ngan").problems())
        assertTrue(config.problems().isEmpty())
        assertNull(config.copy(guestEnabled = false).activeGuestSecret)
        assertNull(config.copy(brokerMode = BrokerMode.EXTERNAL, externalUri = "tcp://100.64.0.1:1883").activeGuestSecret)
    }

    private fun http(url: String, auth: String): Int {
        val conn = URI(url).toURL().openConnection() as HttpURLConnection
        return try {
            conn.connectTimeout = 3_000
            conn.readTimeout = 3_000
            conn.setRequestProperty(StreamAuth.HEADER, auth)
            conn.responseCode
        } finally {
            conn.disconnect()
        }
    }

    /** WebRTC giả: ghi lại cờ tiếng của từng phiên. */
    private class RecordingRtc : RtcPort {
        private val _sessions = MutableStateFlow(0)
        override val sessions: StateFlow<Int> = _sessions
        val audioFlags = CopyOnWriteArrayList<Boolean>()

        override fun micReady() = true

        override suspend fun answer(sessionId: String, offerSdp: String, quality: StreamQuality, audio: Boolean): RtcAnswer {
            audioFlags += audio
            _sessions.value++
            return RtcAnswer.Ok("answer:$sessionId")
        }

        override fun setQuality(quality: StreamQuality) = Unit

        override fun hangup(sessionId: String): Boolean = true

        override fun closeAll() {
            _sessions.value = 0
        }
    }

    private companion object {
        const val DEVICE = "san-nha"
        const val SECRET = "test-secret-0123456789"
        const val GUEST_SECRET = "guest-secret-abcdefghij"
        const val TIMEOUT_MS = 10_000L
        const val SESSION_GUEST = "8f2c1d4e-0000-4000-8000-00000000000a"
        const val SESSION_OWNER = "8f2c1d4e-0000-4000-8000-00000000000b"
        const val OFFER = "v=0\r\no=- 1 2 IN IP4 127.0.0.1\r\na=candidate:1 1 udp 2122260223 100.101.102.103 50000 typ host\r\n"
        val HOME = vn.cnldg.oncam.shared.protocol.GeoLocation(11.94, 108.43, 12f, 1_700_000_000, "gps")
    }
}
