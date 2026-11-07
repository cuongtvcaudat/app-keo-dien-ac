package vn.cnldg.oncam.shared.security

import java.util.UUID
import vn.cnldg.oncam.shared.OnCam
import vn.cnldg.oncam.shared.protocol.Action
import vn.cnldg.oncam.shared.protocol.Command
import vn.cnldg.oncam.shared.protocol.CommandParams
import vn.cnldg.oncam.shared.protocol.DeviceIds
import vn.cnldg.oncam.shared.protocol.RejectReason

/** Nguồn thời gian (giây kể từ 1970-01-01 UTC); tách ra để test được. */
fun interface EpochClock {
    /** Thời điểm hiện tại, giây. */
    fun nowSec(): Long

    companion object {
        /** Đồng hồ hệ thống. */
        val SYSTEM: EpochClock = EpochClock { System.currentTimeMillis() / 1000 }
    }
}

/**
 * Chuỗi chuẩn hóa để ký một lệnh. Ký cả deviceId và toàn bộ tham số để:
 * - lệnh gửi cho camera A không dùng lại được cho camera B;
 * - không sửa được tham số mà giữ nguyên chữ ký.
 */
internal object CommandCanonical {
    private const val PREFIX = "oncam-cmd-v1"
    private val ID_PATTERN = Regex("^[A-Za-z0-9-]{8,64}$")

    fun isValidId(id: String): Boolean = ID_PATTERN.matches(id)

    fun of(deviceId: String, id: String, action: Action, params: CommandParams, timestamp: Long): String {
        val fields = mutableListOf(
            PREFIX,
            deviceId,
            id,
            action.name,
            timestamp.toString(),
            params.camera?.name.orEmpty(),
            params.flash?.toString().orEmpty(),
            params.quality?.name.orEmpty(),
            params.idleTimeoutSec?.toString().orEmpty(),
        )
        // Trường của Phase 7 chỉ thêm vào khi có → lệnh cũ ký giống hệt bản 0.6 (2 app khác phiên bản vẫn hiểu nhau).
        if (params.sdp != null || params.sessionId != null) {
            fields += params.sessionId.orEmpty()
            fields += params.sdp?.let(::sha256Hex).orEmpty()
        }
        // Theo dõi vị trí (0.9): cũng chỉ thêm khi có, nên lệnh cũ vẫn ký như cũ.
        params.trackMinutes?.let { fields += "track:$it" }
        params.maxSeconds?.let { fields += "rec:$it" }
        params.motion?.let { fields += "motion:$it" }
        params.sensitivity?.let { fields += "sens:${it.name}" }
        return fields.joinToString("|")
    }
}

/**
 * Tạo lệnh đã ký (dùng ở viewer).
 *
 * @param secret token bí mật chung, nhập ở màn hình cấu hình của cả 2 app.
 */
class CommandSigner(secret: String, private val clock: EpochClock = EpochClock.SYSTEM) {
    private val hmac = Hmac(secret)

    /** Tạo và ký lệnh [action] gửi tới camera [deviceId]. */
    fun sign(
        deviceId: String,
        action: Action,
        params: CommandParams = CommandParams(),
        id: String = UUID.randomUUID().toString(),
        timestamp: Long = clock.nowSec(),
    ): Command {
        DeviceIds.requireValid(deviceId)
        require(CommandCanonical.isValidId(id)) { "id lệnh không hợp lệ: '$id'" }
        val token = hmac.hex(CommandCanonical.of(deviceId, id, action, params, timestamp))
        return Command(id = id, action = action, params = params, token = token, timestamp = timestamp)
    }
}

/** Kết quả kiểm tra lệnh của [CommandVerifier]. */
sealed interface CommandCheck {
    /** Lệnh hợp lệ, được phép thực hiện. */
    data object Accepted : CommandCheck

    /** Lệnh bị từ chối với [reason]. */
    data class Rejected(val reason: RejectReason) : CommandCheck
}

/**
 * Kiểm tra lệnh nhận được (dùng ở camera-node), theo thứ tự:
 * 1. Định dạng id → MALFORMED.
 * 2. Chữ ký HMAC → BAD_SIGNATURE (kiểm trước để kẻ lạ không làm đầy bộ nhớ chống replay).
 * 3. Lệch giờ > [maxSkewSec] → EXPIRED.
 * 4. id đã thấy trong cửa sổ thời gian → REPLAYED.
 *
 * Lệnh hợp lệ về chữ ký nhưng sai tham số sẽ do nơi xử lý kiểm bằng `Command.paramProblem()`.
 */
class CommandVerifier(
    secret: String,
    private val deviceId: String,
    private val clock: EpochClock = EpochClock.SYSTEM,
    private val maxSkewSec: Long = OnCam.MAX_CLOCK_SKEW_SEC,
    private val replayGuard: ReplayGuard = ReplayGuard(ttlSec = maxSkewSec * 2),
) {
    private val hmac = Hmac(secret)

    init {
        DeviceIds.requireValid(deviceId)
    }

    /** Kiểm tra [command]; chỉ ghi nhận id vào bộ chống replay khi lệnh hợp lệ. */
    fun verify(command: Command): CommandCheck {
        if (!CommandCanonical.isValidId(command.id)) return CommandCheck.Rejected(RejectReason.MALFORMED)

        val canonical = CommandCanonical.of(deviceId, command.id, command.action, command.params, command.timestamp)
        if (!hmac.matches(canonical, command.token)) return CommandCheck.Rejected(RejectReason.BAD_SIGNATURE)

        val now = clock.nowSec()
        if (kotlin.math.abs(now - command.timestamp) > maxSkewSec) {
            return CommandCheck.Rejected(RejectReason.EXPIRED)
        }

        if (!replayGuard.firstSeen(command.id, now)) return CommandCheck.Rejected(RejectReason.REPLAYED)
        return CommandCheck.Accepted
    }
}
