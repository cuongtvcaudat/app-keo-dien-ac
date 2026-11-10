package vn.cnldg.oncam.node.system

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Looper
import androidx.core.content.ContextCompat
import kotlin.coroutines.resume
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import vn.cnldg.oncam.node.core.LocationFix
import vn.cnldg.oncam.node.core.LocationSource
import vn.cnldg.oncam.shared.protocol.CameraCodes
import vn.cnldg.oncam.shared.protocol.GeoLocation

/**
 * Lấy vị trí bằng LocationManager có sẵn của Android (GPS + mạng Wi-Fi/trạm phát sóng) — không cần Google Play
 * Services hay thư viện thêm.
 *
 * Hỏi cùng lúc mọi nguồn đang bật, lấy kết quả chính xác nhất; có kết quả sai số ≤ [GOOD_ACCURACY_M] thì dừng sớm.
 * Hết giờ mà chưa có gì → dùng vị trí cũ nhất máy còn nhớ (thời điểm cũ sẽ hiện trên app Xem).
 *
 * @param allowed service có được dùng vị trí lúc này không (loại foreground "location", hoặc quyền "Luôn cho phép").
 */
class AndroidLocationSource(private val context: Context, private val allowed: () -> Boolean) : LocationSource {

    // Đã kiểm quyền ngay đầu hàm; lint không theo dõi được qua hàm phụ.
    @SuppressLint("MissingPermission")
    override suspend fun fix(timeoutMs: Long): LocationFix {
        if (!hasPermission() || !allowed()) return LocationFix.Failed(CameraCodes.LOCATION_NO_PERMISSION)
        val lm = context.getSystemService(LocationManager::class.java)
            ?: return LocationFix.Failed(CameraCodes.LOCATION_UNAVAILABLE)
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .filter { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }
        if (providers.isEmpty()) return LocationFix.Failed(CameraCodes.LOCATION_OFF)
        return try {
            val loc = bestOf(lm, providers, timeoutMs) ?: lastKnown(lm, providers)
            loc?.let { LocationFix.Ok(it.toGeo()) } ?: LocationFix.Failed(CameraCodes.LOCATION_UNAVAILABLE)
        } catch (_: SecurityException) {
            LocationFix.Failed(CameraCodes.LOCATION_NO_PERMISSION)
        }
    }

    private fun hasPermission() =
        listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION).any {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }

    @SuppressLint("MissingPermission")
    private suspend fun bestOf(lm: LocationManager, providers: List<String>, timeoutMs: Long): Location? = coroutineScope {
        val results = Channel<Location?>(Channel.UNLIMITED)
        val jobs = providers.map { p -> launch { results.send(single(lm, p)) } }
        var best: Location? = null
        withTimeoutOrNull(timeoutMs) {
            repeat(providers.size) {
                val l = results.receive()
                if (l != null && (best?.let { b -> l.accuracy < b.accuracy } != false)) best = l
                val b = best
                if (b != null && b.hasAccuracy() && b.accuracy <= GOOD_ACCURACY_M) return@withTimeoutOrNull
            }
        }
        jobs.forEach { it.cancel() }
        best
    }

    /** Một lần đo từ [provider]; null nếu nguồn bị tắt giữa chừng. Huỷ coroutine = huỷ yêu cầu. */
    @SuppressLint("MissingPermission")
    private suspend fun single(lm: LocationManager, provider: String): Location? = suspendCancellableCoroutine { cont ->
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val signal = CancellationSignal()
            lm.getCurrentLocation(provider, signal, ContextCompat.getMainExecutor(context)) { loc -> cont.resume(loc) }
            cont.invokeOnCancellation { signal.cancel() }
        } else {
            // Android 8–10: LocationListener chưa có hàm mặc định → phải viết đủ các hàm.
            val listener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    if (cont.isActive) cont.resume(location)
                }

                @Deprecated("Android 10 trở xuống vẫn gọi hàm này")
                override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit

                override fun onProviderEnabled(provider: String) = Unit

                override fun onProviderDisabled(provider: String) {
                    if (cont.isActive) cont.resume(null)
                }
            }
            @Suppress("DEPRECATION")
            lm.requestSingleUpdate(provider, listener, Looper.getMainLooper())
            cont.invokeOnCancellation { lm.removeUpdates(listener) }
        }
    }

    @SuppressLint("MissingPermission")
    private fun lastKnown(lm: LocationManager, providers: List<String>): Location? =
        providers.mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }.maxByOrNull { it.time }

    private fun Location.toGeo() = GeoLocation(
        lat = latitude,
        lon = longitude,
        accuracyM = if (hasAccuracy()) accuracy else null,
        timestamp = time / 1000,
        provider = provider,
    )

    private companion object {
        /** Sai số đủ tốt để dừng chờ (GPS ngoài trời thường 3–10 m). */
        const val GOOD_ACCURACY_M = 25f
    }
}
