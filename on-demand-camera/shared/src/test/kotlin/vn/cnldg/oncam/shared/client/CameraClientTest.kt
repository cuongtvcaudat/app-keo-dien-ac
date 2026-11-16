package vn.cnldg.oncam.shared.client

import io.moquette.broker.Server
import io.moquette.broker.config.IConfig
import io.moquette.broker.config.MemoryConfig
import io.moquette.broker.security.IAuthenticator
import java.net.ServerSocket
import java.util.Properties
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import vn.cnldg.oncam.shared.mqtt.MqttClientWrapper
import vn.cnldg.oncam.shared.mqtt.MqttConfig
import vn.cnldg.oncam.shared.mqtt.MqttConnectionState
import vn.cnldg.oncam.shared.mqtt.OutgoingMessage
import vn.cnldg.oncam.shared.protocol.Action
import vn.cnldg.oncam.shared.protocol.CameraState
import vn.cnldg.oncam.shared.protocol.DeviceEvent
import vn.cnldg.oncam.shared.protocol.DeviceStatus
import vn.cnldg.oncam.shared.protocol.EventType
import vn.cnldg.oncam.shared.protocol.GeoLocation
import vn.cnldg.oncam.shared.protocol.LocationStatus
import vn.cnldg.oncam.shared.protocol.ProtocolJson
import vn.cnldg.oncam.shared.protocol.RejectReason
import vn.cnldg.oncam.shared.protocol.Topics
import vn.cnldg.oncam.shared.security.BrokerCredentials
import vn.cnldg.oncam.shared.security.CommandCheck
import vn.cnldg.oncam.shared.security.CommandVerifier
import vn.cnldg.oncam.shared.security.EpochClock
import vn.cnldg.oncam.shared.security.RtcAuth

/**
 * CameraClient với broker thật (Moquette, có mật khẩu như broker nhúng) và một "camera giả" tối giản
 * trả lời lệnh đúng giao thức.
 */
class CameraClientTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val port = ServerSocket(0).use { it.localPort }
    private val broker = startBroker()
    private val endpoint = CameraEndpoint(DEVICE, "tcp://127.0.0.1:$port", SECRET)
    private val clients = mutableListOf<CameraClient>()
    private var node: MqttClientWrapper? = null

    @AfterTest
    fun tearDown() = runBlocking<Unit> {
        clients.forEach { it.stop() }
        node?.stop()
        scope.cancel()
        broker.stopServer()
    }

    @Test
    fun `thay camera online va nhan trang thai`() = runBlocking<Unit> {
        startFakeNode()
        val client = client(endpoint)

        val view = withTimeout(TIMEOUT_MS) { client.view.first { it.online } }

        assertEquals("Sân nhà", view.status?.displayName)
        assertTrue(view.statusReceivedAtMs != null)
    }

    @Test
    fun `gui lenh da ky va nhan ket qua dung lenh`() = runBlocking<Unit> {
        startFakeNode()
        val client = client(endpoint)
        withTimeout(TIMEOUT_MS) { client.view.first { it.online } }

        assertEquals(CommandResult.Ok("PONG"), client.send(Action.PING))
        assertEquals(CommandResult.Ok("http://127.0.0.1:8080/stream.mjpeg"), client.send(Action.START_STREAM))
        assertEquals("http://127.0.0.1:8080/snapshot.jpg", withTimeout(TIMEOUT_MS) {
            client.view.first { it.status?.cameraState == CameraState.STREAMING }
            client.snapshotUrl()
        })
    }

    @Test
    fun `sai token thi khong dang nhap duoc broker va lenh bao chua ket noi`() = runBlocking<Unit> {
        startFakeNode()
        val wrong = client(endpoint.copy(secret = "token-sai-0123456789"))

        withTimeout(TIMEOUT_MS) { wrong.view.first { it.connection is MqttConnectionState.Disconnected } }
        assertFalse(wrong.view.value.online)
        assertEquals(CommandResult.NotConnected, wrong.send(Action.PING))
    }

    @Test
    fun `dang nhap duoc nhung chu ky sai thi camera tu choi`() = runBlocking<Unit> {
        // Kẻ có mật khẩu broker (vd. lộ từ broker riêng) nhưng không có token vẫn không ra lệnh được.
        startFakeNode()
        val forger = client(
            endpoint.copy(
                secret = "token-gia-mao-0123456789",
                brokerUsername = BrokerCredentials.USERNAME,
                brokerPassword = BrokerCredentials.password(SECRET),
            ),
        )
        withTimeout(TIMEOUT_MS) { forger.view.first { it.connection == MqttConnectionState.Connected } }

        assertEquals(CommandResult.Rejected(RejectReason.BAD_SIGNATURE.name), forger.send(Action.START_STREAM))
    }

    @Test
    fun `camera khong tra loi thi het gio`() = runBlocking<Unit> {
        // Không chạy camera giả: lệnh gửi được tới broker nhưng không ai trả lời.
        val client = client(endpoint)
        withTimeout(TIMEOUT_MS) { client.view.first { it.connection == MqttConnectionState.Connected } }

        assertEquals(CommandResult.Timeout, client.send(Action.PING, timeoutMs = 500))
        assertNull(client.snapshotUrl())
    }

    @Test
    fun `su kien camera duoc chuyen cho viewer`() = runBlocking<Unit> {
        startFakeNode()
        val client = client(endpoint)
        withTimeout(TIMEOUT_MS) { client.view.first { it.online } }
        // Bắt đầu nghe trước khi camera phát sự kiện (SharedFlow không giữ lại sự kiện cũ).
        val waiting = scope.async { client.events.first() }
        delay(200)

        node!!.publish(
            OutgoingMessage(
                Topics.event(DEVICE),
                ProtocolJson.encodeEvent(DeviceEvent(EventType.IDLE_TIMEOUT, "180s", 1)),
                Topics.QOS_EVENT,
                false,
            ),
        )
        val event = withTimeout(TIMEOUT_MS) { waiting.await() }
        assertEquals(EventType.IDLE_TIMEOUT, event.event)
    }

    @Test
    fun `dong bo gio khi 2 may cung gio`() = runBlocking<Unit> {
        startFakeNode()
        val client = client(endpoint)
        withTimeout(TIMEOUT_MS) { client.view.first { it.online } }

        val sync = client.syncClock()!!

        assertTrue(kotlin.math.abs(sync.offsetMs) <= sync.roundTripMs / 2 + 5, "lệch đo được: $sync")
        assertEquals(sync, client.clockSync.value)
    }

    @Test
    fun `do duoc gio camera nhanh 5 phut du lenh bi tu choi het han`() = runBlocking<Unit> {
        val ahead = 5 * 60_000L
        startFakeNode(cameraOffsetMs = ahead)
        val client = client(endpoint)
        withTimeout(TIMEOUT_MS) { client.view.first { it.online } }

        assertEquals(CommandResult.Rejected(RejectReason.EXPIRED.name), client.send(Action.PING))
        val sync = client.syncClock()!!

        assertTrue(kotlin.math.abs(sync.offsetMs - ahead) <= sync.roundTripMs / 2 + 5, "lệch đo được: $sync")
    }

    @Test
    fun `camera ban cu khong gui gio ms thi khong do duoc`() = runBlocking<Unit> {
        startFakeNode(sendMs = false)
        val client = client(endpoint)
        withTimeout(TIMEOUT_MS) { client.view.first { it.online } }

        assertNull(client.syncClock())
        assertNull(client.clockSync.value)
    }

    @Test
    fun `mo phien WebRTC nhan answer da ky`() = runBlocking<Unit> {
        startFakeNode()
        val client = client(endpoint)
        withTimeout(TIMEOUT_MS) { client.view.first { it.online } }
        // SDP thật dài ~6 KB: vượt giới hạn mặc định 8 092 byte của Moquette khi tính cả JSON.
        val bigOffer = "v=0\r\n" + "a=x-pad:${"0".repeat(9_000)}\r\n"

        val result = client.rtcOffer(SESSION, bigOffer)

        assertEquals(RtcAnswerResult.Answer("answer:$SESSION:${bigOffer.length}"), result)
        assertEquals(CommandResult.Ok(null), client.rtcHangup(SESSION))
    }

    @Test
    fun `answer gia mao bi bo`() = runBlocking<Unit> {
        // Kẻ đọc/ghi được broker nhưng không có token: ký answer bằng khoá khác.
        startFakeNode(answerSecret = "token-gia-mao-0123456789")
        val client = client(endpoint)
        withTimeout(TIMEOUT_MS) { client.view.first { it.online } }

        assertEquals(
            RtcAnswerResult.Failed(CommandResult.Rejected(CameraClient.BAD_ANSWER_SIGNATURE)),
            client.rtcOffer(SESSION, "v=0\r\n"),
        )
    }

    @Test
    fun `camera ban cu khong hieu RTC_OFFER thi het gio, app Xem dua vao co rtc de khoi gui`() = runBlocking<Unit> {
        startFakeNode(supportsRtc = false)
        val client = client(endpoint)
        val view = withTimeout(TIMEOUT_MS) { client.view.first { it.online } }

        assertFalse(view.status!!.rtc, "camera 0.6 không gửi cờ rtc")
        assertEquals(RtcAnswerResult.Failed(CommandResult.Timeout), client.rtcOffer(SESSION, "v=0\r\n", timeoutMs = 500))
    }

    @Test
    fun `giu vi tri cuoi khi camera offline, xoa khi camera tat chia se`() = runBlocking<Unit> {
        startFakeNode()
        val client = client(endpoint)
        withTimeout(TIMEOUT_MS) { client.view.first { it.online } }
        val here = GeoLocation(11.940419, 108.458313, 8f, 1_700_000_000, "gps")

        publishStatus(online = true, location = LocationStatus(point = here))
        withTimeout(TIMEOUT_MS) { client.view.first { it.lastLocation == here } }

        // Camera mất mạng: trạng thái offline (như LWT của bản cũ, không kèm vị trí) → vẫn giữ vị trí cuối.
        publishStatus(online = false, location = null)
        withTimeout(TIMEOUT_MS) { client.view.first { it.status?.online == false } }
        assertEquals(here, client.view.value.lastLocation)

        // Online lại nhưng đã tắt chia sẻ vị trí → xoá.
        publishStatus(online = true, location = null)
        withTimeout(TIMEOUT_MS) { client.view.first { it.status?.online == true && it.lastLocation == null } }
    }

    @Test
    fun `vi tri da luu hien ngay ca khi chua ket noi`() {
        val here = GeoLocation(11.94, 108.45, null, 1, "network")
        val client = CameraClient(endpoint, "viewer-loc", scope, initialLocation = here)
        assertEquals(here, client.view.value.lastLocation)
    }

    private fun publishStatus(online: Boolean, location: LocationStatus?) = runBlocking {
        node!!.publish(
            OutgoingMessage(
                Topics.status(DEVICE),
                ProtocolJson.encodeStatus(DeviceStatus(deviceId = DEVICE, online = online, lastSeen = 2, location = location)),
                Topics.QOS_STATUS,
                true,
            ),
        )
    }

    // --- camera giả + tiện ích ---

    /**
     * Camera giả: publish trạng thái online, kiểm chữ ký lệnh, trả COMMAND_RESULT.
     * @param cameraOffsetMs đồng hồ camera nhanh hơn máy chạy test bao nhiêu ms.
     * @param sendMs false = giả lập camera bản cũ (không gửi timestampMs).
     * @param answerSecret token dùng ký SDP answer (khác [SECRET] = giả mạo).
     * @param supportsRtc false = camera bản 0.6: không đọc được lệnh RTC (enum lạ) → MALFORMED.
     */
    private suspend fun startFakeNode(
        cameraOffsetMs: Long = 0,
        sendMs: Boolean = true,
        answerSecret: String = SECRET,
        supportsRtc: Boolean = true,
    ) {
        val rtcAuth = RtcAuth(answerSecret, DEVICE)
        val cameraNowMs = { System.currentTimeMillis() + cameraOffsetMs }
        val verifier = CommandVerifier(SECRET, DEVICE, EpochClock { cameraNowMs() / 1000 })
        val wrapper = MqttClientWrapper(
            MqttConfig(
                serverUri = "tcp://127.0.0.1:$port",
                clientId = "fake-node",
                username = BrokerCredentials.USERNAME,
                password = BrokerCredentials.password(SECRET),
                keepAliveSec = 5,
            ),
            scope,
        )
        node = wrapper
        var streaming = false
        fun status() = OutgoingMessage(
            Topics.status(DEVICE),
            ProtocolJson.encodeStatus(
                DeviceStatus(
                    deviceId = DEVICE,
                    displayName = "Sân nhà",
                    online = true,
                    cameraState = if (streaming) CameraState.STREAMING else CameraState.OFF,
                    streamUrl = if (streaming) "http://127.0.0.1:8080/stream.mjpeg" else null,
                    lastSeen = 1,
                ),
            ),
            Topics.QOS_STATUS,
            true,
        )
        scope.launch {
            wrapper.messages.collect { msg ->
                val cmd = ProtocolJson.decodeCommand(msg.payload)?.takeIf { supportsRtc || !it.action.name.startsWith("RTC_") }
                if (cmd == null) {
                    // Như camera 0.6 thật: không đọc được lệnh → MALFORMED không kèm mã lệnh.
                    val ev = DeviceEvent(EventType.COMMAND_RESULT, RejectReason.MALFORMED.name, 1, null, false)
                    wrapper.publish(OutgoingMessage(Topics.event(DEVICE), ProtocolJson.encodeEvent(ev), 0, false))
                    return@collect
                }
                val result = when (val check = verifier.verify(cmd)) {
                    is CommandCheck.Rejected -> DeviceEvent(EventType.COMMAND_RESULT, check.reason.name, 1, cmd.id, false)
                    CommandCheck.Accepted -> when (cmd.action) {
                        Action.PING -> DeviceEvent(EventType.COMMAND_RESULT, "PONG", 1, cmd.id, true)
                        Action.RTC_OFFER -> {
                            val answer = "answer:${cmd.params.sessionId}:${cmd.params.sdp?.length}"
                            DeviceEvent(
                                EventType.COMMAND_RESULT, answer, 1, cmd.id, true,
                                signature = rtcAuth.signAnswer(cmd.id, answer),
                            )
                        }
                        Action.RTC_HANGUP -> DeviceEvent(EventType.COMMAND_RESULT, null, 1, cmd.id, true)
                        Action.START_STREAM -> {
                            streaming = true
                            wrapper.publish(status())
                            DeviceEvent(EventType.COMMAND_RESULT, "http://127.0.0.1:8080/stream.mjpeg", 1, cmd.id, true)
                        }
                        else -> DeviceEvent(EventType.COMMAND_RESULT, "UNSUPPORTED", 1, cmd.id, false)
                    }
                }
                val stamped = if (sendMs) result.copy(timestampMs = cameraNowMs()) else result
                wrapper.publish(OutgoingMessage(Topics.event(DEVICE), ProtocolJson.encodeEvent(stamped), 0, false))
            }
        }
        wrapper.subscribe(Topics.command(DEVICE), 1)
        wrapper.start()
        withTimeout(TIMEOUT_MS) { wrapper.state.first { it == MqttConnectionState.Connected } }
        wrapper.publish(status())
    }

    private fun client(e: CameraEndpoint) =
        CameraClient(e, "viewer-${System.nanoTime()}", scope, keepAliveSec = 5).also {
            clients += it
            it.start()
        }

    private fun startBroker(): Server {
        val props = Properties().apply {
            setProperty(IConfig.HOST_PROPERTY_NAME, "127.0.0.1")
            setProperty(IConfig.PORT_PROPERTY_NAME, port.toString())
            setProperty(IConfig.WEB_SOCKET_PORT_PROPERTY_NAME, "disabled")
            setProperty(IConfig.PERSISTENCE_ENABLED_PROPERTY_NAME, "false")
            setProperty(IConfig.ENABLE_TELEMETRY_NAME, "false") // Moquette mặc định gửi telemetry ra ngoài
            setProperty(IConfig.NETTY_MAX_BYTES_PROPERTY_NAME, "65536") // như broker nhúng: SDP > 8 KB
            setProperty(IConfig.ALLOW_ANONYMOUS_PROPERTY_NAME, "false")
        }
        val expected = BrokerCredentials.password(SECRET)
        val auth = IAuthenticator { _, user, pass ->
            user == BrokerCredentials.USERNAME && pass != null && String(pass, Charsets.UTF_8) == expected
        }
        return Server().apply { startServer(MemoryConfig(props), emptyList(), null, auth, null) }
    }

    private companion object {
        const val DEVICE = "san-nha"
        const val SESSION = "8f2c1d4e-0000-4000-8000-000000000001"
        const val SECRET = "test-secret-0123456789"
        const val TIMEOUT_MS = 10_000L
    }
}
