package vn.cnldg.oncam.viewer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import vn.cnldg.oncam.shared.protocol.StreamQuality
import vn.cnldg.oncam.viewer.R
import vn.cnldg.oncam.viewer.mqtt.CameraEntry

/** Các thao tác trên một camera. */
interface CameraActions {
    /** Bật/tắt camera. */
    fun toggleCamera(entry: CameraEntry)

    /** Đổi camera trước/sau. */
    fun switchCamera(entry: CameraEntry)

    /** Bật/tắt flash. */
    fun toggleFlash(entry: CameraEntry)

    /** Đổi chất lượng. */
    fun setQuality(entry: CameraEntry, quality: StreamQuality)

    /** Chụp ảnh lưu vào Thư viện. */
    fun snapshot(entry: CameraEntry)

    /** Mở màn hình xem trực tiếp. */
    fun openLive(entry: CameraEntry)

    /** Yêu cầu camera lấy vị trí mới. */
    fun updateLocation(entry: CameraEntry)

    /** Theo dõi vị trí 1 phút/lần trong [minutes] phút (0 = dừng). */
    fun trackLocation(entry: CameraEntry, minutes: Int)

    /** Sửa camera. */
    fun edit(entry: CameraEntry)

    /** Xoá camera. */
    fun delete(entry: CameraEntry)
}

/** Màn hình chính (spec mục 7.2): danh sách camera, trạng thái realtime và nút điều khiển. */
@Composable
fun HomeScreen(
    cameras: List<CameraEntry>?,
    busy: Set<String>,
    lastSnapshot: Map<String, ByteArray>,
    nameOf: (CameraEntry) -> String,
    actions: CameraActions,
    onAddCamera: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            nowMs = System.currentTimeMillis()
            delay(TICK_MS)
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 8.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            TextButton(onClick = onAddCamera) { Text(stringResource(R.string.action_add_camera)) }
            TextButton(onClick = onOpenSettings) { Text(stringResource(R.string.action_settings)) }
        }

        when {
            cameras == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            cameras.isEmpty() -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(stringResource(R.string.empty_title), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.empty_hint), style = MaterialTheme.typography.bodyMedium)
                Button(onClick = onAddCamera) { Text(stringResource(R.string.action_add_camera)) }
            }
            else -> LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(cameras, key = { it.saved.deviceId }) { entry ->
                    CameraCard(
                        entry = entry,
                        name = nameOf(entry),
                        busy = entry.saved.deviceId in busy,
                        nowMs = nowMs,
                        lastSnapshot = lastSnapshot[entry.saved.deviceId],
                        onToggleCamera = { actions.toggleCamera(entry) },
                        onSwitchCamera = { actions.switchCamera(entry) },
                        onToggleFlash = { actions.toggleFlash(entry) },
                        onQuality = { actions.setQuality(entry, it) },
                        onSnapshot = { actions.snapshot(entry) },
                        onOpenLive = { actions.openLive(entry) },
                        onUpdateLocation = { actions.updateLocation(entry) },
                        onTrackLocation = { minutes -> actions.trackLocation(entry, minutes) },
                        onEdit = { actions.edit(entry) },
                        onDelete = { actions.delete(entry) },
                    )
                }
            }
        }
    }
}

private const val TICK_MS = 1_000L
