package vn.cnldg.oncam.viewer.rtc

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import org.webrtc.EglBase
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack

/**
 * Vẽ hình WebRTC bằng SurfaceViewRenderer (giải mã phần cứng vẽ thẳng lên màn hình, giữ đúng tỉ lệ).
 * Gắn/gỡ [track] theo vòng đời composable.
 */
@Composable
fun RtcVideoView(track: VideoTrack, egl: EglBase.Context, modifier: Modifier = Modifier) {
    var renderer by remember { mutableStateOf<SurfaceViewRenderer?>(null) }
    AndroidView(
        factory = { ctx ->
            SurfaceViewRenderer(ctx).apply {
                init(egl, null)
                setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
                setEnableHardwareScaler(true)
                renderer = this
            }
        },
        modifier = modifier,
        onRelease = { it.release() },
    )
    DisposableEffect(track, renderer) {
        val r = renderer
        if (r != null) runCatching { track.addSink(r) }
        onDispose {
            // Phiên có thể đã đóng và giải phóng track trước khi giao diện gỡ → bỏ qua lỗi "đã giải phóng".
            if (r != null) runCatching { track.removeSink(r) }
        }
    }
}
