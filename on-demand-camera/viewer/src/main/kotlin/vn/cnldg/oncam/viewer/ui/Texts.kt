package vn.cnldg.oncam.viewer.ui

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import vn.cnldg.oncam.shared.client.CommandResult
import vn.cnldg.oncam.shared.mqtt.MqttConnectionState
import vn.cnldg.oncam.shared.protocol.CameraCodes
import vn.cnldg.oncam.shared.protocol.EventType
import vn.cnldg.oncam.shared.protocol.RejectReason
import vn.cnldg.oncam.viewer.R

/** Một dòng "nhãn: giá trị". */
@Composable
fun InfoRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 12.dp))
    }
}

/** Đổi mã lỗi từ camera (CameraCodes / RejectReason) sang câu tiếng Việt; mã lạ hiện nguyên mã. */
fun codeText(context: Context, code: String?): String {
    if (code == null) return context.getString(R.string.code_unknown)
    val res = when {
        code == CameraCodes.NEEDS_APP_OPEN -> R.string.code_needs_app_open
        code == CameraCodes.NO_PERMISSION -> R.string.code_no_permission
        code == CameraCodes.NO_CAMERA -> R.string.code_no_camera
        code == CameraCodes.NO_TAILSCALE -> R.string.code_no_tailscale
        code == CameraCodes.HTTP_BIND_FAILED -> R.string.code_http_bind_failed
        code == CameraCodes.OPEN_FAILED -> R.string.code_open_failed
        code == CameraCodes.CAMERA_OFF -> R.string.code_camera_off
        code == CameraCodes.NO_FLASH -> R.string.code_no_flash
        code == CameraCodes.NO_FRAME -> R.string.code_no_frame
        code == CameraCodes.ADDRESS_CHANGED -> R.string.code_address_changed
        code == CameraCodes.LOCATION_DISABLED -> R.string.location_err_disabled
        code == CameraCodes.LOCATION_NO_PERMISSION -> R.string.location_err_permission
        code == CameraCodes.LOCATION_OFF -> R.string.location_err_off
        code == CameraCodes.LOCATION_UNAVAILABLE -> R.string.location_err_unavailable
        code == RejectReason.BAD_SIGNATURE.name -> R.string.code_bad_signature
        code == RejectReason.EXPIRED.name -> R.string.code_expired
        code == RejectReason.UNSUPPORTED.name -> R.string.code_unsupported
        code.startsWith(CameraCodes.CAMERA_ERROR) ->
            return context.getString(R.string.code_camera_error, code.substringAfter(':', code))
        else -> return code
    }
    return context.getString(res)
}

/** Câu mô tả kết quả lệnh thất bại. */
fun resultText(context: Context, result: CommandResult): String = when (result) {
    is CommandResult.Ok -> context.getString(R.string.result_ok)
    is CommandResult.Rejected -> codeText(context, result.code)
    CommandResult.NotConnected -> context.getString(R.string.result_not_connected)
    CommandResult.PublishFailed -> context.getString(R.string.result_publish_failed)
    CommandResult.Timeout -> context.getString(R.string.result_timeout)
}

/** Câu mô tả sự kiện từ camera. */
fun eventText(context: Context, cameraName: String, type: EventType, message: String?): String? = when (type) {
    EventType.IDLE_TIMEOUT -> context.getString(R.string.event_idle_timeout, cameraName)
    EventType.THERMAL_THROTTLE -> if (message?.startsWith(CameraCodes.THERMAL_STOPPED) == true) {
        context.getString(R.string.event_thermal_stopped, cameraName, message.substringAfter(':'))
    } else {
        context.getString(R.string.event_thermal_lowered, cameraName, message?.substringAfterLast(':').orEmpty())
    }
    EventType.ERROR -> context.getString(R.string.event_error, cameraName, codeText(context, message))
    EventType.CAMERA_STARTED, EventType.CAMERA_STOPPED, EventType.COMMAND_RESULT -> null
}

/** Mô tả trạng thái kết nối broker của một camera. */
fun connectionText(context: Context, state: MqttConnectionState): String = when (state) {
    MqttConnectionState.Idle -> context.getString(R.string.conn_idle)
    MqttConnectionState.Connecting -> context.getString(R.string.conn_connecting)
    MqttConnectionState.Connected -> context.getString(R.string.conn_connected)
    is MqttConnectionState.Disconnected ->
        if (state.reason?.startsWith("MQTT 4") == true || state.reason?.startsWith("MQTT 5") == true) {
            // Paho: 4 = sai tên/mật khẩu, 5 = không được phép → token không khớp với máy camera.
            context.getString(R.string.conn_bad_credentials)
        } else {
            context.getString(R.string.conn_disconnected, (state.retryInMs / 1000).toInt().coerceAtLeast(1))
        }
}
