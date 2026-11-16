package vn.cnldg.oncam.viewer.rtc

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import vn.cnldg.oncam.rtc.AudioRouting
import vn.cnldg.oncam.rtc.RtcEngine
import vn.cnldg.oncam.viewer.log.TimberLogSink

/**
 * Bộ máy WebRTC của app Xem, tạo khi cần lần đầu và giữ suốt vòng đời app (tạo mất ~100 ms).
 * [engine] null nếu máy không chạy được WebRTC → app dùng MJPEG.
 */
@Singleton
class RtcProvider @Inject constructor(@ApplicationContext private val context: Context) {
    /** Đường tiếng của máy xem: chế độ đàm thoại + loa ngoài trong lúc xem trực tiếp. */
    val audioRouting: AudioRouting by lazy { AudioRouting(context, maxVolume = false) }

    /** Bộ máy WebRTC (EGL để giải mã phần cứng + vẽ video). */
    val engine: RtcEngine? by lazy { RtcEngine.create(context, TimberLogSink, forViewer = true) }
}
