package vn.cnldg.oncam.shared.security

import vn.cnldg.oncam.shared.protocol.DeviceAlert
import vn.cnldg.oncam.shared.protocol.DeviceIds

/**
 * Ký/kiểm cảnh báo của máy camera: chỉ máy giữ token mới tạo được cảnh báo hợp lệ (người lọt vào broker không
 * giả được "có trộm" hay sửa ảnh). Chữ ký bao mọi trường, ảnh tính bằng SHA-256.
 */
class AlertAuth(secret: String, private val deviceId: String) {
    private val hmac = Hmac(secret)

    init {
        DeviceIds.requireValid(deviceId)
    }

    /** Cảnh báo kèm chữ ký. */
    fun sign(alert: DeviceAlert): DeviceAlert = alert.copy(signature = hmac.hex(canonical(alert)))

    /** Chữ ký đúng (và không bị sửa trường nào). */
    fun verify(alert: DeviceAlert): Boolean {
        val sig = alert.signature ?: return false
        return hmac.matches(canonical(alert), sig)
    }

    private fun canonical(a: DeviceAlert): String = listOf(
        PREFIX,
        deviceId,
        a.type.name,
        a.timestampMs.toString(),
        a.cameraName.orEmpty(),
        a.batteryPercent?.toString().orEmpty(),
        a.temperatureC?.toString().orEmpty(),
        a.clip.orEmpty(),
        a.snapshot?.let(::sha256Hex).orEmpty(),
        a.mapsUrl.orEmpty(),
    ).joinToString("|")

    private companion object {
        const val PREFIX = "oncam-alert-v1"
    }
}
