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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import vn.cnldg.oncam.shared.protocol.StreamQuality
import vn.cnldg.oncam.viewer.mqtt.CameraEntry
import vn.cnldg.oncam.viewer.mqtt.CommandSender
import vn.cnldg.oncam.viewer.ui.AddCameraScreen
import vn.cnldg.oncam.viewer.ui.CameraActions
import vn.cnldg.oncam.viewer.ui.HomeScreen
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            OnCamViewerTheme { ViewerApp() }
        }
    }

    override fun onStart() {
        super.onStart()
        sender.connectAll()
    }

    override fun onStop() {
        // Xoay màn hình không tính là rời app.
        if (!isChangingConfigurations) sender.onAppBackground()
        super.onStop()
    }
}

/** Màn hình đang mở. */
private sealed interface Screen {
    data object Home : Screen
    data object Settings : Screen
    data class Edit(val deviceId: String?) : Screen
}

@Composable
private fun ViewerApp(vm: ViewerViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val cameras by vm.cameras.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val lastSnapshot by vm.lastSnapshot.collectAsStateWithLifecycle()
    val idleTimeoutSec by vm.idleTimeoutSec.collectAsStateWithLifecycle()
    val stopOnLeave by vm.stopOnLeave.collectAsStateWithLifecycle()
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    var screenName by rememberSaveable { mutableStateOf(HOME) }
    val screen: Screen = when (screenName) {
        SETTINGS -> Screen.Settings
        EDIT -> Screen.Edit(editing)
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
            }
            if (text != null) snackbar.showSnackbar(text)
        }
    }

    // Android 9 trở xuống cần quyền ghi bộ nhớ để lưu ảnh vào Thư viện.
    var pendingSnapshot by remember { mutableStateOf<CameraEntry?>(null) }
    val storagePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val entry = pendingSnapshot
        pendingSnapshot = null
        if (granted && entry != null) vm.snapshot(entry)
    }

    val actions = remember(vm) {
        object : CameraActions {
            override fun toggleCamera(entry: CameraEntry) = vm.toggleCamera(entry)
            override fun switchCamera(entry: CameraEntry) = vm.switchCamera(entry)
            override fun toggleFlash(entry: CameraEntry) = vm.toggleFlash(entry)
            override fun setQuality(entry: CameraEntry, quality: StreamQuality) = vm.setQuality(entry, quality)
            override fun snapshot(entry: CameraEntry) {
                val needsPermission = Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
                    ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
                    PackageManager.PERMISSION_GRANTED
                if (needsPermission) {
                    pendingSnapshot = entry
                    storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                } else {
                    vm.snapshot(entry)
                }
            }
            override fun edit(entry: CameraEntry) {
                editing = entry.saved.deviceId
                screenName = EDIT
            }
            override fun delete(entry: CameraEntry) = vm.removeCamera(entry.saved.deviceId)
        }
    }

    if (screen != Screen.Home) BackHandler { screenName = HOME }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        val modifier = Modifier.padding(padding)
        when (screen) {
            Screen.Home -> HomeScreen(
                cameras = cameras,
                busy = busy,
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
        }
    }
}

private const val HOME = "home"
private const val SETTINGS = "settings"
private const val EDIT = "edit"
