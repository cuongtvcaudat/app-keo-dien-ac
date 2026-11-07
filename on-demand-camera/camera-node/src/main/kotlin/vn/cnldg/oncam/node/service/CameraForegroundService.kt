package vn.cnldg.oncam.node.service

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import vn.cnldg.oncam.node.broker.EmbeddedBroker
import vn.cnldg.oncam.node.camera.CameraController
import vn.cnldg.oncam.node.config.NodeConfigRepository
import vn.cnldg.oncam.node.core.BrokerMode
import vn.cnldg.oncam.node.core.LocationTracker
import vn.cnldg.oncam.node.core.NodeController
import vn.cnldg.oncam.node.core.RecordingStore
import vn.cnldg.oncam.node.core.StreamManager
import vn.cnldg.oncam.node.core.TailscaleIp
import vn.cnldg.oncam.node.log.TimberLogSink
import vn.cnldg.oncam.node.rtc.NodeRtc
import vn.cnldg.oncam.node.system.AndroidLocationSource
import vn.cnldg.oncam.node.system.DeviceInfoReader
import vn.cnldg.oncam.node.system.NetworkMonitor
import vn.cnldg.oncam.rtc.AudioRouting
import vn.cnldg.oncam.rtc.CameraRtc
import vn.cnldg.oncam.rtc.RtcEngine
import vn.cnldg.oncam.shared.protocol.CameraState
import vn.cnldg.oncam.shared.rtc.TailscaleAddress
import vn.cnldg.oncam.shared.security.StreamAuth

/**
 * Foreground Service của camera node — chạy thường trực ở chế độ "nghe lệnh".
 *
 * Thiết kế theo ràng buộc Android 14+ (spec mục 4.1):
 * - Chỉ một service; khi có lệnh START_STREAM, chính service này mở camera (CameraX gắn vào vòng đời service),
 *   không start service mới.
 * - Loại `camera` chỉ được dùng khi service được khởi động lúc app đang hiển thị ([EXTRA_FROM_UI]).
 *   Khởi động lại từ nền (START_STICKY, khởi động máy) → chỉ dùng `specialUse`: vẫn nghe lệnh được,
 *   nhưng chưa mở được camera cho tới khi người dùng mở app (MainActivity gửi lại [EXTRA_FROM_UI]).
 * - Giữ partial WakeLock để ping MQTT đúng giờ khi màn hình tắt (máy camera luôn cắm sạc).
 */
@AndroidEntryPoint
class CameraForegroundService : LifecycleService() {

    @Inject lateinit var configRepository: NodeConfigRepository

    @Inject lateinit var runtime: NodeRuntime

    @Inject lateinit var deviceInfo: DeviceInfoReader

    @Inject lateinit var notifications: NotificationHelper

    @Inject lateinit var networkMonitor: NetworkMonitor

