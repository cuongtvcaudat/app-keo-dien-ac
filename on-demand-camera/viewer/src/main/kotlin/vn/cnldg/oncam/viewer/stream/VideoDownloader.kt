package vn.cnldg.oncam.viewer.stream

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URI
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import vn.cnldg.oncam.shared.client.CameraClient
import vn.cnldg.oncam.shared.protocol.ProtocolJson
import vn.cnldg.oncam.shared.protocol.RecordingInfo
import vn.cnldg.oncam.shared.protocol.Recordings
import vn.cnldg.oncam.shared.security.StreamAuth

/** Kết quả một lần tải video. */
data class VideoSync(val saved: Int, val bytes: Long, val failed: Int, val unreachable: Boolean = false)

/**
 * Tải video từ máy camera (cổng tải mở theo yêu cầu, có header xác thực) và lưu vào Thư viện › Movies/OnCam.
 * Ghi thẳng từ mạng vào Thư viện (không cần bộ nhớ tạm), nhớ clip nào đã tải để không tải lại.
 */
@Singleton
class VideoDownloader @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Clip [name] của camera [deviceId] đã được tải về máy này chưa. */
    fun isDownloaded(deviceId: String, name: String): Boolean = name in downloaded(deviceId)

    /**
     * Có tự tải clip [bytes] byte không (không hỏi người dùng): lưu được ngay (Android 10+, hoặc đã cấp quyền ghi
     * bộ nhớ) và máy xem đang ở Wi-Fi — đi 4G thì chỉ tự tải clip ≤ [AUTO_METERED_MAX_BYTES] (~1,5 phút), clip dài
     * hơn chờ người dùng bấm "Tải video về máy" (1 clip 10 phút ≈ 150 MB).
     */
    fun autoDownloadAllowed(bytes: Long?): Boolean {
        val canSave = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        if (!canSave) return false
        val metered = context.getSystemService(ConnectivityManager::class.java)?.isActiveNetworkMetered ?: true
        return !metered || (bytes != null && bytes <= AUTO_METERED_MAX_BYTES)
    }

    /**
     * Tải các clip chưa có (hoặc chỉ [only] nếu chỉ định) của camera.
     * Mở cổng tải trên máy camera trước (lệnh OPEN_RECORDINGS) — không cần bật camera.
     */
    suspend fun sync(client: CameraClient, only: String? = null): VideoSync = withContext(Dispatchers.IO) {
        val deviceId = client.endpoint.deviceId
        val base = client.openRecordings() ?: return@withContext VideoSync(0, 0, 0, unreachable = true)
        val list = fetchList(base, client.streamAuthHeader()) ?: return@withContext VideoSync(0, 0, 0, unreachable = true)
        // Chỉ nhận tên đúng mẫu máy camera đặt (chặn "../" khi ghi file kiểu cũ).
        val todo = list.filter {
            Recordings.isValidName(it.name) && (only == null || it.name == only) && !isDownloaded(deviceId, it.name)
        }
        var saved = 0
        var bytes = 0L
        var failed = 0
        // Cũ trước, mới sau: Thư viện xếp đúng thứ tự thời gian.
        for (info in todo.sortedBy { it.createdAt }) {
            if (download(base, info, client.streamAuthHeader(), deviceId) != null) {
                markDownloaded(deviceId, info.name)
                saved++
                bytes += info.sizeBytes
            } else {
                failed++
            }
        }
        VideoSync(saved, bytes, failed)
    }

    private fun fetchList(base: String, auth: String): List<RecordingInfo>? {
        return try {
            open(base + Recordings.LIST_PATH, auth).use { conn ->
                if (conn.connection.responseCode != HttpURLConnection.HTTP_OK) {
                    null
                } else {
                    ProtocolJson.decodeRecordings(conn.connection.inputStream.bufferedReader().readText())
                }
            }
        } catch (e: Exception) {
            Timber.w(e, "Không lấy được danh sách video")
            null
        }
    }

    private fun download(base: String, info: RecordingInfo, auth: String, deviceId: String): Uri? {
        return try {
            open(base + Recordings.filePath(info.name), auth).use { conn ->
                if (conn.connection.responseCode != HttpURLConnection.HTTP_OK) {
                    null
                } else {
                    val displayName = "OnCam_${deviceId}_${info.name.removePrefix("oncam-")}"
                    conn.connection.inputStream.use { input ->
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            saveScoped(input, displayName)
                        } else {
                            saveLegacy(input, displayName)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Timber.w(e, "Không tải được video %s", info.name)
            null
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun saveScoped(input: InputStream, name: String): Uri? {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, MP4)
            put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/$ALBUM")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values) ?: return null
        val ok = try {
            resolver.openOutputStream(uri)?.use { out -> copy(input, out) } ?: false
        } catch (e: Exception) {
            Timber.w(e, "Ghi video lỗi")
            false
        }
        if (!ok) {
            resolver.delete(uri, null, null) // không để lại file dở trong Thư viện
            return null
        }
        values.clear()
        values.put(MediaStore.Video.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        return uri
    }

    // Android 9 trở xuống: ghi vào Movies/OnCam (cần WRITE_EXTERNAL_STORAGE) rồi đăng ký với MediaStore.
    @Suppress("DEPRECATION")
    private fun saveLegacy(input: InputStream, name: String): Uri? {
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), ALBUM)
        if (!dir.exists() && !dir.mkdirs()) return null
        val file = File(dir, name)
        val ok = file.outputStream().use { copy(input, it) }
        if (!ok) {
            file.delete()
            return null
        }
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, MP4)
            put(MediaStore.Video.Media.DATA, file.absolutePath)
        }
        return context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
    }

    private fun copy(input: InputStream, out: OutputStream): Boolean {
        input.copyTo(out, BUFFER)
        return true
    }

    private fun downloaded(deviceId: String): Set<String> = prefs.getStringSet(KEY_PREFIX + deviceId, emptySet()).orEmpty()

    @Synchronized
    private fun markDownloaded(deviceId: String, name: String) {
        // Chỉ nhớ 500 tên gần nhất mỗi camera (máy camera giữ tối đa 2 GB ≈ vài trăm clip).
        val next = (downloaded(deviceId) + name).sortedDescending().take(MAX_REMEMBERED).toSet()
        prefs.edit().putStringSet(KEY_PREFIX + deviceId, next).apply()
    }

    private class Conn(val connection: HttpURLConnection) : Closeable {
        override fun close() = connection.disconnect()
    }

    private fun open(url: String, auth: String) = Conn(
        (URI(url).toURL().openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty(StreamAuth.HEADER, auth)
        },
    )

    private companion object {
        const val PREFS = "oncam_videos"
        const val KEY_PREFIX = "downloaded_"
        const val ALBUM = "OnCam"
        const val MP4 = "video/mp4"
        const val BUFFER = 64 * 1024
        const val MAX_REMEMBERED = 500
        const val AUTO_METERED_MAX_BYTES = 25L * 1024 * 1024
        const val CONNECT_TIMEOUT_MS = 5_000
        const val READ_TIMEOUT_MS = 30_000
    }
}
