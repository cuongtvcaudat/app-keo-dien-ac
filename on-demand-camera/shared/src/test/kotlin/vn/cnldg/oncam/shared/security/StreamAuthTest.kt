package vn.cnldg.oncam.shared.security

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class StreamAuthTest {

    private val secret = "test-secret-0123456789"
    private var now = 1_700_000_000L
    private val clock = EpochClock { now }
    private val auth = StreamAuth(secret, "san-nha", clock)

    @Test
    fun `header khop gia tri tinh san`() {
        // hmac.new(b"test-secret-0123456789", b"oncam-stream-v1|san-nha|1700000000", sha256)
        assertEquals(
            "1700000000.f923c7dccdcdd927bdc61a0697883eb5db1c6aed81b6c1df82a0d9d91ef5dc45",
            auth.headerValue(),
        )
    }

    @Test
    fun `header hop le duoc chap nhan trong 60 giay`() {
        val header = auth.headerValue()

        assertTrue(auth.isValid(header))
        now += 60
        assertTrue(auth.isValid(header))
        now += 1
        assertFalse(auth.isValid(header))
    }

    @Test
    fun `header sai thiet bi sai token hoac bi sua bi tu choi`() {
        val header = auth.headerValue()

        assertFalse(StreamAuth(secret, "camera-khac", clock).isValid(header))
        assertFalse(StreamAuth("token-sai-0123456789", "san-nha", clock).isValid(header))
        assertFalse(auth.isValid(header.replaceBefore('.', "1700000001")))
    }

    @Test
    fun `header sai dinh dang bi tu choi`() {
        listOf(null, "", "abc", "1700000000.", ".abcdef", "x.y", "1700000000").forEach {
            assertFalse(auth.isValid(it), it.toString())
        }
    }

    @Test
    fun `mat khau broker nhung suy ra on dinh tu token`() {
        // hmac.new(b"test-secret-0123456789", b"oncam-mqtt-password-v1", sha256).hexdigest()[:32]
        assertEquals("97ae1415661345d2a24116ec19d63627", BrokerCredentials.password(secret))
        assertNotEquals(BrokerCredentials.password(secret), BrokerCredentials.password("token-khac-0123456789"))
        assertEquals("oncam", BrokerCredentials.USERNAME)
    }

    @Test
    fun `token sinh ngau nhien du dai va khong trung`() {
        val a = SharedSecret.generate()
        val b = SharedSecret.generate()

        assertEquals(43, a.length)
        assertNotEquals(a, b)
        assertTrue(SharedSecret.isAcceptable(a))
        assertFalse(SharedSecret.isAcceptable("ngan-qua"))
        assertFalse(SharedSecret.isAcceptable(" khoang-trang-o-dau-123"))
    }
}
