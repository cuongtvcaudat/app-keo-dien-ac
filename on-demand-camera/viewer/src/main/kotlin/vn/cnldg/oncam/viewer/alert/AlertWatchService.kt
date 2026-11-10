package vn.cnldg.oncam.viewer.alert

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import timber.log.Timber
import vn.cnldg.oncam.viewer.data.ViewerSettings
import vn.cnldg.oncam.viewer.mqtt.CommandSender

/**
 * Dịch vụ nền "Nhận cảnh báo" (0.12): giữ kết nối MQTT tới các camera khi app bị ẩn để cảnh báo tới ngay.
 *
 * - Có thông báo thường trực (bắt buộc với dịch vụ chạy nền) — người dùng luôn biết app đang chạy.
 * - Chỉ chạy khi bật "Nhận cảnh báo" trong Cài đặt; tắt cài đặt là dừng.
 * - Việc hiện cảnh báo do [AlertCenter] làm (chạy cùng tiến trình); dịch vụ này chỉ giữ tiến trình + kết nối sống.
 */
@AndroidEntryPoint
class AlertWatchService : Service() {

    @Inject lateinit var sender: CommandSender

    @Inject lateinit var notifier: AlertNotifier

    @Inject lateinit var settings: ViewerSettings

    private var watching = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Luôn gọi startForeground trước (Android bắt buộc trong vài giây sau startForegroundService).
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        try {
            ServiceCompat.startForeground(this, AlertNotifier.WATCH_NOTIFICATION_ID, notifier.buildWatching(), type)
        } catch (e: Exception) {
            // Android 12+: hệ thống không cho chạy nền lúc này (app chưa được "Cho phép chạy nền").
            Timber.w(e, "Không chạy được dịch vụ nhận cảnh báo")
            stopSelf()
            return START_NOT_STICKY
        }
        if (!settings.alertsEnabled.value) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!watching) {
            watching = true
            sender.startWatching()
            Timber.i("Bắt đầu nhận cảnh báo nền")
        }
        return START_STICKY
    }

    override fun onDestroy() {
        if (watching) {
            watching = false
            sender.stopWatching()
            Timber.i("Dừng nhận cảnh báo nền")
        }
        super.onDestroy()
    }

    companion object {
        /** Chạy dịch vụ nếu đang bật cài đặt; trả false nếu hệ thống từ chối. */
        fun start(context: Context): Boolean = try {
            ContextCompat.startForegroundService(context, Intent(context, AlertWatchService::class.java))
            true
        } catch (e: Exception) {
            Timber.w(e, "Không khởi động được dịch vụ nhận cảnh báo")
            false
        }

        /** Dừng dịch vụ. */
        fun stop(context: Context) {
            context.stopService(Intent(context, AlertWatchService::class.java))
        }
    }
}
