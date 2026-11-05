package vn.cnldg.oncam.node

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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import vn.cnldg.oncam.node.config.ConfigLoad
import vn.cnldg.oncam.node.service.CameraForegroundService
import vn.cnldg.oncam.node.service.NodeRuntime
import vn.cnldg.oncam.node.ui.BatteryGuideScreen
import vn.cnldg.oncam.node.ui.NodeViewModel
import vn.cnldg.oncam.node.ui.SetupScreen
import vn.cnldg.oncam.node.ui.StatusScreen
import vn.cnldg.oncam.node.ui.theme.OnCamNodeTheme

/**
 * Màn hình chính của camera-node: Setup (lần đầu) → Trạng thái; thêm màn hình hướng dẫn chống tắt app.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var runtime: NodeRuntime

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            OnCamNodeTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    NodeApp(modifier = Modifier.padding(innerPadding))
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // Service đang chạy nhưng bị khởi động lại từ nền (mất quyền camera) → app đang hiển thị,
        // gửi lại lệnh start để service nâng lên loại "camera".
        if (runtime.controller.value != null && !runtime.cameraAccess.value && hasCameraPermission()) {
            CameraForegroundService.start(this, fromUi = true)
        }
    }

    private fun hasCameraPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
}

private enum class Screen { STATUS, SETUP, GUIDE }

@Composable
private fun NodeApp(modifier: Modifier = Modifier, vm: NodeViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val config by vm.config.collectAsStateWithLifecycle()
    val autoStart by vm.autoStart.collectAsStateWithLifecycle()
    val running by vm.running.collectAsStateWithLifecycle()
    val nodeState by vm.nodeState.collectAsStateWithLifecycle()
    val cameraAccess by vm.cameraAccess.collectAsStateWithLifecycle()
    val selfTesting by vm.selfTesting.collectAsStateWithLifecycle()
    var screen by rememberSaveable { mutableStateOf(Screen.STATUS) }

    // Xin quyền Camera + Thông báo (spec mục 7.1 bước 3) rồi mới khởi động service.
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        CameraForegroundService.start(context, fromUi = true)
    }
    val startNode: () -> Unit = {
        val needed = buildList {
            add(Manifest.permission.CAMERA)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
        }.filter { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }
        if (needed.isEmpty()) {
            CameraForegroundService.start(context, fromUi = true)
        } else {
            permissionLauncher.launch(needed.toTypedArray())
        }
    }

    if (screen != Screen.STATUS) BackHandler { screen = Screen.STATUS }

    when (val load = config) {
        ConfigLoad.Loading -> Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        ConfigLoad.Missing -> SetupScreen(
            initial = null,
            initialAutoStart = autoStart,
            generateSecret = vm::generateSecret,
            onSave = { cfg, auto -> vm.save(cfg, auto) { screen = Screen.STATUS } },
            onCancel = null,
            modifier = modifier,
        )
        is ConfigLoad.Loaded -> when (screen) {
            Screen.SETUP -> SetupScreen(
                initial = load.config,
                initialAutoStart = autoStart,
                generateSecret = vm::generateSecret,
                onSave = { cfg, auto -> vm.save(cfg, auto) { screen = Screen.STATUS } },
                onCancel = { screen = Screen.STATUS },
                modifier = modifier,
            )
            Screen.GUIDE -> BatteryGuideScreen(onBack = { screen = Screen.STATUS }, modifier = modifier)
            Screen.STATUS -> StatusScreen(
                config = load.config,
                running = running,
                state = nodeState,
                cameraAccess = cameraAccess,
                selfTesting = selfTesting,
                onStart = startNode,
                onStop = { CameraForegroundService.stop(context) },
                onSelfTest = vm::selfTest,
                onOpenGuide = { screen = Screen.GUIDE },
                onEditConfig = { screen = Screen.SETUP },
                onStartCamera = vm::startCameraTest,
                onStopCamera = vm::stopCamera,
                latestFrame = vm::latestFrame,
                modifier = modifier,
            )
        }
    }
}
