package vn.cnldg.oncam.node.core

import java.io.IOException
import java.time.ZoneOffset
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import vn.cnldg.oncam.shared.protocol.AlertType
import vn.cnldg.oncam.shared.protocol.DeviceAlert
import vn.cnldg.oncam.shared.protocol.NetworkType
import vn.cnldg.oncam.shared.security.AlertAuth

/** Khi nào báo, báo mấy lần, email ra sao (đồng hồ giả, thời gian chờ rút ngắn). */
class AlertManagerTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val auth = AlertAuth("test-secret-0123456789", "san-nha")
    private val sent = CopyOnWriteArrayList<DeviceAlert>()
    private val mails = CopyOnWriteArrayList<MailMessage>()
    private val jpeg = ByteArray(3_000) { (it * 7).toByte() }

    @Volatile private var now = 1800000000_000L // 15/01/2027 08:00:00 UTC (15:00 giờ Việt Nam)
    @Volatile private var settings = AlertSettings()
    @Volatile private var frame: ByteArray? = jpeg
    private val mailAttempts = AtomicInteger()

    private val manager = AlertManager(
        deviceId = "san-nha",
        auth = auth,
        scope = scope,
        settings = { settings },
        cameraName = { "Sân nhà" },
        snapshot = { frame },
        mapsUrl = { "https://www.google.com/maps/search/?api=1&query=11.940000,108.430000" },
        mailer = { m ->
            mails += m
        },
        nowMs = { now },
        zone = ZoneOffset.ofHours(7),
        policy = AlertManager.Policy(motionSnapshotDelayMs = 20, emailRetryDelaysMs = listOf(30, 30)),
    )

    init {
        scope.launch { manager.outgoing.collect { sent += it } }
    }

    @AfterTest
    fun tearDown() = scope.cancel()

    private fun device(plugged: Boolean?, battery: Int = 80, temp: Double = 33.0) =
        DeviceSnapshot(battery, plugged, temp, NetworkType.WIFI, plugged = plugged)

    private suspend fun await(count: Int, list: List<*> = sent) = withTimeout(3_000) { while (list.size < count) delay(10) }

    private suspend fun settle() = delay(150)

    @Test
    fun `chuyen dong - bao kem anh da ky, toi da 1 lan moi phut, email 1 lan moi 5 phut`() = runBlocking<Unit> {
        manager.onMotion("oncam-20270115-010000.mp4")
        await(1)
        await(1, mails)
        val a = sent.single()
        assertEquals(AlertType.MOTION, a.type)
        assertEquals("Sân nhà", a.cameraName)
        assertEquals("oncam-20270115-010000.mp4", a.clip)
        assertContentEquals(jpeg, Base64.getDecoder().decode(a.snapshot))
        assertTrue(auth.verify(a), "chữ ký đúng")
        assertFalse(auth.verify(a.copy(clip = "khac.mp4")), "sửa nội dung → sai chữ ký")
        assertFalse(AlertAuth("token-khac-0123456789", "san-nha").verify(a))

        // 30 giây sau: còn trong 1 phút → không báo.
        now += 30_000
        manager.onMotion("b.mp4")
        settle()
        assertEquals(1, sent.size)

        // 61 giây sau lần đầu: báo qua app, chưa gửi email (chưa đủ 5 phút).
        now += 31_000
        manager.onMotion("c.mp4")
        await(2)
        settle()
        assertEquals(1, mails.size)

        // Sau 5 phút: email lại.
        now += 5 * 60_000
        manager.onMotion("d.mp4")
        await(3)
        await(2, mails)
    }

    @Test
    fun `tat loai canh bao trong cau hinh thi khong bao`() = runBlocking<Unit> {
        settings = AlertSettings(motion = false, power = false, heat = false)
        manager.onMotion("a.mp4")
        manager.onDevice(device(plugged = true))
        now += 60_000
        manager.onDevice(device(plugged = false, battery = 10, temp = 50.0))
        now += 60_000
        manager.onDevice(device(plugged = false, battery = 10, temp = 50.0))
        settle()
        assertTrue(sent.isEmpty())
        assertTrue(mails.isEmpty())
    }

    @Test
    fun `rut nguon qua 30 giay thi bao mat dien, cam lai thi bao co dien`() = runBlocking<Unit> {
        frame = null // camera đang tắt: không có ảnh
        manager.onDevice(device(plugged = true))
        now += 10_000
        manager.onDevice(device(plugged = false))
        now += 20_000
        manager.onDevice(device(plugged = false))
        settle()
        assertTrue(sent.isEmpty(), "mới 20 giây (lỏng dây chớp nhoáng) chưa báo")

        now += 10_000
        manager.onDevice(device(plugged = false, battery = 79))
        await(1)
        assertEquals(AlertType.POWER_LOST, sent[0].type)
        assertEquals(79, sent[0].batteryPercent)
        assertNull(sent[0].snapshot)
        assertTrue(auth.verify(sent[0]))

        now += 60_000
        manager.onDevice(device(plugged = false))
        settle()
        assertEquals(1, sent.size, "không báo lặp")

        now += 60_000
        manager.onDevice(device(plugged = true))
        await(2)
        assertEquals(AlertType.POWER_RESTORED, sent[1].type)
        await(2, mails)
        assertTrue(mails[0].subject.contains("Mất điện"), mails[0].subject)
        assertNull(mails[0].attachment)
    }

    @Test
    fun `mo app luc dang khong cam sac thi khong coi la mat dien, pin yeu thi bao 1 lan`() = runBlocking<Unit> {
        manager.onDevice(device(plugged = false, battery = 60))
        now += 120_000
        manager.onDevice(device(plugged = false, battery = 21))
        settle()
        assertTrue(sent.isEmpty())

        manager.onDevice(device(plugged = false, battery = 20))
        await(1)
        assertEquals(AlertType.BATTERY_LOW, sent[0].type)
        now += 60_000
        manager.onDevice(device(plugged = false, battery = 15))
        settle()
        assertEquals(1, sent.size)

        // Sạc lại lên 30 % rồi tụt lần nữa → báo lại.
        manager.onDevice(device(plugged = true, battery = 35))
        now += 3_600_000
        manager.onDevice(device(plugged = false, battery = 19))
        await(2)
        assertEquals(AlertType.BATTERY_LOW, sent[1].type)
    }

    @Test
    fun `qua nong bao 1 lan, nguoi lai roi nong lai trong 30 phut khong bao lap`() = runBlocking<Unit> {
        manager.onDevice(device(plugged = true, temp = 41.9))
        settle()
        assertTrue(sent.isEmpty())
        manager.onDevice(device(plugged = true, temp = 42.5))
        await(1)
        assertEquals(AlertType.OVERHEAT, sent[0].type)
        assertEquals(42.5, sent[0].temperatureC)
        manager.onDevice(device(plugged = true, temp = 44.0))
        manager.onDevice(device(plugged = true, temp = 38.0))
        now += 10 * 60_000
        manager.onDevice(device(plugged = true, temp = 43.0))
        settle()
        assertEquals(1, sent.size)
        now += 21 * 60_000
        manager.onDevice(device(plugged = true, temp = 43.0))
        await(2)
    }

    @Test
    fun `email loi mang thi thu lai, sai mat khau thi thoi`() = runBlocking<Unit> {
        // 2 lần đầu lỗi mạng, lần 3 thành công.
        val ok = CopyOnWriteArrayList<MailMessage>()
        val m = AlertManager(
            deviceId = "san-nha", auth = auth, scope = scope, settings = { AlertSettings() }, cameraName = { "Sân nhà" },
            snapshot = { null },
            mailer = { msg -> if (mailAttempts.incrementAndGet() <= 2) throw IOException("mất mạng") else ok += msg },
            nowMs = { now }, policy = AlertManager.Policy(motionSnapshotDelayMs = 0, emailRetryDelaysMs = listOf(30, 30)),
        )
        m.onMotion(null)
        await(1, ok)
        assertEquals(3, mailAttempts.get())

        // Sai mật khẩu ứng dụng (535): không thử lại.
        val attempts = AtomicInteger()
        val bad = AlertManager(
            deviceId = "san-nha", auth = auth, scope = scope, settings = { AlertSettings() }, cameraName = { "Sân nhà" },
            snapshot = { null },
            mailer = { attempts.incrementAndGet(); throw SmtpException(535, "SMTP AUTH → 535") },
            nowMs = { now }, policy = AlertManager.Policy(motionSnapshotDelayMs = 0, emailRetryDelaysMs = listOf(30, 30)),
        )
        bad.onMotion(null)
        delay(300)
        assertEquals(1, attempts.get())
    }

    @Test
    fun `noi dung email tieng Viet du thong tin`() {
        val alert = DeviceAlert(
            type = AlertType.MOTION,
            timestampMs = now,
            cameraName = "Sân nhà",
            batteryPercent = 85,
            temperatureC = 36.5,
            clip = "oncam-20270115-010000.mp4",
            mapsUrl = "https://www.google.com/maps/search/?api=1&query=11.940000,108.430000",
        )
        val mail = manager.mail(alert, jpeg)
        assertEquals("[OnCam] Sân nhà: Có chuyển động lúc 15:00 15/01", mail.subject)
        assertTrue(mail.body.contains("Lúc: 15:00:00 15/01/2027"), mail.body)
        assertTrue(mail.body.contains("Pin: 85 % • Nhiệt độ: 36,5 °C"), mail.body)
        assertTrue(mail.body.contains("query=11.940000,108.430000"))
        assertEquals("oncam-san-nha-20270115-150000.jpg", mail.attachmentName)
        assertNotNull(mail.attachment)
        assertTrue(manager.mail(alert.copy(type = AlertType.OVERHEAT, temperatureC = 43.2), null).subject.contains("43,2 °C"))
    }
}
