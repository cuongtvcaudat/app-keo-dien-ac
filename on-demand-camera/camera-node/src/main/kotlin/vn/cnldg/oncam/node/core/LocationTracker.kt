package vn.cnldg.oncam.node.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
 * Theo dõi vị trí máy camera khi bật "Chia sẻ vị trí": lấy ngay khi khởi động, mỗi [intervalMs] (mặc định 15 phút)
 * và khi app Xem bấm "Cập nhật vị trí". Lần lấy lỗi không xoá vị trí cũ (vẫn hiện kèm thời điểm).
 * Lấy vị trí chạy ở nền ([requestUpdate] trả về ngay) để không chặn các lệnh camera.
 */
class LocationTracker(
    private val source: LocationSource,
    private val scope: CoroutineScope,
    private val intervalMs: Long = 15 * 60_000L,
    private val fixTimeoutMs: Long = 30_000L,
    private val log: LogSink = LogSink.NONE,
) {
    private val mutex = Mutex()
    private val _status = MutableStateFlow(LocationStatus())

    /** Trạng thái vị trí gửi kèm trạng thái máy camera. */
    val status: StateFlow<LocationStatus> = _status.asStateFlow()

    /** Lấy vị trí mới ở nền (bỏ qua nếu đang lấy). */
    fun requestUpdate() {
        if (_status.value.updating) return
        scope.launch { refresh() }
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
                        LocationStatus(point = fix.location, updating = false, error = null)
                    } else {
                        it.copy(updating = false, error = CameraCodes.LOCATION_UNAVAILABLE)
                    }
                is LocationFix.Failed -> it.copy(updating = false, error = fix.code)
            }
        }
        fix
    }

    /** Vòng cập nhật định kỳ (chạy suốt lúc node chạy; huỷ coroutine để dừng). */
    suspend fun run() {
        while (true) {
            refresh()
            delay(intervalMs)
        }
    }
}
