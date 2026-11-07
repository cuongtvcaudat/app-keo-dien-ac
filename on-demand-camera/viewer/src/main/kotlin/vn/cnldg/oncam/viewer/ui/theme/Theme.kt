package vn.cnldg.oncam.viewer.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val ViewerBlue = Color(0xFF1565C0)
private val ViewerBlueLight = Color(0xFF90CAF9)

private val LightColors = lightColorScheme(primary = ViewerBlue)
private val DarkColors = darkColorScheme(primary = ViewerBlueLight)

/**
 * Theme Compose của viewer (tông xanh để phân biệt với app camera-node tông đỏ).
 */
@Composable
fun OnCamViewerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
