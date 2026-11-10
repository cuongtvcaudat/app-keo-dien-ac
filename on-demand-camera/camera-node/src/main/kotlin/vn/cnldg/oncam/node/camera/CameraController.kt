package vn.cnldg.oncam.node.camera

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.util.Size
import android.view.Surface
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraState as XCameraState
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.Observer
import com.google.common.util.concurrent.ListenableFuture
import java.io.File
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import vn.cnldg.oncam.node.core.CameraPort
import vn.cnldg.oncam.node.core.LumaSink
import vn.cnldg.oncam.node.core.MountOrientation
import vn.cnldg.oncam.node.core.OpenResult
import vn.cnldg.oncam.node.core.RecordingOutcome
import vn.cnldg.oncam.node.core.YuvToNv21
import vn.cnldg.oncam.shared.LogSink
import vn.cnldg.oncam.shared.protocol.CameraCodes
import vn.cnldg.oncam.shared.protocol.CameraFacing
import vn.cnldg.oncam.shared.protocol.StreamQuality

/**
 * Camera thật qua CameraX, chạy trong Foreground Service ([lifecycleOwner] = service).
 *
 * Chỉ dùng ImageAnalysis (không cần màn hình xem trước): mỗi khung YUV được giới hạn theo FPS của
 * [StreamQuality], xoay theo [orientation], rồi mã hoá JPEG một lần và đưa cho MJPEG server.
 * [close] gỡ mọi use case → CameraX đóng camera, app Camera khác dùng được ngay.
 */
