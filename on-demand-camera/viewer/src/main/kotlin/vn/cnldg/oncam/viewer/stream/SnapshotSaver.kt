package vn.cnldg.oncam.viewer.stream

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import vn.cnldg.oncam.shared.security.StreamAuth

/**
 * Tải ảnh chụp từ camera (`/snapshot.jpg`, có header xác thực) và lưu vào Thư viện ảnh
 * (Pictures/OnCam) để hiện trong app Ảnh của máy (spec mục 3.2: MediaStore).
 */
@Singleton
class SnapshotSaver @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    /** Tải ảnh; null nếu lỗi mạng hoặc camera trả mã khác 200. */
    suspend fun download(url: String, authHeader: String): ByteArray? = withContext(Dispatchers.IO) {
        val conn = URI(url).toURL().openConnection() as HttpURLConnection
        conn.connectTimeout = CONNECT_TIMEOUT_MS
        conn.readTimeout = READ_TIMEOUT_MS
        conn.setRequestProperty(StreamAuth.HEADER, authHeader)
        try {
            if (conn.responseCode == HttpURLConnection.HTTP_OK) conn.inputStream.use { it.readBytes() } else null
        } catch (e: Exception) {
            Timber.w(e, "Không tải được ảnh từ %s", url)
            null
        } finally {
            conn.disconnect()
        }
    }

    /** Lưu JPEG vào Thư viện; trả về Uri của ảnh hoặc null nếu lỗi. */
    suspend fun save(jpeg: ByteArray, deviceId: String, atMs: Long = System.currentTimeMillis()): Uri? =
        withContext(Dispatchers.IO) {
            val name = "OnCam_${deviceId}_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(atMs))}.jpg"
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) saveScoped(jpeg, name) else saveLegacy(jpeg, name)
            } catch (e: Exception) {
                Timber.w(e, "Không lưu được ảnh %s", name)
                null
            }
        }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun saveScoped(jpeg: ByteArray, name: String): Uri? {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, JPEG)
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/$ALBUM")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = resolver.insert(collection, values) ?: return null
        val written = resolver.openOutputStream(uri)?.use { it.write(jpeg); true } ?: false
        if (!written) {
            resolver.delete(uri, null, null)
            return null
        }
        values.clear()
        values.put(MediaStore.Images.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        return uri
    }

    // Android 9 trở xuống: ghi file vào Pictures/OnCam (cần WRITE_EXTERNAL_STORAGE) rồi đăng ký với MediaStore.
    // Các API này bị đánh dấu deprecated từ Android 10 nhưng là cách đúng cho Android 8–9.
    @Suppress("DEPRECATION")
    private fun saveLegacy(jpeg: ByteArray, name: String): Uri? {
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), ALBUM)
        if (!dir.exists() && !dir.mkdirs()) return null
        val file = File(dir, name)
        file.writeBytes(jpeg)
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, JPEG)
            put(MediaStore.Images.Media.DATA, file.absolutePath)
        }
        return context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
    }

    private companion object {
        const val ALBUM = "OnCam"
        const val JPEG = "image/jpeg"
        const val CONNECT_TIMEOUT_MS = 5_000
        const val READ_TIMEOUT_MS = 10_000
    }
}
