package vn.cnldg.oncam.app

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import javax.inject.Provider
import timber.log.Timber
import vn.cnldg.oncam.shared.OnCam
import vn.cnldg.oncam.viewer.alert.AlertCenter

/**
 * Application gộp: điểm khởi tạo Hilt + Timber cho cả 2 vai trò.
 *
 * AlertCenter (nhận cảnh báo của vai trò Máy xem) lấy qua [Provider] để **chỉ dựng khi máy này là Máy xem** —
 * máy Camera không đụng tới đồ hình của phần xem.
 */
@HiltAndroidApp
class OnCamApp : Application() {

    @Inject lateinit var alertCenter: Provider<AlertCenter>

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }
        if (RoleStore.role(this) == AppRole.VIEWER) {
            alertCenter.get().start() // hiện cảnh báo (khi bật trong Cài đặt) kể cả lúc không mở màn hình nào
        }
        Timber.i("OnCam khởi động (vai trò %s), giao thức v%d", RoleStore.role(this), OnCam.PROTOCOL_VERSION)
    }
}
