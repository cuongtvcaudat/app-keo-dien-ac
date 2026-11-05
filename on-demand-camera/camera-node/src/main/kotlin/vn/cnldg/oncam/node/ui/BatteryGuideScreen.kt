package vn.cnldg.oncam.node.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import timber.log.Timber
import vn.cnldg.oncam.node.R
import vn.cnldg.oncam.node.system.BatteryOptimization

/**
 * Hướng dẫn chống hệ thống tắt app (spec mục 4.1.4), viết cho ColorOS (Oppo) — máy camera của dự án.
 * Tên mục cài đặt có thể khác đôi chút giữa ColorOS 11/12/13.
 */
@Composable
fun BatteryGuideScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var ignored by remember { mutableStateOf(BatteryOptimization.isIgnoring(context)) }
    LifecycleResumeEffect(Unit) {
        ignored = BatteryOptimization.isIgnoring(context)
        onPauseOrDispose { }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.guide_title), style = MaterialTheme.typography.headlineSmall)
        Text(stringResource(R.string.guide_intro), style = MaterialTheme.typography.bodyMedium)

        SectionCard(title = stringResource(R.string.guide_step1_title)) {
            Text(
                stringResource(if (ignored) R.string.guide_step1_done else R.string.guide_step1_todo),
                style = MaterialTheme.typography.bodyMedium,
            )
            if (!ignored) {
                Button(onClick = { context.openSettings(BatteryOptimization.requestIgnoreIntent(context)) }) {
                    Text(stringResource(R.string.guide_step1_button))
                }
            }
        }
        SectionCard(title = stringResource(R.string.guide_step2_title)) {
            Text(stringResource(R.string.guide_step2_body), style = MaterialTheme.typography.bodyMedium)
            OutlinedButton(onClick = { context.openSettings(BatteryOptimization.appDetailsIntent(context)) }) {
                Text(stringResource(R.string.guide_open_app_settings))
            }
        }
        SectionCard(title = stringResource(R.string.guide_step3_title)) {
            Text(stringResource(R.string.guide_step3_body), style = MaterialTheme.typography.bodyMedium)
        }
        SectionCard(title = stringResource(R.string.guide_step4_title)) {
            Text(stringResource(R.string.guide_step4_body), style = MaterialTheme.typography.bodyMedium)
        }
        SectionCard(title = stringResource(R.string.guide_step5_title)) {
            Text(stringResource(R.string.guide_step5_body), style = MaterialTheme.typography.bodyMedium)
        }
        TextButton(onClick = onBack) { Text(stringResource(R.string.action_back)) }
    }
}

private fun Context.openSettings(intent: Intent) {
    try {
        startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        // Một số bản ROM không có màn hình này → mở danh sách tối ưu pin chung.
        Timber.w(e, "Không mở được %s", intent.action)
        runCatching { startActivity(BatteryOptimization.batteryListIntent()) }
    }
}
