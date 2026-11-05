package vn.cnldg.oncam.viewer

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import timber.log.Timber
import vn.cnldg.oncam.shared.OnCam

/**
 * Application của viewer: điểm khởi tạo Hilt và Timber.
 */
@HiltAndroidApp
class ViewerApp : Application() {

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }
        Timber.i("Viewer khởi động, giao thức v%d", OnCam.PROTOCOL_VERSION)
    }
}
