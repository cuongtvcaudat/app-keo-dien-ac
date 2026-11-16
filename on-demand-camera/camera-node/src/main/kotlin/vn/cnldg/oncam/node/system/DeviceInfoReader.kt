package vn.cnldg.oncam.node.system

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import vn.cnldg.oncam.node.core.DeviceInfoSource
import vn.cnldg.oncam.node.core.DeviceSnapshot
import vn.cnldg.oncam.shared.protocol.NetworkType

/**
 * Đọc pin, nhiệt độ PIN (BatteryManager, đơn vị 0,1°C — không phải nhiệt độ CPU) và loại mạng.
 */
@Singleton
class DeviceInfoReader @Inject constructor(
    @ApplicationContext private val context: Context,
) : DeviceInfoSource {

    override fun snapshot(): DeviceSnapshot {
        // Broadcast "dính" (sticky): truyền receiver null để chỉ đọc giá trị mới nhất, không đăng ký lâu dài.
        // ACTION_BATTERY_CHANGED là broadcast hệ thống được bảo vệ nên không cần cờ RECEIVER_*.
        val battery: Intent? = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val status = battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val tenthsC = battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE) ?: Int.MIN_VALUE
        val plugged = battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) ?: -1

        return DeviceSnapshot(
            batteryPercent = if (level >= 0 && scale > 0) level * 100 / scale else null,
            charging = when (status) {
                BatteryManager.BATTERY_STATUS_CHARGING, BatteryManager.BATTERY_STATUS_FULL -> true
                -1 -> null
                else -> false
            },
            temperatureC = if (tenthsC == Int.MIN_VALUE) null else tenthsC / 10.0,
            network = networkType(),
            // Cảnh báo mất điện dựa vào đây (không dựa vào "đang sạc": bảo vệ pin 85 % ngừng sạc khi vẫn cắm).
            plugged = if (plugged < 0) null else plugged != 0,
        )
    }

    private fun networkType(): NetworkType {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return NetworkType.NONE
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return NetworkType.NONE
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkType.WIFI
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkType.CELLULAR
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetworkType.ETHERNET
            else -> NetworkType.OTHER // vd. chỉ thấy VPN Tailscale
        }
    }
}