class CameraController(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val orientation: MountOrientation,
    private val log: LogSink = LogSink.NONE,
) : CameraPort {

    private val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "oncam-camera").apply { isDaemon = true }
    }
    private var provider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var stateObserver: Observer<XCameraState>? = null
    private var currentSelector: CameraSelector? = null
    private var currentAnalysis: ImageAnalysis? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null

    /**
     * Nhận khung NV21 (đã xoay đúng chiều, đã giới hạn FPS) cho WebRTC (Phase 7): (dữ liệu, rộng, cao, thời điểm ns).
     * Gọi trên luồng camera; null = không ai cần.
     */
    @Volatile
    var rawFrameSink: ((ByteArray, Int, Int, Long) -> Unit)? = null

    /**
     * Có cần JPEG đủ số hình/giây không (đang có người xem MJPEG, hoặc không có phiên WebRTC nào).
     * false → chỉ nén JPEG 1 khung/giây (đủ cho chụp ảnh và xem thử trên máy), đỡ tốn CPU khi chỉ xem WebRTC.
     */
    @Volatile
    var fullRateJpeg: () -> Boolean = { true }

    /** Kênh Y mỗi khung cho phát hiện chuyển động (0.11); null = không canh. */
    @Volatile
    private var luma: LumaSink? = null

    override fun setLumaSink(sink: LumaSink?) {
        luma = sink
    }

        private val _errors = MutableSharedFlow<String>(extraBufferCapacity = 4)
    override val errors: Flow<String> = _errors.asSharedFlow()

    override suspend fun hasCamera(facing: CameraFacing): Boolean = withContext(Dispatchers.Main) {
        runCatching { provider().hasCamera(selector(facing)) }.getOrDefault(false)
    }

    override suspend fun open(facing: CameraFacing, quality: StreamQuality, sink: (ByteArray) -> Unit): OpenResult =
        withContext(Dispatchers.Main) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                return@withContext OpenResult.Failed(CameraCodes.NO_PERMISSION)
            }
            val p = try {
                provider()
            } catch (e: Exception) {
                log.log(LogSink.Level.WARN, "Không lấy được CameraProvider", e)
                return@withContext OpenResult.Failed(CameraCodes.OPEN_FAILED)
            }
            val sel = selector(facing)
            if (!runCatching { p.hasCamera(sel) }.getOrDefault(false)) {
                return@withContext OpenResult.Failed(CameraCodes.NO_CAMERA)
            }

            val analysis = ImageAnalysis.Builder()
                .setResolutionSelector(
                    ResolutionSelector.Builder()
                        .setResolutionStrategy(
                            ResolutionStrategy(
                                Size(quality.width, quality.height),
                                ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER,
                            ),
                        )
                        .build(),
                )
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                .setOutputImageRotationEnabled(true)
                .setTargetRotation(surfaceRotation(orientation))
                .build()
            analysis.setAnalyzer(analysisExecutor, FrameEncoder(quality, sink, { rawFrameSink }, { fullRateJpeg() }, { luma }))

            try {
                detachObserver()
                p.unbindAll()
                videoCapture = null
                val cam = p.bindToLifecycle(lifecycleOwner, sel, analysis)
                attach(cam)
                currentSelector = sel
                currentAnalysis = analysis
                log.log(LogSink.Level.INFO, "Đã mở camera $facing ở $quality", null)
                OpenResult.Opened(hasFlash = cam.cameraInfo.hasFlashUnit())
            } catch (e: Exception) {
                log.log(LogSink.Level.WARN, "Không mở được camera $facing", e)
                p.unbindAll()
                camera = null
                currentSelector = null
                currentAnalysis = null
                OpenResult.Failed(CameraCodes.OPEN_FAILED)
            }
        }

    /**
     * Quay MP4 (H.264 720p ~2 Mbit/s + AAC nếu [withAudio]) bằng CameraX VideoCapture, chạy song song với luồng
     * xem (ImageAnalysis). Lần đầu quay sẽ gắn thêm VideoCapture → CameraX mở lại camera (hình khựng ~1 giây).
     * Máy không cho 2 luồng cùng lúc → trả false và giữ nguyên luồng xem.
     */
    @SuppressLint("MissingPermission") // đã kiểm quyền RECORD_AUDIO ngay trước withAudioEnabled()
    override suspend fun startRecording(
        file: File,
        maxMs: Long,
        withAudio: Boolean,
        onFinished: (RecordingOutcome) -> Unit,
    ): Boolean = withContext(Dispatchers.Main) {
        val p = provider ?: return@withContext false
        val sel = currentSelector ?: return@withContext false
        val analysis = currentAnalysis ?: return@withContext false
        if (recording != null) return@withContext false
        val capture = ensureVideoCapture(p, sel, analysis) ?: return@withContext false
        val audio = withAudio &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val options = FileOutputOptions.Builder(file).setDurationLimitMillis(maxMs).build()
        val pending = capture.output.prepareRecording(context, options).let { if (audio) it.withAudioEnabled() else it }
        recording = try {
            pending.start(ContextCompat.getMainExecutor(context)) { event ->
                if (event is VideoRecordEvent.Finalize) {
                    recording = null
                    onFinished(outcome(event, file, audio))
                }
            }
        } catch (e: Exception) {
            log.log(LogSink.Level.WARN, "Không bắt đầu quay được", e)
            return@withContext false
        }
        true
    }

    override suspend fun prepareRecording(): Boolean = withContext(Dispatchers.Main) {
        val p = provider ?: return@withContext false
        val sel = currentSelector ?: return@withContext false
        val analysis = currentAnalysis ?: return@withContext false
        ensureVideoCapture(p, sel, analysis) != null
    }

    /**
     * Gắn VideoCapture cạnh luồng xem (nếu chưa gắn). Gắn thêm làm CameraX mở lại camera (hình khựng ~1 giây).
     * Máy không cho 2 luồng cùng lúc → null, giữ nguyên luồng xem.
     */
    private fun ensureVideoCapture(p: ProcessCameraProvider, sel: CameraSelector, analysis: ImageAnalysis): VideoCapture<Recorder>? {
        videoCapture?.let { return it }
        val recorder = Recorder.Builder()
            .setQualitySelector(QualitySelector.from(Quality.HD, FallbackStrategy.lowerQualityOrHigherThan(Quality.HD)))
            .setTargetVideoEncodingBitRate(VIDEO_BITRATE)
            .build()
        val vc = VideoCapture.Builder(recorder).setTargetRotation(surfaceRotation(orientation)).build()
        return try {
            detachObserver()
            p.unbindAll()
            attach(p.bindToLifecycle(lifecycleOwner, sel, analysis, vc))
            videoCapture = vc
            vc
        } catch (e: Exception) {
            log.log(LogSink.Level.WARN, "Máy không quay video song song với luồng xem được", e)
            runCatching {
                p.unbindAll()
                attach(p.bindToLifecycle(lifecycleOwner, sel, analysis))
            }
            null
        }
    }

    override suspend fun stopRecording() = withContext(Dispatchers.Main) {
        recording?.stop()
        Unit
    }

    private fun outcome(event: VideoRecordEvent.Finalize, file: File, audio: Boolean): RecordingOutcome {
        val len = if (file.exists()) file.length() else 0L
        // Hết giờ / hết dung lượng đặt / camera đóng giữa chừng: file vẫn dùng được.
        val usable = len > 0 && event.error in setOf(
            VideoRecordEvent.Finalize.ERROR_NONE,
            VideoRecordEvent.Finalize.ERROR_DURATION_LIMIT_REACHED,
            VideoRecordEvent.Finalize.ERROR_FILE_SIZE_LIMIT_REACHED,
            VideoRecordEvent.Finalize.ERROR_SOURCE_INACTIVE,
        )
        if (usable) return RecordingOutcome.Saved(file, len, audio)
        log.log(LogSink.Level.WARN, "Quay video lỗi (mã ${event.error})", event.cause)
        if (len == 0L) file.delete()
        return RecordingOutcome.Failed(
            if (event.error == VideoRecordEvent.Finalize.ERROR_INSUFFICIENT_STORAGE) {
                CameraCodes.RECORDING_STORAGE_FULL
            } else {
                CameraCodes.RECORDING_FAILED
            },
        )
    }

    private fun attach(cam: Camera) {
        camera = cam
        val observer = Observer<XCameraState> { state -> onCameraState(state) }
        cam.cameraInfo.cameraState.observe(lifecycleOwner, observer)
        stateObserver = observer
    }

    override suspend fun setTorch(on: Boolean): Boolean = withContext(Dispatchers.Main) {
        val cam = camera ?: return@withContext false
        if (!cam.cameraInfo.hasFlashUnit()) return@withContext false
        runCatching { cam.cameraControl.enableTorch(on).await() }.isSuccess
    }

    override suspend fun close() = withContext(Dispatchers.Main) {
        recording?.stop()
        detachObserver()
        provider?.unbindAll()
        camera = null
        videoCapture = null
        currentSelector = null
        currentAnalysis = null
        log.log(LogSink.Level.INFO, "Đã nhả camera", null)
    }

    /** Giải phóng luồng mã hoá khi service bị huỷ. */
    fun release() {
        analysisExecutor.shutdown()
    }

    private fun onCameraState(state: XCameraState) {
        val error = state.error ?: return
        // Lỗi CameraX tự thử lại được (camera bị app khác chiếm tạm thời...) thì bỏ qua;
        // chỉ báo khi camera đã đóng hẳn hoặc bị hệ thống chặn.
        val fatal = state.type == XCameraState.Type.CLOSED ||
            error.code == XCameraState.ERROR_CAMERA_DISABLED ||
            error.code == XCameraState.ERROR_CAMERA_FATAL_ERROR ||
            error.code == XCameraState.ERROR_DO_NOT_DISTURB_MODE_ENABLED
        if (fatal) _errors.tryEmit("${CameraCodes.CAMERA_ERROR}:${errorName(error.code)}")
    }

    private fun detachObserver() {
        stateObserver?.let { obs -> camera?.cameraInfo?.cameraState?.removeObserver(obs) }
        stateObserver = null
    }

    private suspend fun provider(): ProcessCameraProvider =
        provider ?: ProcessCameraProvider.getInstance(context).await().also { provider = it }

    private fun selector(facing: CameraFacing) =
        if (facing == CameraFacing.FRONT) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA

    private fun surfaceRotation(o: MountOrientation) = when (o) {
        MountOrientation.UP -> Surface.ROTATION_0
        MountOrientation.LEFT -> Surface.ROTATION_90
        MountOrientation.DOWN -> Surface.ROTATION_180
        MountOrientation.RIGHT -> Surface.ROTATION_270
    }

    private fun errorName(code: Int) = when (code) {
        XCameraState.ERROR_MAX_CAMERAS_IN_USE -> "MAX_CAMERAS_IN_USE"
        XCameraState.ERROR_CAMERA_IN_USE -> "CAMERA_IN_USE"
        XCameraState.ERROR_OTHER_RECOVERABLE_ERROR -> "RECOVERABLE"
        XCameraState.ERROR_STREAM_CONFIG -> "STREAM_CONFIG"
        XCameraState.ERROR_CAMERA_DISABLED -> "CAMERA_DISABLED"
        XCameraState.ERROR_CAMERA_FATAL_ERROR -> "FATAL"
        XCameraState.ERROR_DO_NOT_DISTURB_MODE_ENABLED -> "DO_NOT_DISTURB"
        else -> code.toString()
    }

    /**
     * Giới hạn FPS, đưa kênh Y cho phát hiện chuyển động (nếu đang canh), chuyển NV21 một lần rồi: nén JPEG (MJPEG/ảnh chụp) — đủ FPS hoặc 1 khung/giây tuỳ [fullJpeg] —
     * và đưa sang WebRTC nếu có phiên; luôn đóng ImageProxy để CameraX gửi khung tiếp.
     */
    private class FrameEncoder(
        private val quality: StreamQuality,
        private val sink: (ByteArray) -> Unit,
        private val raw: () -> ((ByteArray, Int, Int, Long) -> Unit)?,
        private val fullJpeg: () -> Boolean,
        private val motion: () -> LumaSink?,
    ) : ImageAnalysis.Analyzer {
        private val intervalNs = 1_000_000_000L / quality.fps
        private val converter = YuvToNv21()
        private var lastNs = 0L
        private var lastJpegNs = 0L

        override fun analyze(image: ImageProxy) {
            try {
                val now = System.nanoTime()
                if (now - lastNs < intervalNs) return
                lastNs = now
                // Canh chuyển động: đọc thẳng kênh Y (không cần chuyển NV21/JPEG), bộ phát hiện tự giới hạn 4 khung/giây.
                motion()?.let { m ->
                    val y = image.planes[0]
                    m(y.buffer, y.rowStride, image.width, image.height)
                }
                val rtc = raw()
                val jpeg = fullJpeg() || now - lastJpegNs >= SLOW_JPEG_INTERVAL_NS
                if (!jpeg && rtc == null) return
                val frame = YuvJpeg.nv21(image, converter)
                if (jpeg) {
                    sink(YuvJpeg.encode(frame, quality.jpegQuality))
                    lastJpegNs = now
                }
                rtc?.invoke(frame.data, frame.width, frame.height, now)
            } catch (_: Exception) {
                // Bỏ khung lỗi (hiếm), không làm dừng camera.
            } finally {
                image.close()
            }
        }

        private companion object {
            const val SLOW_JPEG_INTERVAL_NS = 1_000_000_000L
        }
    }
}

/** Bitrate video quay (bit/s): 720p 2 Mbit/s ≈ 15 MB/phút — đủ nét cho giám sát, nhẹ để tải qua 4G. */
private const val VIDEO_BITRATE = 2_000_000

/** Chờ ListenableFuture của CameraX mà không cần thêm thư viện. */
private suspend fun <T> ListenableFuture<T>.await(): T = suspendCancellableCoroutine { cont ->
    addListener({
        try {
            cont.resume(get())
        } catch (e: ExecutionException) {
            cont.resumeWithException(e.cause ?: e)
        } catch (e: Exception) {
            cont.resumeWithException(e)
        }
    }, Runnable::run)
    cont.invokeOnCancellation { cancel(false) }
}
