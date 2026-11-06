package vn.cnldg.oncam.node.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import kotlinx.coroutines.delay
import vn.cnldg.oncam.node.R
import vn.cnldg.oncam.node.core.BrokerState
import vn.cnldg.oncam.node.core.NodeConfig
import vn.cnldg.oncam.node.core.NodeState
import vn.cnldg.oncam.node.core.SelfTestFailure
import vn.cnldg.oncam.node.core.SelfTestResult
import vn.cnldg.oncam.node.core.pairingCode
import vn.cnldg.oncam.node.system.BatteryOptimization
import vn.cnldg.oncam.shared.mqtt.MqttConnectionState
import vn.cnldg.oncam.shared.protocol.NetworkType

/**
 * Màn hình chính khi đã cấu hình: bật/tắt node, xem kết nối, thông tin cần nhập ở app Xem,
 * tự kiểm tra và hướng dẫn chống bị tắt.
 */
@Composable
fun StatusScreen(
    config: NodeConfig,
    running: Boolean,
    state: NodeState,
    cameraAccess: Boolean,
    micAccess: Boolean,
    selfTesting: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onSelfTest: () -> Unit,
    onOpenGuide: () -> Unit,
    onEditConfig: () -> Unit,
    onStartCamera: () -> Unit,
    onStopCamera: () -> Unit,
    latestFrame: () -> ByteArray?,
    onRequestMic: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var batteryIgnored by remember { mutableStateOf(BatteryOptimization.isIgnoring(context)) }
    var micGranted by remember { mutableStateOf(micPermission(context)) }
    LifecycleResumeEffect(Unit) {
        batteryIgnored = BatteryOptimization.isIgnoring(context)
        micGranted = micPermission(context)
        onPauseOrDispose { }
    }
    var showQr by remember { mutableStateOf(false) }
    var nowSec by remember { mutableLongStateOf(System.currentTimeMillis() / 1000) }
    LaunchedEffect(running) {
        while (running) {
            nowSec = System.currentTimeMillis() / 1000
            delay(TICK_MS)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(config.displayName, style = MaterialTheme.typography.headlineSmall)
        Text(stringResource(R.string.status_device_id, config.deviceId), style = MaterialTheme.typography.bodyMedium)

        if (!batteryIgnored) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.status_battery_warning), color = MaterialTheme.colorScheme.onErrorContainer)
                    Button(onClick = onOpenGuide) { Text(stringResource(R.string.status_open_guide)) }
                }
            }
        }

        SectionCard(title = stringResource(R.string.status_section_node)) {
            Text(
                stringResource(if (running) R.string.status_running else R.string.status_stopped),
                style = MaterialTheme.typography.bodyLarge,
            )
            if (running) {
                OutlinedButton(onClick = onStop) { Text(stringResource(R.string.status_stop)) }
            } else {
                Button(onClick = onStart) { Text(stringResource(R.string.status_start)) }
            }
        }

        if (running) {
            SectionCard(title = stringResource(R.string.status_section_connection)) {
                InfoRow(stringResource(R.string.status_broker), brokerText(state.broker))
                InfoRow(stringResource(R.string.status_mqtt), mqttText(state.mqtt))
                InfoRow(stringResource(R.string.status_disconnects), state.disconnects.toString())
                state.startedAtSec?.let { InfoRow(stringResource(R.string.status_uptime), uptimeText(nowSec - it)) }
            }

            CameraSection(
                camera = state.camera,
                connected = state.mqtt == MqttConnectionState.Connected,
                cameraAccess = cameraAccess,
                onStartCamera = onStartCamera,
                onStopCamera = onStopCamera,
                latestFrame = latestFrame,
            )

            SectionCard(title = stringResource(R.string.status_section_viewer)) {
                val code = config.pairingCode(state.brokerUri)
                Button(onClick = { showQr = true }, enabled = code != null) { Text(stringResource(R.string.status_show_qr)) }
                if (code == null) {
                    Text(stringResource(R.string.status_qr_wait), style = MaterialTheme.typography.bodySmall)
                } else if (showQr) {
                    PairingQrDialog(code = code, onDismiss = { showQr = false })
                }
                Text(stringResource(R.string.status_viewer_hint), style = MaterialTheme.typography.bodySmall)
                InfoRow(stringResource(R.string.status_broker_address), state.brokerUri ?: stringResource(R.string.value_none))
                InfoRow(stringResource(R.string.status_device_id_label), config.deviceId)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    state.brokerUri?.let { uri ->
                        OutlinedButton(onClick = {
                            copyToClipboard(context, context.getString(R.string.status_broker_address), uri, sensitive = false)
                        }) { Text(stringResource(R.string.status_copy_address)) }
                    }
                    OutlinedButton(onClick = {
                        copyToClipboard(context, context.getString(R.string.setup_secret), config.secret, sensitive = true)
                    }) { Text(stringResource(R.string.status_copy_secret)) }
                }
            }

            SectionCard(title = stringResource(R.string.status_section_device)) {
                val d = state.device
                InfoRow(
                    stringResource(R.string.status_battery),
                    d.batteryPercent?.let { stringResource(R.string.value_percent, it) } ?: stringResource(R.string.value_none),
                )
                InfoRow(
                    stringResource(R.string.status_charging),
                    stringResource(
                        when (d.charging) {
                            true -> R.string.value_yes
                            false -> R.string.value_no
                            null -> R.string.value_none
                        },
                    ),
                )
                InfoRow(
                    stringResource(R.string.status_temperature),
                    d.temperatureC?.let { stringResource(R.string.value_celsius, it) } ?: stringResource(R.string.value_none),
                )
                InfoRow(stringResource(R.string.status_network), networkText(d.network))
                InfoRow(
                    stringResource(R.string.status_camera_access),
                    stringResource(if (cameraAccess) R.string.value_yes else R.string.status_camera_access_no),
                )
                InfoRow(
                    stringResource(R.string.status_mic_access),
                    stringResource(
                        when {
                            micAccess -> R.string.value_yes
                            !micGranted -> R.string.status_mic_no_permission
                            else -> R.string.status_camera_access_no
                        },
                    ),
                )
                if (!micGranted) {
                    OutlinedButton(onClick = onRequestMic) { Text(stringResource(R.string.status_mic_allow)) }
                }
            }

            SectionCard(title = stringResource(R.string.status_section_test)) {
                Button(
                    onClick = onSelfTest,
                    enabled = !selfTesting && state.mqtt == MqttConnectionState.Connected,
                ) { Text(stringResource(if (selfTesting) R.string.status_self_testing else R.string.status_self_test)) }
                state.selfTest?.let { Text(selfTestText(it), style = MaterialTheme.typography.bodyMedium) }
                state.lastCommand?.let { cmd ->
                    InfoRow(
                        stringResource(R.string.status_last_command),
                        stringResource(
                            if (cmd.success) R.string.status_last_command_ok else R.string.status_last_command_fail,
                            cmd.action?.name ?: stringResource(R.string.value_none),
                            cmd.message.orEmpty(),
                        ),
                    )
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onOpenGuide) { Text(stringResource(R.string.status_open_guide)) }
            OutlinedButton(onClick = onEditConfig) { Text(stringResource(R.string.status_edit_config)) }
        }
    }
}

