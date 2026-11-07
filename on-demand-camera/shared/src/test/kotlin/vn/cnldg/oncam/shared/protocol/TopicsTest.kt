package vn.cnldg.oncam.shared.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TopicsTest {

    @Test
    fun `tao dung ten topic theo spec`() {
        assertEquals("camera/san-nha/cmd", Topics.command("san-nha"))
        assertEquals("camera/san-nha/status", Topics.status("san-nha"))
        assertEquals("camera/san-nha/event", Topics.event("san-nha"))
        assertEquals("camera/+/status", Topics.ALL_STATUS)
    }

    @Test
    fun `QoS lenh 1 trang thai 1 de retain chac chan su kien 0`() {
        assertEquals(1, Topics.QOS_COMMAND)
        assertEquals(1, Topics.QOS_STATUS)
        assertEquals(0, Topics.QOS_EVENT)
        assertEquals(1, Topics.QOS_WILL)
    }

    @Test
    fun `phan tich topic`() {
        assertEquals(Topics.Parsed("cam1", Topics.Kind.STATUS), Topics.parse("camera/cam1/status"))
        assertEquals(Topics.Parsed("cam_2", Topics.Kind.COMMAND), Topics.parse("camera/cam_2/cmd"))
        assertNull(Topics.parse("camera/cam1/khac"))
        assertNull(Topics.parse("other/cam1/status"))
        assertNull(Topics.parse("camera/cam1/status/extra"))
        assertNull(Topics.parse("camera/CAM/status"))
    }

    @Test
    fun `deviceId hop le`() {
        listOf("a", "san-nha", "cam_01", "0cam", "a".repeat(32)).forEach {
            assertTrue(DeviceIds.isValid(it), it)
        }
    }

    @Test
    fun `deviceId khong hop le bi tu choi`() {
        // Có dấu tiếng Việt, chữ hoa, ký tự đặc biệt của MQTT, rỗng, quá dài, bắt đầu bằng '-'.
        listOf("camera-sân", "SanNha", "a/b", "a+b", "a#b", "", "a".repeat(33), "-cam", "cam nha", "a|b").forEach {
            assertFalse(DeviceIds.isValid(it), it)
        }
        assertFailsWith<IllegalArgumentException> { Topics.command("a/b") }
    }
}
