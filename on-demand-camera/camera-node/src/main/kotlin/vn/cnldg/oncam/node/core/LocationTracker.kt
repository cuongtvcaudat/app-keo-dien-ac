package vn.cnldg.oncam.node.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import vn.cnldg.oncam.shared.LogSink
import vn.cnldg.oncam.shared.protocol.CameraCodes
import vn.cnldg.oncam.shared.protocol.GeoLocation
import vn.cnldg.oncam.shared.protocol.LocationStatus

/** Kết quả một lần lấy vị trí từ phần cứng. */
sealed interface LocationFix {
    /** Lấy được vị trí. */
    data class Ok(val location: GeoLocation) : LocationFix

    /** Không lấy được; [code] là CameraCodes.LOCATION_*. */
    data class Failed(val code: String) : LocationFix
}

/** Cổng tới GPS/mạng của máy (Android: `system/AndroidLocationSource`). Tách ra để test trên JVM. */
fun interface LocationSource {
    /** Lấy vị trí mới, chờ tối đa [timeoutMs]. */
    suspend fun fix(timeoutMs: Long): LocationFix
}

/**
 * Theo dõi vị trí máy camera khi bật "Chia sẻ vị trí".
 *
 * - Tự cập nhật mỗi [intervalMs] (cài đặt trên máy camera: 15 phút / 60 phút / tắt = null). Tắt thì GPS chỉ chạy
 *   khi app Xem yêu cầu, kể cả lúc khởi động cũng không lấy.
 * - "Cập nhật vị trí" ([requestUpdate]): lấy 1 lần.
 * - "Theo dõi" ([track]): lấy mỗi [trackIntervalMs] (1 phút) trong N phút rồi tự trở về chế độ thường.
 * GPS chỉ chạy trong lúc lấy một vị trí (vài giây tới tối đa [fixTimeoutMs]), không bật liên tục.
 * Lần lấy lỗi không xoá vị trí cũ. Lấy vị trí chạy ở nền để không chặn các lệnh camera.
 *
 * @param minuteMs độ dài "1 phút" của [track] (test đặt nhỏ để chạy nhanh).
 */
class LocationTracker(
    private val source: LocationSource,
    private val scope: CoroutineScope,
    private val intervalMs: Long? = 15 * 60_000L,
    private val trackIntervalMs: Long = 60_000L,
    private val fixTimeoutMs: Long = 30_000L,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val minuteMs: Long = 60_000L,
    private val log: LogSink = LogSink.NONE,
) {
    private val mutex = Mutex()
    private val wake = Channel<Unit>(Channel.CONFLATED)

    @Volatile
    private var trackUntilMs: Long? = null

    @Volatile
    private var refreshOnWake = false

    private val _status = MutableStateFlow(LocationStatus(canTrack = true, intervalSec = intervalMs?.let { (it / 1000).toInt() }))

    /** Trạng thái vị trí gửi kèm trạng thái máy camera. */
    val status: StateFlow<LocationStatus> = _status.asStateFlow()

    /** Lấy vị trí mới ở nền (bỏ qua nếu đang lấy). */
    fun requestUpdate() {
        if (_status.value.updating) return
        scope.launch { refresh() }
    }

    /** Theo dõi [minutes] phút, mỗi phút lấy 1 lần (bắt đầu ngay); 0 = dừng theo dõi. Bấm lại = gia hạn. */
    fun track(minutes: Int) {
        val until = if (minutes > 0) nowMs() + minutes * minuteMs else null
        trackUntilMs = until
        refreshOnWake = until != null
        _status.update { it.copy(trackingUntil = until?.let { ms -> (ms + 999) / 1000 }) }
        wake.trySend(Unit)
    }

    /** Lấy vị trí mới và chờ kết quả. */
    suspend fun refresh(): LocationFix = mutex.withLock {
        _status.update { it.copy(updating = true) }
        val fix = try {
            source.fix(fixTimeoutMs)
        } catch (e: RuntimeException) {
            log.log(LogSink.Level.WARN, "Lấy vị trí lỗi", e)
            LocationFix.Failed(CameraCodes.LOCATION_UNAVAILABLE)
        }
        _status.update {
            when (fix) {
                is LocationFix.Ok ->
                    if (fix.location.isValid()) {
                        it.copy(point = fix.location, updating = false, error = null)
                    } else {
                        it.copy(updating = false, error = CameraCodes.LOCATION_UNAVAILABLE)
                    }
                is LocationFix.Failed -> it.copy(updating = false, error = fix.code)
            }
        }
        fix
    }

    /** Vòng cập nhật (chạy suốt lúc node chạy; huỷ coroutine để dừng). */
    suspend fun run() {
        if (intervalMs != null) refresh()
        while (true) {
            expireTracking()
            val wait = if (tracking()) trackIntervalMs else intervalMs
            val woke = if (wait == null) {
                wake.receive()
                true
            } else {
                withTimeoutOrNull(wait) { wake.receive() } != null
            }
            expireTracking()
            // Hết giờ chờ = tới lượt cập nhật; bị đánh thức = vừa bắt đầu theo dõi (lấy ngay) hoặc vừa dừng (không lấy).
            val now = if (woke) refreshOnWake.also { refreshOnWake = false } else true
            if (now) refresh()
        }
    }

    private fun tracking(): Boolean = trackUntilMs?.let { nowMs() < it } == true

    private fun expireTracking() {
        val until = trackUntilMs ?: return
        if (nowMs() >= until) {
            trackUntilMs = null
            _status.update { it.copy(trackingUntil = null) }
        }
    }
}
