package vn.cnldg.oncam.node.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import vn.cnldg.oncam.node.R
import vn.cnldg.oncam.node.core.BrokerMode
import vn.cnldg.oncam.node.core.ConfigProblem
import vn.cnldg.oncam.node.core.MountOrientation
import vn.cnldg.oncam.node.core.NodeConfig
import vn.cnldg.oncam.shared.OnCam
import vn.cnldg.oncam.shared.client.BrokerAddress

/**
 * Màn hình cấu hình ban đầu (spec mục 7.1): tên, mã camera, token bí mật, nơi đặt broker.
 *
 * @param initial cấu hình đang có (null nếu cấu hình lần đầu).
 * @param onCancel null nếu chưa có cấu hình (không cho thoát).
 */
@Composable
fun SetupScreen(
    initial: NodeConfig?,
    initialAutoStart: Boolean,
    generateSecret: () -> String,
    onSave: (NodeConfig, Boolean) -> Unit,
    onCancel: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var displayName by rememberSaveable { mutableStateOf(initial?.displayName ?: context.getString(R.string.setup_default_name)) }
    var deviceId by rememberSaveable { mutableStateOf(initial?.deviceId ?: DEFAULT_DEVICE_ID) }
    var secret by rememberSaveable { mutableStateOf(initial?.secret ?: generateSecret()) }
    var mode by rememberSaveable { mutableStateOf(initial?.brokerMode ?: BrokerMode.EMBEDDED) }
    var port by rememberSaveable { mutableStateOf((initial?.brokerPort ?: OnCam.DEFAULT_MQTT_PORT).toString()) }
    var externalUri by rememberSaveable { mutableStateOf(initial?.externalUri.orEmpty()) }
    var externalUser by rememberSaveable { mutableStateOf(initial?.externalUsername.orEmpty()) }
    var externalPass by rememberSaveable { mutableStateOf(initial?.externalPassword.orEmpty()) }
    var orientation by rememberSaveable { mutableStateOf(initial?.orientation ?: MountOrientation.UP) }
    var autoStart by rememberSaveable { mutableStateOf(initialAutoStart) }
    var shareLocation by rememberSaveable { mutableStateOf(initial?.shareLocation ?: false) }
    var problems by remember { mutableStateOf(emptyList<ConfigProblem>()) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.setup_title), style = MaterialTheme.typography.headlineSmall)
        Text(stringResource(R.string.setup_intro), style = MaterialTheme.typography.bodyMedium)

        SectionCard(title = stringResource(R.string.setup_section_camera)) {
            OutlinedTextField(
                value = displayName,
                onValueChange = { displayName = it },
                label = { Text(stringResource(R.string.setup_display_name)) },
                singleLine = true,
                isError = ConfigProblem.EMPTY_DISPLAY_NAME in problems,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = deviceId,
                onValueChange = { deviceId = it.trim().lowercase() },
                label = { Text(stringResource(R.string.setup_device_id)) },
                supportingText = { Text(stringResource(R.string.setup_device_id_hint)) },
                singleLine = true,
                isError = ConfigProblem.INVALID_DEVICE_ID in problems,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        SectionCard(title = stringResource(R.string.setup_section_orientation)) {
            Text(stringResource(R.string.setup_orientation_hint), style = MaterialTheme.typography.bodySmall)
            MountOrientation.entries.forEach { o ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectable(selected = orientation == o, onClick = { orientation = o }),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = orientation == o, onClick = { orientation = o })
                    Text(stringResource(o.labelRes()), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }

        SectionCard(title = stringResource(R.string.setup_section_secret)) {
            Text(stringResource(R.string.setup_secret_hint), style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(
                value = secret,
                onValueChange = { secret = it.trim() },
                label = { Text(stringResource(R.string.setup_secret)) },
                singleLine = true,
                isError = ConfigProblem.WEAK_SECRET in problems,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { secret = generateSecret() }) { Text(stringResource(R.string.setup_secret_generate)) }
                OutlinedButton(onClick = {
                    copyToClipboard(context, context.getString(R.string.setup_secret), secret, sensitive = true)
                }) { Text(stringResource(R.string.action_copy)) }
            }
        }

        SectionCard(title = stringResource(R.string.setup_section_broker)) {
            BrokerOption(
                selected = mode == BrokerMode.EMBEDDED,
                title = stringResource(R.string.setup_broker_embedded),
                description = stringResource(R.string.setup_broker_embedded_desc),
                onSelect = { mode = BrokerMode.EMBEDDED },
            )
            BrokerOption(
                selected = mode == BrokerMode.EXTERNAL,
                title = stringResource(R.string.setup_broker_external),
                description = stringResource(R.string.setup_broker_external_desc),
                onSelect = { mode = BrokerMode.EXTERNAL },
            )
            if (mode == BrokerMode.EMBEDDED) {
                OutlinedTextField(
                    value = port,
                    onValueChange = { v -> port = v.filter(Char::isDigit).take(5) },
                    label = { Text(stringResource(R.string.setup_port)) },
                    singleLine = true,
                    isError = ConfigProblem.INVALID_PORT in problems,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                OutlinedTextField(
                    value = externalUri,
                    onValueChange = { externalUri = it.trim() },
                    label = { Text(stringResource(R.string.setup_external_uri)) },
                    placeholder = { Text(stringResource(R.string.setup_external_uri_placeholder)) },
                    singleLine = true,
                    isError = ConfigProblem.INVALID_EXTERNAL_URI in problems,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = externalUser,
                    onValueChange = { externalUser = it.trim() },
                    label = { Text(stringResource(R.string.setup_external_user)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = externalPass,
                    onValueChange = { externalPass = it },
                    label = { Text(stringResource(R.string.setup_external_pass)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = autoStart, onCheckedChange = { autoStart = it })
            Text(stringResource(R.string.setup_auto_start), style = MaterialTheme.typography.bodyMedium)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = shareLocation, onCheckedChange = { shareLocation = it })
            Column {
                Text(stringResource(R.string.setup_share_location), style = MaterialTheme.typography.bodyMedium)
                Text(stringResource(R.string.setup_share_location_hint), style = MaterialTheme.typography.bodySmall)
            }
        }

        problems.forEach { problem ->
            Text(
                text = stringResource(problem.messageRes()),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                if (mode == BrokerMode.EXTERNAL) externalUri = BrokerAddress.normalize(externalUri)
                val config = NodeConfig(
                    deviceId = deviceId,
                    displayName = displayName.trim(),
                    secret = secret,
                    brokerMode = mode,
                    brokerPort = port.toIntOrNull() ?: 0,
                    externalUri = externalUri,
                    externalUsername = externalUser,
                    externalPassword = externalPass,
                    orientation = orientation,
                    shareLocation = shareLocation,
                )
                problems = config.problems()
                if (problems.isEmpty()) onSave(config, autoStart)
            }) { Text(stringResource(R.string.action_save)) }
            if (onCancel != null) {
                TextButton(onClick = onCancel) { Text(stringResource(R.string.action_cancel)) }
            }
        }
        if (initial != null) {
            Text(stringResource(R.string.setup_restart_note), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun BrokerOption(selected: Boolean, title: String, description: String, onSelect: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onSelect),
        verticalAlignment = Alignment.Top,
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Column(modifier = Modifier.padding(top = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun MountOrientation.labelRes(): Int = when (this) {
    MountOrientation.UP -> R.string.orientation_up
    MountOrientation.LEFT -> R.string.orientation_left
    MountOrientation.RIGHT -> R.string.orientation_right
    MountOrientation.DOWN -> R.string.orientation_down
}

private fun ConfigProblem.messageRes(): Int = when (this) {
    ConfigProblem.INVALID_DEVICE_ID -> R.string.problem_device_id
    ConfigProblem.EMPTY_DISPLAY_NAME -> R.string.problem_display_name
    ConfigProblem.WEAK_SECRET -> R.string.problem_secret
    ConfigProblem.INVALID_PORT -> R.string.problem_port
    ConfigProblem.INVALID_EXTERNAL_URI -> R.string.problem_external_uri
}

private const val DEFAULT_DEVICE_ID = "san-nha"
