package vn.cnldg.oncam.viewer

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import org.junit.runner.RunWith
import vn.cnldg.oncam.viewer.data.CameraStore
import vn.cnldg.oncam.viewer.data.SavedCamera
import vn.cnldg.oncam.viewer.stream.SnapshotSaver

/** Chạy trên emulator/máy thật: lưu ảnh vào Thư viện (MediaStore) và lưu danh sách camera mã hoá. */
@RunWith(AndroidJUnit4::class)
class ViewerOnDeviceTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun luuAnhChupVaoThuVien() = runBlocking<Unit> {
        val jpeg = ByteArrayOutputStream().also {
            Bitmap.createBitmap(64, 48, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.JPEG, 80, it)
        }.toByteArray()

        val uri = assertNotNull(SnapshotSaver(context).save(jpeg, "test-cam"))

        val stored = context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
        assertContentEquals(jpeg, stored)
        val bmp = assertNotNull(BitmapFactory.decodeByteArray(stored, 0, stored.size))
        assertEquals(64, bmp.width)
        context.contentResolver.delete(uri, null, null)
    }

    @Test
    fun luuVaDocLaiDanhSachCameraMaHoa() = runBlocking<Unit> {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val camera = SavedCamera("test-cam", "tcp://100.64.0.9:1883", "test-secret-0123456789", label = "Thử")
            val store = CameraStore(context, scope)
            withTimeout(10_000) { store.cameras.filterNotNull().first() }
            store.upsert(camera)
            store.upsert(camera.copy(label = "Đã sửa"))

            // Đọc lại bằng một instance mới (như khi mở lại app).
            val reopened = CameraStore(context, scope)
            val list = withTimeout(10_000) { reopened.cameras.filterNotNull().first() }
            assertEquals(listOf(camera.copy(label = "Đã sửa")), list.filter { it.deviceId == "test-cam" })

            reopened.remove("test-cam")
            assertEquals(emptyList(), reopened.cameras.value.orEmpty().filter { it.deviceId == "test-cam" })
        } finally {
            scope.cancel()
        }
    }
}
