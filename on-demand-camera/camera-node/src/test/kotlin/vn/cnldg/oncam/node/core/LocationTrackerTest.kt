package vn.cnldg.oncam.node.core

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
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
import vn.cnldg.oncam.shared.protocol.CameraCodes
import vn.cnldg.oncam.shared.protocol.GeoLocation
import vn.cnldg.oncam.shared.protocol.LocationStatus
import vn.cnldg.oncam.shared.protocol.ProtocolJson
import vn.cnldg.oncam.shared.protocol.DeviceStatus

class LocationTrackerTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val home = GeoLocation(lat = 11.940419, lon = 108.458313, accuracyM = 8f, timestamp = 1_700_000_000, provider = "gps")

    @AfterTest
    fun tearDown() = scope.cancel()

    @Test
    fun `lay duoc vi tri thi luu va xoa loi cu`() = runBlocking<Unit> {
        var next: LocationFix = LocationFix.Failed(CameraCodes.LOCATION_UNAVAILABLE)
        val t = LocationTracker({ next }, scope)

        t.refresh()
        assertEquals(LocationStatus(point = null, updating = false, error = CameraCodes.LOCATION_UNAVAILABLE), t.status.value)

        next = LocationFix.Ok(home)
        t.refresh()
        assertEquals(LocationStatus(point = home), t.status.value)
    }

    @Test
    fun `lan lay sau loi van giu vi tri cu`() = runBlocking<Unit> {
        var next: LocationFix = LocationFix.Ok(home)
        val t = LocationTracker({ next }, scope)
        t.refresh()

        next = LocationFix.Failed(CameraCodes.LOCATION_OFF)
        t.refresh()

        assertEquals(home, t.status.value.point)
        assertEquals(CameraCodes.LOCATION_OFF, t.status.value.error)
    }

    @Test
    fun `cap nhat theo yeu cau chay nen va bao dang lay`() = runBlocking<Unit> {
        val t = LocationTracker({ delay(300); LocationFix.Ok(home) }, scope)

        t.requestUpdate()
        withTimeout(2_000) { t.status.first { it.updating } }
        t.requestUpdate() // đang lấy → bỏ qua, không chạy chồng
        withTimeout(2_000) { t.status.first { !it.updating && it.point == home } }
    }

    @Test
    fun `toa do sai bi bo`() = runBlocking<Unit> {
        val t = LocationTracker({ LocationFix.Ok(home.copy(lat = 123.0)) }, scope)
        t.refresh()
        assertNull(t.status.value.point)
        assertEquals(CameraCodes.LOCATION_UNAVAILABLE, t.status.value.error)
    }

    @Test
    fun `cap nhat dinh ky`() = runBlocking<Unit> {
        var calls = 0
        val t = LocationTracker({ calls++; LocationFix.Ok(home) }, scope, intervalMs = 50)
        val job = scope.launch { t.run() }
        delay(400)
        job.cancel()
        assertTrue(calls >= 4, "chỉ lấy $calls lần trong 400 ms với chu kỳ 50 ms")
    }

    @Test
    fun `link Google Maps luon dung dau cham du may dang dung tieng Viet`() {
        val old = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.forLanguageTag("vi-VN")) // tiếng Việt dùng dấu phẩy thập phân
            assertEquals("https://www.google.com/maps/search/?api=1&query=11.940419,108.458313", home.mapsUrl())
        } finally {
            java.util.Locale.setDefault(old)
        }
    }

    @Test
    fun `vi tri qua duoc JSON trang thai, may cu khong co thi null`() {
        val st = DeviceStatus(deviceId = "san-nha", online = true, lastSeen = 1, location = LocationStatus(point = home))
        assertEquals(st, ProtocolJson.decodeStatus(ProtocolJson.encodeStatus(st)))
        assertNull(ProtocolJson.decodeStatus("""{"deviceId":"san-nha","online":true,"lastSeen":1}""")!!.location)
    }
}
