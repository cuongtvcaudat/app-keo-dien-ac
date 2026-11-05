package vn.cnldg.oncam.viewer.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import vn.cnldg.oncam.shared.mqtt.MqttConnectionState
import vn.cnldg.oncam.shared.protocol.CameraFacing
import vn.cnldg.oncam.shared.protocol.CameraState
import vn.cnldg.oncam.shared.protocol.NetworkType
import vn.cnldg.oncam.shared.protocol.StreamQuality
import vn.cnldg.oncam.viewer.R
import vn.cnldg.oncam.viewer.mqtt.CameraEntry

/**
 * Thẻ một camera: online/offline, trạng thái camera, pin/nhiệt độ/mạng và các nút điều khiển
 * (bật/tắt, đổi camera, flash, chất lượng, chụp ảnh).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CameraCard(
    entry: CameraEntry,
    name: String,
    busy: Boolean,
    nowMs: Long,
    lastSnapshot: ByteArray?,
    onToggleCamera: () -> Unit,
    onSwitchCamera: () -> Unit,
    onToggleFlash: () -> Unit,
    onQuality: (StreamQuality) -> Unit,
    onSnapshot: () -> Unit,
    onOpenLive: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val context = LocalContext.current
    val view = entry.view
    val status = view.status
    val online = view.online
    val camState = if (online) status?.cameraState ?: CameraState.OFF else CameraState.OFF
    val streaming = online && camState == CameraState.STREAMING
    val cameraOn = online && (camState == CameraState.STREAMING || camState == CameraState.STARTING)
    var confirmDelete by remember { mutableStateOf(false) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Text(
                    stringResource(if (online) R.string.online else R.string.offline),
                    color = if (online) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
            if (!online) {
                Text(
                    if (view.status?.online == false && view.connection == MqttConnectionState.Connected) {
                        stringResource(R.string.camera_reports_offline)
                    } else {
                        connectionText(context, view.connection)
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            if (online && status != null) {
                InfoRow(stringResource(R.string.camera_state), cameraStateText(camState, status.viewers))
                InfoRow(
                    stringResource(R.string.camera_lens),
                    stringResource(if (status.currentCamera == CameraFacing.FRONT) R.string.lens_front else R.string.lens_back),
                )
                InfoRow(
                    stringResource(R.string.battery),
                    status.batteryPercent?.let {
                        stringResource(if (status.charging == true) R.string.battery_charging else R.string.battery_value, it)
                    } ?: stringResource(R.string.value_none),
                )
                InfoRow(
                    stringResource(R.string.temperature),
                    status.temperatureC?.let { stringResource(R.string.value_celsius, it) } ?: stringResource(R.string.value_none),
                )
                InfoRow(stringResource(R.string.network), networkText(status.network))
                view.statusReceivedAtMs?.let {
                    InfoRow(stringResource(R.string.updated), stringResource(R.string.seconds_ago, ((nowMs - it) / 1000).coerceAtLeast(0)))
                }
                if (status.cameraReady == false) {
                    Text(stringResource(R.string.code_needs_app_open), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                status.error?.let {
                    Text(codeText(context, it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }

            Button(onClick = onOpenLive, enabled = online, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.action_live))
            }
            OutlinedButton(onClick = onToggleCamera, enabled = online && !busy, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(if (cameraOn) R.string.action_camera_off else R.string.action_camera_on))
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onSwitchCamera, enabled = online && !busy) {
                    Text(stringResource(R.string.action_switch_camera))
                }
                OutlinedButton(onClick = onToggleFlash, enabled = streaming && !busy) {
                    Text(stringResource(if (status?.flashOn == true) R.string.action_flash_off else R.string.action_flash_on))
                }
                OutlinedButton(onClick = onSnapshot, enabled = streaming && !busy) {
                    Text(stringResource(R.string.action_snapshot))
                }
            }
            if (online) {
                Text(stringResource(R.string.quality), style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StreamQuality.entries.forEach { q ->
                        val selected = status?.quality == q
                        if (selected) {
                            Button(onClick = {}, enabled = !busy) { Text(qualityText(q)) }
                        } else {
                            OutlinedButton(onClick = { onQuality(q) }, enabled = !busy) { Text(qualityText(q)) }
                        }
                    }
                }
            }

            lastSnapshot?.let { bytes ->
                remember(bytes) { BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap() }?.let { img ->
                    Text(stringResource(R.string.last_snapshot), style = MaterialTheme.typography.labelLarge)
                    Image(
                        bitmap = img,
                        contentDescription = stringResource(R.string.last_snapshot),
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxWidth().heightIn(max = 240.dp),
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onEdit) { Text(stringResource(R.string.action_edit)) }
                TextButton(onClick = { confirmDelete = true }) { Text(stringResource(R.string.action_delete)) }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.delete_title)) },
            text = { Text(stringResource(R.string.delete_message, name)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    onDelete()
                }) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

@Composable
private fun cameraStateText(state: CameraState, viewers: Int): String = when (state) {
    CameraState.OFF -> stringResource(R.string.cam_off)
    CameraState.STARTING -> stringResource(R.string.cam_starting)
    CameraState.STREAMING -> stringResource(R.string.cam_streaming, viewers)
    CameraState.ERROR -> stringResource(R.string.cam_error)
}

@Composable
private fun qualityText(q: StreamQuality): String = stringResource(
    when (q) {
        StreamQuality.LOW -> R.string.quality_low
        StreamQuality.MEDIUM -> R.string.quality_medium
        StreamQuality.HIGH -> R.string.quality_high
    },
)

@Composable
private fun networkText(network: NetworkType?): String = stringResource(
    when (network) {
        NetworkType.WIFI -> R.string.network_wifi
        NetworkType.CELLULAR -> R.string.network_cellular
        NetworkType.ETHERNET -> R.string.network_ethernet
        NetworkType.OTHER -> R.string.network_other
        NetworkType.NONE, null -> R.string.network_none
    },
)
