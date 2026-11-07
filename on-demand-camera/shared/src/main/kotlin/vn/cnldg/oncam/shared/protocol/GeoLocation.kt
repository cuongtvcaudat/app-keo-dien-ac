package vn.cnldg.oncam.shared.protocol

import java.util.Locale
import kotlinx.serialization.Serializable

/**
 * Vị trí máy camera (chỉ gửi khi người dùng bật "Chia sẻ vị trí" trên máy camera).
 *
 * @property lat vĩ độ (độ, WGS84 — hệ toạ độ của GPS và Google Maps).
 * @property lon kinh độ (độ).
 * @property accuracyM bán kính sai số (m, độ tin cậy 68%) do Android báo.
 * @property timestamp lúc lấy được vị trí, giây kể từ 1970-01-01 UTC (vị trí cũ thì giá trị này cũ).
 * @property provider nguồn: "gps" (vệ tinh, chính xác) hoặc "network" (Wi-Fi/trạm phát sóng, sai số lớn hơn).
 */
@Serializable
data class GeoLocation(
    val lat: Double,
    val lon: Double,
    val accuracyM: Float? = null,
    val timestamp: Long,
    val provider: String? = null,
) {
    /** "10.943210,108.104220" — luôn dấu chấm thập phân, 6 chữ số (~0,1 m) bất kể ngôn ngữ máy. */
    fun coordinates(): String = String.format(Locale.US, "%.6f,%.6f", lat, lon)

    /** Link mở đúng điểm này trên Google Maps (app nếu có, không thì trình duyệt); không cần API key. */
    fun mapsUrl(): String = "https://www.google.com/maps/search/?api=1&query=${coordinates()}"

    /** Toạ độ hợp lệ (vĩ độ −90..90, kinh độ −180..180, không phải NaN). */
    fun isValid(): Boolean = lat in -90.0..90.0 && lon in -180.0..180.0
}

/**
 * Phần vị trí trong trạng thái máy camera. Cả mục null = máy camera không chia sẻ vị trí (hoặc bản cũ).
 *
 * @property point vị trí gần nhất lấy được (giữ lại kể cả khi lần lấy sau lỗi).
 * @property updating đang lấy vị trí mới (GPS có thể mất tới 30 giây).
 * @property error mã lỗi lần lấy gần nhất ([CameraCodes].LOCATION_*), null nếu thành công.
 * @property canTrack máy camera hỗ trợ "Theo dõi" (bản ≥ 0.9) — app Xem chỉ hiện nút khi true.
 * @property trackingUntil đang theo dõi 1 phút/lần tới thời điểm này (giây từ 1970, UTC); null = không theo dõi.
 * @property intervalSec chu kỳ tự cập nhật khi không theo dõi (giây); null = tắt, chỉ lấy khi được yêu cầu.
 */
@Serializable
data class LocationStatus(
    val point: GeoLocation? = null,
    val updating: Boolean = false,
    val error: String? = null,
    val canTrack: Boolean = false,
    val trackingUntil: Long? = null,
    val intervalSec: Int? = null,
)
