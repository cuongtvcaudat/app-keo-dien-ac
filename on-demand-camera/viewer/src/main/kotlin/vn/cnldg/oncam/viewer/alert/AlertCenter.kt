package vn.cnldg.oncam.viewer.alert

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import vn.cnldg.oncam.viewer.data.ViewerSettings
import vn.cnldg.oncam.viewer.di.ApplicationScope
import vn.cnldg.oncam.viewer.mqtt.CommandSender

/**
 * Nhận cảnh báo từ mọi camera và hiện thông báo Android (khi bật "Nhận cảnh báo" và đúng loại đã chọn).
 * Chạy suốt đời tiến trình app; khi app ẩn, [AlertWatchService] giữ tiến trình và kết nối sống.
 */
@Singleton
class AlertCenter @Inject constructor(
    private val sender: CommandSender,
    private val settings: ViewerSettings,
    private val notifier: AlertNotifier,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private var started = false

    /** Bắt đầu lắng nghe (gọi một lần lúc app khởi động). */
    @Synchronized
    fun start() {
        if (started) return
        started = true
        scope.launch {
            sender.alerts.collect { a ->
                if (settings.alertsEnabled.value && settings.wants(a.alert.type)) notifier.show(a)
            }
        }
    }
}
