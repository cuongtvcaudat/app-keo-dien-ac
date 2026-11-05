package vn.cnldg.oncam.shared.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProtocolJsonTest {

    @Test
    fun `doc duoc lenh dung mau muc 6_1 cua spec`() {
        val json = """
            {
              "id": "0b8f6c2e-3d1a-4c5e-9f7a-1234567890ab",
              "action": "START_STREAM",
              "params": { "camera": "BACK", "flash": false, "quality": "MEDIUM", "idleTimeoutSec": 180 },
              "token": "abc123",
              "timestamp": 1700000000
            }
        """.trimIndent()

        val cmd = assertNotNull(ProtocolJson.decodeCommand(json))

        assertEquals("0b8f6c2e-3d1a-4c5e-9f7a-1234567890ab", cmd.id)
        assertEquals(Action.START_STREAM, cmd.action)
        assertEquals(CommandParams(CameraFacing.BACK, false, StreamQuality.MEDIUM, 180), cmd.params)
        assertEquals("abc123", cmd.token)
        assertEquals(1_700_000_000L, cmd.timestamp)
    }

    @Test
    fun `doc duoc trang thai dung mau muc 6_2 cua spec`() {
        val json = """
            {
              "deviceId": "camera-san",
              "online": true,
              "cameraState": "STREAMING",
              "currentCamera": "BACK",
              "streamUrl": "http://100.64.0.5:8080/stream.mjpeg",
              "batteryPercent": 87,
              "temperatureC": 35.2,
              "lastSeen": 1700000000,
              "error": null
            }
        """.trimIndent()

        val status = assertNotNull(ProtocolJson.decodeStatus(json))

        assertEquals("camera-san", status.deviceId)
        assertTrue(status.online)
        assertEquals(CameraState.STREAMING, status.cameraState)
        assertEquals(CameraFacing.BACK, status.currentCamera)
        assertEquals("http://100.64.0.5:8080/stream.mjpeg", status.streamUrl)
        assertEquals(87, status.batteryPercent)
        assertEquals(35.2, status.temperatureC)
        assertNull(status.error)
        // Trường bổ sung không có trong JSON gốc → lấy giá trị mặc định.
        assertFalse(status.flashOn)
        assertEquals(StreamQuality.MEDIUM, status.quality)
        assertNull(status.network)
    }

    @Test
    fun `doc duoc su kien dung mau muc 6_3 cua spec`() {
        val json = """{ "event": "IDLE_TIMEOUT", "message": "Không ai xem 180s", "timestamp": 1700000000 }"""

        val event = assertNotNull(ProtocolJson.decodeEvent(json))

        assertEquals(EventType.IDLE_TIMEOUT, event.event)
        assertEquals("Không ai xem 180s", event.message)
        assertNull(event.commandId)
    }

    @Test
    fun `ma hoa roi giai ma giu nguyen du lieu`() {
        val cmd = Command(
            id = "11111111-2222-3333-4444-555555555555",
            action = Action.SET_QUALITY,
            params = CommandParams(quality = StreamQuality.LOW),
            token = "ff",
            timestamp = 1_700_000_123,
        )
        val status = DeviceStatus(
            deviceId = "san-nha",
            displayName = "Sân nhà",
            online = true,
            cameraState = CameraState.OFF,
            batteryPercent = 50,
            charging = true,
            temperatureC = 31.5,
            network = NetworkType.WIFI,
            lastSeen = 1_700_000_000,
        )
        val event = DeviceEvent(EventType.COMMAND_RESULT, "PONG", 1_700_000_001, cmd.id, true)

        assertEquals(cmd, ProtocolJson.decodeCommand(ProtocolJson.encodeCommand(cmd)))
        assertEquals(status, ProtocolJson.decodeStatus(ProtocolJson.encodeStatus(status)))
        assertEquals(event, ProtocolJson.decodeEvent(ProtocolJson.encodeEvent(event)))
    }

    @Test
    fun `khong ghi truong null ra JSON`() {
        val json = ProtocolJson.encodeStatus(DeviceStatus.offline("san-nha", null, 1_700_000_000))

        assertFalse(json.contains("null"), json)
        assertTrue(json.contains("\"online\":false"), json)
    }

    @Test
    fun `bo qua truong la de tuong thich phien ban moi`() {
        val json = """{"event":"CAMERA_STARTED","timestamp":1,"truongMoi":{"a":1}}"""

        assertEquals(EventType.CAMERA_STARTED, ProtocolJson.decodeEvent(json)?.event)
    }

    @Test
    fun `JSON hong hoac gia tri la tra ve null thay vi nem loi`() {
        assertNull(ProtocolJson.decodeCommand("không phải json"))
        assertNull(ProtocolJson.decodeCommand(""))
        assertNull(ProtocolJson.decodeCommand("""{"id":"x","action":"SELF_DESTRUCT","token":"t","timestamp":1}"""))
        assertNull(ProtocolJson.decodeCommand("""{"id":"x","action":"PING","timestamp":1}""")) // thiếu token
        assertNull(ProtocolJson.decodeStatus("""{"deviceId":"a","online":"có","lastSeen":1}"""))
        assertNull(ProtocolJson.decodeEvent("[]"))
    }
}
