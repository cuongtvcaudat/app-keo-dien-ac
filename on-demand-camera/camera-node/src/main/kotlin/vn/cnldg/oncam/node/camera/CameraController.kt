package vn.cnldg.oncam.node.camera

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Size
import android.view.Surface
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.Observer
import com.google.common.util.concurrent.ListenableFuture
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
import vn.cnldg.oncam.node.core.MountOrientation
import vn.cnldg.oncam.node.core.OpenResult
import vn.cnldg.oncam.shared.LogSink
import vn.cnldg.oncam.shared.protocol.CameraCodes
import vn.cnldg.oncam.shared.protocol.CameraFacing
import vn.cnldg.oncam.shared.protocol.StreamQuality
import androidx.camera.core.CameraState as XCameraState

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
            analysis.setAnalyzer(analysisExecutor, FrameEncoder(quality, sink))

            try {
                detachObserver()
                p.unbindAll()
                val cam = p.bindToLifecycle(lifecycleOwner, sel, analysis)
                camera = cam
                val observer = Observer<XCameraState> { state -> onCameraState(state) }
                cam.cameraInfo.cameraState.observe(lifecycleOwner, observer)
                stateObserver = observer
                log.log(LogSink.Level.INFO, "Đã mở camera $facing ở $quality", null)
                OpenResult.Opened(hasFlash = cam.cameraInfo.hasFlashUnit())
            } catch (e: Exception) {
                log.log(LogSink.Level.WARN, "Không mở được camera $facing", e)
                p.unbindAll()
                camera = null
                OpenResult.Failed(CameraCodes.OPEN_FAILED)
            }
        }

    override suspend fun setTorch(on: Boolean): Boolean = withContext(Dispatchers.Main) {
        val cam = camera ?: return@withContext false
        if (!cam.cameraInfo.hasFlashUnit()) return@withContext false
        runCatching { cam.cameraControl.enableTorch(on).await() }.isSuccess
    }

    override suspend fun close() = withContext(Dispatchers.Main) {
        detachObserver()
        provider?.unbindAll()
        camera = null
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

    /** Giới hạn FPS rồi mã hoá JPEG; luôn đóng ImageProxy để CameraX gửi khung tiếp. */
    private class FrameEncoder(
        private val quality: StreamQuality,
        private val sink: (ByteArray) -> Unit,
    ) : ImageAnalysis.Analyzer {
        private val intervalNs = 1_000_000_000L / quality.fps
        private var lastNs = 0L

        override fun analyze(image: ImageProxy) {
            try {
                val now = System.nanoTime()
                if (now - lastNs < intervalNs) return
                lastNs = now
                sink(YuvJpeg.encode(image, quality.jpegQuality))
            } catch (_: Exception) {
                // Bỏ khung lỗi (hiếm), không làm dừng camera.
            } finally {
                image.close()
            }
        }
    }
}

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