@Composable
private fun brokerText(state: BrokerState): String = when (state) {
    BrokerState.NotUsed -> stringResource(R.string.broker_external)
    BrokerState.WaitingForAddress -> stringResource(R.string.broker_waiting_tailscale)
    is BrokerState.Running -> stringResource(R.string.broker_running, state.host, state.port)
    is BrokerState.Failed -> stringResource(R.string.broker_failed, state.reason)
}

@Composable
private fun mqttText(state: MqttConnectionState): String = when (state) {
    MqttConnectionState.Idle -> stringResource(R.string.mqtt_idle)
    MqttConnectionState.Connecting -> stringResource(R.string.mqtt_connecting)
    MqttConnectionState.Connected -> stringResource(R.string.mqtt_connected)
    is MqttConnectionState.Disconnected -> stringResource(
        R.string.mqtt_disconnected,
        (state.retryInMs / 1000).toInt().coerceAtLeast(1),
    )
}

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

@Composable
private fun uptimeText(seconds: Long): String {
    val s = seconds.coerceAtLeast(0)
    return stringResource(R.string.value_uptime, (s / 3600).toInt(), ((s % 3600) / 60).toInt())
}

@Composable
private fun selfTestText(result: SelfTestResult): String = when (result) {
    is SelfTestResult.Passed -> stringResource(R.string.self_test_passed, result.roundTripMs.toInt())
    is SelfTestResult.Failed -> stringResource(
        when (result.reason) {
            SelfTestFailure.NOT_CONNECTED -> R.string.self_test_not_connected
            SelfTestFailure.PUBLISH_FAILED -> R.string.self_test_publish_failed
            SelfTestFailure.TIMEOUT -> R.string.self_test_timeout
            SelfTestFailure.REJECTED -> R.string.self_test_rejected
        },
    )
}

private fun micPermission(context: android.content.Context) =
    androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) ==
        android.content.pm.PackageManager.PERMISSION_GRANTED

private const val TICK_MS = 30_000L
