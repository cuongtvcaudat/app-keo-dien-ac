package vn.cnldg.oncam.viewer.alert

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import timber.log.Timber
import vn.cnldg.oncam.viewer.data.ViewerSettings

/**
 * Sau khi khởi động lại máy / cập nhật app: chạy lại dịch vụ nhận cảnh báo nếu đang bật trong Cài đặt.
 * ColorOS (Oppo) chỉ gửi BOOT_COMPLETED khi app được bật "Cho phép tự khởi chạy".
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val settings = EntryPointAccessors.fromApplication(context.applicationContext, BootEntryPoint::class.java).settings()
        if (!settings.alertsEnabled.value) return
        Timber.i("Tự chạy dịch vụ nhận cảnh báo sau %s: %s", intent.action, AlertWatchService.start(context))
    }

    /** Lấy phụ thuộc Hilt trong BroadcastReceiver. */
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface BootEntryPoint {
        /** Cài đặt app Xem. */
        fun settings(): ViewerSettings
    }
}
