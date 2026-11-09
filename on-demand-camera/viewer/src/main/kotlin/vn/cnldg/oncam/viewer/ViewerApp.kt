package vn.cnldg.oncam.viewer

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import timber.log.Timber
import vn.cnldg.oncam.shared.OnCam
import vn.cnldg.oncam.viewer.alert.AlertCenter

/**
 * Application của viewer: điểm khởi tạo Hilt và Timber.
 */
@HiltAndroidApp
class ViewerApp : Application() {

    @Inject lateinit var alertCenter: AlertCenter

    override fun onCreate() {
        super.onCreate()
        alertCenter.start() // hiện cảnh báo từ camera (khi bật trong Cài đặt), kể cả lúc không có màn hình nào mở
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }
        Timber.i("Viewer khởi động, giao thức v%d", OnCam.PROTOCOL_VERSION)
    }
}
