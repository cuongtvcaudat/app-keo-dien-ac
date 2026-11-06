package vn.cnldg.oncam.node.mqtt

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking
import vn.cnldg.oncam.shared.protocol.Action
import vn.cnldg.oncam.shared.protocol.CommandParams
import vn.cnldg.oncam.shared.protocol.EventType
import vn.cnldg.oncam.shared.protocol.ProtocolJson
import vn.cnldg.oncam.shared.protocol.RejectReason
import vn.cnldg.oncam.shared.security.CommandSigner
import vn.cnldg.oncam.shared.security.CommandVerifier
import vn.cnldg.oncam.shared.security.EpochClock
import vn.cnldg.oncam.shared.security.RtcAuth

class CommandHandlerTest {

    private val secret = "test-secret-0123456789"
    private val clock = EpochClock { 1_700_000_000L }
    private val signer = CommandSigner(secret, clock)
    private val performed = mutableListOf<Action>()
    private val handler = CommandHandler(CommandVerifier(secret, "san-nha", clock), clock, nowMs = { 1_700_000_000_123L }) { cmd ->
        performed += cmd.action
        if (cmd.action == Action.SNAPSHOT) ActionOutcome.Failed("camera đang tắt") else ActionOutcome.Done("ok")
    }

    private fun json(action: Action, params: CommandParams = CommandParams()) =
        ProtocolJson.encodeCommand(signer.sign("san-nha", action, params))

    @Test
    fun `PING tra loi PONG ma khong goi den camera`() = runBlocking<Unit> {
        val handled = handler.handle(json(Action.PING))

        assertEquals(EventType.COMMAND_RESULT, handled.event.event)
        assertEquals(true, handled.event.success)
        assertEquals(CommandHandler.PONG, handled.event.message)
        assertEquals(handled.command?.id, handled.event.commandId)
        assertEquals(emptyList(), performed)
    }

    @Test
    fun `moi ket qua kem gio ms de viewer do lech gio ke ca khi tu choi`() = runBlocking<Unit> {
        val pong = handler.handle(json(Action.PING))
        val malformed = handler.handle("{rác")
        val expired = CommandHandler(CommandVerifier(secret, "san-nha", EpochClock { 1_700_000_000L + 3_600 }), nowMs = { 42L })
            .handle(json(Action.PING))

        assertEquals(1_700_000_000_123L, pong.event.timestampMs)
        assertEquals(1_700_000_000_123L, malformed.event.timestampMs)
        assertEquals(RejectReason.EXPIRED.name, expired.event.message)
        assertEquals(42L, expired.event.timestampMs)
    }

    @Test
    fun `answer WebRTC duoc ky de app Xem xac thuc`() = runBlocking<Unit> {
        val rtc = RtcAuth(secret, "san-nha")
        val h = CommandHandler(CommandVerifier(secret, "san-nha", clock), clock, rtcAuth = rtc) { ActionOutcome.Done("answer-sdp") }
        val offer = signer.sign("san-nha", Action.RTC_OFFER, CommandParams(sdp = "v=0", sessionId = "8f2c1d4e-0000-4000-8000-000000000001"))

        val ev = h.handle(ProtocolJson.encodeCommand(offer)).event

        assertEquals("answer-sdp", ev.message)
        assertEquals(true, rtc.verifyAnswer(offer.id, "answer-sdp", ev.signature))
        // Kết quả lệnh khác không cần chữ ký.
        assertNull(h.handle(json(Action.START_STREAM)).event.signature)
    }

    @Test
    fun `lenh moi chua biet tra MALFORMED kem ma lenh de app Xem khoi cho`() = runBlocking<Unit> {
        val handled = handler.handle("{\"id\":\"abcdef12-3456\",\"action\":\"LENH_TUONG_LAI\",\"token\":\"x\",\"timestamp\":1}")

        assertEquals(RejectReason.MALFORMED.name, handled.event.message)
        assertEquals("abcdef12-3456", handled.event.commandId)
    }

    @Test
    fun `lenh hop le duoc giao cho camera`() = runBlocking<Unit> {
        val ok = handler.handle(json(Action.START_STREAM))
        val failed = handler.handle(json(Action.SNAPSHOT))

        assertEquals(true, ok.event.success)
        assertEquals(false, failed.event.success)
        assertEquals("camera đang tắt", failed.event.message)
        assertEquals(listOf(Action.START_STREAM, Action.SNAPSHOT), performed)
    }

    @Test
    fun `JSON hong bao MALFORMED`() = runBlocking<Unit> {
        val handled = handler.handle("{rác")

        assertNull(handled.command)
        assertNull(handled.event.commandId)
        assertEquals(false, handled.event.success)
        assertEquals(RejectReason.MALFORMED.name, handled.event.message)
    }

    @Test
    fun `sai chu ky hoac gui lai bi tu choi va khong goi camera`() = runBlocking<Unit> {
        val forged = ProtocolJson.encodeCommand(
            CommandSigner("token-khac-0123456789", clock).sign("san-nha", Action.STOP_STREAM),
        )
        assertEquals(RejectReason.BAD_SIGNATURE.name, handler.handle(forged).event.message)

        val once = json(Action.STOP_STREAM)
        assertEquals(true, handler.handle(once).event.success)
        assertEquals(RejectReason.REPLAYED.name, handler.handle(once).event.message)
        assertEquals(listOf(Action.STOP_STREAM), performed)
    }

    @Test
    fun `thieu tham so bao BAD_PARAMS`() = runBlocking<Unit> {
        val handled = handler.handle(json(Action.SET_FLASH))

        assertEquals(false, handled.event.success)
        assertEquals("BAD_PARAMS:FLASH_REQUIRED", handled.event.message)
        assertEquals(emptyList(), performed)
    }

    @Test
    fun `mac dinh lenh camera la chua ho tro`() = runBlocking<Unit> {
        val plain = CommandHandler(CommandVerifier(secret, "san-nha", clock), clock)

        assertEquals(RejectReason.UNSUPPORTED.name, plain.handle(json(Action.SWITCH_CAMERA)).event.message)
        assertEquals(CommandHandler.PONG, plain.handle(json(Action.PING)).event.message)
    }
}
