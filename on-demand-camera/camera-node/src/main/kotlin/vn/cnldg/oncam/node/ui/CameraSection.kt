package vn.cnldg.oncam.node.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import vn.cnldg.oncam.node.R
import vn.cnldg.oncam.node.core.CameraStatus
import vn.cnldg.oncam.shared.protocol.CameraCodes
import vn.cnldg.oncam.shared.protocol.CameraFacing
import vn.cnldg.oncam.shared.protocol.CameraState
import vn.cnldg.oncam.shared.protocol.StreamQuality

/**
 * Phần camera trên màn hình trạng thái: trạng thái, số người xem, xem thử và nút bật/tắt.
 * Nút "Bật camera thử" gửi lệnh START_STREAM đã ký qua broker — đi đúng đường của app Xem.
 */
@Composable
fun CameraSection(
    camera: CameraStatus,
    connected: Boolean,
    cameraAccess: Boolean,
    onStartCamera: () -> Unit,
    onStopCamera: () -> Unit,
    latestFrame: () -> ByteArray?,
) {
    SectionCard(title = stringResource(R.string.camera_section)) {
        InfoRow(stringResource(R.string.camera_state), cameraStateText(camera.state))
        val on = camera.state == CameraState.STREAMING || camera.state == CameraState.STARTING
        if (on) {
            InfoRow(stringResource(R.string.camera_viewers), camera.clients.toString())
            if (camera.recording.active) {
                InfoRow(stringResource(R.string.camera_recording), stringResource(R.string.camera_recording_on))
            }
            if (camera.motion.armed) {
                InfoRow(stringResource(R.string.camera_motion), stringResource(R.string.camera_motion_on))
            }
            InfoRow(
                stringResource(R.string.camera_facing),
                stringResource(if (camera.facing == CameraFacing.FRONT) R.string.camera_front else R.string.camera_back),
            )
            InfoRow(stringResource(R.string.camera_quality), qualityText(camera.quality))
            InfoRow(stringResource(R.string.camera_flash), stringResource(if (camera.flashOn) R.string.value_yes else R.string.value_no))
            camera.streamUrl?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            FramePreview(latestFrame)
            OutlinedButton(onClick = onStopCamera) { Text(stringResource(R.string.camera_stop)) }
        } else {
            camera.error?.let {
                Text(cameraCodeText(it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }
            Button(onClick = onStartCamera, enabled = connected && cameraAccess) {
                Text(stringResource(R.string.camera_start_test))
            }
            Text(
                stringResource(if (cameraAccess) R.string.camera_start_test_hint else R.string.camera_needs_app_open),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/** Hiện khung mới nhất, làm mới 2 lần/giây (giải mã JPEG ngoài luồng giao diện). */
@Composable
private fun FramePreview(latestFrame: () -> ByteArray?) {
    var bitmap by remember { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(Unit) {
        while (true) {
            val bytes = latestFrame()
            bitmap = bytes?.let {
                withContext(Dispatchers.Default) { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }
            }
            delay(PREVIEW_INTERVAL_MS)
        }
    }
    bitmap?.let {
        Image(
            bitmap = it,
            contentDescription = stringResource(R.string.camera_preview_desc),
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(it.width.toFloat() / it.height.coerceAtLeast(1)),
        )
    }
}

@Composable
private fun cameraStateText(state: CameraState): String = stringResource(
    when (state) {
        CameraState.OFF -> R.string.camera_state_off
        CameraState.STARTING -> R.string.camera_state_starting
        CameraState.STREAMING -> R.string.camera_state_streaming
        CameraState.ERROR -> R.string.camera_state_error
    },
)

@Composable
private fun qualityText(q: StreamQuality): String = stringResource(
    when (q) {
        StreamQuality.LOW -> R.string.quality_low
        StreamQuality.MEDIUM -> R.string.quality_medium
        StreamQuality.HIGH -> R.string.quality_high
    },
)

/** Đổi mã lỗi CameraCodes thành câu tiếng Việt; mã lạ thì hiện nguyên mã. */
@Composable
fun cameraCodeText(code: String): String = when {
    code == CameraCodes.NEEDS_APP_OPEN -> stringResource(R.string.code_needs_app_open)
    code == CameraCodes.NO_PERMISSION -> stringResource(R.string.code_no_permission)
    code == CameraCodes.NO_CAMERA -> stringResource(R.string.code_no_camera)
    code == CameraCodes.NO_TAILSCALE -> stringResource(R.string.code_no_tailscale)
    code == CameraCodes.HTTP_BIND_FAILED -> stringResource(R.string.code_http_bind_failed)
    code == CameraCodes.OPEN_FAILED -> stringResource(R.string.code_open_failed)
    code == CameraCodes.ADDRESS_CHANGED -> stringResource(R.string.code_address_changed)
    code.startsWith(CameraCodes.CAMERA_ERROR) ->
        stringResource(R.string.code_camera_error, code.substringAfter(':', code))
    else -> code
}

private const val PREVIEW_INTERVAL_MS = 500L
