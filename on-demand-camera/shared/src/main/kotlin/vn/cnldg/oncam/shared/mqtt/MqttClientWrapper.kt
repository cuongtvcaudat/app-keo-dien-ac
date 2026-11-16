package vn.cnldg.oncam.shared.mqtt

import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.SSLSocketFactory
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.eclipse.paho.client.mqttv3.IMqttActionListener
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.IMqttToken
import org.eclipse.paho.client.mqttv3.MqttAsyncClient
import org.eclipse.paho.client.mqttv3.MqttCallback
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttException
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.MqttPingSender
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import vn.cnldg.oncam.shared.LogSink
import vn.cnldg.oncam.shared.i
import vn.cnldg.oncam.shared.w

/**
 * Bọc Paho MQTT (mqttv3) thành API coroutine/Flow, tự kết nối lại khi mất mạng.
 *
 * Hành vi:
 * - [start] chạy vòng lặp kết nối: thất bại thì chờ lùi dần 1s → 2s → 4s → 8s → tối đa
 *   [MqttConfig.maxReconnectDelayMs] rồi thử lại. [reconnectNow] bỏ qua thời gian chờ
 *   (gọi khi Android báo có mạng trở lại).
 * - Mọi topic đã [subscribe] được ghi nhớ và subscribe lại sau mỗi lần kết nối lại
 *   (dùng clean session nên broker không giữ hộ).
 * - [messages] là luồng một người nhận (single consumer), có bộ đệm, nên tin nhắn retain đến ngay
 *   sau khi subscribe không bị mất dù bên nhận bắt đầu collect muộn một chút.
 * - [stop] ngắt kết nối "êm" (gửi DISCONNECT) → broker KHÔNG phát LWT. Bên gọi tự publish
 *   trạng thái offline trước khi stop nếu cần.
 *
 * @param scope phạm vi chạy vòng lặp kết nối (vd. lifecycleScope của Service).
 * @param pingSenderFactory cho phép thay bộ gửi ping mặc định (Timer) — dùng ở Android nếu
 *   cần giữ ping đúng giờ khi máy ngủ sâu.
 */
