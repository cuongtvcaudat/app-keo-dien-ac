package vn.cnldg.oncam.node.core

import java.net.ServerSocket
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import vn.cnldg.oncam.node.mqtt.ActionOutcome
import vn.cnldg.oncam.shared.protocol.Action
import vn.cnldg.oncam.shared.protocol.CameraCodes
import vn.cnldg.oncam.shared.protocol.CameraState
import vn.cnldg.oncam.shared.protocol.Command
import vn.cnldg.oncam.shared.protocol.CommandParams
import vn.cnldg.oncam.shared.protocol.StreamQuality
import vn.cnldg.oncam.shared.security.StreamAuth

/** RTC_OFFER/RTC_HANGUP trong StreamManager với phiên WebRTC giả (WebRTC thật cần Android → test trên emulator). */
class StreamManagerRtcTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val port = ServerSocket(0).use { it.localPort }
    private val fake = FakeCameraPort(scope)
    private val rtc = FakeRtc()

    @Volatile private var allowed = true

    private fun manager(rtcPort: RtcPort? = rtc) = StreamManager(
        camera = fake,
        auth = StreamAuth("test-secret-0123456789", "san-nha"),
        scope = scope,
        addressProvider = { "127.0.0.1" },
        cameraAllowed = { allowed },
        deviceInfo = { DeviceSnapshot(temperatureC = 35.0) },
        settings = StreamManager.Settings(httpPort = port, checkIntervalMs = 30, msPerIdleSecond = 10),
        rtc = rtcPort,
    )

    private val manager = manager()

    @AfterTest
    fun tearDown() = runBlocking<Unit> {
        manager.shutdown()
        scope.cancel()
    }

    @Test
    fun `offer khi camera dang tat thi bat camera roi tra answer`() = runBlocking<Unit> {
        val result = manager.perform(offer(StreamQuality.LOW))

        assertEquals(ActionOutcome.Done("answer:$SESSION"), result)
        assertEquals(CameraState.STREAMING, manager.status.value.state)
        assertEquals(StreamQuality.LOW, rtc.lastQuality)
        assertTrue(manager.rtcAvailable())
    }

    @Test
    fun `con phien WebRTC thi khong tu tat, het phien thi tu tat`() = runBlocking<Unit> {
        manager.perform(offer(idleTimeoutSec = 30)) // 30 "giây" = 0,3 s trong test
        delay(800)
        assertEquals(CameraState.STREAMING, manager.status.value.state, "đang có 1 phiên WebRTC")
        assertEquals(1, manager.status.value.clients)

        assertEquals(ActionOutcome.Done(), manager.perform(cmd(Action.RTC_HANGUP, CommandParams(sessionId = SESSION))))
        withTimeout(5_000) { manager.status.first { it.state == CameraState.OFF } }
    }

    @Test
    fun `tat camera thi dong moi phien`() = runBlocking<Unit> {
        manager.perform(offer())
        manager.perform(cmd(Action.STOP_STREAM))

        assertEquals(0, rtc.sessions.value)
        assertEquals(1, rtc.closeAllCalls)
    }

    @Test
    fun `doi chat luong ap dung cho phien WebRTC`() = runBlocking<Unit> {
        manager.perform(offer())
        manager.perform(cmd(Action.SET_QUALITY, CommandParams(quality = StreamQuality.HIGH)))

        assertEquals(StreamQuality.HIGH, rtc.lastQuality)
    }

    @Test
    fun `camera chua duoc phep mo thi khong tao phien`() = runBlocking<Unit> {
        allowed = false

        assertEquals(ActionOutcome.Failed(CameraCodes.NEEDS_APP_OPEN), manager.perform(offer()))
        assertEquals(0, rtc.answers)
    }

    @Test
    fun `may khong chay duoc WebRTC thi bao chua ho tro`() = runBlocking<Unit> {
        val noRtc = manager(rtcPort = null)
        try {
            assertEquals(ActionOutcome.Unsupported, noRtc.perform(offer()))
        } finally {
            noRtc.shutdown()
        }
    }

    @Test
    fun `loi WebRTC duoc bao ve app Xem`() = runBlocking<Unit> {
        rtc.failWith = "RTC_NO_ADDRESS"
        assertEquals(ActionOutcome.Failed("RTC_NO_ADDRESS"), manager.perform(offer()))
    }

    private fun offer(quality: StreamQuality? = null, idleTimeoutSec: Int? = null) =
        cmd(Action.RTC_OFFER, CommandParams(sdp = "v=0\r\n", sessionId = SESSION, quality = quality, idleTimeoutSec = idleTimeoutSec))

    private fun cmd(action: Action, params: CommandParams = CommandParams()) =
        Command(id = "11111111-2222-3333-4444-555555555555", action = action, params = params, token = "t", timestamp = 0)

    private class FakeRtc : RtcPort {
        private val _sessions = MutableStateFlow(0)
        override val sessions: StateFlow<Int> = _sessions
        var lastQuality: StreamQuality? = null
        var closeAllCalls = 0
        var answers = 0
        var failWith: String? = null
        val audioFlags = mutableListOf<Boolean>()

        override fun micReady() = true

        override suspend fun answer(sessionId: String, offerSdp: String, quality: StreamQuality, audio: Boolean): RtcAnswer {
            audioFlags += audio
            answers++
            failWith?.let { return RtcAnswer.Failed(it) }
            lastQuality = quality
            _sessions.value++
            return RtcAnswer.Ok("answer:$sessionId")
        }

        override fun setQuality(quality: StreamQuality) {
            lastQuality = quality
        }

        override fun hangup(sessionId: String): Boolean {
            if (_sessions.value == 0) return false
            _sessions.value--
            return true
        }

        override fun closeAll() {
            closeAllCalls++
            _sessions.value = 0
        }
    }

    private companion object {
        const val SESSION = "8f2c1d4e-0000-4000-8000-000000000001"
    }
}
