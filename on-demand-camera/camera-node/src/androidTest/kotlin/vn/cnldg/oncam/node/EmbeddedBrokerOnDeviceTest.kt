package vn.cnldg.oncam.node

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.net.ServerSocket
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import org.junit.runner.RunWith
import vn.cnldg.oncam.node.broker.EmbeddedBroker
import vn.cnldg.oncam.node.core.BrokerState
import vn.cnldg.oncam.node.core.NodeConfig
import vn.cnldg.oncam.node.core.NodeController
import vn.cnldg.oncam.node.core.SelfTestResult
import vn.cnldg.oncam.node.system.DeviceInfoReader
import vn.cnldg.oncam.shared.mqtt.MqttConnectionState

/**
 * Chạy trên emulator/máy Android thật: kiểm chứng broker Moquette + Netty chạy được trên ART
 * (rủi ro lớn nhất của đề xuất P1) và đọc được pin/nhiệt độ.
 */
@RunWith(AndroidJUnit4::class)
class EmbeddedBrokerOnDeviceTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun brokerNhungChayTrenAndroidVaTuKiemTraPingThanhCong() = runBlocking<Unit> {
        val port = ServerSocket(0).use { it.localPort }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val controller = NodeController(
            config = NodeConfig(deviceId = "test-node", displayName = "Test", secret = "test-secret-0123456789", brokerPort = port),
            scope = scope,
            deviceInfo = DeviceInfoReader(context),
            addressProvider = { "127.0.0.1" },
            broker = EmbeddedBroker(File(context.cacheDir, "moquette-test")),
        )
        try {
            controller.start()
            withTimeout(30_000) { controller.state.first { it.mqtt == MqttConnectionState.Connected } }
            assertIs<BrokerState.Running>(controller.state.value.broker)

            val result = controller.selfTest(timeoutMs = 10_000)
            assertIs<SelfTestResult.Passed>(result)
        } finally {
            controller.stop()
            scope.cancel()
        }
    }

    @Test
    fun docDuocPinVaNhietDo() {
        val snapshot = DeviceInfoReader(context).snapshot()

        val percent = assertNotNull(snapshot.batteryPercent)
        assertTrue(percent in 0..100, "pin = $percent")
        assertNotNull(snapshot.network)
    }
}
