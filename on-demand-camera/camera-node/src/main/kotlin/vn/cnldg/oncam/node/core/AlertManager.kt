package vn.cnldg.oncam.node.core

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import vn.cnldg.oncam.shared.LogSink
import vn.cnldg.oncam.shared.protocol.AlertType
import vn.cnldg.oncam.shared.protocol.DeviceAlert
import vn.cnldg.oncam.shared.protocol.MAX_ALERT_SNAPSHOT_BYTES
import vn.cnldg.oncam.shared.security.AlertAuth

/** Loại cảnh báo được bật trên máy camera (cấu hình). */
data class AlertSettings(
    val motion: Boolean = true,
    val power: Boolean = true,
    val heat: Boolean = true,
) {
    /** Loại [type] có được báo không. */
    fun allows(type: AlertType): Boolean = when (type) {
        AlertType.MOTION -> motion
        AlertType.POWER_LOST, AlertType.POWER_RESTORED, AlertType.BATTERY_LOW -> power
        AlertType.OVERHEAT -> heat
    }
}

/**
 * Quyết định khi nào báo và gửi cảnh báo (0.12): qua MQTT ([outgoing], app Xem nhận qua Tailscale) và qua email
 * ([mailer], dự phòng khi máy xem tắt app/mất mạng).
 *
 * - MOTION: khi bắt đầu một clip do chuyển động; tối đa 1 lần/[Policy.motionCooldownMs], email tối đa
 *   1 lần/[Policy.motionEmailCooldownMs] (Gmail giới hạn ~500 mail/ngày). Ảnh chụp sau [Policy.motionSnapshotDelayMs]
 *   để thấy rõ người/xe (khung lúc phát hiện thường mới lấp ló).
 * - POWER_LOST: rút nguồn ≥ [Policy.powerLostAfterMs] (bỏ qua lỏng dây chớp nhoáng); POWER_RESTORED khi cắm lại.
 *   Dựa vào **có cắm nguồn** chứ không phải "đang sạc": Samsung "Bảo vệ pin 85 %" ngừng sạc khi vẫn cắm điện.
 * - BATTERY_LOW: không cắm nguồn và pin ≤ [Policy.batteryLowAt] %, báo lại khi pin đã lên ≥ [Policy.batteryResetAt] %.
 * - OVERHEAT: nhiệt độ pin ≥ [Policy.heatAtC] °C, báo lại khi đã nguội ≤ [Policy.heatResetAtC] °C và qua
 *   [Policy.heatCooldownMs].
 *
 * [onDevice] gọi tuần tự (một vòng đọc thông tin máy), [onMotion] gọi từ luồng bất kỳ.
 */
