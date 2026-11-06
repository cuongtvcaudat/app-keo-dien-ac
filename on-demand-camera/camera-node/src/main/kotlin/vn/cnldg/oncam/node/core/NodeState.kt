package vn.cnldg.oncam.node.core

import vn.cnldg.oncam.shared.mqtt.MqttConnectionState
import vn.cnldg.oncam.shared.protocol.Action
import vn.cnldg.oncam.shared.protocol.LocationStatus
import vn.cnldg.oncam.shared.protocol.NetworkType

/** Thông tin máy đọc định kỳ để gửi kèm trạng thái. */
data class DeviceSnapshot(
    val batteryPercent: Int? = null,
    val charging: Boolean? = null,
    val temperatureC: Double? = null,
    val network: NetworkType? = null,
)

/** Nguồn đọc [DeviceSnapshot] (Android: BatteryManager + ConnectivityManager). */
fun interface DeviceInfoSource {
    /** Đọc thông tin hiện tại. */
    fun snapshot(): DeviceSnapshot
}

/** Trạng thái broker nhúng. */
sealed interface BrokerState {
    /** Dùng broker ngoài. */
    data object NotUsed : BrokerState

    /** Chưa có IP Tailscale để mở broker (không bao giờ mở trên 0.0.0.0). */
    data object WaitingForAddress : BrokerState

    /** Broker đang nghe tại [host]:[port]. */
    data class Running(val host: String, val port: Int) : BrokerState

    /** Mở broker thất bại, sẽ thử lại. */
    data class Failed(val reason: String) : BrokerState
}

/** Lệnh gần nhất đã xử lý (để hiện trên màn hình). */
data class CommandRecord(
    val action: Action?,
    val success: Boolean,
    val message: String?,
    val atSec: Long,
)

/** Kết quả nút "Tự kiểm tra". */
sealed interface SelfTestResult {
    /** PING đi vòng qua broker và được xử lý thành công trong [roundTripMs]. */
    data class Passed(val roundTripMs: Long) : SelfTestResult

    /** Thất bại vì [reason]. */
    data class Failed(val reason: SelfTestFailure) : SelfTestResult
}

/** Lý do tự kiểm tra thất bại. */
enum class SelfTestFailure { NOT_CONNECTED, PUBLISH_FAILED, TIMEOUT, REJECTED }

/**
 * Trạng thái chạy của camera node, hiển thị trên màn hình và notification.
 *
 * @property startedAtSec thời điểm bắt đầu chạy (để tính thời gian chạy liên tục — tiêu chí 24h).
 * @property disconnects số lần mất kết nối MQTT kể từ khi chạy.
 * @property brokerUri địa chỉ viewer cần nhập để kết nối.
 */
data class NodeState(
    val running: Boolean = false,
    val startedAtSec: Long? = null,
    val broker: BrokerState = BrokerState.NotUsed,
    val brokerUri: String? = null,
    val mqtt: MqttConnectionState = MqttConnectionState.Idle,
    val disconnects: Int = 0,
    val lastCommand: CommandRecord? = null,
    val selfTest: SelfTestResult? = null,
    val device: DeviceSnapshot = DeviceSnapshot(),
    val camera: CameraStatus = CameraStatus(),
    val location: LocationStatus? = null,
)
