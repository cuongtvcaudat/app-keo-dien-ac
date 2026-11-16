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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import vn.cnldg.oncam.node.R
import vn.cnldg.oncam.node.core.BrokerMode
import vn.cnldg.oncam.node.core.ConfigProblem
import vn.cnldg.oncam.node.core.MailAccount
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
    emailTest: EmailTest?,
    onTestEmail: (MailAccount, String) -> Unit,
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
    var locationInterval by rememberSaveable { mutableStateOf(initial?.locationIntervalMin ?: 15) }
    var allowRecording by rememberSaveable { mutableStateOf(initial?.allowRecording ?: true) }
    var allowAudio by rememberSaveable { mutableStateOf(initial?.allowAudio ?: true) }
    var alertMotion by rememberSaveable { mutableStateOf(initial?.alertMotion ?: true) }
    var alertPower by rememberSaveable { mutableStateOf(initial?.alertPower ?: true) }
    var alertHeat by rememberSaveable { mutableStateOf(initial?.alertHeat ?: true) }
    var alertEmail by rememberSaveable { mutableStateOf(initial?.alertEmail ?: false) }
    var emailFrom by rememberSaveable { mutableStateOf(initial?.emailFrom.orEmpty()) }
    var emailPassword by rememberSaveable { mutableStateOf(initial?.emailPassword.orEmpty()) }
    var emailTo by rememberSaveable { mutableStateOf(initial?.emailTo.orEmpty()) }
    var guestEnabled by rememberSaveable { mutableStateOf(initial?.guestEnabled ?: false) }
    var guestSecret by rememberSaveable { mutableStateOf(initial?.guestSecret.orEmpty()) }
    var guestRenewed by rememberSaveable { mutableStateOf(false) }
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

        // Tuỳ chọn chức năng: chủ máy camera quyết định app Xem được dùng những gì.
        SectionCard(title = stringResource(R.string.setup_section_features)) {
            Text(stringResource(R.string.setup_features_hint), style = MaterialTheme.typography.bodySmall)
            OptionRow(autoStart, { autoStart = it }, stringResource(R.string.setup_auto_start))
            OptionRow(
                allowRecording,
                { allowRecording = it },
                stringResource(R.string.setup_allow_recording),
                stringResource(R.string.setup_allow_recording_hint),
            )
            OptionRow(
                allowAudio,
                { allowAudio = it },
                stringResource(R.string.setup_allow_audio),
                stringResource(R.string.setup_allow_audio_hint),
            )
            OptionRow(
                shareLocation,
                { shareLocation = it },
                stringResource(R.string.setup_share_location),
                stringResource(R.string.setup_share_location_hint),
            )
            if (shareLocation) {
                Text(stringResource(R.string.setup_location_interval), style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf(0 to R.string.location_interval_off, 15 to R.string.location_interval_15, 60 to R.string.location_interval_60)
                        .forEach { (minutes, label) ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.selectable(selected = locationInterval == minutes, onClick = { locationInterval = minutes }),
                            ) {
                                RadioButton(selected = locationInterval == minutes, onClick = { locationInterval = minutes })
                                Text(stringResource(label), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                }
            }
        }

        // Quyền "chỉ xem" (0.13): mã QR riêng cho khách; đổi mã = thu hồi mọi mã đã phát.
        SectionCard(title = stringResource(R.string.setup_section_guest)) {
            val embedded = mode == BrokerMode.EMBEDDED
            OptionRow(
                guestEnabled && embedded,
                { on ->
                    guestEnabled = on
                    if (on && guestSecret.isBlank()) guestSecret = generateSecret()
                },
                stringResource(R.string.setup_guest),
                stringResource(if (embedded) R.string.setup_guest_hint else R.string.setup_guest_needs_embedded),
                enabled = embedded,
            )
            if (guestEnabled && embedded) {
                OutlinedButton(onClick = {
                    guestSecret = generateSecret()
                    guestRenewed = true
                }) { Text(stringResource(R.string.setup_guest_renew)) }
                if (guestRenewed) {
                    Text(
                        stringResource(R.string.setup_guest_renewed),
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        SectionCard(title = stringResource(R.string.setup_section_alerts)) {
            Text(stringResource(R.string.setup_alerts_hint), style = MaterialTheme.typography.bodySmall)
            OptionRow(
                alertMotion && allowRecording,
                { alertMotion = it },
                stringResource(R.string.setup_alert_motion),
                stringResource(R.string.setup_alert_motion_hint),
                enabled = allowRecording,
            )
            OptionRow(alertPower, { alertPower = it }, stringResource(R.string.setup_alert_power), stringResource(R.string.setup_alert_power_hint))
            OptionRow(alertHeat, { alertHeat = it }, stringResource(R.string.setup_alert_heat), stringResource(R.string.setup_alert_heat_hint))
            OptionRow(alertEmail, { alertEmail = it }, stringResource(R.string.setup_alert_email), stringResource(R.string.setup_alert_email_hint))
            if (alertEmail) {
                val emailError = ConfigProblem.INVALID_EMAIL in problems
                OutlinedTextField(
                    value = emailFrom,
                    onValueChange = { emailFrom = it.trim() },
                    label = { Text(stringResource(R.string.setup_email_from)) },
                    singleLine = true,
                    isError = emailError,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = emailPassword,
                    onValueChange = { emailPassword = it },
                    label = { Text(stringResource(R.string.setup_email_password)) },
                    supportingText = { Text(stringResource(R.string.setup_email_password_hint)) },
                    singleLine = true,
                    isError = emailError,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = emailTo,
                    onValueChange = { emailTo = it },
                    label = { Text(stringResource(R.string.setup_email_to)) },
                    supportingText = { Text(stringResource(R.string.setup_email_to_hint)) },
                    singleLine = true,
                    isError = emailError,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    modifier = Modifier.fillMaxWidth(),
                )
                val draft = MailAccount.fromInput(emailFrom, emailPassword, emailTo)
                OutlinedButton(
                    onClick = { draft?.let { onTestEmail(it, displayName.trim()) } },
                    enabled = draft != null && emailTest != EmailTest.Sending,
                ) { Text(stringResource(R.string.setup_email_test)) }
                when (emailTest) {
                    EmailTest.Sending -> Text(stringResource(R.string.setup_email_sending), style = MaterialTheme.typography.bodySmall)
                    EmailTest.Sent -> Text(
                        stringResource(R.string.setup_email_sent),
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    is EmailTest.Failed -> {
                        val code = emailTest.smtpCode
                        Text(
                            when {
                                code == null -> stringResource(R.string.setup_email_failed_network)
                                code in 530..535 -> stringResource(R.string.setup_email_failed_auth)
                                else -> stringResource(R.string.setup_email_failed_code, code)
                            },
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    null -> Unit
                }
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
                    locationIntervalMin = locationInterval,
                    allowRecording = allowRecording,
                    allowAudio = allowAudio,
                    alertMotion = alertMotion,
                    alertPower = alertPower,
                    alertHeat = alertHeat,
                    alertEmail = alertEmail,
                    emailFrom = emailFrom,
                    emailPassword = emailPassword,
                    emailTo = emailTo.trim(),
                    guestEnabled = guestEnabled && mode == BrokerMode.EMBEDDED,
                    guestSecret = guestSecret,
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

/** Một ô chọn bật/tắt chức năng kèm dòng giải thích. */
@Composable
private fun OptionRow(
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    title: String,
    hint: String? = null,
    enabled: Boolean = true,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = onChange, enabled = enabled)
        Column {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            if (hint != null) {
                Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
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
    ConfigProblem.INVALID_EMAIL -> R.string.problem_email
    ConfigProblem.INVALID_GUEST -> R.string.problem_guest
}

private const val DEFAULT_DEVICE_ID = "san-nha"