class AlertManager(
    private val deviceId: String,
    private val auth: AlertAuth,
    private val scope: CoroutineScope,
    private val settings: () -> AlertSettings,
    private val cameraName: () -> String,
    private val snapshot: () -> ByteArray?,
    private val mapsUrl: () -> String? = { null },
    private val mailer: ((MailMessage) -> Unit)? = null,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val policy: Policy = Policy(),
    private val log: LogSink = LogSink.NONE,
) {
    /** Ngưỡng/thời gian (rút ngắn được trong test). */
    data class Policy(
        val motionCooldownMs: Long = 60_000,
        val motionEmailCooldownMs: Long = 5 * 60_000L,
        val motionSnapshotDelayMs: Long = 1_200,
        val powerLostAfterMs: Long = 30_000,
        val batteryLowAt: Int = 20,
        val batteryResetAt: Int = 30,
        val heatAtC: Double = 42.0,
        val heatResetAtC: Double = 39.0,
        val heatCooldownMs: Long = 30 * 60_000L,
        val emailRetryDelaysMs: List<Long> = listOf(30_000, 120_000),
    )

    private val queue = Channel<DeviceAlert>(capacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** Cảnh báo đã ký, chờ gửi qua MQTT (giữ lại nếu đang mất kết nối; app Xem bỏ cái cũ hơn 10 phút). */
    val outgoing: Flow<DeviceAlert> = queue.receiveAsFlow()

    private var lastMotionMs = Long.MIN_VALUE / 2
    private var lastMotionEmailMs = Long.MIN_VALUE / 2
    private var lastPlugged: Boolean? = null
    private var unpluggedSinceMs: Long? = null
    private var powerLostSent = false
    private var batteryLowSent = false
    private var heatActive = false
    private var lastHeatMs = Long.MIN_VALUE / 2

    /** Bắt đầu một clip do chuyển động ([clip] = tên file). */
    fun onMotion(clip: String?) {
        if (!settings().motion) return
        val now = nowMs()
        val email: Boolean
        synchronized(this) {
            if (now - lastMotionMs < policy.motionCooldownMs) return
            lastMotionMs = now
            email = now - lastMotionEmailMs >= policy.motionEmailCooldownMs
            if (email) lastMotionEmailMs = now
        }
        scope.launch {
            delay(policy.motionSnapshotDelayMs)
            send(AlertType.MOTION, now, null, clip = clip, email = email)
        }
    }

    /** Thông tin máy mới (pin, cắm nguồn, nhiệt độ) — gọi định kỳ ~10 giây. */
    fun onDevice(d: DeviceSnapshot) {
        val now = nowMs()
        val plugged = d.plugged
        if (plugged != null) {
            val before = lastPlugged
            lastPlugged = plugged
            if (plugged) {
                unpluggedSinceMs = null
                if (powerLostSent) {
                    powerLostSent = false
                    send(AlertType.POWER_RESTORED, now, d)
                }
                if ((d.batteryPercent ?: 0) >= policy.batteryResetAt) batteryLowSent = false
            } else {
                // Lần đọc đầu (vừa mở app) mà đang không cắm thì không tính là mất điện.
                if (before == true && unpluggedSinceMs == null) unpluggedSinceMs = now
                val since = unpluggedSinceMs
                if (since != null && !powerLostSent && now - since >= policy.powerLostAfterMs) {
                    powerLostSent = true
                    send(AlertType.POWER_LOST, now, d)
                }
                val pct = d.batteryPercent
                if (pct != null && pct <= policy.batteryLowAt && !batteryLowSent) {
                    batteryLowSent = true
                    send(AlertType.BATTERY_LOW, now, d)
                }
            }
        }
        val temp = d.temperatureC ?: return
        if (temp >= policy.heatAtC && !heatActive && now - lastHeatMs >= policy.heatCooldownMs) {
            heatActive = true
            lastHeatMs = now
            send(AlertType.OVERHEAT, now, d)
        } else if (temp <= policy.heatResetAtC) {
            heatActive = false
        }
    }

    private fun send(type: AlertType, atMs: Long, d: DeviceSnapshot?, clip: String? = null, email: Boolean = true) {
        if (!settings().allows(type)) return
        val jpeg = snapshot()?.takeIf { it.size <= MAX_ALERT_SNAPSHOT_BYTES }
        val alert = auth.sign(
            DeviceAlert(
                type = type,
                timestampMs = atMs,
                cameraName = cameraName(),
                batteryPercent = d?.batteryPercent,
                temperatureC = d?.temperatureC,
                clip = clip,
                snapshot = jpeg?.let { Base64.getEncoder().encodeToString(it) },
                mapsUrl = mapsUrl(),
            ),
        )
        queue.trySend(alert)
        log.log(LogSink.Level.INFO, "Cảnh báo $type", null)
        val m = mailer ?: return
        if (!email) return
        scope.launch(Dispatchers.IO) { sendMail(m, mail(alert, jpeg)) }
    }

    /** Gửi email, lỗi mạng thì thử lại theo [Policy.emailRetryDelaysMs] (sai mật khẩu thì thôi). */
    private suspend fun sendMail(m: (MailMessage) -> Unit, message: MailMessage) {
        val delays = listOf(0L) + policy.emailRetryDelaysMs
        for ((i, wait) in delays.withIndex()) {
            if (wait > 0) delay(wait)
            try {
                withContext(Dispatchers.IO) { m(message) }
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: SmtpException) {
                log.log(LogSink.Level.WARN, "Gửi email cảnh báo bị từ chối (mã ${e.code})", null)
                if (e.code in 500..599) return // sai mật khẩu ứng dụng / địa chỉ: thử lại vô ích
            } catch (e: Exception) {
                log.log(LogSink.Level.WARN, "Gửi email cảnh báo lỗi (lần ${i + 1})", e)
            }
        }
    }

    /** Nội dung email tiếng Việt. */
    internal fun mail(a: DeviceAlert, jpeg: ByteArray?): MailMessage {
        val time = Instant.ofEpochMilli(a.timestampMs).atZone(zone)
        val name = a.cameraName ?: deviceId
        val title = when (a.type) {
            AlertType.MOTION -> "Có chuyển động"
            AlertType.POWER_LOST -> "Mất điện / rút nguồn"
            AlertType.POWER_RESTORED -> "Đã có điện lại"
            AlertType.BATTERY_LOW -> "Pin yếu (${a.batteryPercent ?: "?"} %)"
            AlertType.OVERHEAT -> "Máy camera quá nóng (${a.temperatureC?.let(::celsius) ?: "?"})"
        }
        val detail = when (a.type) {
            AlertType.MOTION -> "Máy camera đang tự quay clip" + (a.clip?.let { " $it" } ?: "") +
                ". Mở app OnCam Xem → \"Tải video về máy\" để xem."
            AlertType.POWER_LOST -> "Máy camera không còn cắm nguồn hơn 30 giây (mất điện lưới hoặc bị rút dây). " +
                "Máy đang chạy bằng pin."
            AlertType.POWER_RESTORED -> "Máy camera đã được cắm nguồn lại."
            AlertType.BATTERY_LOW -> "Máy camera không cắm nguồn và pin sắp hết; hết pin là camera tắt."
            AlertType.OVERHEAT -> "Máy camera tự hạ chất lượng; ≥ 45 °C sẽ tự tắt camera. Đặt máy chỗ thoáng, tránh nắng."
        }
        val body = buildString {
            append("Camera: ").append(name).append('\n')
            append("Lúc: ").append(time.format(FULL)).append(" (giờ máy camera)\n")
            append("Sự kiện: ").append(title).append('\n')
            append(detail).append('\n')
            val info = listOfNotNull(
                a.batteryPercent?.let { "Pin: $it %" },
                a.temperatureC?.let { "Nhiệt độ: ${celsius(it)}" },
            )
            if (info.isNotEmpty()) append(info.joinToString(" • ")).append('\n')
            a.mapsUrl?.let { append("Vị trí: ").append(it).append('\n') }
            append('\n')
            append(if (jpeg != null) "Ảnh lúc báo được đính kèm.\n" else "Camera đang tắt nên không có ảnh.\n")
            append("— Gửi tự động từ OnCam Quay (").append(deviceId).append(").")
        }
        return MailMessage(
            subject = "[OnCam] $name: $title lúc ${time.format(SHORT)}",
            body = body,
            attachmentName = jpeg?.let { "oncam-${deviceId}-${time.format(FILE)}.jpg" },
            attachment = jpeg,
        )
    }

    private companion object {
        val FULL: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss dd/MM/yyyy", Locale.US)
        val SHORT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm dd/MM", Locale.US)
        val FILE: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.US)

        fun celsius(c: Double) = String.format(Locale.US, "%.1f °C", c).replace('.', ',')
    }
}