class MqttClientWrapper(
    private val config: MqttConfig,
    private val scope: CoroutineScope,
    private val log: LogSink = LogSink.NONE,
    private val pingSenderFactory: (() -> MqttPingSender)? = null,
) {
    private val _state = MutableStateFlow<MqttConnectionState>(MqttConnectionState.Idle)

    /** Trạng thái kết nối hiện tại. */
    val state: StateFlow<MqttConnectionState> = _state.asStateFlow()

    private val incoming = Channel<IncomingMessage>(MESSAGE_BUFFER)

    /** Tin nhắn nhận được từ các topic đã subscribe. Chỉ nên có MỘT nơi collect. */
    val messages: Flow<IncomingMessage> = incoming.receiveAsFlow()

    private val subscriptions = ConcurrentHashMap<String, Int>()
    private val wakeUp = Channel<Unit>(Channel.CONFLATED)
    private val lost = Channel<Throwable>(Channel.CONFLATED)
    private val lifecycleLock = Any()

    @Volatile
    private var client: MqttAsyncClient? = null
    private var loopJob: Job? = null

    /** Bắt đầu kết nối (gọi nhiều lần không sao). */
    fun start() {
        synchronized(lifecycleLock) {
            if (loopJob?.isActive == true) return
            loopJob = scope.launch { runLoop() }
        }
    }

    /** Thử kết nối lại ngay, không chờ hết thời gian lùi. */
    fun reconnectNow() {
        wakeUp.trySend(Unit)
    }

    /**
     * Subscribe [topicFilter]. Topic được ghi nhớ để subscribe lại sau khi kết nối lại.
     * @return true nếu đã subscribe ngay; false nếu đang mất kết nối (sẽ tự subscribe khi kết nối được)
     *   hoặc broker từ chối.
     */
    suspend fun subscribe(topicFilter: String, qos: Int): Boolean {
        require(qos in 0..2) { "qos phải là 0, 1 hoặc 2" }
        subscriptions[topicFilter] = qos
        val c = connectedClient() ?: return false
        return subscribeOn(c, topicFilter, qos)
    }

    /** Bỏ subscribe [topicFilter] và không subscribe lại nữa. */
    suspend fun unsubscribe(topicFilter: String) {
        subscriptions.remove(topicFilter)
        val c = connectedClient() ?: return
        try {
            withTimeoutOrNull(OPERATION_TIMEOUT_MS) { awaitToken { l -> c.unsubscribe(topicFilter, null, l) } }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.w("Unsubscribe lỗi: $topicFilter", e)
        }
    }

    /**
     * Gửi [message].
     * @return true nếu gửi thành công (QoS 1: broker đã xác nhận); false nếu đang mất kết nối hoặc lỗi.
     */
    suspend fun publish(message: OutgoingMessage): Boolean {
        val c = connectedClient() ?: return false
        return try {
            val mqttMessage = MqttMessage(message.payload.toByteArray(Charsets.UTF_8)).apply {
                qos = message.qos
                isRetained = message.retained
            }
            withTimeoutOrNull(OPERATION_TIMEOUT_MS) {
                awaitToken { l -> c.publish(message.topic, mqttMessage, null, l) }
            } != null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.w("Publish lỗi: ${message.topic}", e)
            false
        }
    }

    /** Ngắt kết nối êm (không kích hoạt LWT) và dừng tự kết nối lại. */
    suspend fun stop() {
        val job = synchronized(lifecycleLock) { loopJob.also { loopJob = null } }
        val c = client
        client = null // từ đây callback của c bị bỏ qua
        job?.cancelAndJoin()
        if (c != null) {
            if (c.isConnected) {
                try {
                    withTimeoutOrNull(OPERATION_TIMEOUT_MS) { awaitToken { l -> c.disconnect(QUIESCE_MS, null, l) } }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.w("Disconnect lỗi", e)
                }
            }
            closeQuietly(c)
        }
        _state.value = MqttConnectionState.Idle
    }

    /**
     * Chỉ dùng trong test: cắt kết nối đột ngột (không gửi DISCONNECT) như khi mất mạng,
     * để broker phát LWT và vòng lặp tự kết nối lại.
     */
    internal fun simulateNetworkLossForTest() {
        val c = client ?: return
        try {
            c.disconnectForcibly(0, 0, false)
        } catch (_: Exception) {
            // Bỏ qua: mục tiêu chỉ là cắt kết nối.
        }
        lost.trySend(IOException("Mô phỏng mất mạng"))
    }

    private suspend fun runLoop() {
        var attempt = 0
        var lastError: Throwable? = null
        try {
            while (currentCoroutineContext().isActive) {
                _state.value = MqttConnectionState.Connecting
                while (lost.tryReceive().isSuccess) Unit // bỏ tín hiệu cũ

                val c = try {
                    connectOnce()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    lastError = e
                    log.w("Kết nối MQTT thất bại (${config.serverUri}): ${describe(e)}")
                    null
                }

                if (c != null) {
                    attempt = 0
                    log.i("Đã kết nối MQTT ${config.serverUri}")
                    subscriptions.forEach { (filter, qos) -> subscribeOn(c, filter, qos) }
                    _state.value = MqttConnectionState.Connected

                    val cause = lost.receive()
                    lastError = cause
                    log.w("Mất kết nối MQTT: ${describe(cause)}")
                    if (client === c) client = null
                    closeQuietly(c)
                }

                val delayMs = backoffDelayMs(attempt++)
                _state.value = MqttConnectionState.Disconnected(lastError?.let(::describe), delayMs)
                withTimeoutOrNull(delayMs) { wakeUp.receive() }
            }
        } finally {
            // Bị huỷ ngoài stop() (vd. scope bị huỷ): đóng client, broker sẽ phát LWT.
            client?.let {
                client = null
                closeQuietly(it)
            }
        }
    }

    private suspend fun connectOnce(): MqttAsyncClient {
        val c = newClient()
        client = c
        c.setCallback(callbackFor(c))
        try {
            withTimeoutOrNull(config.connectTimeoutSec * 1000L + CONNECT_GRACE_MS) {
                awaitToken { l -> c.connect(connectOptions(), null, l) }
            } ?: throw IOException("Quá thời gian kết nối")
            return c
        } catch (e: Throwable) {
            if (client === c) client = null
            closeQuietly(c)
            throw e
        }
    }

    private fun newClient(): MqttAsyncClient {
        val persistence = MemoryPersistence()
        val pingSender = pingSenderFactory?.invoke()
        return if (pingSender != null) {
            MqttAsyncClient(config.serverUri, config.clientId, persistence, pingSender)
        } else {
            MqttAsyncClient(config.serverUri, config.clientId, persistence)
        }
    }

    private fun connectOptions() = MqttConnectOptions().apply {
        isCleanSession = true
        isAutomaticReconnect = false // tự quản lý để giới hạn thời gian chờ tối đa
        keepAliveInterval = config.keepAliveSec
        connectionTimeout = config.connectTimeoutSec
        mqttVersion = MqttConnectOptions.MQTT_VERSION_3_1_1
        config.username?.let { userName = it }
        config.password?.let { password = it.toCharArray() }
        config.will?.let { setWill(it.topic, it.payload.toByteArray(Charsets.UTF_8), it.qos, it.retained) }
        if (config.isTls) {
            socketFactory = SSLSocketFactory.getDefault()
            isHttpsHostnameVerificationEnabled = true
        }
    }

    private fun callbackFor(c: MqttAsyncClient) = object : MqttCallback {
        override fun connectionLost(cause: Throwable?) {
            if (client === c) lost.trySend(cause ?: IOException("Mất kết nối"))
        }

        override fun messageArrived(topic: String, message: MqttMessage) {
            val msg = IncomingMessage(
                topic = topic,
                payload = String(message.payload, Charsets.UTF_8),
                qos = message.qos,
                retained = message.isRetained,
            )
            if (incoming.trySend(msg).isFailure) log.w("Bộ đệm tin nhắn đầy, bỏ tin từ $topic")
        }

        override fun deliveryComplete(token: IMqttDeliveryToken?) = Unit
    }

    private suspend fun subscribeOn(c: MqttAsyncClient, filter: String, qos: Int): Boolean =
        try {
            val token = withTimeoutOrNull(OPERATION_TIMEOUT_MS) { awaitToken { l -> c.subscribe(filter, qos, null, l) } }
            when {
                token == null -> {
                    log.w("Subscribe quá thời gian: $filter")
                    false
                }
                token.grantedQos?.any { it == SUBACK_FAILURE } == true -> {
                    log.w("Broker từ chối subscribe: $filter")
                    false
                }
                else -> true
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.w("Subscribe lỗi: $filter", e)
            false
        }

    private fun connectedClient(): MqttAsyncClient? = client?.takeIf { it.isConnected }

    private fun backoffDelayMs(attempt: Int): Long =
        (1_000L shl attempt.coerceAtMost(MAX_BACKOFF_SHIFT)).coerceAtMost(config.maxReconnectDelayMs)

    private fun closeQuietly(c: MqttAsyncClient) {
        try {
            c.setCallback(null)
        } catch (_: Exception) {
            // Bỏ qua khi dọn dẹp.
        }
        try {
            if (c.isConnected) c.disconnectForcibly(0, 0, false)
        } catch (_: Exception) {
            // Bỏ qua khi dọn dẹp.
        }
        try {
            c.close()
        } catch (_: Exception) {
            // Bỏ qua khi dọn dẹp.
        }
    }

    private fun describe(e: Throwable): String =
        if (e is MqttException) "MQTT ${e.reasonCode}: ${e.message}" else (e.message ?: e.javaClass.simpleName)

    private suspend fun awaitToken(block: (IMqttActionListener) -> IMqttToken): IMqttToken =
        suspendCancellableCoroutine { cont ->
            val listener = object : IMqttActionListener {
                override fun onSuccess(asyncActionToken: IMqttToken) {
                    if (cont.isActive) cont.resume(asyncActionToken)
                }

                override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                    if (cont.isActive) {
                        cont.resumeWithException(
                            exception ?: MqttException(MqttException.REASON_CODE_CLIENT_EXCEPTION.toInt()),
                        )
                    }
                }
            }
            try {
                block(listener)
            } catch (e: Exception) {
                if (cont.isActive) cont.resumeWithException(e)
            }
        }

    private companion object {
        const val MESSAGE_BUFFER = 256
        const val OPERATION_TIMEOUT_MS = 10_000L
        const val CONNECT_GRACE_MS = 5_000L
        const val QUIESCE_MS = 1_000L
        const val MAX_BACKOFF_SHIFT = 4
        const val SUBACK_FAILURE = 0x80
    }
}
