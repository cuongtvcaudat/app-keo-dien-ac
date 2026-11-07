package vn.cnldg.oncam.viewer

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.launch
import vn.cnldg.oncam.shared.protocol.MotionSensitivity
import vn.cnldg.oncam.shared.protocol.StreamQuality
import vn.cnldg.oncam.viewer.mqtt.CameraEntry
import vn.cnldg.oncam.viewer.mqtt.CommandSender
import vn.cnldg.oncam.viewer.ui.AddCameraScreen
import vn.cnldg.oncam.viewer.ui.CameraActions
import vn.cnldg.oncam.viewer.ui.HomeScreen
import vn.cnldg.oncam.viewer.ui.LiveViewScreen
import vn.cnldg.oncam.viewer.ui.SettingsScreen
import vn.cnldg.oncam.viewer.ui.UiMessage
import vn.cnldg.oncam.viewer.ui.ViewerViewModel
import vn.cnldg.oncam.viewer.ui.eventText
import vn.cnldg.oncam.viewer.ui.resultText
import vn.cnldg.oncam.viewer.ui.theme.OnCamViewerTheme

/**
 * Màn hình chính của app Xem. Kết nối camera khi app hiện lên, ngắt (và tắt camera do mình bật)
 * khi app bị ẩn — xem [CommandSender].
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var sender: CommandSender

    private val vm: ViewerViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            OnCamViewerTheme { ViewerApp(vm) }
        }
    }

    override fun onStart() {
        super.onStart()
        sender.connectAll()
        vm.resumeLive()
    }

    override fun onStop() {
        // Xoay màn hình không tính là rời app.
        if (!isChangingConfigurations) {
            vm.pauseLive()
            sender.onAppBackground()
        }
        super.onStop()
    }
}

/** Màn hình đang mở. */
private sealed interface Screen {
    data object Home : Screen
    data object Settings : Screen
    data class Edit(val deviceId: String?) : Screen
    data class Live(val deviceId: String) : Screen
}

