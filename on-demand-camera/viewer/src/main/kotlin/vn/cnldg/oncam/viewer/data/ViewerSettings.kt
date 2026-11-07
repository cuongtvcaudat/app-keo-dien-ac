package vn.cnldg.oncam.viewer.data

import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import vn.cnldg.oncam.shared.OnCam

/** Cài đặt của app Xem (không bí mật). */
@Singleton
class ViewerSettings @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    private val _idleTimeoutSec = MutableStateFlow(prefs.getInt(KEY_IDLE, OnCam.DEFAULT_IDLE_TIMEOUT_SEC))

    /** Camera tự tắt sau bao nhiêu giây không có ai xem (gửi kèm lệnh bật camera). */
    val idleTimeoutSec: StateFlow<Int> = _idleTimeoutSec.asStateFlow()

    private val _stopOnLeave = MutableStateFlow(prefs.getBoolean(KEY_STOP_ON_LEAVE, true))

    /** Tắt camera do app này bật khi rời app (spec mục 7.3). */
    val stopOnLeave: StateFlow<Boolean> = _stopOnLeave.asStateFlow()

    /** Đặt thời gian chờ tự tắt. */
    fun setIdleTimeoutSec(value: Int) {
        val v = value.coerceIn(OnCam.MIN_IDLE_TIMEOUT_SEC, OnCam.MAX_IDLE_TIMEOUT_SEC)
        prefs.edit { putInt(KEY_IDLE, v) }
        _idleTimeoutSec.value = v
    }

    /** Bật/tắt "tắt camera khi rời app". */
    fun setStopOnLeave(value: Boolean) {
        prefs.edit { putBoolean(KEY_STOP_ON_LEAVE, value) }
        _stopOnLeave.value = value
    }

    private companion object {
        const val FILE = "oncam_viewer_settings"
        const val KEY_IDLE = "idle_timeout_sec"
        const val KEY_STOP_ON_LEAVE = "stop_on_leave"
    }
}
