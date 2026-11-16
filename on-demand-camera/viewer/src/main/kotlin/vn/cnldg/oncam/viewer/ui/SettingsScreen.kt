package vn.cnldg.oncam.viewer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import vn.cnldg.oncam.viewer.R

/** Các công tắc cảnh báo của máy xem. */
data class AlertOptions(
    val enabled: Boolean = false,
    val motion: Boolean = true,
    val power: Boolean = true,
    val heat: Boolean = true,
)

/** Thao tác trên phần cảnh báo ở màn hình Cài đặt. */
interface AlertOptionActions {
    /** Bật/tắt nhận cảnh báo (bật: xin quyền thông báo, chạy dịch vụ nền). */
    fun setEnabled(on: Boolean)

    /** Bật/tắt cảnh báo chuyển động. */
    fun setMotion(on: Boolean)

    /** Bật/tắt cảnh báo nguồn điện. */
    fun setPower(on: Boolean)

    /** Bật/tắt cảnh báo quá nhiệt. */
    fun setHeat(on: Boolean)

    /** App đang được hiện thông báo không. */
    fun canNotify(): Boolean

    /** Mở trang cài đặt thông báo của app. */
    fun openNotificationSettings()

    /** App đã được bỏ tối ưu pin chưa. */
    fun batteryExempt(): Boolean

    /** Mở hộp thoại "cho phép chạy nền". */
    fun requestBatteryExemption()
}

/** Cài đặt: tự tắt camera, tắt camera khi rời app, tuỳ chọn nhận cảnh báo. */
@Composable
fun SettingsScreen(
    idleTimeoutSec: Int,
    stopOnLeave: Boolean,
    onIdleTimeout: (Int) -> Unit,
    onStopOnLeave: (Boolean) -> Unit,
    alerts: AlertOptions,
    alertActions: AlertOptionActions,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Quyền thông báo / tối ưu pin đổi ở màn hình Cài đặt hệ thống → đọc lại mỗi lần quay về app.
    var canNotify by remember { mutableStateOf(alertActions.canNotify()) }
    var batteryExempt by remember { mutableStateOf(alertActions.batteryExempt()) }
    LifecycleResumeEffect(alerts.enabled) {
        canNotify = alertActions.canNotify()
        batteryExempt = alertActions.batteryExempt()
        onPauseOrDispose { }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.headlineSmall)

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.settings_idle), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.settings_idle_hint), style = MaterialTheme.typography.bodySmall)
                IDLE_CHOICES_MIN.forEach { minutes ->
                    val sec = minutes * 60
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(selected = idleTimeoutSec == sec, onClick = { onIdleTimeout(sec) }),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = idleTimeoutSec == sec, onClick = { onIdleTimeout(sec) })
                        Text(stringResource(R.string.minutes, minutes))
                    }
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.settings_stop_on_leave), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.settings_stop_on_leave_hint), style = MaterialTheme.typography.bodySmall)
                }
                Switch(checked = stopOnLeave, onCheckedChange = onStopOnLeave)
            }
        }

        // Tuỳ chọn chức năng: nhận cảnh báo nền + chọn loại.
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SwitchRow(
                    title = stringResource(R.string.settings_alerts),
                    hint = stringResource(R.string.settings_alerts_hint),
                    checked = alerts.enabled,
                    onChange = alertActions::setEnabled,
                    titleStyle = MaterialTheme.typography.titleMedium,
                )
                if (alerts.enabled) {
                    if (!canNotify) {
                        TextButton(onClick = alertActions::openNotificationSettings) {
                            Text(stringResource(R.string.settings_alerts_no_permission), color = MaterialTheme.colorScheme.error)
                        }
                    }
                    Text(stringResource(R.string.settings_alerts_types), style = MaterialTheme.typography.labelLarge)
                    SwitchRow(stringResource(R.string.settings_notify_motion), null, alerts.motion, alertActions::setMotion)
                    SwitchRow(stringResource(R.string.settings_notify_power), null, alerts.power, alertActions::setPower)
                    SwitchRow(stringResource(R.string.settings_notify_heat), null, alerts.heat, alertActions::setHeat)
                    if (batteryExempt) {
                        Text(stringResource(R.string.settings_battery_ok), style = MaterialTheme.typography.bodySmall)
                    } else {
                        OutlinedButton(onClick = alertActions::requestBatteryExemption) {
                            Text(stringResource(R.string.settings_battery_ask))
                        }
                        Text(stringResource(R.string.settings_battery_hint), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }

        val context = LocalContext.current
        val versionName = remember {
            runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: ""
        }
        Text(stringResource(R.string.settings_version, versionName), style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = onBack) { Text(stringResource(R.string.action_back)) }
    }
}

/** Một dòng có công tắc bật/tắt. */
@Composable
private fun SwitchRow(
    title: String,
    hint: String?,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    titleStyle: TextStyle = MaterialTheme.typography.bodyMedium,
) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = titleStyle)
            if (hint != null) Text(hint, style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

private val IDLE_CHOICES_MIN = listOf(1, 3, 5, 10)
