package vn.cnldg.oncam.node

import android.Manifest
import android.graphics.BitmapFactory
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URI
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import vn.cnldg.oncam.node.camera.CameraController
import vn.cnldg.oncam.node.core.DeviceSnapshot
import vn.cnldg.oncam.node.core.MountOrientation
import vn.cnldg.oncam.node.core.OpenResult
import vn.cnldg.oncam.node.core.StreamManager
import vn.cnldg.oncam.node.mqtt.ActionOutcome
import vn.cnldg.oncam.shared.protocol.Action
import vn.cnldg.oncam.shared.protocol.CameraCodes
import vn.cnldg.oncam.shared.protocol.CameraFacing
import vn.cnldg.oncam.shared.protocol.CameraState
import vn.cnldg.oncam.shared.protocol.Command
import vn.cnldg.oncam.shared.protocol.CommandParams
import vn.cnldg.oncam.shared.protocol.StreamPaths
import vn.cnldg.oncam.shared.protocol.StreamQuality
import vn.cnldg.oncam.shared.security.StreamAuth

/**
 * Chạy trên emulator (camera sau giả lập) hoặc máy thật: CameraX mở được camera trong tiến trình
 * không có Activity, mã hoá YUV → JPEG đúng, và MJPEG server trả ảnh giải mã được.
 */
@RunWith(AndroidJUnit4::class)
class CameraOnDeviceTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val owner = TestLifecycleOwner()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var camera: CameraController

    @Before
    fun setUp() {
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.CAMERA)
        instrumentation.runOnMainSync { owner.registry.currentState = Lifecycle.State.RESUMED }
        camera = CameraController(context, owner, MountOrientation.UP)
    }

    @After
    fun tearDown() {
        runBlocking { camera.close() }
        camera.release()
        instrumentation.runOnMainSync { owner.registry.currentState = Lifecycle.State.DESTROYED }
        scope.cancel()
    }

    @Test
    fun moCameraSauVaNhanKhungJpeg() = runBlocking<Unit> {
        val frames = CopyOnWriteArrayList<ByteArray>()

        val result = camera.open(CameraFacing.BACK, StreamQuality.LOW) { frames += it }
        assertIs<OpenResult.Opened>(result)

        withTimeout(20_000) { while (frames.size < 3) delay(100) }
        val jpeg = frames.last()
        assertEquals(0xFF.toByte(), jpeg[0])
        assertEquals(0xD8.toByte(), jpeg[1])
        val bmp = assertNotNull(BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size))
        assertTrue(bmp.width > 0 && bmp.height > 0)

        camera.close()
        val countAfterClose = frames.size
        delay(1_000)
        assertTrue(frames.size <= countAfterClose + 1, "Đã nhả camera thì không còn khung mới")
    }

    @Test
    fun cameraKhongCoThiBaoLoiRoRang() = runBlocking<Unit> {
        // Emulator CI chỉ bật camera sau.
        if (!camera.hasCamera(CameraFacing.FRONT)) {
            assertEquals(OpenResult.Failed(CameraCodes.NO_CAMERA), camera.open(CameraFacing.FRONT, StreamQuality.LOW) { })
        }
        assertFalse(camera.setTorch(true), "Chưa mở camera thì không bật được flash")
    }

    @Test
    fun batStreamQuaStreamManagerVaTaiSnapshot() = runBlocking<Unit> {
        val port = ServerSocket(0).use { it.localPort }
        val auth = StreamAuth("test-secret-0123456789", "test-node")
        val manager = StreamManager(
            camera = camera,
            auth = auth,
            scope = scope,
            addressProvider = { "127.0.0.1" },
            cameraAllowed = { true },
            deviceInfo = { DeviceSnapshot() },
            settings = StreamManager.Settings(httpPort = port),
        )
        try {
            val started = manager.perform(command(Action.START_STREAM, CommandParams(quality = StreamQuality.LOW)))
            assertIs<ActionOutcome.Done>(started)
            assertEquals(CameraState.STREAMING, manager.status.value.state)

            val jpeg = withTimeout(20_000) {
                var body: ByteArray? = null
                while (body == null) {
                    body = fetch("http://127.0.0.1:$port${StreamPaths.SNAPSHOT}", auth.headerValue())
                    if (body == null) delay(200)
                }
                body
            }
            val bmp = assertNotNull(BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size))
            assertTrue(bmp.width > 0)

            assertEquals(ActionOutcome.Done(), manager.perform(command(Action.STOP_STREAM)))
            assertEquals(CameraState.OFF, manager.status.value.state)
        } finally {
            manager.shutdown()
        }
    }

    private fun command(action: Action, params: CommandParams = CommandParams()) =
        Command(id = "11111111-2222-3333-4444-555555555555", action = action, params = params, token = "t", timestamp = 0)

    private fun fetch(url: String, auth: String): ByteArray? {
        val conn = URI(url).toURL().openConnection() as HttpURLConnection
        conn.setRequestProperty(StreamAuth.HEADER, auth)
        return try {
            if (conn.responseCode == 200) conn.inputStream.use { it.readBytes() } else null
        } finally {
            conn.disconnect()
        }
    }

    /** LifecycleOwner tối giản thay cho Service trong test. */
    private class TestLifecycleOwner : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
    }
}
