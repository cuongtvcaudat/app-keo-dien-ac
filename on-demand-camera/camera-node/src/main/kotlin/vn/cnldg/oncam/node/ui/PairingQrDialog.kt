package vn.cnldg.oncam.node.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import kotlinx.coroutines.delay
import vn.cnldg.oncam.node.R
import vn.cnldg.oncam.node.core.QrCodes

/**
 * Hiện mã QR ghép đôi cho app Xem quét. Mã chứa token bí mật nên tự ẩn sau [visibleSec] giây
 * và ẩn ngay khi app bị ẩn (bấm Home, tắt màn hình).
 */
@Composable
fun PairingQrDialog(code: String, onDismiss: () -> Unit, visibleSec: Int = QR_VISIBLE_SEC) {
    val image = remember(code) { qrImage(code) }
    var remaining by remember { mutableIntStateOf(visibleSec) }
    LaunchedEffect(code) {
        while (remaining > 0) {
            delay(1_000)
            remaining--
        }
        onDismiss()
    }
    LifecycleResumeEffect(Unit) {
        onPauseOrDispose { onDismiss() }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) } },
        title = { Text(stringResource(R.string.qr_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                // Nền trắng cả khi giao diện tối: máy quét cần ô đen trên nền sáng.
                Box(modifier = Modifier.background(Color.White).padding(8.dp)) {
                    Image(
                        bitmap = image,
                        contentDescription = stringResource(R.string.qr_title),
                        filterQuality = FilterQuality.None,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.size(260.dp),
                    )
                }
                Text(stringResource(R.string.qr_steps), style = MaterialTheme.typography.bodyMedium)
                Text(
                    stringResource(R.string.qr_warning, remaining),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
    )
}

/** Ma trận QR → ảnh, mỗi ô 1 điểm ảnh (vẽ phóng to bằng FilterQuality.None nên vẫn sắc nét). */
private fun qrImage(code: String): ImageBitmap {
    val m = QrCodes.encode(code)
    val pixels = IntArray(m.width * m.height) { i ->
        if (m[i % m.width, i / m.width]) android.graphics.Color.BLACK else android.graphics.Color.WHITE
    }
    return Bitmap.createBitmap(pixels, m.width, m.height, Bitmap.Config.ARGB_8888).asImageBitmap()
}

/** 2 phút: đủ để mở app Xem và quét, không để token nằm trên màn hình lâu. */
const val QR_VISIBLE_SEC: Int = 120
