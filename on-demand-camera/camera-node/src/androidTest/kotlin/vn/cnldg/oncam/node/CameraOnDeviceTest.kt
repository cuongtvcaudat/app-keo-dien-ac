package vn.cnldg.oncam.node

import android.Manifest
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.util.Log
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assume
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import vn.cnldg.oncam.node.camera.CameraController
import vn.cnldg.oncam.node.core.DeviceSnapshot
import vn.cnldg.oncam.node.core.MountOrientation
import vn.cnldg.oncam.node.core.OpenResult
import vn.cnldg.oncam.node.core.RecordingOutcome
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

    /**
     * Quay video thật (0.10) song song với luồng xem: file MP4 đọc được, luồng xem không đứng, kênh Y vẫn tới bộ
     * phát hiện chuyển động (0.11). Không thu tiếng (test không có quyền micro).
     */
    @Test
    fun quayVideoThatSongSongVoiLuongXem() = runBlocking<Unit> {
        val frames = AtomicInteger()
        val luma = AtomicInteger()
        assertIs<OpenResult.Opened>(camera.open(CameraFacing.BACK, StreamQuality.LOW) { frames.incrementAndGet() })
        camera.setLumaSink { y, stride, w, h -> if (stride >= w && y.limit() >= stride * (h - 1) + w) luma.incrementAndGet() }
        withTimeout(20_000) { while (frames.get() < 3) delay(100) }

        val file = File(context.cacheDir, "oncam-test-${System.nanoTime()}.mp4")
        val done = CompletableDeferred<RecordingOutcome>()
        try {
            // Camera ảo của emulator CI không bind được ImageAnalysis + VideoCapture cùng lúc → bỏ qua test (máy
            // thật như Galaxy A54 quay song song được). Vẫn phải thấy: thất bại thì trả false sạch, luồng xem không đứng.
            val prepared = camera.prepareRecording()
            if (!prepared) {
                Log.i("OnCamBench", "Máy này không quay song song với luồng xem (thường là emulator) → bỏ qua")
                val before = frames.get()
                delay(2_000)
                assertTrue(frames.get() > before, "luồng xem vẫn chạy sau khi thử gắn bộ quay")
                Assume.assumeTrue("Máy không quay song song với luồng xem", false)
            }
            assertTrue(
                camera.startRecording(file, maxMs = 60_000, withAudio = false) { done.complete(it) },
                "bắt đầu quay được",
            )
            val before = frames.get()
            delay(4_000)
            assertTrue(frames.get() > before, "luồng xem vẫn chạy khi đang quay")
            assertTrue(luma.get() > 0, "kênh Y tới bộ phát hiện chuyển động")
            camera.stopRecording()
            val saved = assertIs<RecordingOutcome.Saved>(withTimeout(20_000) { done.await() })
            assertFalse(saved.audio)
            assertTrue(saved.bytes > 10_000, "file chỉ ${saved.bytes} byte")

            val meta = MediaMetadataRetriever()
            try {
                meta.setDataSource(file.absolutePath)
                val durationMs = assertNotNull(meta.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)).toLong()
                val w = meta.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                val h = meta.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                assertEquals("yes", meta.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO))
                assertTrue(durationMs >= 1_500, "clip chỉ dài $durationMs ms")
                Log.i(
                    "OnCamBench",
                    "Quay thử trên Android: ${w}x$h, $durationMs ms, ${saved.bytes} byte " +
                        "(~${saved.bytes * 8 / durationMs} kbit/s), khung xem trong lúc quay: ${frames.get() - before}",
                )
            } finally {
                meta.release()
            }
        } finally {
            camera.setLumaSink(null)
            file.delete()
        }
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
                    body = fetch(port, StreamPaths.SNAPSHOT, auth.headerValue())
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

    /**
     * GET qua socket thô: HttpURLConnection trong tiến trình app bị chính sách mạng của Android chặn
     * HTTP không mã hoá (cleartext). App camera-node chỉ làm server nên không cần mở chính sách đó.
     */
    private fun fetch(port: Int, path: String, auth: String): ByteArray? =
        Socket("127.0.0.1", port).use { socket ->
            socket.soTimeout = 5_000
            val request = "GET $path HTTP/1.1\r\nHost: 127.0.0.1\r\n${StreamAuth.HEADER}: $auth\r\nConnection: close\r\n\r\n"
            socket.getOutputStream().apply {
                write(request.toByteArray(Charsets.US_ASCII))
                flush()
            }
            val all = socket.getInputStream().readBytes()
            val split = String(all, Charsets.ISO_8859_1).indexOf("\r\n\r\n")
            if (split < 0 || !String(all, 0, split, Charsets.ISO_8859_1).startsWith("HTTP/1.1 200")) {
                null
            } else {
                all.copyOfRange(split + 4, all.size)
            }
        }

    /** LifecycleOwner tối giản thay cho Service trong test. */
    private class TestLifecycleOwner : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
    }
}
