package vn.cnldg.oncam.viewer.alert

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.util.Base64
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import timber.log.Timber
import vn.cnldg.oncam.shared.protocol.AlertType
import vn.cnldg.oncam.shared.protocol.DeviceAlert
import vn.cnldg.oncam.viewer.MainActivity
import vn.cnldg.oncam.viewer.R
import vn.cnldg.oncam.viewer.mqtt.CameraAlert

/**
 * Thông báo Android của app Xem (0.12): cảnh báo từ camera (kèm ảnh) và thông báo thường trực của dịch vụ nhận
 * cảnh báo.
 */
@Singleton
class AlertNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val manager = NotificationManagerCompat.from(context)

    /** Tạo 2 kênh thông báo (gọi nhiều lần không sao). */
    fun ensureChannels() {
        manager.createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL_ALERTS, NotificationManagerCompat.IMPORTANCE_HIGH)
                .setName(context.getString(R.string.channel_alerts))
                .setDescription(context.getString(R.string.channel_alerts_desc))
                .build(),
        )
        manager.createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL_WATCH, NotificationManagerCompat.IMPORTANCE_LOW)
                .setName(context.getString(R.string.channel_watch))
                .setDescription(context.getString(R.string.channel_watch_desc))
                .setShowBadge(false)
                .build(),
        )
    }

    /** App có được hiện thông báo không (Android 13+ phải xin quyền; người dùng có thể tắt trong Cài đặt). */
    fun canNotify(): Boolean {
        val granted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        return granted && manager.areNotificationsEnabled()
    }

    /** Hiện cảnh báo; cùng camera + cùng loại thì thay thông báo cũ (không chồng hàng chục cái). */
    @SuppressLint("MissingPermission") // đã kiểm canNotify() ngay đầu hàm
    fun show(a: CameraAlert) {
        if (!canNotify()) {
            Timber.w("Không hiện được cảnh báo %s: chưa cho phép thông báo", a.alert.type)
            return
        }
        ensureChannels()
        val alert = a.alert
        val time = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(alert.timestampMs))
        val text = listOfNotNull(time, detail(alert)).joinToString(" • ")
        val builder = NotificationCompat.Builder(context, CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_stat_alert)
            .setContentTitle(context.getString(R.string.alert_title, a.name, title(context, alert)))
            .setContentText(text)
            .setWhen(alert.timestampMs)
            .setShowWhen(true)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(openApp())
        val picture = alert.snapshot?.let(::decode)
        if (picture != null) {
            builder.setLargeIcon(picture)
            builder.setStyle(NotificationCompat.BigPictureStyle().bigPicture(picture).bigLargeIcon(null as Bitmap?).setSummaryText(text))
        } else {
            builder.setStyle(NotificationCompat.BigTextStyle().bigText(text))
        }
        try {
            manager.notify("${a.deviceId}/${alert.type.name}".hashCode(), builder.build())
        } catch (e: SecurityException) {
            Timber.w(e, "Hệ thống từ chối hiện thông báo")
        }
    }

    /** Thông báo thường trực của dịch vụ nhận cảnh báo. */
    fun buildWatching(): Notification {
        ensureChannels()
        return NotificationCompat.Builder(context, CHANNEL_WATCH)
            .setSmallIcon(R.drawable.ic_stat_alert)
            .setContentTitle(context.getString(R.string.watch_title))
            .setContentText(context.getString(R.string.watch_text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openApp())
            .build()
    }

    private fun detail(a: DeviceAlert): String? = when (a.type) {
        AlertType.MOTION -> context.getString(R.string.alert_motion_detail)
        AlertType.POWER_LOST, AlertType.POWER_RESTORED, AlertType.BATTERY_LOW ->
            a.batteryPercent?.let { context.getString(R.string.alert_battery_detail, it) }
        AlertType.OVERHEAT -> a.temperatureC?.let { context.getString(R.string.value_celsius, it) }
    }

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** Giải mã ảnh JPEG (base64), thu nhỏ cho vừa thông báo (rộng ≤ ~1 024 px). */
    private fun decode(base64: String): Bitmap? = try {
        val bytes = Base64.decode(base64, Base64.DEFAULT)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (bounds.outWidth / sample > MAX_PICTURE_WIDTH) sample *= 2
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    } catch (e: Exception) {
        Timber.w(e, "Không giải mã được ảnh cảnh báo")
        null
    }

    companion object {
        /** Mã thông báo thường trực của dịch vụ. */
        const val WATCH_NOTIFICATION_ID: Int = 1001

        private const val CHANNEL_ALERTS = "oncam_alerts"
        private const val CHANNEL_WATCH = "oncam_watch"
        private const val MAX_PICTURE_WIDTH = 1_024

        /** Tên loại cảnh báo bằng tiếng Việt. */
        fun title(context: Context, a: DeviceAlert): String = context.getString(
            when (a.type) {
                AlertType.MOTION -> R.string.alert_motion
                AlertType.POWER_LOST -> R.string.alert_power_lost
                AlertType.POWER_RESTORED -> R.string.alert_power_restored
                AlertType.BATTERY_LOW -> R.string.alert_battery_low
                AlertType.OVERHEAT -> R.string.alert_overheat
            },
        )
    }
}
