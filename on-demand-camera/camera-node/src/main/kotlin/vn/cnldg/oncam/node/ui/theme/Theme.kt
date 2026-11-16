package vn.cnldg.oncam.node.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val NodeRed = Color(0xFFC62828)
private val NodeRedLight = Color(0xFFEF9A9A)

private val LightColors = lightColorScheme(primary = NodeRed)
private val DarkColors = darkColorScheme(primary = NodeRedLight)

/**
 * Theme Compose của camera-node (tông đỏ để phân biệt với app viewer tông xanh).
 */
@Composable
fun OnCamNodeTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
