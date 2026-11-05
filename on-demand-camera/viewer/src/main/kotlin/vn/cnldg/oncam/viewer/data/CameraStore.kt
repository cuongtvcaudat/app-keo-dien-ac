package vn.cnldg.oncam.viewer.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import timber.log.Timber
import vn.cnldg.oncam.viewer.di.ApplicationScope

/**
 * Danh sách camera đã thêm, lưu bằng EncryptedSharedPreferences (token bí mật nằm trong đó).
 */
@Singleton
class CameraStore @Inject constructor(
    @ApplicationContext private val context: Context,
    @ApplicationScope appScope: CoroutineScope,
) {
    private val secure: SharedPreferences by lazy { openSecurePrefs() }
    private val plain: SharedPreferences = context.getSharedPreferences(PLAIN_FILE, Context.MODE_PRIVATE)

    private val _cameras = MutableStateFlow<List<SavedCamera>?>(null)

    /** Danh sách camera; null khi đang đọc. */
    val cameras: StateFlow<List<SavedCamera>?> = _cameras.asStateFlow()

    init {
        appScope.launch { _cameras.value = read() }
    }

    /** Mã ngẫu nhiên của bản cài này, dùng làm clientId MQTT (mỗi máy Xem một mã riêng). */
    val installationId: String by lazy {
        plain.getString(KEY_INSTALLATION, null) ?: UUID.randomUUID().toString().take(8).also {
            plain.edit { putString(KEY_INSTALLATION, it) }
        }
    }

    /** Thêm mới hoặc cập nhật camera (theo mã camera). */
    suspend fun upsert(camera: SavedCamera, replacingDeviceId: String? = null) = withContext(Dispatchers.IO) {
        val current = _cameras.value ?: read()
        val key = replacingDeviceId ?: camera.deviceId
        val next = current.filterNot { it.deviceId == key || it.deviceId == camera.deviceId } + camera
        write(next)
    }

    /** Xoá camera. */
    suspend fun remove(deviceId: String) = withContext(Dispatchers.IO) {
        write((_cameras.value ?: read()).filterNot { it.deviceId == deviceId })
    }

    private fun write(list: List<SavedCamera>) {
        secure.edit(commit = true) { putString(KEY_CAMERAS, json.encodeToString(ListSerializer(SavedCamera.serializer()), list)) }
        _cameras.update { list }
    }

    private fun read(): List<SavedCamera> = try {
        secure.getString(KEY_CAMERAS, null)
            ?.let { json.decodeFromString(ListSerializer(SavedCamera.serializer()), it) }
            .orEmpty()
    } catch (e: Exception) {
        Timber.e(e, "Không đọc được danh sách camera")
        emptyList()
    }

    private fun openSecurePrefs(): SharedPreferences = try {
        createSecurePrefs()
    } catch (e: Exception) {
        // Khoá Keystore hỏng (hiếm) → xoá và tạo lại; người dùng thêm lại camera.
        Timber.e(e, "Kho mã hoá hỏng, tạo lại")
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
        const val SECURE_FILE = "oncam_viewer_secure"
        const val PLAIN_FILE = "oncam_viewer"
        const val KEY_CAMERAS = "cameras"
        const val KEY_INSTALLATION = "installation_id"
        val json = Json { ignoreUnknownKeys = true }
    }
}
