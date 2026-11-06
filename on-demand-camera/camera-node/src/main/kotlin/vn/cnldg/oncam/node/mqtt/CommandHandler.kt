package vn.cnldg.oncam.node.mqtt

import vn.cnldg.oncam.shared.protocol.Action
import vn.cnldg.oncam.shared.protocol.Command
import vn.cnldg.oncam.shared.protocol.DeviceEvent
import vn.cnldg.oncam.shared.protocol.EventType
import vn.cnldg.oncam.shared.protocol.ProtocolJson
import vn.cnldg.oncam.shared.protocol.RejectReason
import vn.cnldg.oncam.shared.protocol.paramProblem
import vn.cnldg.oncam.shared.security.CommandCheck
import vn.cnldg.oncam.shared.security.CommandVerifier
import vn.cnldg.oncam.shared.security.EpochClock

/** Kết quả thực hiện một lệnh camera (Phase 3 sẽ cài đặt các lệnh camera). */
sealed interface ActionOutcome {
    /** Thành công. */
    data class Done(val message: String? = null) : ActionOutcome

    /** Thất bại với [message]. */
    data class Failed(val message: String) : ActionOutcome

    /** Chưa hỗ trợ ở phiên bản này. */
    data object Unsupported : ActionOutcome
}

/** Lệnh đã xử lý: [command] null nếu không đọc được JSON; [event] là COMMAND_RESULT cần publish. */
data class HandledCommand(val command: Command?, val event: DeviceEvent)

/**
 * Nhận payload từ topic `cmd`, kiểm tra và thực hiện lệnh, trả về sự kiện COMMAND_RESULT.
 *
 * Thứ tự: đọc JSON → kiểm chữ ký/thời gian/replay → kiểm tham số → thực hiện.
 * PING xử lý ngay tại đây; các lệnh khác giao cho [perform].
 * Mọi COMMAND_RESULT (kể cả khi từ chối) kèm giờ máy camera tính bằng ms để viewer đo độ lệch đồng hồ.
 */
class CommandHandler(
    private val verifier: CommandVerifier,
    private val clock: EpochClock = EpochClock.SYSTEM,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val perform: suspend (Command) -> ActionOutcome = { ActionOutcome.Unsupported },
) {
    /** Xử lý một payload lệnh. */
    suspend fun handle(payload: String): HandledCommand {
        val command = ProtocolJson.decodeCommand(payload)
            ?: return HandledCommand(null, result(null, false, RejectReason.MALFORMED.name))

        when (val check = verifier.verify(command)) {
            is CommandCheck.Rejected -> return HandledCommand(command, result(command.id, false, check.reason.name))
            CommandCheck.Accepted -> Unit
        }

        command.paramProblem()?.let {
            return HandledCommand(command, result(command.id, false, "${RejectReason.BAD_PARAMS.name}:${it.name}"))
        }

        val outcome = if (command.action == Action.PING) {
            ActionOutcome.Done(PONG)
        } else {
            perform(command)
        }
        val event = when (outcome) {
            is ActionOutcome.Done -> result(command.id, true, outcome.message)
            is ActionOutcome.Failed -> result(command.id, false, outcome.message)
            ActionOutcome.Unsupported -> result(command.id, false, RejectReason.UNSUPPORTED.name)
        }
        return HandledCommand(command, event)
    }

    private fun result(commandId: String?, success: Boolean, message: String?) = DeviceEvent(
        event = EventType.COMMAND_RESULT,
        message = message,
        timestamp = clock.nowSec(),
        commandId = commandId,
        success = success,
        timestampMs = nowMs(),
    )

    companion object {
        /** Nội dung trả lời PING. */
        const val PONG: String = "PONG"
    }
}
