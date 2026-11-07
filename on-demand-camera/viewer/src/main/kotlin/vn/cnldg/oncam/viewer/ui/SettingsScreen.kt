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
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import vn.cnldg.oncam.viewer.BuildConfig
import vn.cnldg.oncam.viewer.R

/** Cài đặt: thời gian camera tự tắt khi không ai xem, tắt camera khi rời app. */
@Composable
fun SettingsScreen(
    idleTimeoutSec: Int,
    stopOnLeave: Boolean,
    onIdleTimeout: (Int) -> Unit,
    onStopOnLeave: (Boolean) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
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

        Text(stringResource(R.string.settings_version, BuildConfig.VERSION_NAME), style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = onBack) { Text(stringResource(R.string.action_back)) }
    }
}

private val IDLE_CHOICES_MIN = listOf(1, 3, 5, 10)
