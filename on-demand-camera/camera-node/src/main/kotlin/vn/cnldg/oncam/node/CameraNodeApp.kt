package vn.cnldg.oncam.node

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import timber.log.Timber
import vn.cnldg.oncam.shared.OnCam

/**
 * Application của camera-node: điểm khởi tạo Hilt và Timber.
 */
@HiltAndroidApp
class CameraNodeApp : Application() {

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }
        Timber.i("Camera node khởi động, giao thức v%d", OnCam.PROTOCOL_VERSION)
    }
}
