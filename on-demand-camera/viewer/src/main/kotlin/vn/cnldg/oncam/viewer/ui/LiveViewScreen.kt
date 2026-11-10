package vn.cnldg.oncam.viewer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import java.util.Locale
import kotlinx.coroutines.delay
import org.webrtc.EglBase
import vn.cnldg.oncam.shared.protocol.CameraState
import vn.cnldg.oncam.shared.protocol.StreamQuality
import vn.cnldg.oncam.viewer.R
import vn.cnldg.oncam.viewer.mqtt.CameraEntry
import vn.cnldg.oncam.viewer.rtc.LiveRtcState
import vn.cnldg.oncam.viewer.rtc.LiveTransport
import vn.cnldg.oncam.viewer.rtc.RtcVideoView
import vn.cnldg.oncam.viewer.stream.MjpegView
import vn.cnldg.oncam.viewer.stream.StreamPhase
import vn.cnldg.oncam.viewer.stream.StreamState

/**
 * Xem trực tiếp toàn màn hình: video (WebRTC nếu camera hỗ trợ, không thì MJPEG) + nút điều khiển đè lên video.
 * WebRTC có thêm nghe tiếng sân và "Giữ để nói". Chạm vào video để ẩn/hiện nút. Giữ màn hình sáng trong lúc xem.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LiveViewScreen(
    entry: CameraEntry?,
    name: String,
    stream: StreamState,
    busy: Boolean,
    onBack: () -> Unit,
    onToggleCamera: () -> Unit,
    onSwitchCamera: () -> Unit,
    onToggleFlash: () -> Unit,
    onQuality: (StreamQuality) -> Unit,
    onSnapshot: () -> Unit,
    rtc: LiveRtcState,
    egl: EglBase.Context?,
    onListen: (Boolean) -> Unit,
    onTalkEnable: (Boolean) -> Unit,
    onTalk: (Boolean) -> Unit,
    onToggleRecording: () -> Unit,
) {
    val context = LocalContext.current
    val view = LocalView.current
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
    var controlsVisible by rememberSaveable { mutableStateOf(true) }

    val status = entry?.view?.status
    val online = entry?.view?.online == true
    val camState = if (online) status?.cameraState ?: CameraState.OFF else CameraState.OFF
    val streaming = camState == CameraState.STREAMING
    val rtcVideo = rtc.video.takeIf { rtc.transport == LiveTransport.WEBRTC }
    val webrtc = rtcVideo != null && egl != null
    val playing = webrtc || stream.phase == StreamPhase.PLAYING
    // Quay video (camera bản ≥ 0.10): đồng hồ đếm giây trong lúc quay.
    val rec = status?.recording?.takeIf { online }
    var nowSec by remember { mutableLongStateOf(System.currentTimeMillis() / 1000) }
    LaunchedEffect(rec?.active) {
        while (rec?.active == true) {
            nowSec = System.currentTimeMillis() / 1000
            delay(1_000)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                controlsVisible = !controlsVisible
            },
        contentAlignment = Alignment.Center,
    ) {
        if (rtcVideo != null && egl != null) {
            RtcVideoView(rtcVideo, egl, Modifier.fillMaxSize())
        } else {
            MjpegView(state = stream, modifier = Modifier.fillMaxSize())
        }
        // Thông báo giữa màn hình khi chưa có hình.
        if (!playing) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.padding(24.dp),
            ) {
                // WebRTC tự bật camera khi bắt tay → lúc đó camera còn báo "tắt" nhưng thực ra đang mở.
                val connectingRtc = online && rtc.transport == LiveTransport.CONNECTING
                val cameraOff = !connectingRtc && (camState == CameraState.OFF || camState == CameraState.ERROR)
                val message = when {
                    !online -> stringResource(R.string.live_offline)
                    connectingRtc -> stringResource(R.string.live_connecting_rtc)
                    cameraOff -> stringResource(R.string.live_camera_off)
                    stream.phase == StreamPhase.RECONNECTING -> stringResource(R.string.live_reconnecting)
                    else -> stringResource(R.string.live_connecting)
                }
                if (online && !cameraOff) CircularProgressIndicator()
                Text(message, color = Color.White, style = MaterialTheme.typography.bodyLarge)
                status?.error?.takeIf { camState != CameraState.STREAMING }?.let {
                    Text(codeText(context, it), color = Color.White, style = MaterialTheme.typography.bodySmall)
                }
                if (online && cameraOff) {
                    Button(onClick = onToggleCamera, enabled = !busy) { Text(stringResource(R.string.action_camera_on)) }
                }
            }
        }

        if (controlsVisible || !playing) {
            Column(modifier = Modifier.fillMaxSize().safeDrawingPadding(), verticalArrangement = Arrangement.SpaceBetween) {
                // Thanh trên: quay lại, tên camera, thông số luồng.
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color.Black.copy(alpha = 0.5f))
                        .padding(horizontal = 4.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onBack) { Text(stringResource(R.string.action_back), color = Color.White) }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(name, color = Color.White, style = MaterialTheme.typography.titleMedium)
                        if (playing) {
                            Text(
                                if (webrtc) {
                                    rtcInfo(rtc, status?.viewers ?: 0)
                                } else {
                                    streamInfo(stream, status?.viewers ?: 0)
                                },
                                color = Color.White,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }

                // Thanh dưới: nút điều khiển.
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color.Black.copy(alpha = 0.5f))
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = onToggleCamera, enabled = online && !busy) {
                            Text(
                                stringResource(if (streaming) R.string.action_camera_off else R.string.action_camera_on),
                                color = Color.White,
                            )
                        }
                        OutlinedButton(onClick = onSwitchCamera, enabled = online && !busy) {
                            Text(stringResource(R.string.action_switch_camera), color = Color.White)
                        }
                        OutlinedButton(onClick = onToggleFlash, enabled = streaming && !busy) {
                            Text(
                                stringResource(if (status?.flashOn == true) R.string.action_flash_off else R.string.action_flash_on),
                                color = Color.White,
                            )
                        }
                        Button(onClick = onSnapshot, enabled = playing && !busy) {
                            Text(stringResource(R.string.action_snapshot))
                        }
                        if (rec != null) {
                            if (rec.active) {
                                Button(
                                    onClick = onToggleRecording,
                                    enabled = !busy,
                                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                                ) {
                                    Text(stringResource(R.string.action_record_stop, durationText(recordingElapsedSec(rec, nowSec))))
                                }
                            } else {
                                OutlinedButton(onClick = onToggleRecording, enabled = !busy) {
                                    Text(stringResource(R.string.action_record), color = Color.White)
                                }
                            }
                        }
                        // Vị trí máy camera (khi camera bật "Chia sẻ vị trí"): mở thẳng Google Maps.
                        entry?.view?.lastLocation?.let { point ->
                            val uri = LocalUriHandler.current
                            OutlinedButton(onClick = { runCatching { uri.openUri(point.mapsUrl()) } }) {
                                Text(stringResource(R.string.action_map), color = Color.White)
                            }
                        }
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        StreamQuality.entries.forEach { q ->
                            val label = stringResource(
                                when (q) {
                                    StreamQuality.LOW -> R.string.quality_low
                                    StreamQuality.MEDIUM -> R.string.quality_medium
                                    StreamQuality.HIGH -> R.string.quality_high
                                },
                            )
                            if (status?.quality == q) {
                                Button(onClick = {}, enabled = online && !busy) { Text(label) }
                            } else {
                                OutlinedButton(onClick = { onQuality(q) }, enabled = online && !busy) {
                                    Text(label, color = Color.White)
                                }
                            }
                        }
                    }
                    if (webrtc) {
                        AudioControls(rtc, onListen, onTalkEnable, onTalk)
                    } else {
                        val note = rtc.fallbackCode?.let { stringResource(R.string.live_mjpeg_fallback, it) }
                            ?: stringResource(R.string.live_no_audio)
                        Text(note, color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

/** Nghe tiếng sân + nói ra loa máy camera (chỉ có khi xem bằng WebRTC). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AudioControls(
    rtc: LiveRtcState,
    onListen: (Boolean) -> Unit,
    onTalkEnable: (Boolean) -> Unit,
    onTalk: (Boolean) -> Unit,
) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (rtc.hasAudio) {
            OutlinedButton(onClick = { onListen(!rtc.listening) }) {
                Text(
                    stringResource(if (rtc.listening) R.string.action_listen_off else R.string.action_listen_on),
                    color = Color.White,
                )
            }
        }
        if (rtc.talkEnabled) {
            TalkButton(rtc.talking, onTalk)
            TextButton(onClick = { onTalkEnable(false) }) { Text(stringResource(R.string.action_talk_disable), color = Color.White) }
        } else {
            OutlinedButton(onClick = { onTalkEnable(true) }) {
                Text(stringResource(R.string.action_talk_enable), color = Color.White)
            }
        }
    }
    if (!rtc.hasAudio) {
        Text(stringResource(R.string.live_no_camera_mic), color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.bodySmall)
    }
}

/** Nút kiểu bộ đàm: giữ thì nói (tiếng sân tạm tắt để không vọng), thả ra thì nghe. */
@Composable
private fun TalkButton(talking: Boolean, onTalk: (Boolean) -> Unit) {
    val current by rememberUpdatedState(onTalk)
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(24.dp))
            .background(if (talking) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        current(true)
                        try {
                            awaitRelease()
                        } finally {
                            current(false)
                        }
                    },
                )
            }
            .padding(horizontal = 20.dp, vertical = 10.dp),
    ) {
        Text(
            stringResource(if (talking) R.string.action_talking else R.string.action_hold_to_talk),
            color = if (talking) MaterialTheme.colorScheme.onError else MaterialTheme.colorScheme.onPrimary,
        )
    }
}

/** "WebRTC H264 1280×720 • 15 hình/giây • 820 kbit/s • 1 người xem" (chuẩn nén/kbit/s có sau ~2 giây). */
@Composable
private fun rtcInfo(rtc: LiveRtcState, viewers: Int): String {
    val st = rtc.stats
    val parts = buildList {
        add(listOfNotNull("WebRTC", st.codec, st.width?.let { w -> st.height?.let { h -> "$w×$h" } }).joinToString(" "))
        add(stringResource(R.string.live_fps, rtc.fps))
        st.kbps?.let { add(stringResource(R.string.live_kbps, it)) }
        add(stringResource(R.string.live_viewers, viewers))
    }
    return parts.joinToString(" • ")
}

@Composable
private fun streamInfo(stream: StreamState, viewers: Int): String {
    val latency = stream.latencyMs?.let { String.format(Locale.US, "%.1f", it / 1000.0) }
    return if (latency != null) {
        stringResource(R.string.live_info_latency, stream.fps ?: 0, latency, viewers)
    } else {
        stringResource(R.string.live_info, stream.fps ?: 0, viewers)
    }
}
