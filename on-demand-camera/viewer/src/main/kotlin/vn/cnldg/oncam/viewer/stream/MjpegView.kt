package vn.cnldg.oncam.viewer.stream

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import vn.cnldg.oncam.viewer.R

/**
 * Hiển thị luồng MJPEG (Compose): vẽ khung mới nhất của [StreamClient], giữ đúng tỉ lệ, nền đen.
 * [overlay] vẽ đè lên video (thông báo trạng thái, nút điều khiển).
 */
@Composable
fun MjpegView(
    state: StreamState,
    modifier: Modifier = Modifier,
    overlay: @Composable () -> Unit = {},
) {
    Box(modifier = modifier.background(Color.Black), contentAlignment = Alignment.Center) {
        val bmp = state.frame
        if (bmp != null) {
            // Bitmap được tái sử dụng → tạo ImageBitmap mới theo số thứ tự khung để Compose vẽ lại.
            val image = remember(state.frameSeq) { bmp.asImageBitmap() }
            Image(
                bitmap = image,
                contentDescription = stringResource(R.string.live_video_desc),
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        }
        overlay()
    }
}
