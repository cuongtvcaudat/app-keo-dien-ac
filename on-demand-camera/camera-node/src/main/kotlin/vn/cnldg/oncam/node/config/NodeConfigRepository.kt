package vn.cnldg.oncam.node.config

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import vn.cnldg.oncam.node.core.BrokerMode
import vn.cnldg.oncam.node.core.MountOrientation
import vn.cnldg.oncam.node.core.NodeConfig
import vn.cnldg.oncam.node.di.ApplicationScope
import vn.cnldg.oncam.shared.OnCam

/** Trạng thái đọc cấu hình. */
sealed interface ConfigLoad {
    /** Đang đọc từ bộ nhớ mã hoá. */
    data object Loading : ConfigLoad

    /** Chưa cấu hình lần nào. */
    data object Missing : ConfigLoad

    /** Đã có cấu hình. */
    data class Loaded(val config: NodeConfig) : ConfigLoad
}

/**
 * Lưu cấu hình camera-node.
 *
 * - Token bí mật, mật khẩu broker ngoài... → EncryptedSharedPreferences (khoá nằm trong Android Keystore).
 * - Cờ không bí mật (đang bật node, tự chạy khi khởi động) → SharedPreferences thường để BootReceiver đọc nhanh.
 */
@Singleton
class NodeConfigRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    @ApplicationScope appScope: CoroutineScope,
) {
    private val plain: SharedPreferences = context.getSharedPreferences(PLAIN_FILE, Context.MODE_PRIVATE)
    private val secure: SharedPreferences by lazy { openSecurePrefs() }

    private val _config = MutableStateFlow<ConfigLoad>(ConfigLoad.Loading)

    /** Cấu hình hiện tại. */
    val config: StateFlow<ConfigLoad> = _config.asStateFlow()

    private val _autoStart = MutableStateFlow(plain.getBoolean(KEY_AUTO_START, true))

    /** Tự chạy camera node sau khi khởi động lại máy. */
    val autoStart: StateFlow<Boolean> = _autoStart.asStateFlow()

    init {
        appScope.launch(Dispatchers.IO) { _config.value = read()?.let { ConfigLoad.Loaded(it) } ?: ConfigLoad.Missing }
    }

    /** Chờ đọc xong rồi trả về cấu hình (null nếu chưa có). */
    suspend fun current(): NodeConfig? = (config.first { it !is ConfigLoad.Loading } as? ConfigLoad.Loaded)?.config

    /** Lưu cấu hình (ghi mã hoá). */
    suspend fun save(config: NodeConfig) = withContext(Dispatchers.IO) {
        secure.edit(commit = true) {
            putString(KEY_DEVICE_ID, config.deviceId)
            putString(KEY_DISPLAY_NAME, config.displayName)
            putString(KEY_SECRET, config.secret)
            putString(KEY_BROKER_MODE, config.brokerMode.name)
            putInt(KEY_BROKER_PORT, config.brokerPort)
            putString(KEY_EXTERNAL_URI, config.externalUri)
            putString(KEY_EXTERNAL_USERNAME, config.externalUsername)
            putString(KEY_EXTERNAL_PASSWORD, config.externalPassword)
            putString(KEY_ORIENTATION, config.orientation.name)
        }
        _config.value = ConfigLoad.Loaded(config)
    }

    /** Bật/tắt tự chạy sau khi khởi động máy. */
    fun setAutoStart(enabled: Boolean) {
        plain.edit { putBoolean(KEY_AUTO_START, enabled) }
        _autoStart.value = enabled
    }

    /** Người dùng đang muốn node chạy (bấm Khởi động) hay đã chủ động Dừng. */
    var nodeEnabled: Boolean
        get() = plain.getBoolean(KEY_NODE_ENABLED, false)
        set(value) = plain.edit { putBoolean(KEY_NODE_ENABLED, value) }

    private fun read(): NodeConfig? = try {
        val deviceId = secure.getString(KEY_DEVICE_ID, null) ?: return null
        NodeConfig(
            deviceId = deviceId,
            displayName = secure.getString(KEY_DISPLAY_NAME, "").orEmpty(),
            secret = secure.getString(KEY_SECRET, "").orEmpty(),
            brokerMode = runCatching { BrokerMode.valueOf(secure.getString(KEY_BROKER_MODE, "").orEmpty()) }
                .getOrDefault(BrokerMode.EMBEDDED),
            brokerPort = secure.getInt(KEY_BROKER_PORT, OnCam.DEFAULT_MQTT_PORT),
            externalUri = secure.getString(KEY_EXTERNAL_URI, "").orEmpty(),
            externalUsername = secure.getString(KEY_EXTERNAL_USERNAME, "").orEmpty(),
            externalPassword = secure.getString(KEY_EXTERNAL_PASSWORD, "").orEmpty(),
            orientation = runCatching { MountOrientation.valueOf(secure.getString(KEY_ORIENTATION, "").orEmpty()) }
                .getOrDefault(MountOrientation.UP),
        )
    } catch (e: Exception) {
        Timber.e(e, "Không đọc được cấu hình mã hoá")
        null
    }

    private fun openSecurePrefs(): SharedPreferences = try {
        createSecurePrefs()
    } catch (e: Exception) {
        // Khoá Keystore hỏng (hiếm, vd. sau khi khôi phục máy) → xoá và tạo lại; người dùng cấu hình lại.
        Timber.e(e, "Kho cấu hình mã hoá hỏng, tạo lại")
        context.deleteSharedPreferences(SECURE_FILE)
        createSecurePrefs()
    }

    private fun createSecurePrefs(): SharedPreferences =
        EncryptedSharedPreferences.create(
            SECURE_FILE,
            MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC),
            context,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )

    private companion object {
        const val SECURE_FILE = "oncam_node_secure"
        const val PLAIN_FILE = "oncam_node"
        const val KEY_DEVICE_ID = "device_id"
        const val KEY_DISPLAY_NAME = "display_name"
        const val KEY_SECRET = "secret"
        const val KEY_BROKER_MODE = "broker_mode"
        const val KEY_BROKER_PORT = "broker_port"
        const val KEY_EXTERNAL_URI = "external_uri"
        const val KEY_EXTERNAL_USERNAME = "external_username"
        const val KEY_EXTERNAL_PASSWORD = "external_password"
        const val KEY_ORIENTATION = "orientation"
        const val KEY_AUTO_START = "auto_start"
        const val KEY_NODE_ENABLED = "node_enabled"
    }
}