@Composable
private fun ViewerApp(vm: ViewerViewModel) {
    val context = LocalContext.current
    val cameras by vm.cameras.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val lastSnapshot by vm.lastSnapshot.collectAsStateWithLifecycle()
    val idleTimeoutSec by vm.idleTimeoutSec.collectAsStateWithLifecycle()
    val stopOnLeave by vm.stopOnLeave.collectAsStateWithLifecycle()
    val streamState by vm.streamState.collectAsStateWithLifecycle()
    val liveDeviceId by vm.liveDeviceId.collectAsStateWithLifecycle()
    val liveRtc by vm.liveRtc.collectAsStateWithLifecycle()
    val downloading by vm.downloading.collectAsStateWithLifecycle()
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    var screenName by rememberSaveable { mutableStateOf(HOME) }
    val live = liveDeviceId
    val screen: Screen = when {
        live != null -> Screen.Live(live)
        screenName == SETTINGS -> Screen.Settings
        screenName == EDIT -> Screen.Edit(editing)
        else -> Screen.Home
    }
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        vm.messages.collect { msg ->
            val text = when (msg) {
                is UiMessage.CommandFailed -> resultText(context, msg.result)
                is UiMessage.CameraEvent -> eventText(context, msg.cameraName, msg.event.event, msg.event.message)
                UiMessage.SnapshotSaved -> context.getString(R.string.snapshot_saved)
                UiMessage.SnapshotFailed -> context.getString(R.string.snapshot_failed)
                is UiMessage.ClockSkew -> context.getString(R.string.clock_skew, msg.cameraName, msg.seconds)
                is UiMessage.VideosSaved -> {
                    val mb = String.format(Locale.US, "%.1f", msg.bytes / 1_048_576.0)
                    if (msg.failed > 0) {
                        context.getString(R.string.videos_saved_some_failed, msg.count, mb, msg.failed)
                    } else {
                        context.getString(R.string.videos_saved, msg.count, mb)
                    }
                }
                UiMessage.NoNewVideos -> context.getString(R.string.videos_none_new)
                UiMessage.VideosUnreachable -> context.getString(R.string.videos_unreachable)
            }
            if (text != null) snackbar.showSnackbar(text)
        }
    }

    // Android 9 trở xuống cần quyền ghi bộ nhớ để lưu ảnh vào Thư viện: giữ việc cần làm, xin quyền, làm tiếp.
    var pendingSave by remember { mutableStateOf<(() -> Unit)?>(null) }
    val storagePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val action = pendingSave
        pendingSave = null
        if (granted) action?.invoke()
    }
    val withStoragePermission: (() -> Unit) -> Unit = { action ->
        val needsPermission = Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
            PackageManager.PERMISSION_GRANTED
        if (needsPermission) {
            pendingSave = action
            storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else {
            action()
        }
    }

    val scope = rememberCoroutineScope()
    // Nói ra loa máy camera (Phase 7): xin quyền micro lúc bấm "Bật nói chuyện".
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            vm.setTalkEnabled(true)
        } else {
            scope.launch { snackbar.showSnackbar(context.getString(R.string.talk_needs_mic)) }
        }
    }
    val enableTalk: (Boolean) -> Unit = { on ->
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (!on || granted) vm.setTalkEnabled(on) else micPermission.launch(Manifest.permission.RECORD_AUDIO)
    }

    val actions = remember(vm) {
        object : CameraActions {
            override fun toggleCamera(entry: CameraEntry) = vm.toggleCamera(entry)
            override fun switchCamera(entry: CameraEntry) = vm.switchCamera(entry)
            override fun toggleFlash(entry: CameraEntry) = vm.toggleFlash(entry)
            override fun setQuality(entry: CameraEntry, quality: StreamQuality) = vm.setQuality(entry, quality)
            override fun snapshot(entry: CameraEntry) = withStoragePermission { vm.snapshot(entry) }
            override fun edit(entry: CameraEntry) {
                editing = entry.saved.deviceId
                screenName = EDIT
            }
            override fun delete(entry: CameraEntry) = vm.removeCamera(entry.saved.deviceId)
            override fun openLive(entry: CameraEntry) = vm.openLive(entry)
            override fun updateLocation(entry: CameraEntry) = vm.updateLocation(entry)
            override fun trackLocation(entry: CameraEntry, minutes: Int) = vm.trackLocation(entry, minutes)
            override fun toggleRecording(entry: CameraEntry) = vm.toggleRecording(entry)
            override fun downloadVideos(entry: CameraEntry) = withStoragePermission { vm.downloadVideos(entry) }
            override fun toggleMotion(entry: CameraEntry) = vm.toggleMotion(entry)
            override fun setMotionSensitivity(entry: CameraEntry, sensitivity: MotionSensitivity) =
                vm.setMotionSensitivity(entry, sensitivity)
        }
    }

    if (screen is Screen.Live) {
        BackHandler { vm.closeLive() }
    } else if (screen != Screen.Home) {
        BackHandler { screenName = HOME }
    }

    if (screen is Screen.Live) {
        val entry = cameras?.firstOrNull { it.saved.deviceId == screen.deviceId }
        // Toàn màn hình, nền đen: không đặt trong Scaffold để video chiếm hết màn hình.
        Box(Modifier.fillMaxSize()) {
            LiveViewScreen(
                entry = entry,
                name = entry?.let(vm::displayName) ?: screen.deviceId,
                stream = streamState,
                busy = screen.deviceId in busy,
                onBack = vm::closeLive,
                onToggleCamera = { entry?.let(vm::toggleCamera) },
                onSwitchCamera = { entry?.let(vm::switchCamera) },
                onToggleFlash = { entry?.let(vm::toggleFlash) },
                onQuality = { q -> entry?.let { vm.setQuality(it, q) } },
                onSnapshot = { entry?.let { e -> withStoragePermission { vm.snapshotFromLive(e) } } },
                rtc = liveRtc,
                egl = vm.rtcEglContext,
                onListen = vm::setListening,
                onTalkEnable = enableTalk,
                onTalk = vm::setTalking,
                onToggleRecording = { entry?.let(vm::toggleRecording) },
            )
            SnackbarHost(snackbar, modifier = Modifier.align(Alignment.Center))
        }
        return
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        val modifier = Modifier.padding(padding)
        when (screen) {
            Screen.Home -> HomeScreen(
                cameras = cameras,
                busy = busy,
                downloading = downloading,
                lastSnapshot = lastSnapshot,
                nameOf = vm::displayName,
                actions = actions,
                onAddCamera = {
                    editing = null
                    screenName = EDIT
                },
                onOpenSettings = { screenName = SETTINGS },
                modifier = modifier,
            )
            Screen.Settings -> SettingsScreen(
                idleTimeoutSec = idleTimeoutSec,
                stopOnLeave = stopOnLeave,
                onIdleTimeout = vm::setIdleTimeoutSec,
                onStopOnLeave = vm::setStopOnLeave,
                onBack = { screenName = HOME },
                modifier = modifier,
            )
            is Screen.Edit -> AddCameraScreen(
                initial = cameras?.firstOrNull { it.saved.deviceId == screen.deviceId }?.saved,
                onSave = { camera -> vm.saveCamera(camera, screen.deviceId) { screenName = HOME } },
                onCancel = { screenName = HOME },
                modifier = modifier,
            )
            is Screen.Live -> Unit // đã vẽ ở trên
        }
    }
}

private const val HOME = "home"
private const val SETTINGS = "settings"
private const val EDIT = "edit"
