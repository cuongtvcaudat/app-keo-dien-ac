package vn.cnldg.oncam.viewer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import java.util.Locale
import vn.cnldg.oncam.shared.protocol.CameraState
import vn.cnldg.oncam.shared.protocol.StreamQuality
import vn.cnldg.oncam.viewer.R
import vn.cnldg.oncam.viewer.mqtt.CameraEntry
import vn.cnldg.oncam.viewer.stream.MjpegView
import vn.cnldg.oncam.viewer.stream.StreamPhase
import vn.cnldg.oncam.viewer.stream.StreamState

/**
 * Xem trực tiếp toàn màn hình (spec Phase 5): video MJPEG + nút điều khiển đè lên video.
 * Chạm vào video để ẩn/hiện nút. Giữ màn hình sáng trong lúc xem.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LiveViewScreen(
    entry: CameraEntry?,
    name: String,
    stream: StreamState,
    busy: Boolean,
    onBack: () -> Unit,
    onToggleCamera: () -> Unit,
    onSwitchCamera: () -> Unit,
    onToggleFlash: () -> Unit,
    onQuality: (StreamQuality) -> Unit,
    onSnapshot: () -> Unit,
) {
    val context = LocalContext.current
    val view = LocalView.current
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
    var controlsVisible by rememberSaveable { mutableStateOf(true) }

    val status = entry?.view?.status
    val online = entry?.view?.online == true
    val camState = if (online) status?.cameraState ?: CameraState.OFF else CameraState.OFF
    val streaming = camState == CameraState.STREAMING
    val playing = stream.phase == StreamPhase.PLAYING

    MjpegView(
        state = stream,
        modifier = Modifier
            .fillMaxSize()
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                controlsVisible = !controlsVisible
            },
    ) {
        // Thông báo giữa màn hình khi chưa có hình.
        if (!playing) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.padding(24.dp),
            ) {
                val message = when {
                    !online -> stringResource(R.string.live_offline)
                    camState == CameraState.OFF || camState == CameraState.ERROR -> stringResource(R.string.live_camera_off)
                    stream.phase == StreamPhase.RECONNECTING -> stringResource(R.string.live_reconnecting)
                    else -> stringResource(R.string.live_connecting)
                }
                if (online && camState != CameraState.OFF && camState != CameraState.ERROR) CircularProgressIndicator()
                Text(message, color = Color.White, style = MaterialTheme.typography.bodyLarge)
                status?.error?.takeIf { camState != CameraState.STREAMING }?.let {
                    Text(codeText(context, it), color = Color.White, style = MaterialTheme.typography.bodySmall)
                }
                if (online && (camState == CameraState.OFF || camState == CameraState.ERROR)) {
                    Button(onClick = onToggleCamera, enabled = !busy) { Text(stringResource(R.string.action_camera_on)) }
                }
            }
        }

        if (controlsVisible || !playing) {
            Column(modifier = Modifier.fillMaxSize().safeDrawingPadding(), verticalArrangement = Arrangement.SpaceBetween) {
                // Thanh trên: quay lại, tên camera, thông số luồng.
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color.Black.copy(alpha = 0.5f))
                        .padding(horizontal = 4.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onBack) { Text(stringResource(R.string.action_back), color = Color.White) }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(name, color = Color.White, style = MaterialTheme.typography.titleMedium)
                        if (playing) {
                            Text(
                                streamInfo(stream, status?.viewers ?: 0),
                                color = Color.White,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }

                // Thanh dưới: nút điều khiển.
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color.Black.copy(alpha = 0.5f))
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = onToggleCamera, enabled = online && !busy) {
                            Text(
                                stringResource(if (streaming) R.string.action_camera_off else R.string.action_camera_on),
                                color = Color.White,
                            )
                        }
                        OutlinedButton(onClick = onSwitchCamera, enabled = online && !busy) {
                            Text(stringResource(R.string.action_switch_camera), color = Color.White)
                        }
                        OutlinedButton(onClick = onToggleFlash, enabled = streaming && !busy) {
                            Text(
                                stringResource(if (status?.flashOn == true) R.string.action_flash_off else R.string.action_flash_on),
                                color = Color.White,
                            )
                        }
                        Button(onClick = onSnapshot, enabled = playing && !busy) {
                            Text(stringResource(R.string.action_snapshot))
                        }
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        StreamQuality.entries.forEach { q ->
                            val label = stringResource(
                                when (q) {
                                    StreamQuality.LOW -> R.string.quality_low
                                    StreamQuality.MEDIUM -> R.string.quality_medium
                                    StreamQuality.HIGH -> R.string.quality_high
                                },
                            )
                            if (status?.quality == q) {
                                Button(onClick = {}, enabled = online && !busy) { Text(label) }
                            } else {
                                OutlinedButton(onClick = { onQuality(q) }, enabled = online && !busy) {
                                    Text(label, color = Color.White)
                                }
                            }
                        }
                    }
                    Text(stringResource(R.string.live_no_audio), color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun streamInfo(stream: StreamState, viewers: Int): String {
    val latency = stream.latencyMs?.let { String.format(Locale.US, "%.1f", it / 1000.0) }
    return if (latency != null) {
        stringResource(R.string.live_info_latency, stream.fps ?: 0, latency, viewers)
    } else {
        stringResource(R.string.live_info, stream.fps ?: 0, viewers)
    }
}
