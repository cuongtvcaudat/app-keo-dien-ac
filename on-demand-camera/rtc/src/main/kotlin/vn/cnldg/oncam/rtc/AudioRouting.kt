package vn.cnldg.oncam.rtc

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build

/**
 * Chuyển tiếng sang chế độ đàm thoại + loa ngoài khi có phiên WebRTC, trả lại như cũ khi hết phiên.
 *
 * Chế độ đàm thoại (MODE_IN_COMMUNICATION) bật khử vọng/lọc ồn của máy. Có tai nghe thì để Android tự chọn.
 * Đếm số phiên: nhiều phiên cùng lúc chỉ bật một lần, phiên cuối đóng mới trả lại.
 *
 * @param maxVolume true ở máy camera: đặt âm lượng đàm thoại tối đa để người ở sân nghe rõ, khôi phục khi xong.
 */
class AudioRouting(context: Context, private val maxVolume: Boolean) {
    private val audio = context.applicationContext.getSystemService(AudioManager::class.java)
    private var users = 0
    private var savedMode = AudioManager.MODE_NORMAL
    private var savedVolume = -1

    /** Bắt đầu dùng tiếng (mở phiên). */
    @Synchronized
    fun acquire() {
        val am = audio ?: return
        if (users++ > 0) return
        savedMode = am.mode
        am.mode = AudioManager.MODE_IN_COMMUNICATION
        if (!hasHeadset(am)) speaker(am, on = true)
        if (maxVolume) {
            savedVolume = am.getStreamVolume(AudioManager.STREAM_VOICE_CALL)
            // Máy đang bật Không làm phiền có thể chặn đổi âm lượng (SecurityException) → bỏ qua.
            runCatching {
                am.setStreamVolume(AudioManager.STREAM_VOICE_CALL, am.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL), 0)
            }
        }
    }

    /** Thôi dùng tiếng (đóng phiên). */
    @Synchronized
    fun release() {
        val am = audio ?: return
        if (users == 0 || --users > 0) return
        speaker(am, on = false)
        if (maxVolume && savedVolume >= 0) runCatching { am.setStreamVolume(AudioManager.STREAM_VOICE_CALL, savedVolume, 0) }
        am.mode = savedMode
    }

    private fun speaker(am: AudioManager, on: Boolean) = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (on) {
                am.availableCommunicationDevices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                    ?.let { am.setCommunicationDevice(it) }
            } else {
                am.clearCommunicationDevice()
            }
        } else {
            @Suppress("DEPRECATION")
            am.isSpeakerphoneOn = on
        }
    }

    private fun hasHeadset(am: AudioManager): Boolean =
        am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any {
            it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                it.type == AudioDeviceInfo.TYPE_USB_HEADSET
        }
}
