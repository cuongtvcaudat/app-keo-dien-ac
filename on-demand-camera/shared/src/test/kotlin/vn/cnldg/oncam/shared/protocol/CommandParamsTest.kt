package vn.cnldg.oncam.shared.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CommandParamsTest {

    private fun cmd(action: Action, params: CommandParams = CommandParams()) =
        Command(id = "11111111-2222-3333-4444-555555555555", action = action, params = params, token = "t", timestamp = 1)

    @Test
    fun `lenh khong can tham so thi hop le`() {
        listOf(Action.START_STREAM, Action.STOP_STREAM, Action.SWITCH_CAMERA, Action.SNAPSHOT, Action.PING).forEach {
            assertNull(cmd(it).paramProblem(), it.name)
        }
    }

    @Test
    fun `SET_FLASH bat buoc co flash`() {
        assertEquals(CommandProblem.FLASH_REQUIRED, cmd(Action.SET_FLASH).paramProblem())
        assertNull(cmd(Action.SET_FLASH, CommandParams(flash = true)).paramProblem())
    }

    @Test
    fun `SET_QUALITY bat buoc co quality`() {
        assertEquals(CommandProblem.QUALITY_REQUIRED, cmd(Action.SET_QUALITY).paramProblem())
        assertNull(cmd(Action.SET_QUALITY, CommandParams(quality = StreamQuality.HIGH)).paramProblem())
    }

    @Test
    fun `idleTimeoutSec phai trong khoang 30 den 3600`() {
        assertEquals(
            CommandProblem.IDLE_TIMEOUT_OUT_OF_RANGE,
            cmd(Action.START_STREAM, CommandParams(idleTimeoutSec = 29)).paramProblem(),
        )
        assertEquals(
            CommandProblem.IDLE_TIMEOUT_OUT_OF_RANGE,
            cmd(Action.START_STREAM, CommandParams(idleTimeoutSec = 3601)).paramProblem(),
        )
        assertNull(cmd(Action.START_STREAM, CommandParams(idleTimeoutSec = 30)).paramProblem())
        assertNull(cmd(Action.START_STREAM, CommandParams(idleTimeoutSec = 3600)).paramProblem())
    }

    @Test
    fun `muc chat luong dung yeu cau muc 4_2 cua spec`() {
        assertEquals(Triple(640, 480, 10), StreamQuality.LOW.let { Triple(it.width, it.height, it.fps) })
        assertEquals(Triple(1280, 720, 15), StreamQuality.MEDIUM.let { Triple(it.width, it.height, it.fps) })
        assertEquals(Triple(1280, 720, 30), StreamQuality.HIGH.let { Triple(it.width, it.height, it.fps) })
    }
}
