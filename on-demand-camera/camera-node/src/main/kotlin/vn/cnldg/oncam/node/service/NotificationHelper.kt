package vn.cnldg.oncam.node.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import vn.cnldg.oncam.node.MainActivity
import vn.cnldg.oncam.node.R
import vn.cnldg.oncam.node.core.BrokerState
import vn.cnldg.oncam.node.core.NodeState
import vn.cnldg.oncam.shared.mqtt.MqttConnectionState
import vn.cnldg.oncam.shared.protocol.CameraState

/**
 * Notification thường trực của Foreground Service (bắt buộc từ Android 8) + nút "Dừng" khẩn cấp.
 */
@Singleton
class NotificationHelper @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    /** Tạo kênh thông báo (gọi nhiều lần không sao). */
    fun ensureChannels() {
        val manager = NotificationManagerCompat.from(context)
        manager.createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL_STATUS, NotificationManagerCompat.IMPORTANCE_LOW)
                .setName(context.getString(R.string.notif_channel_status))
                .setDescription(context.getString(R.string.notif_channel_status_desc))
                .setShowBadge(false)
                .build(),
        )
        manager.createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL_ALERT, NotificationManagerCompat.IMPORTANCE_DEFAULT)
                .setName(context.getString(R.string.notif_channel_alert))
                .build(),
        )
    }

    /** Dòng trạng thái ngắn hiển thị trên notification. */
    fun statusText(state: NodeState?): String {
        if (state == null || !state.running) return context.getString(R.string.notif_starting)
        val mqtt = state.mqtt
        return when {
            // Người cầm máy camera luôn biết máy đang quay (video lưu trên máy).
            state.camera.recording.active -> context.getString(R.string.notif_recording)
            state.camera.state == CameraState.STREAMING ->
                context.getString(R.string.notif_streaming, state.camera.clients)
            state.camera.state == CameraState.STARTING -> context.getString(R.string.notif_camera_starting)
            state.broker is BrokerState.WaitingForAddress -> context.getString(R.string.notif_waiting_tailscale)
            state.broker is BrokerState.Failed -> context.getString(R.string.notif_broker_failed)
            mqtt == MqttConnectionState.Connected -> context.getString(R.string.notif_listening)
            mqtt is MqttConnectionState.Disconnected -> context.getString(
                R.string.notif_disconnected,
                (mqtt.retryInMs / 1000).toInt().coerceAtLeast(1),
            )
            else -> context.getString(R.string.notif_connecting)
        }
    }

    /** Notification của Foreground Service; [cameraOn] = thêm nút "Tắt camera" (spec mục 4.1.3). */
    fun buildOngoing(text: String, cameraOn: Boolean): Notification {
        val openApp = PendingIntent.getActivity(
            context,
            REQUEST_OPEN,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            context,
            REQUEST_STOP,
            CameraForegroundService.stopIntent(context),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = NotificationCompat.Builder(context, CHANNEL_STATUS)
            .setSmallIcon(R.drawable.ic_stat_node)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(openApp)
        if (cameraOn) {
            val stopCamera = PendingIntent.getService(
                context,
                REQUEST_STOP_CAMERA,
                CameraForegroundService.stopCameraIntent(context),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            builder.addAction(0, context.getString(R.string.notif_action_stop_camera), stopCamera)
        }
        return builder.addAction(0, context.getString(R.string.notif_action_stop), stop).build()
    }

    /** Cập nhật nội dung notification thường trực. */
    fun updateOngoing(text: String, cameraOn: Boolean) = notifyIfAllowed(NOTIFICATION_ID, buildOngoing(text, cameraOn))

    /** Báo người dùng service không tự chạy lại được (vd. hệ thống chặn khởi động từ nền). */
    fun showCannotStart() {
        val openApp = PendingIntent.getActivity(
            context,
            REQUEST_OPEN,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ALERT)
            .setSmallIcon(R.drawable.ic_stat_node)
            .setContentTitle(context.getString(R.string.notif_cannot_start_title))
            .setContentText(context.getString(R.string.notif_cannot_start_text))
            .setAutoCancel(true)
            .setContentIntent(openApp)
            .build()
        notifyIfAllowed(ALERT_ID, notification)
    }

    // Đã kiểm tra quyền POST_NOTIFICATIONS ngay trước khi gọi notify (Android 13+).
    @SuppressLint("MissingPermission")
    private fun notifyIfAllowed(id: Int, notification: Notification) {
        val allowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (allowed) NotificationManagerCompat.from(context).notify(id, notification)
    }

    companion object {
        /** Id notification của Foreground Service. */
        const val NOTIFICATION_ID: Int = 1001
        private const val ALERT_ID = 1002
        private const val CHANNEL_STATUS = "oncam_node_status"
        private const val CHANNEL_ALERT = "oncam_node_alert"
        private const val REQUEST_OPEN = 1
        private const val REQUEST_STOP = 2
        private const val REQUEST_STOP_CAMERA = 3
    }
}
