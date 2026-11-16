package vn.cnldg.oncam.node.core

import java.net.ServerSocket
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import vn.cnldg.oncam.node.mqtt.ActionOutcome
import vn.cnldg.oncam.shared.OnCam
import vn.cnldg.oncam.shared.protocol.Action
import vn.cnldg.oncam.shared.protocol.CameraCodes
import vn.cnldg.oncam.shared.protocol.CameraState
import vn.cnldg.oncam.shared.protocol.Command
import vn.cnldg.oncam.shared.protocol.CommandParams
import vn.cnldg.oncam.shared.protocol.MotionSensitivity
import vn.cnldg.oncam.shared.protocol.StreamQuality
import vn.cnldg.oncam.shared.security.StreamAuth

/** Canh chuyển động (0.11): bật camera liên tục, có chuyển động thì quay, hết thì tự dừng. */
class StreamManagerMotionTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val fake = FakeCameraPort(scope)
    private val dir = createTempDirectory("oncam-motion").toFile()
    private val clipMs = 400L

    private fun manager(recordings: RecordingStore? = RecordingStore(dir)) = StreamManager(
        camera = fake,
        auth = StreamAuth("test-secret-0123456789", "san-nha"),
        scope = scope,
        addressProvider = { "127.0.0.1" },
        cameraAllowed = { true },
        deviceInfo = { DeviceSnapshot(temperatureC = 35.0) },
        settings = StreamManager.Settings(
            httpPort = ServerSocket(0).use { it.localPort },
            recordingPort = ServerSocket(0).use { it.localPort },
            checkIntervalMs = 30,
            msPerIdleSecond = 10,
            motionClipMs = clipMs,
            motionIntervalMs = 0,
            motionWarmupFrames = 2,
        ),
        recordings = recordings,
        audioAllowed = { false },
    )

    private val manager = manager()

    @AfterTest
    fun tearDown() = runBlocking<Unit> {
        manager.shutdown()
        scope.cancel()
        dir.deleteRecursively()
    }

    @Test
    fun `bat canh thi camera bat muc thap, khong tu tat va tu choi STOP_STREAM`() = runBlocking<Unit> {
        val armed = manager.perform(cmd(Action.SET_MOTION, CommandParams(motion = true, idleTimeoutSec = OnCam.MIN_IDLE_TIMEOUT_SEC)))
        assertIs<ActionOutcome.Done>(armed)
        val st = manager.status.value
        assertEquals(CameraState.STREAMING, st.state)
        assertEquals(StreamQuality.LOW, st.quality, "canh ở mức Thấp cho đỡ nóng")
        assertTrue(st.motion.armed)
        assertTrue(manager.motionArmed())
        assertNotNull(fake.luma)
        assertTrue(fake.prepareCount >= 1, "gắn sẵn bộ quay")

        // Quá thời gian chờ (30 "giây" × 10 ms) mà không ai xem: vẫn bật vì đang canh.
        delay(OnCam.MIN_IDLE_TIMEOUT_SEC * 10L * 3)
        assertEquals(CameraState.STREAMING, manager.status.value.state)

        assertEquals(ActionOutcome.Failed(CameraCodes.MOTION_ARMED), manager.perform(cmd(Action.STOP_STREAM)))
        assertEquals(CameraState.STREAMING, manager.status.value.state)

        // Tắt canh: không ai xem → tự tắt sau thời gian chờ như thường.
        assertIs<ActionOutcome.Done>(manager.perform(cmd(Action.SET_MOTION, CommandParams(motion = false))))
        assertFalse(manager.status.value.motion.armed)
        assertNull(fake.luma)
        withTimeout(3_000) { manager.status.first { it.state == CameraState.OFF } }
    }

    @Test
    fun `co chuyen dong thi tu quay, het chuyen dong thi tu dung va luu clip`() = runBlocking<Unit> {
        manager.perform(cmd(Action.SET_MOTION, CommandParams(motion = true)))
        repeat(4) { fake.pushLuma(W, H, 100) } // học nền
        delay(100)
        assertFalse(manager.status.value.recording.active, "cảnh đứng yên không quay")

        // Người đi vào (khối 80×80 ≈ 8 % khung), 2 khung liên tiếp.
        fake.pushLuma(W, H, 100, intArrayOf(100, 80, 80, 80))
        fake.pushLuma(W, H, 100, intArrayOf(120, 80, 80, 80))
        withTimeout(2_000) { manager.status.first { it.recording.active } }
        val st = manager.status.value
        assertEquals(120, st.recording.maxSeconds, "clip chuyển động tối đa 2 phút")
        assertNotNull(st.motion.lastMotionAt)

        // Hết chuyển động: dừng sau ~clipMs.
        repeat(3) { fake.pushLuma(W, H, 100) }
        val done = withTimeout(clipMs + 3_000) { manager.status.first { !it.recording.active } }
        assertNotNull(done.recording.lastFile)
        assertTrue(done.motion.armed, "vẫn canh tiếp")
        assertEquals(CameraState.STREAMING, done.state)
    }

    @Test
    fun `bam quay tay trong luc canh thi khong tu dung khi het chuyen dong`() = runBlocking<Unit> {
        manager.perform(cmd(Action.SET_MOTION, CommandParams(motion = true)))
        assertIs<ActionOutcome.Done>(manager.perform(cmd(Action.START_RECORDING)))
        delay(clipMs * 3)
        assertTrue(manager.status.value.recording.active)
        manager.perform(cmd(Action.STOP_RECORDING))
        assertFalse(manager.status.value.recording.active)
    }

    @Test
    fun `camera bi tat vi ly do khac thi tu tat canh va bao ly do`() = runBlocking<Unit> {
        manager.perform(cmd(Action.SET_MOTION, CommandParams(motion = true)))
        manager.stopStreaming(CameraCodes.STOP_SHUTDOWN)
        val st = manager.status.value
        assertEquals(CameraState.OFF, st.state)
        assertFalse(st.motion.armed)
        assertEquals(CameraCodes.MOTION_STOPPED, st.motion.error)
        assertNull(fake.luma)
        // Bật lại canh thì xoá lỗi cũ.
        manager.perform(cmd(Action.SET_MOTION, CommandParams(motion = true)))
        assertNull(manager.status.value.motion.error)
    }

    @Test
    fun `doi do nhay khong can bat canh, camera ban khong quay duoc thi bao chua ho tro`() = runBlocking<Unit> {
        manager.perform(cmd(Action.SET_MOTION, CommandParams(motion = false, sensitivity = MotionSensitivity.HIGH)))
        assertEquals(MotionSensitivity.HIGH, manager.status.value.motion.sensitivity)
        assertFalse(manager.status.value.motion.armed)
        assertEquals(CameraState.OFF, manager.status.value.state)

        val old = manager(recordings = null)
        try {
            assertEquals(
                ActionOutcome.Failed(CameraCodes.RECORDING_UNSUPPORTED),
                old.perform(cmd(Action.SET_MOTION, CommandParams(motion = true))),
            )
        } finally {
            old.shutdown()
        }
    }

    private fun cmd(action: Action, params: CommandParams = CommandParams()) =
        Command(id = "11111111-2222-3333-4444-555555555555", action = action, params = params, token = "t", timestamp = 0)

    private companion object {
        const val W = 320
        const val H = 240
    }
}
