package vn.cnldg.oncam.node.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import timber.log.Timber
import vn.cnldg.oncam.node.R
import vn.cnldg.oncam.node.core.RomFamily
import vn.cnldg.oncam.node.system.BatteryOptimization

/** Một bước hướng dẫn; [openAppSettings] = có nút mở "Thông tin ứng dụng". */
private data class GuideStep(@StringRes val title: Int, @StringRes val body: Int, val openAppSettings: Boolean = false)

/** Tiêu đề, lời dẫn và các bước (sau bước 1 "Bỏ tối ưu pin" dùng chung) theo từng hãng. */
private data class Guide(@StringRes val title: Int, @StringRes val intro: Int, val steps: List<GuideStep>)

private fun guideFor(family: RomFamily): Guide = when (family) {
    RomFamily.SAMSUNG -> Guide(
        R.string.guide_title_samsung,
        R.string.guide_intro_samsung,
        listOf(
            GuideStep(R.string.guide_samsung_step2_title, R.string.guide_samsung_step2_body, openAppSettings = true),
            GuideStep(R.string.guide_samsung_step3_title, R.string.guide_samsung_step3_body),
            GuideStep(R.string.guide_samsung_step4_title, R.string.guide_samsung_step4_body),
            GuideStep(R.string.guide_samsung_step5_title, R.string.guide_samsung_step5_body),
            GuideStep(R.string.guide_samsung_step6_title, R.string.guide_samsung_step6_body),
        ),
    )
    RomFamily.COLOR_OS -> Guide(
        R.string.guide_title,
        R.string.guide_intro,
        listOf(
            GuideStep(R.string.guide_step2_title, R.string.guide_step2_body, openAppSettings = true),
            GuideStep(R.string.guide_step3_title, R.string.guide_step3_body),
            GuideStep(R.string.guide_step4_title, R.string.guide_step4_body),
            GuideStep(R.string.guide_step5_title, R.string.guide_step5_body),
        ),
    )
    RomFamily.OTHER -> Guide(
        R.string.guide_title_other,
        R.string.guide_intro_other,
        listOf(
            GuideStep(R.string.guide_other_step2_title, R.string.guide_other_step2_body, openAppSettings = true),
            GuideStep(R.string.guide_other_step3_title, R.string.guide_other_step3_body),
            GuideStep(R.string.guide_other_step4_title, R.string.guide_other_step4_body),
            GuideStep(R.string.guide_step5_title, R.string.guide_step5_body),
        ),
    )
}

/**
 * Hướng dẫn chống hệ thống tắt app (spec mục 4.1.4), tự chọn theo hãng máy (Samsung One UI, Oppo ColorOS,
 * hãng khác); xem được hướng dẫn hãng khác bằng các nút phía trên.
 */
@Composable
fun BatteryGuideScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var ignored by remember { mutableStateOf(BatteryOptimization.isIgnoring(context)) }
    LifecycleResumeEffect(Unit) {
        ignored = BatteryOptimization.isIgnoring(context)
        onPauseOrDispose { }
    }
    var family by rememberSaveable { mutableStateOf(RomFamily.of(Build.MANUFACTURER)) }
    val guide = guideFor(family)

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RomFamily.entries.forEach { f ->
                val label = stringResource(
                    when (f) {
                        RomFamily.SAMSUNG -> R.string.guide_family_samsung
                        RomFamily.COLOR_OS -> R.string.guide_family_coloros
                        RomFamily.OTHER -> R.string.guide_family_other
                    },
                )
                if (f == family) {
                    Button(onClick = {}) { Text(label) }
                } else {
                    OutlinedButton(onClick = { family = f }) { Text(label) }
                }
            }
        }
        Text(stringResource(guide.title), style = MaterialTheme.typography.headlineSmall)
        Text(stringResource(guide.intro), style = MaterialTheme.typography.bodyMedium)

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
        guide.steps.forEach { step ->
            SectionCard(title = stringResource(step.title)) {
                Text(stringResource(step.body), style = MaterialTheme.typography.bodyMedium)
                if (step.openAppSettings) {
                    OutlinedButton(onClick = { context.openSettings(BatteryOptimization.appDetailsIntent(context)) }) {
                        Text(stringResource(R.string.guide_open_app_settings))
                    }
                }
            }
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