    private val work = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var controller: NodeController? = null
    private var cameraController: CameraController? = null
    private var lastFromUi = false
    private var rtcEngine: RtcEngine? = null
    private var cameraRtc: CameraRtc? = null
    private var starting = false
    private var observers: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        notifications.ensureChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_STOP) {
            stopNode()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_STOP_CAMERA) {
            val c = controller
            if (c == null) {
                stopSelf()
                return START_NOT_STICKY
            }
            work.launch { c.stopCamera() }
            return START_STICKY
        }
        val fromUi = intent?.getBooleanExtra(EXTRA_FROM_UI, false) == true
        lastFromUi = fromUi
        if (!enterForeground(fromUi)) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (controller == null && !starting) startNode()
        return START_STICKY
    }

    override fun onDestroy() {
        observers?.cancel()
        val c = controller
        controller = null
        if (c != null) {
            runtime.detach(c)
            // Bị huỷ ngoài ý muốn: cố publish trạng thái offline trong tối đa 3 giây.
            runBlocking { withTimeoutOrNull(STOP_TIMEOUT_MS) { c.stop() } }
        }
        work.cancel()
        releaseRtc()
        cameraController?.release()
        cameraController = null
        releaseWakeLock()
        super.onDestroy()
    }

    private fun enterForeground(fromUi: Boolean): Boolean {
        val state = controller?.state?.value
        val notification = notifications.buildOngoing(notifications.statusText(state), state?.camera?.state?.isOn() == true)
        // Android 11+: quyền camera/micro "khi đang dùng" chỉ có khi service được (khởi động lại) lúc app đang hiển thị.
        val whileInUse = fromUi || Build.VERSION.SDK_INT < Build.VERSION_CODES.R
        val cameraUsable = granted(Manifest.permission.CAMERA) && whileInUse
        val micUsable = granted(Manifest.permission.RECORD_AUDIO) && whileInUse
        val locationUsable = runtime.locationWanted && whileInUse &&
            (granted(Manifest.permission.ACCESS_FINE_LOCATION) || granted(Manifest.permission.ACCESS_COARSE_LOCATION))

        // Thử từ đủ quyền nhất tới ít nhất: + vị trí → camera + micro → chỉ camera → chỉ nghe lệnh.
        val full = Types(cameraUsable, micUsable, locationUsable)
        val attempts = listOf(full, full.copy(location = false), full.copy(location = false, mic = false), Types())
            .distinct()
        for (t in attempts) {
            if (tryStartForeground(notification, t)) {
                runtime.setCameraAccess(t.camera)
                runtime.setMicAccess(t.mic)
                runtime.setLocationAccess(t.location)
                return true
            }
        }
        notifications.showCannotStart()
        return false
    }

    /** Các loại foreground service xin cùng lúc. */
    private data class Types(val camera: Boolean = false, val mic: Boolean = false, val location: Boolean = false)

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun tryStartForeground(notification: android.app.Notification, t: Types): Boolean =
        try {
            ServiceCompat.startForeground(this, NotificationHelper.NOTIFICATION_ID, notification, foregroundTypes(t))
            true
        } catch (e: Exception) {
            // SecurityException (thiếu quyền cho loại camera/micro/vị trí) hoặc ForegroundServiceStartNotAllowedException.
            Timber.w(e, "startForeground thất bại (%s)", t)
            false
        }

    private fun foregroundTypes(t: Types): Int {
        var types = 0
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (t.camera) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            if (t.mic) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        }
        // Loại "location" có từ Android 10.
        if (t.location && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        }
        return types
    }

    private fun startNode() {
        starting = true
        configRepository.nodeEnabled = true
        work.launch {
            val config = configRepository.current()
            if (config != null && config.shareLocation != runtime.locationWanted) {
                // Lần đầu biết cấu hình bật vị trí: nâng service lên loại "location" (nếu đang ở màn hình app).
                runtime.locationWanted = config.shareLocation
                withContext(Dispatchers.Main) { enterForeground(lastFromUi) }
            }
            if (config == null || config.problems().isNotEmpty()) {
                Timber.w("Chưa có cấu hình hợp lệ, dừng service")
                withContext(Dispatchers.Main) {
                    starting = false
                    stopSelf()
                }
                return@launch
            }
            val broker = if (config.brokerMode == BrokerMode.EMBEDDED) {
                EmbeddedBroker(File(noBackupFilesDir, "moquette"), TimberLogSink)
            } else {
                null
            }
            val cam = CameraController(applicationContext, this@CameraForegroundService, config.orientation, TimberLogSink)
            // WebRTC (Phase 7): máy không nạp được thư viện thì vẫn chạy MJPEG như bản 0.6.
            val micAllowed = { runtime.micAccess.value && granted(Manifest.permission.RECORD_AUDIO) }
            val engine = RtcEngine.create(applicationContext, TimberLogSink, forViewer = false)
            val cameraRtc = engine?.let {
                CameraRtc(
                    engine = it,
                    scope = work,
                    keepCandidate = TailscaleAddress::isTailscale,
                    micAllowed = micAllowed,
                    audioRouting = AudioRouting(applicationContext, maxVolume = true),
                    log = TimberLogSink,
                )
            }
            cameraRtc?.let { r -> cam.rawFrameSink = r::onFrame }
            val streams = StreamManager(
                camera = cam,
                auth = StreamAuth(config.secret, config.deviceId),
                scope = work,
                addressProvider = TailscaleIp::find,
                cameraAllowed = { runtime.cameraAccess.value },
                deviceInfo = deviceInfo,
                log = TimberLogSink,
                rtc = cameraRtc?.let { NodeRtc(it, micAllowed) },
                // Video lưu trong thư mục riêng của app (không cần quyền bộ nhớ), giữ tối đa 2 GB.
                recordings = RecordingStore(
                    File(getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: File(filesDir, "videos"), "OnCam"),
                ),
                audioAllowed = micAllowed,
            )
            // Chỉ xem WebRTC → không cần nén JPEG đủ số hình/giây (giữ 1 khung/giây cho chụp ảnh): máy đỡ nóng.
            // JPEG đủ tốc độ chỉ khi có người xem MJPEG (hoặc chưa ai xem WebRTC); đang canh chuyển động mà không ai
            // xem thì 1 khung/giây là đủ (ảnh chụp, xem thử) → đỡ nóng máy khi camera chạy cả ngày.
            cam.fullRateJpeg = {
                streams.mjpegClients() > 0 || ((cameraRtc?.sessionCount?.value ?: 0) == 0 && !streams.motionArmed())
            }
            // Vị trí (nếu bật chia sẻ): được lấy khi service có loại "location" hoặc người dùng chọn "Luôn cho phép".
            val tracker = if (config.shareLocation) {
                LocationTracker(
                    source = AndroidLocationSource(applicationContext) {
                        runtime.locationAccess.value || granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                    },
                    scope = work,
                    intervalMs = config.locationIntervalMin.takeIf { it > 0 }?.let { it * 60_000L },
                    log = TimberLogSink,
                )
            } else {
                null
            }
            val c = NodeController(
                config = config,
                scope = work,
                deviceInfo = deviceInfo,
                addressProvider = TailscaleIp::find,
                broker = broker,
                log = TimberLogSink,
                camera = streams,
                location = tracker,
            )
            withContext(Dispatchers.Main) {
                controller = c
                cameraController = cam
                rtcEngine = engine
                this@CameraForegroundService.cameraRtc = cameraRtc
                starting = false
                runtime.attach(c)
                acquireWakeLock()
                observers = lifecycleScope.launch {
                    launch {
                        c.state.map { notifications.statusText(it) to it.camera.state.isOn() }
                            .distinctUntilChanged()
                            .collect { (text, cameraOn) -> notifications.updateOngoing(text, cameraOn) }
                    }
                    launch { networkMonitor.changes().collect { c.onNetworkChanged() } }
                }
            }
            c.start()
            Timber.i("Camera node đã khởi động: %s", config)
        }
    }

    private fun stopNode() {
        configRepository.nodeEnabled = false
        observers?.cancel()
        observers = null
        val c = controller
        controller = null
        c?.let { runtime.detach(it) }
        work.launch {
            c?.stop()
            withContext(Dispatchers.Main) {
                releaseRtc()
                releaseWakeLock()
                ServiceCompat.stopForeground(this@CameraForegroundService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun releaseRtc() {
        cameraController?.rawFrameSink = null
        cameraRtc?.dispose()
        cameraRtc = null
        rtcEngine?.dispose()
        rtcEngine = null
    }

    // Không đặt thời hạn: máy camera cắm sạc 24/7 và cần CPU thức để ping MQTT đúng giờ.
    // WakeLock được nhả khi dừng node hoặc service bị huỷ.
    @SuppressLint("WakelockTimeout")
    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        wakeLock = getSystemService(PowerManager::class.java)
            ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
            ?.apply {
                setReferenceCounted(false)
                acquire()
            }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    companion object {
        private const val ACTION_START = "vn.cnldg.oncam.node.action.START"
        private const val ACTION_STOP = "vn.cnldg.oncam.node.action.STOP"
        private const val ACTION_STOP_CAMERA = "vn.cnldg.oncam.node.action.STOP_CAMERA"
        private const val EXTRA_FROM_UI = "from_ui"
        private const val WAKE_LOCK_TAG = "OnCam:node"
        private const val STOP_TIMEOUT_MS = 3_000L

        /**
         * Khởi động (hoặc làm mới) service.
         * @param fromUi true khi gọi từ màn hình đang hiển thị → được dùng loại `camera`.
         * @return false nếu hệ thống không cho khởi động (vd. từ nền trên Android 12+).
         */
        fun start(context: Context, fromUi: Boolean): Boolean {
            val intent = Intent(context, CameraForegroundService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_FROM_UI, fromUi)
            return try {
                ContextCompat.startForegroundService(context, intent)
                true
            } catch (e: Exception) {
                Timber.w(e, "Không khởi động được camera node")
                false
            }
        }

        /** Intent tắt camera (giữ node chạy) cho nút "Tắt camera" trên notification. */
        fun stopCameraIntent(context: Context): Intent =
            Intent(context, CameraForegroundService::class.java).setAction(ACTION_STOP_CAMERA)

        /** Intent dừng node (dùng cho nút trên notification và màn hình). */
        fun stopIntent(context: Context): Intent =
            Intent(context, CameraForegroundService::class.java).setAction(ACTION_STOP)

        /** Dừng node từ màn hình. */
        fun stop(context: Context) {
            try {
                context.startService(stopIntent(context))
            } catch (e: Exception) {
                Timber.w(e, "Không gửi được lệnh dừng")
            }
        }
    }
}

private fun CameraState.isOn() = this == CameraState.STREAMING || this == CameraState.STARTING
