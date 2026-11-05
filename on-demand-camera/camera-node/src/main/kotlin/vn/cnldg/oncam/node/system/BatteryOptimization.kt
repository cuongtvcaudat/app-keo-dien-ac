package vn.cnldg.oncam.node.system

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings

/**
 * Tiện ích tắt tối ưu pin — bắt buộc để service sống 24/7 trên ColorOS/MIUI/One UI.
 */
object BatteryOptimization {

    /** App đã được bỏ khỏi danh sách tối ưu pin chưa. */
    fun isIgnoring(context: Context): Boolean =
        context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) == true

    /**
     * Hộp thoại hệ thống "Cho phép chạy nền không giới hạn".
     * Google Play hạn chế quyền này, nhưng app cài APK trực tiếp nên được dùng.
     */
    @SuppressLint("BatteryLife")
    fun requestIgnoreIntent(context: Context): Intent =
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))

    /** Trang "Thông tin ứng dụng" (ColorOS: Pin → cho phép hoạt động nền, tự khởi chạy). */
    fun appDetailsIntent(context: Context): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))

    /** Danh sách tối ưu pin của hệ thống. */
    fun batteryListIntent(): Intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
}
