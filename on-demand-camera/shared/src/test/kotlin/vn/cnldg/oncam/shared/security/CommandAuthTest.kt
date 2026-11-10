package vn.cnldg.oncam.shared.security

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import vn.cnldg.oncam.shared.protocol.Action
import vn.cnldg.oncam.shared.protocol.CameraFacing
import vn.cnldg.oncam.shared.protocol.CommandParams
import vn.cnldg.oncam.shared.protocol.RejectReason
import vn.cnldg.oncam.shared.protocol.StreamQuality

class CommandAuthTest {

    private val secret = "test-secret-0123456789"
    private val device = "san-nha"
    private var now = 1_700_000_000L
    private val clock = EpochClock { now }

    private val signer = CommandSigner(secret, clock)
    private fun verifier(deviceId: String = device, key: String = secret) = CommandVerifier(key, deviceId, clock)

    @Test
    fun `chu ky khop gia tri tinh san - khoa dinh dang tren day`() {
        // Giá trị tính độc lập bằng Python:
        // hmac.new(b"test-secret-0123456789", b"oncam-cmd-v1|san-nha|1111...|START_STREAM|1700000000|BACK||LOW|180", sha256)
        val cmd = signer.sign(
            deviceId = device,
            action = Action.START_STREAM,
            params = CommandParams(camera = CameraFacing.BACK, quality = StreamQuality.LOW, idleTimeoutSec = 180),
            id = "11111111-2222-3333-4444-555555555555",
            timestamp = 1_700_000_000,
        )

        assertEquals("89f5476bce85ba181dcc6559bb95f5ae60bf828a7aef5fa1958b272f29683bdd", cmd.token)
    }

    @Test
    fun `lenh dung chu ky duoc chap nhan`() {
        val cmd = signer.sign(device, Action.PING)

        assertEquals(CommandCheck.Accepted, verifier().verify(cmd))
    }

    @Test
    fun `sua action hoac tham so thi sai chu ky`() {
        val cmd = signer.sign(device, Action.SET_QUALITY, CommandParams(quality = StreamQuality.LOW))
        val v = verifier()

        assertEquals(rejected(RejectReason.BAD_SIGNATURE), v.verify(cmd.copy(action = Action.STOP_STREAM)))
        assertEquals(
            rejected(RejectReason.BAD_SIGNATURE),
            v.verify(cmd.copy(params = CommandParams(quality = StreamQuality.HIGH))),
        )
        assertEquals(rejected(RejectReason.BAD_SIGNATURE), v.verify(cmd.copy(timestamp = cmd.timestamp + 1)))
        assertEquals(rejected(RejectReason.BAD_SIGNATURE), v.verify(cmd.copy(token = "00".repeat(32))))
    }

    @Test
    fun `lenh cho camera khac hoac sai token bi tu choi`() {
        val cmd = signer.sign(device, Action.START_STREAM)

        assertEquals(rejected(RejectReason.BAD_SIGNATURE), verifier(deviceId = "camera-khac").verify(cmd))
        assertEquals(rejected(RejectReason.BAD_SIGNATURE), verifier(key = "token-sai-0123456789").verify(cmd))
    }

    @Test
    fun `lech gio qua 60 giay bi tu choi`() {
        val old = signer.sign(device, Action.PING, timestamp = now - 61)
        val future = signer.sign(device, Action.PING, timestamp = now + 61)
        val edge = signer.sign(device, Action.PING, timestamp = now - 60)

        val v = verifier()
        assertEquals(rejected(RejectReason.EXPIRED), v.verify(old))
        assertEquals(rejected(RejectReason.EXPIRED), v.verify(future))
        assertEquals(CommandCheck.Accepted, v.verify(edge))
    }

    @Test
    fun `gui lai cung lenh bi tu choi`() {
        val cmd = signer.sign(device, Action.START_STREAM)
        val v = verifier()

        assertEquals(CommandCheck.Accepted, v.verify(cmd))
        assertEquals(rejected(RejectReason.REPLAYED), v.verify(cmd))
    }

    @Test
    fun `lenh gia mao khong lam ban bo nho chong replay`() {
        val real = signer.sign(device, Action.PING)
        val v = verifier()

        assertEquals(rejected(RejectReason.BAD_SIGNATURE), v.verify(real.copy(token = "ab".repeat(32))))
        assertEquals(CommandCheck.Accepted, v.verify(real))
    }

    @Test
    fun `id sai dinh dang bi tu choi`() {
        val cmd = signer.sign(device, Action.PING)

        assertEquals(rejected(RejectReason.MALFORMED), verifier().verify(cmd.copy(id = "a|b")))
        assertEquals(rejected(RejectReason.MALFORMED), verifier().verify(cmd.copy(id = "")))
        assertFailsWith<IllegalArgumentException> { signer.sign(device, Action.PING, id = "ngắn") }
        assertFailsWith<IllegalArgumentException> { signer.sign("Sai/Id", Action.PING) }
    }

    @Test
    fun `bo nho chong replay het han va gioi han kich thuoc`() {
        val guard = ReplayGuard(ttlSec = 120, maxEntries = 3)

        assertTrue(guard.firstSeen("a", 0))
        assertFalse(guard.firstSeen("a", 119))
        assertTrue(guard.firstSeen("a", 120)) // đã hết hạn

        assertTrue(guard.firstSeen("b", 121))
        assertTrue(guard.firstSeen("c", 121))
        assertTrue(guard.firstSeen("d", 121)) // đầy → bỏ id cũ nhất ("a")
        assertEquals(3, guard.size())
        assertTrue(guard.firstSeen("a", 122))
    }

    private fun rejected(reason: RejectReason) = CommandCheck.Rejected(reason)
}
