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
import vn.cnldg.oncam.shared.protocol.AlertType

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

    private val _alertsEnabled = MutableStateFlow(prefs.getBoolean(KEY_ALERTS, false))

    /**
     * Nhận cảnh báo (0.12): app giữ kết nối tới camera cả khi bị ẩn (dịch vụ nền + thông báo thường trực) và hiện
     * thông báo kèm ảnh. Mặc định tắt vì tốn thêm pin và cần quyền thông báo.
     */
    val alertsEnabled: StateFlow<Boolean> = _alertsEnabled.asStateFlow()

    private val _notifyMotion = MutableStateFlow(prefs.getBoolean(KEY_NOTIFY_MOTION, true))

    /** Hiện cảnh báo có chuyển động. */
    val notifyMotion: StateFlow<Boolean> = _notifyMotion.asStateFlow()

    private val _notifyPower = MutableStateFlow(prefs.getBoolean(KEY_NOTIFY_POWER, true))

    /** Hiện cảnh báo mất điện / có điện lại / pin yếu. */
    val notifyPower: StateFlow<Boolean> = _notifyPower.asStateFlow()

    private val _notifyHeat = MutableStateFlow(prefs.getBoolean(KEY_NOTIFY_HEAT, true))

    /** Hiện cảnh báo máy camera quá nóng. */
    val notifyHeat: StateFlow<Boolean> = _notifyHeat.asStateFlow()

    /** Máy này có muốn nhận loại cảnh báo [type] không. */
    fun wants(type: AlertType): Boolean = when (type) {
        AlertType.MOTION -> _notifyMotion.value
        AlertType.POWER_LOST, AlertType.POWER_RESTORED, AlertType.BATTERY_LOW -> _notifyPower.value
        AlertType.OVERHEAT -> _notifyHeat.value
    }

    /** Bật/tắt nhận cảnh báo nền. */
    fun setAlertsEnabled(value: Boolean) {
        prefs.edit { putBoolean(KEY_ALERTS, value) }
        _alertsEnabled.value = value
    }

    /** Bật/tắt từng loại cảnh báo. */
    fun setNotifyMotion(value: Boolean) {
        prefs.edit { putBoolean(KEY_NOTIFY_MOTION, value) }
        _notifyMotion.value = value
    }

    /** Bật/tắt cảnh báo nguồn điện. */
    fun setNotifyPower(value: Boolean) {
        prefs.edit { putBoolean(KEY_NOTIFY_POWER, value) }
        _notifyPower.value = value
    }

    /** Bật/tắt cảnh báo quá nhiệt. */
    fun setNotifyHeat(value: Boolean) {
        prefs.edit { putBoolean(KEY_NOTIFY_HEAT, value) }
        _notifyHeat.value = value
    }

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
        const val KEY_ALERTS = "alerts_enabled"
        const val KEY_NOTIFY_MOTION = "notify_motion"
        const val KEY_NOTIFY_POWER = "notify_power"
        const val KEY_NOTIFY_HEAT = "notify_heat"
    }
}
