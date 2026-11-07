package vn.cnldg.oncam.node.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import timber.log.Timber
import vn.cnldg.oncam.node.config.NodeConfigRepository

/**
 * Tự chạy lại camera node sau khi khởi động máy hoặc cập nhật app.
 *
 * Chỉ chạy nếu người dùng đã bấm "Khởi động" (chưa bấm "Dừng") và bật "Tự chạy khi khởi động máy".
 * Lưu ý: ColorOS chỉ gửi BOOT_COMPLETED nếu app được bật "Cho phép tự khởi chạy".
 * Sau khi khởi động máy, service chỉ nghe lệnh; muốn mở camera từ xa thì cần mở app một lần
 * (giới hạn của Android 11+, xem docs/ARCHITECTURE.md mục 5).
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val repo = EntryPointAccessors.fromApplication(context.applicationContext, BootEntryPoint::class.java)
            .configRepository()
        if (!repo.nodeEnabled || !repo.autoStart.value) {
            Timber.i("Bỏ qua tự khởi chạy (nodeEnabled=%s, autoStart=%s)", repo.nodeEnabled, repo.autoStart.value)
            return
        }
        val started = CameraForegroundService.start(context, fromUi = false)
        Timber.i("Tự khởi chạy camera node sau %s: %s", intent.action, started)
    }

    /** Lấy phụ thuộc Hilt trong BroadcastReceiver mà không cần @AndroidEntryPoint. */
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface BootEntryPoint {
        /** Kho cấu hình. */
        fun configRepository(): NodeConfigRepository
    }
}
