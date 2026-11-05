package vn.cnldg.oncam.shared.protocol

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * Mã hóa/giải mã JSON cho lệnh, trạng thái, sự kiện.
 *
 * - Bỏ qua trường lạ → bản app cũ vẫn đọc được JSON của bản mới.
 * - Không ghi trường null → payload gọn hơn.
 * - Giải mã lỗi (JSON hỏng, enum lạ) trả về null thay vì ném lỗi.
 */
object ProtocolJson {
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
    }

    /** Lệnh → JSON. */
    fun encodeCommand(command: Command): String = json.encodeToString(Command.serializer(), command)

    /** JSON → lệnh, null nếu không đọc được. */
    fun decodeCommand(text: String): Command? = decode { json.decodeFromString(Command.serializer(), text) }

    /** Trạng thái → JSON. */
    fun encodeStatus(status: DeviceStatus): String = json.encodeToString(DeviceStatus.serializer(), status)

    /** JSON → trạng thái, null nếu không đọc được. */
    fun decodeStatus(text: String): DeviceStatus? = decode { json.decodeFromString(DeviceStatus.serializer(), text) }

    /** Sự kiện → JSON. */
    fun encodeEvent(event: DeviceEvent): String = json.encodeToString(DeviceEvent.serializer(), event)

    /** JSON → sự kiện, null nếu không đọc được. */
    fun decodeEvent(text: String): DeviceEvent? = decode { json.decodeFromString(DeviceEvent.serializer(), text) }

    private inline fun <T> decode(block: () -> T): T? =
        try {
            block()
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
}
