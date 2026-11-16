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
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import vn.cnldg.oncam.shared.mqtt.MqttConnectionState
import vn.cnldg.oncam.shared.protocol.CameraCodes
import vn.cnldg.oncam.shared.protocol.CameraFacing
import vn.cnldg.oncam.shared.protocol.CameraState
import vn.cnldg.oncam.shared.protocol.GeoLocation
import vn.cnldg.oncam.shared.protocol.MotionSensitivity
import vn.cnldg.oncam.shared.protocol.MotionStatus
import vn.cnldg.oncam.shared.protocol.NetworkType
import vn.cnldg.oncam.shared.protocol.RecordingStatus
import vn.cnldg.oncam.shared.protocol.Recordings
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
    downloading: Boolean,
    nowMs: Long,
    lastSnapshot: ByteArray?,
    onToggleCamera: () -> Unit,
    onSwitchCamera: () -> Unit,
    onToggleFlash: () -> Unit,
    onQuality: (StreamQuality) -> Unit,
    onSnapshot: () -> Unit,
    onOpenLive: () -> Unit,
    onUpdateLocation: () -> Unit,
    onTrackLocation: (Int) -> Unit,
    onToggleRecording: () -> Unit,
    onDownloadVideos: () -> Unit,
    onToggleMotion: () -> Unit,
    onMotionSensitivity: (MotionSensitivity) -> Unit,
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

            // Vị trí: hiện cả khi camera offline (vị trí cuối đã lưu) để tìm máy khi mất trộm/mất điện.
            val loc = status?.location?.takeIf { online }
            val last = view.lastLocation
            if (loc != null || last != null) {
                LocationBlock(
                    point = last,
                    updating = loc?.updating == true,
                    error = loc?.error,
                    online = online,
                    canUpdate = online && loc != null && !busy,
                    nowSec = nowMs / 1000,
                    onUpdate = onUpdateLocation,
                    canTrack = online && loc?.canTrack == true && !busy,
                    trackingUntil = loc?.trackingUntil,
                    onTrack = onTrackLocation,
                )
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
            // Quay video (camera bản ≥ 0.10 mới gửi recording): clip lưu trên máy camera, dừng xong tự chép về máy này.
            status?.recording?.takeIf { online }?.let { rec ->
                RecordingBlock(
                    rec = rec,
                    nowSec = nowMs / 1000,
                    busy = busy,
                    downloading = downloading,
                    onToggle = onToggleRecording,
                    onDownload = onDownloadVideos,
                )
            }
            // Canh chuyển động (camera ≥ 0.11): có chuyển động thì máy camera tự quay, clip tự chép về máy này.
            status?.motion?.takeIf { online }?.let { motion ->
                MotionBlock(
                    motion = motion,
                    nowSec = nowMs / 1000,
                    busy = busy,
                    onToggle = onToggleMotion,
                    onSensitivity = onMotionSensitivity,
                )
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

/** "vừa xong" / "5 phút trước" / "2 giờ trước" / "3 ngày trước". */
@Composable
private fun ageText(seconds: Long): String {
    val age = seconds.coerceAtLeast(0)
    return when {
        age < 60 -> stringResource(R.string.age_just_now)
        age < 3_600 -> stringResource(R.string.age_minutes, (age / 60).toInt())
        age < 86_400 -> stringResource(R.string.age_hours, (age / 3_600).toInt())
        else -> stringResource(R.string.age_days, (age / 86_400).toInt())
    }
}

/** Canh chuyển động: trạng thái, lần chuyển động gần nhất, nút bật/tắt, độ nhạy. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MotionBlock(
    motion: MotionStatus,
    nowSec: Long,
    busy: Boolean,
    onToggle: () -> Unit,
    onSensitivity: (MotionSensitivity) -> Unit,
) {
    val context = LocalContext.current
    InfoRow(
        stringResource(R.string.motion_title),
        stringResource(if (motion.armed) R.string.motion_on else R.string.motion_off),
    )
    if (motion.armed) {
        Text(stringResource(R.string.motion_hint, motion.clipSeconds), style = MaterialTheme.typography.bodySmall)
    }
    motion.lastMotionAt?.let {
        Text(stringResource(R.string.motion_last, ageText(nowSec - it)), style = MaterialTheme.typography.bodySmall)
    }
    motion.error?.takeIf { !motion.armed }?.let {
        Text(codeText(context, it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    }
    if (motion.armed) {
        Button(onClick = onToggle, enabled = !busy) { Text(stringResource(R.string.action_motion_off)) }
    } else {
        OutlinedButton(onClick = onToggle, enabled = !busy) { Text(stringResource(R.string.action_motion_on)) }
    }
    Text(stringResource(R.string.motion_sensitivity), style = MaterialTheme.typography.labelLarge)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        MotionSensitivity.entries.forEach { s ->
            val label = stringResource(
                when (s) {
                    MotionSensitivity.LOW -> R.string.quality_low
                    MotionSensitivity.MEDIUM -> R.string.quality_medium
                    MotionSensitivity.HIGH -> R.string.quality_high
                },
            )
            if (motion.sensitivity == s) {
                Button(onClick = {}, enabled = !busy) { Text(label) }
            } else {
                OutlinedButton(onClick = { onSensitivity(s) }, enabled = !busy) { Text(label) }
            }
        }
    }
}

/** Đang quay bao lâu + nút Quay/Dừng + Tải video về máy. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RecordingBlock(
    rec: RecordingStatus,
    nowSec: Long,
    busy: Boolean,
    downloading: Boolean,
    onToggle: () -> Unit,
    onDownload: () -> Unit,
) {
    val context = LocalContext.current
    if (rec.active) {
        val elapsed = durationText(recordingElapsedSec(rec, nowSec))
        val max = durationText((rec.maxSeconds ?: Recordings.DEFAULT_MAX_SECONDS).toLong())
        Text(
            stringResource(if (rec.audio == false) R.string.recording_active_no_audio else R.string.recording_active, elapsed, max),
            color = MaterialTheme.colorScheme.error,
            fontWeight = FontWeight.SemiBold,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
    rec.error?.takeIf { !rec.active }?.let {
        Text(codeText(context, it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    }
    if (downloading) Text(stringResource(R.string.videos_downloading), style = MaterialTheme.typography.bodySmall)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (rec.active) {
            Button(
                onClick = onToggle,
                enabled = !busy,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
            ) {
                Text(stringResource(R.string.action_record_stop, durationText(recordingElapsedSec(rec, nowSec))))
            }
        } else {
            OutlinedButton(onClick = onToggle, enabled = !busy) { Text(stringResource(R.string.action_record)) }
        }
        OutlinedButton(onClick = onDownload, enabled = !downloading) { Text(stringResource(R.string.action_download_videos)) }
    }
}

/** Vị trí máy camera + nút mở Google Maps / cập nhật. */
@Composable
private fun LocationBlock(
    point: GeoLocation?,
    updating: Boolean,
    error: String?,
    online: Boolean,
    canUpdate: Boolean,
    nowSec: Long,
    onUpdate: () -> Unit,
    canTrack: Boolean,
    trackingUntil: Long?,
    onTrack: (Int) -> Unit,
) {
    val uri = LocalUriHandler.current
    val trackLeftMin = trackingUntil?.let { ((it - nowSec + 59) / 60).coerceAtLeast(0).toInt() }?.takeIf { it > 0 }
    if (point != null) {
        InfoRow(stringResource(R.string.location), point.coordinates())
        val age = ageText(nowSec - point.timestamp)
        val detail = listOfNotNull(
            point.accuracyM?.let { stringResource(R.string.location_accuracy, it.toInt()) },
            stringResource(if (point.provider == "gps") R.string.location_source_gps else R.string.location_source_network),
            age,
        ).joinToString(" • ")
        Text(
            if (online) detail else stringResource(R.string.location_last_known, detail),
            style = MaterialTheme.typography.bodySmall,
        )
    }
    if (online && trackLeftMin != null) {
        Text(
            stringResource(R.string.location_tracking, trackLeftMin),
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.bodySmall,
        )
    }
    when {
        updating -> Text(stringResource(R.string.location_updating), style = MaterialTheme.typography.bodySmall)
        error != null -> Text(locationErrorText(error), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        point == null -> Text(stringResource(R.string.location_none), style = MaterialTheme.typography.bodySmall)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (point != null) {
            OutlinedButton(onClick = { runCatching { uri.openUri(point.mapsUrl()) } }) {
                Text(stringResource(R.string.action_open_maps))
            }
        }
        if (online) {
            OutlinedButton(onClick = onUpdate, enabled = canUpdate && !updating) {
                Text(stringResource(R.string.action_update_location))
            }
        }
    }
    // Theo dõi: GPS máy camera chỉ chạy dày (1 phút/lần) trong thời gian này rồi tự dừng.
    if (online && canTrack) {
        if (trackLeftMin != null) {
            OutlinedButton(onClick = { onTrack(0) }) { Text(stringResource(R.string.action_track_stop)) }
        } else {
            OutlinedButton(onClick = { onTrack(TRACK_MINUTES) }) { Text(stringResource(R.string.action_track, TRACK_MINUTES)) }
        }
    }
}

/** Mỗi lần bấm "Theo dõi" kéo dài bao lâu (phút). */
private const val TRACK_MINUTES = 15

@Composable
private fun locationErrorText(code: String): String = stringResource(
    when (code) {
        CameraCodes.LOCATION_NO_PERMISSION -> R.string.location_err_permission
        CameraCodes.LOCATION_OFF -> R.string.location_err_off
        CameraCodes.LOCATION_DISABLED -> R.string.location_err_disabled
        else -> R.string.location_err_unavailable
    },
)
