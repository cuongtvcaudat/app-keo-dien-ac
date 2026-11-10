package vn.cnldg.oncam.node.core

import java.net.ServerSocket
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
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
import vn.cnldg.oncam.node.mqtt.ActionOutcome
import vn.cnldg.oncam.shared.protocol.Action
import vn.cnldg.oncam.shared.protocol.CameraCodes
import vn.cnldg.oncam.shared.protocol.CameraFacing
import vn.cnldg.oncam.shared.protocol.CameraState
import vn.cnldg.oncam.shared.protocol.Command
import vn.cnldg.oncam.shared.protocol.CommandParams
import vn.cnldg.oncam.shared.protocol.DeviceEvent
import vn.cnldg.oncam.shared.protocol.EventType
import vn.cnldg.oncam.shared.protocol.StreamPaths
import vn.cnldg.oncam.shared.protocol.StreamQuality
import vn.cnldg.oncam.shared.security.StreamAuth

class StreamManagerTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val port = ServerSocket(0).use { it.localPort }
    private val auth = StreamAuth("test-secret-0123456789", "san-nha")
    private val fake = FakeCameraPort(scope)

    @Volatile private var address: String? = "127.0.0.1"
    @Volatile private var allowed = true
    @Volatile private var temperature: Double? = 35.0

    private val events = mutableListOf<DeviceEvent>()
    private val manager = StreamManager(
        camera = fake,
        auth = auth,
        scope = scope,
        addressProvider = { address },
        cameraAllowed = { allowed },
        deviceInfo = { DeviceSnapshot(temperatureC = temperature) },
        settings = StreamManager.Settings(
            httpPort = port,
            checkIntervalMs = 30,
            msPerIdleSecond = 10, // 30 "giây" chờ = 0,3 s thật
            thermalCheckMs = 30,
            thermalLowerCooldownMs = 0,
        ),
    ).also { m -> scope.launch { m.events.collect { synchronized(events) { events += it } } } }

    @AfterTest
    fun tearDown() = runBlocking<Unit> {
        manager.shutdown()
        scope.cancel()
    }

    @Test
    fun `bat camera thi mo camera phat MJPEG tren IP Tailscale va bao CAMERA_STARTED`() = runBlocking<Unit> {
        val result = manager.perform(cmd(Action.START_STREAM, CommandParams(quality = StreamQuality.LOW)))

        val url = "http://127.0.0.1:$port${StreamPaths.STREAM}"
        assertEquals(ActionOutcome.Done(url), result)
        assertEquals(CameraState.STREAMING, manager.status.value.state)
        assertEquals(url, manager.status.value.streamUrl)
        assertEquals(CameraFacing.BACK to StreamQuality.LOW, fake.opened)
        awaitEvent(EventType.CAMERA_STARTED)

        delay(100)
        val snap = httpGet("http://127.0.0.1:$port${StreamPaths.SNAPSHOT}", auth.headerValue())
        assertEquals(200, snap.code)
        assertEquals(0xD8.toByte(), snap.body[1])
        assertIs<ActionOutcome.Done>(manager.perform(cmd(Action.SNAPSHOT)))
    }

    @Test
    fun `tat camera thi nha camera dong cong HTTP va bao CAMERA_STOPPED`() = runBlocking<Unit> {
        manager.perform(cmd(Action.START_STREAM))

        assertEquals(ActionOutcome.Done(), manager.perform(cmd(Action.STOP_STREAM)))

        assertNull(fake.opened)
        assertEquals(CameraState.OFF, manager.status.value.state)
        assertNull(manager.status.value.streamUrl)
        assertEquals(CameraCodes.STOP_BY_VIEWER, awaitEvent(EventType.CAMERA_STOPPED).message)
        assertTrue(runCatching { ServerSocket(port).close() }.isSuccess, "Cổng HTTP phải được nhả")
        assertEquals(ActionOutcome.Done(CameraCodes.ALREADY_OFF), manager.perform(cmd(Action.STOP_STREAM)))
    }

    @Test
    fun `khong ai xem qua thoi gian cho thi tu tat va bao IDLE_TIMEOUT`() = runBlocking<Unit> {
        manager.perform(cmd(Action.START_STREAM, CommandParams(idleTimeoutSec = 30)))

        withTimeout(5_000) { manager.status.first { it.state == CameraState.OFF } }
        assertEquals("30s", awaitEvent(EventType.IDLE_TIMEOUT).message)
        assertNull(fake.opened)
    }

    @Test
    fun `dang co nguoi xem thi khong tu tat`() = runBlocking<Unit> {
        manager.perform(cmd(Action.START_STREAM, CommandParams(idleTimeoutSec = 30)))
        val (socket, parts) = openMjpeg("127.0.0.1", port, auth.headerValue(), parts = 2)
        assertEquals(2, parts)
        try {
            withTimeout(3_000) { manager.status.first { it.clients == 1 } }
            delay(800) // gấp hơn 2 lần thời gian chờ 0,3 s
            assertEquals(CameraState.STREAMING, manager.status.value.state)
        } finally {
            socket.close()
        }
        // Người xem rời đi → hết thời gian chờ thì tự tắt.
        withTimeout(5_000) { manager.status.first { it.state == CameraState.OFF } }
    }

    @Test
    fun `chua duoc phep mo camera hoac chua co Tailscale thi bao loi ro rang`() = runBlocking<Unit> {
        allowed = false
        assertEquals(ActionOutcome.Failed(CameraCodes.NEEDS_APP_OPEN), manager.perform(cmd(Action.START_STREAM)))
        assertEquals(CameraCodes.NEEDS_APP_OPEN, manager.status.value.error)
        assertNull(fake.opened)

        allowed = true
        address = null
        assertEquals(ActionOutcome.Failed(CameraCodes.NO_TAILSCALE), manager.perform(cmd(Action.START_STREAM)))

        address = "127.0.0.1"
        fake.failOpenWith = CameraCodes.OPEN_FAILED
        assertEquals(ActionOutcome.Failed(CameraCodes.OPEN_FAILED), manager.perform(cmd(Action.START_STREAM)))
        assertEquals(CameraState.ERROR, manager.status.value.state)

        fake.failOpenWith = null
        assertIs<ActionOutcome.Done>(manager.perform(cmd(Action.START_STREAM)))
        assertNull(manager.status.value.error)
    }

    @Test
    fun `flash chi bat duoc khi camera dang mo va co den`() = runBlocking<Unit> {
        assertEquals(ActionOutcome.Failed(CameraCodes.CAMERA_OFF), manager.perform(cmd(Action.SET_FLASH, CommandParams(flash = true))))

        manager.perform(cmd(Action.START_STREAM))
        assertEquals(ActionOutcome.Done(), manager.perform(cmd(Action.SET_FLASH, CommandParams(flash = true))))
        assertTrue(fake.torch)
        assertTrue(manager.status.value.flashOn)

        // Đổi sang camera trước (không có flash) → đèn tắt, bật lại báo NO_FLASH.
        assertIs<ActionOutcome.Done>(manager.perform(cmd(Action.SWITCH_CAMERA)))
        assertEquals(CameraFacing.FRONT to StreamQuality.MEDIUM, fake.opened)
        assertEquals(false, manager.status.value.flashOn)
        assertEquals(ActionOutcome.Failed(CameraCodes.NO_FLASH), manager.perform(cmd(Action.SET_FLASH, CommandParams(flash = true))))
    }

    @Test
    fun `doi camera va chat luong khi dang phat giu nguyen HTTP server`() = runBlocking<Unit> {
        manager.perform(cmd(Action.START_STREAM))
        val (socket, _) = openMjpeg("127.0.0.1", port, auth.headerValue(), parts = 1)
        try {
            manager.perform(cmd(Action.SET_QUALITY, CommandParams(quality = StreamQuality.HIGH)))
            assertEquals(CameraFacing.BACK to StreamQuality.HIGH, fake.opened)
            manager.perform(cmd(Action.SWITCH_CAMERA, CommandParams(camera = CameraFacing.FRONT)))
            assertEquals(CameraFacing.FRONT to StreamQuality.HIGH, fake.opened)
            assertEquals(3, fake.openCount)
            // Người xem vẫn nhận khung sau khi đổi camera.
            val more = socket.getInputStream().read(ByteArray(64))
            assertTrue(more > 0)
        } finally {
            socket.close()
        }

        fake.available = setOf(CameraFacing.BACK)
        assertEquals(ActionOutcome.Failed(CameraCodes.NO_CAMERA), manager.perform(cmd(Action.SWITCH_CAMERA, CommandParams(camera = CameraFacing.FRONT))))
    }

    @Test
    fun `nong qua nguong thi ha chat luong roi tat`() = runBlocking<Unit> {
        manager.perform(cmd(Action.START_STREAM, CommandParams(quality = StreamQuality.HIGH, idleTimeoutSec = 3600)))

        temperature = 43.0
        withTimeout(3_000) { manager.status.first { it.quality == StreamQuality.LOW } }
        assertTrue(awaitEvent(EventType.THERMAL_THROTTLE).message!!.startsWith(CameraCodes.THERMAL_LOWERED))

        temperature = 45.5
        withTimeout(3_000) { manager.status.first { it.state == CameraState.OFF } }
        // Sự kiện được thu gom bất đồng bộ: chờ đúng sự kiện STOPPED, không lấy "sự kiện cuối" (có thể vẫn là LOWERED).
        val stopped = awaitEvent(EventType.THERMAL_THROTTLE) { it.message?.startsWith(CameraCodes.THERMAL_STOPPED) == true }
        assertEquals("${CameraCodes.THERMAL_STOPPED}:45.5", stopped.message)
        assertNull(fake.opened)
    }

    @Test
    fun `camera bao loi khi dang chay thi tat va chuyen sang ERROR`() = runBlocking<Unit> {
        manager.perform(cmd(Action.START_STREAM))

        fake.errorFlow.emit("${CameraCodes.CAMERA_ERROR}:CAMERA_IN_USE")

        withTimeout(3_000) { manager.status.first { it.state == CameraState.ERROR } }
        assertEquals("${CameraCodes.CAMERA_ERROR}:CAMERA_IN_USE", manager.status.value.error)
        assertNull(fake.opened)
    }

    @Test
    fun `IP Tailscale doi khi dang phat thi tat stream`() = runBlocking<Unit> {
        manager.perform(cmd(Action.START_STREAM, CommandParams(idleTimeoutSec = 3600)))

        address = "127.0.0.2"

        withTimeout(3_000) { manager.status.first { it.state == CameraState.OFF } }
        assertEquals(CameraCodes.ADDRESS_CHANGED, manager.status.value.error)
    }

    @Test
    fun `xem thu tren may lay duoc khung moi nhat`() = runBlocking<Unit> {
        assertNull(manager.latestFrame())
        manager.perform(cmd(Action.START_STREAM))
        delay(100)
        val frame = manager.latestFrame()!!
        assertContentEquals(byteArrayOf(0xFF.toByte(), 0xD8.toByte()), frame.copyOfRange(0, 2))
    }

    private fun cmd(action: Action, params: CommandParams = CommandParams()) =
        Command(id = "11111111-2222-3333-4444-555555555555", action = action, params = params, token = "t", timestamp = 0)

    private suspend fun awaitEvent(type: EventType, match: (DeviceEvent) -> Boolean = { true }): DeviceEvent = withTimeout(3_000) {
        var found: DeviceEvent? = null
        while (found == null) {
            found = synchronized(events) { events.lastOrNull { it.event == type && match(it) } }
            if (found == null) delay(20)
        }
        found
    }
}
