package vn.cnldg.oncam.shared.security

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import vn.cnldg.oncam.shared.protocol.Action
import vn.cnldg.oncam.shared.protocol.CommandParams
import vn.cnldg.oncam.shared.protocol.CommandProblem
import vn.cnldg.oncam.shared.protocol.MAX_SDP_LENGTH
import vn.cnldg.oncam.shared.protocol.ProtocolJson
import vn.cnldg.oncam.shared.protocol.RejectReason
import vn.cnldg.oncam.shared.protocol.StreamQuality
import vn.cnldg.oncam.shared.protocol.paramProblem
import vn.cnldg.oncam.shared.rtc.SdpCandidates
import vn.cnldg.oncam.shared.rtc.TailscaleAddress

/** Giao thức WebRTC (Phase 7): ký offer/answer, tương thích ngược, lọc ứng viên ICE. */
class RtcProtocolTest {

    private val secret = "test-secret-0123456789"
    private val clock = EpochClock { 1_700_000_000L }
    private val signer = CommandSigner(secret, clock)
    private val session = "8f2c1d4e-0000-4000-8000-000000000001"
    private val offer = "v=0\r\no=- 1 2 IN IP4 127.0.0.1\r\na=candidate:1 1 udp 2122260223 100.101.102.103 50000 typ host\r\n"

    @Test
    fun `lenh cu ky giong het ban 0_6 de 2 app khac phien ban van hieu nhau`() {
        assertEquals(
            "oncam-cmd-v1|san-nha|id-12345678|START_STREAM|100|||LOW|",
            CommandCanonical.of("san-nha", "id-12345678", Action.START_STREAM, CommandParams(quality = StreamQuality.LOW), 100),
        )
    }

    @Test
    fun `chu ky RTC_OFFER bao ca SDP - sua SDP la bi tu choi`() {
        val verifier = CommandVerifier(secret, "san-nha", clock)
        val cmd = signer.sign("san-nha", Action.RTC_OFFER, CommandParams(sdp = offer, sessionId = session))
        assertEquals(CommandCheck.Accepted, verifier.verify(cmd))

        val other = signer.sign("san-nha", Action.RTC_OFFER, CommandParams(sdp = offer, sessionId = session))
        val tampered = other.copy(params = other.params.copy(sdp = offer.replace("100.101.102.103", "100.66.66.66")))
        assertEquals(CommandCheck.Rejected(RejectReason.BAD_SIGNATURE), verifier.verify(tampered))
    }

    @Test
    fun `lenh RTC qua duoc JSON va kiem tham so`() {
        val cmd = signer.sign("san-nha", Action.RTC_OFFER, CommandParams(sdp = offer, sessionId = session))
        assertEquals(cmd, ProtocolJson.decodeCommand(ProtocolJson.encodeCommand(cmd)))
        assertNull(cmd.paramProblem())

        fun problem(action: Action, p: CommandParams) = signer.sign("san-nha", action, p).paramProblem()
        assertEquals(CommandProblem.SDP_REQUIRED, problem(Action.RTC_OFFER, CommandParams(sessionId = session)))
        assertEquals(
            CommandProblem.SDP_TOO_LARGE,
            problem(Action.RTC_OFFER, CommandParams(sdp = "x".repeat(MAX_SDP_LENGTH + 1), sessionId = session)),
        )
        assertEquals(CommandProblem.SESSION_REQUIRED, problem(Action.RTC_OFFER, CommandParams(sdp = offer)))
        assertEquals(CommandProblem.SESSION_REQUIRED, problem(Action.RTC_HANGUP, CommandParams(sessionId = "x y")))
        assertNull(problem(Action.RTC_HANGUP, CommandParams(sessionId = session)))
    }

    @Test
    fun `khong in SDP ra log`() {
        assertFalse(CommandParams(sdp = offer, sessionId = session).toString().contains("candidate"))
    }

    @Test
    fun `answer chi hop le khi dung token dung lenh dung noi dung`() {
        val auth = RtcAuth(secret, "san-nha")
        val sig = auth.signAnswer("cmd-1", "answer-sdp")

        assertTrue(auth.verifyAnswer("cmd-1", "answer-sdp", sig))
        assertFalse(auth.verifyAnswer("cmd-2", "answer-sdp", sig), "dùng lại cho lệnh khác")
        assertFalse(auth.verifyAnswer("cmd-1", "answer-sdp-gia", sig), "sửa SDP")
        assertFalse(auth.verifyAnswer("cmd-1", "answer-sdp", null))
        assertFalse(RtcAuth("token-khac-0123456789", "san-nha").verifyAnswer("cmd-1", "answer-sdp", sig))
        assertFalse(RtcAuth(secret, "cong-truoc").verifyAnswer("cmd-1", "answer-sdp", sig), "camera khác")
    }

    @Test
    fun `chi giu ung vien IP Tailscale`() {
        val sdp = listOf(
            "v=0",
            "m=video 9 UDP/TLS/RTP/SAVPF 96",
            "a=candidate:1 1 udp 2122260223 192.168.1.20 40000 typ host generation 0",
            "a=candidate:2 1 udp 2122194687 100.101.102.103 40001 typ host generation 0",
            "a=candidate:3 1 udp 2122129151 10.20.30.40 40002 typ host generation 0",
            "a=candidate:4 1 udp 2122063615 fd7a:115c:a1e0::1234 40003 typ host generation 0",
            "a=mid:0",
            "",
        ).joinToString("\r\n")

        val filtered = SdpCandidates.filter(sdp, TailscaleAddress::isTailscale)

        assertEquals(listOf("100.101.102.103", "fd7a:115c:a1e0::1234"), SdpCandidates.addresses(filtered))
        assertTrue(filtered.contains("a=mid:0\r\n"), "giữ nguyên dòng khác và kiểu xuống dòng")
        assertTrue(filtered.startsWith("v=0\r\nm=video"))
    }

    @Test
    fun `nhan dien dai IP Tailscale`() {
        assertTrue(TailscaleAddress.isTailscale("100.64.0.1"))
        assertTrue(TailscaleAddress.isTailscale("100.127.255.254"))
        assertFalse(TailscaleAddress.isTailscale("100.63.0.1"))
        assertFalse(TailscaleAddress.isTailscale("100.128.0.1"))
        assertFalse(TailscaleAddress.isTailscale("192.168.1.1"))
        assertFalse(TailscaleAddress.isTailscale("100.100.100.300"))
        assertTrue(TailscaleAddress.isTailscale("FD7A:115C:A1E0:AB12::1"))
        assertFalse(TailscaleAddress.isTailscale("fe80::1"))
    }
}
