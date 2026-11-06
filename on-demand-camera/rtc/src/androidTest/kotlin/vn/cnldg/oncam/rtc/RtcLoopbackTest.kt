package vn.cnldg.oncam.rtc

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.webrtc.VideoFrame
import org.webrtc.VideoSink
import vn.cnldg.oncam.shared.LogSink
import vn.cnldg.oncam.shared.protocol.StreamQuality
import vn.cnldg.oncam.shared.rtc.SdpCandidates

/**
 * WebRTC thật trên Android: phía camera ([CameraRtc], nhận khung NV21 giả) ↔ phía app Xem ([ViewerRtc])
 * trong cùng một máy. Emulator không có Tailscale nên cho phép mọi địa chỉ (máy thật chỉ giữ IP 100.x).
 */
@RunWith(AndroidJUnit4::class)
class RtcLoopbackTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val log = LogSink { level, message, error -> android.util.Log.println(level.ordinal + 3, "OnCamRtcTest", "$message ${error ?: ""}") }
    private val cameraEngine = checkNotNull(RtcEngine.create(context, log, forViewer = false))
    private val viewerEngine = checkNotNull(RtcEngine.create(context, log, forViewer = true))
    private val camera = CameraRtc(cameraEngine, scope, keepCandidate = { true }, micAllowed = { false }, log = log)
    private val viewers = mutableListOf<ViewerRtc>()

    @After
    fun tearDown() {
        viewers.forEach { it.close() }
        camera.dispose()
        runBlocking { delay(500) } // chờ PeerConnection đóng trên luồng nền
        cameraEngine.dispose()
        viewerEngine.dispose()
        scope.cancel()
    }

    @Test
    fun cameraGuiHinhQuaWebRtcToiMayXem() = runBlocking<Unit> {
        val feeding = scope.launch { feedFrames() }
        val viewer = viewer()

        val connected = viewer.connect { offer ->
            assertTrue(SdpCandidates.addresses(offer).isNotEmpty(), "offer phải có ứng viên ICE (không trickle)")
            (camera.answer(SESSION, offer, StreamQuality.LOW) as CameraRtcAnswer.Ok).sdp
        }

        assertTrue(connected, "phải kết nối được")
        assertEquals(RtcPhase.CONNECTED, viewer.phase.value)
        assertEquals(1, camera.sessionCount.value)

        val track = assertNotNull(withTimeout(10_000) { viewer.video.first { it != null } })
        val frames = AtomicInteger()
        val counter = VideoSink { _: VideoFrame? -> frames.incrementAndGet() }
        track.addSink(counter)
        withTimeout(15_000) { while (frames.get() < 10) delay(100) }
        track.removeSink(counter)
        assertTrue(viewer.fps.value > 0, "đếm được số hình/giây")

        // Thống kê thật của WebRTC: biết chuẩn nén đang dùng và lượng dữ liệu (hiện trên màn hình xem).
        val stats = withTimeout(10_000) { viewer.stats.first { it.codec != null && (it.kbps ?: 0) > 0 } }
        android.util.Log.i("OnCamBench", "WebRTC loopback: codec=${stats.codec} ${stats.width}x${stats.height} ${stats.kbps} kbit/s, " +
            "bộ nén phần cứng=${cameraEngine.hardwareVideoEncoders}")

        assertTrue(camera.hangup(SESSION))
        assertEquals(0, camera.sessionCount.value)
        feeding.cancel()
    }

    @Test
    fun khongCoUngVienHopLeThiBaoLoiDeQuayVeMjpeg() = runBlocking<Unit> {
        // Như máy thật chưa bật Tailscale: lọc bỏ hết địa chỉ.
        val strict = CameraRtc(cameraEngine, scope, keepCandidate = { false }, micAllowed = { false }, log = log)
        try {
            val viewer = viewer()
            var code: String? = null
            val ok = viewer.connect { offer ->
                when (val a = strict.answer(SESSION, offer, StreamQuality.LOW)) {
                    is CameraRtcAnswer.Ok -> a.sdp
                    is CameraRtcAnswer.Failed -> {
                        code = a.code
                        null
                    }
                }
            }
            assertEquals(false, ok)
            assertEquals(RtcPhase.FAILED, viewer.phase.value)
            assertEquals(CameraRtc.CODE_NO_ADDRESS, code)
            assertEquals(0, strict.sessionCount.value)
        } finally {
            strict.dispose()
        }
    }

    @Test
    fun toiDaHaiPhienCungLuc() = runBlocking<Unit> {
        val feeding = scope.launch { feedFrames() }
        repeat(2) { i ->
            assertTrue(viewer().connect { (camera.answer("$SESSION-$i", it, StreamQuality.LOW) as CameraRtcAnswer.Ok).sdp })
        }
        val third = viewer()
        var code: String? = null
        val ok = third.connect { offer ->
            (camera.answer("$SESSION-3", offer, StreamQuality.LOW) as? CameraRtcAnswer.Failed)?.let { code = it.code }
            null
        }

        assertEquals(false, ok)
        assertEquals(CameraRtc.CODE_BUSY, code)
        assertEquals(2, camera.sessionCount.value)
        camera.closeAll()
        assertEquals(0, camera.sessionCount.value)
        feeding.cancel()
    }

    private fun viewer() = ViewerRtc(viewerEngine, scope, keepCandidate = { true }, talk = false, log = log).also { viewers += it }

    /** Khung NV21 640×480 giả (mỗi khung một màu xám khác) ở 15 hình/giây. */
    private suspend fun feedFrames() {
        val w = 640
        val h = 480
        var n = 0
        while (scope.isActive) {
            val nv21 = ByteArray(w * h * 3 / 2) { if (it < w * h) (n * 7).toByte() else 128.toByte() }
            camera.onFrame(nv21, w, h, System.nanoTime())
            n++
            delay(66)
        }
    }

    private companion object {
        const val SESSION = "8f2c1d4e-0000-4000-8000-000000000001"
    }
}
