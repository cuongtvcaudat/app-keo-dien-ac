package vn.cnldg.oncam.node.core

import java.net.ServerSocket
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import vn.cnldg.oncam.node.mqtt.ActionOutcome
import vn.cnldg.oncam.shared.protocol.Action
import vn.cnldg.oncam.shared.protocol.CameraCodes
import vn.cnldg.oncam.shared.protocol.CameraState
import vn.cnldg.oncam.shared.protocol.Command
import vn.cnldg.oncam.shared.protocol.CommandParams
import vn.cnldg.oncam.shared.protocol.ProtocolJson
import vn.cnldg.oncam.shared.protocol.Recordings
import vn.cnldg.oncam.shared.security.StreamAuth

/** Quay video (0.10) trong StreamManager với camera giả; cổng tải video thật (NanoHTTPD). */
class StreamManagerRecordingTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val httpPort = ServerSocket(0).use { it.localPort }
    private val recPort = ServerSocket(0).use { it.localPort }
    private val auth = StreamAuth("test-secret-0123456789", "san-nha")
    private val fake = FakeCameraPort(scope)
    private val dir = createTempDirectory("oncam-rec").toFile()
    private val store = RecordingStore(dir)

    private fun manager(recordings: RecordingStore? = store) = StreamManager(
        camera = fake,
        auth = auth,
        scope = scope,
        addressProvider = { "127.0.0.1" },
        cameraAllowed = { true },
        deviceInfo = { DeviceSnapshot(temperatureC = 35.0) },
        settings = StreamManager.Settings(
            httpPort = httpPort,
            checkIntervalMs = 30,
            msPerIdleSecond = 10,
            recordingPort = recPort,
        ),
        recordings = recordings,
        audioAllowed = { true },
    )

    private val manager = manager()

    @AfterTest
    fun tearDown() = runBlocking<Unit> {
        manager.shutdown()
        scope.cancel()
        dir.deleteRecursively()
    }

    @Test
    fun `quay khi camera dang tat thi bat camera, dung thi luu file va mo cong tai`() = runBlocking<Unit> {
        val started = manager.perform(cmd(Action.START_RECORDING))
        val name = assertIs<ActionOutcome.Done>(started).message!!
        assertTrue(Recordings.isValidName(name))
        assertEquals(CameraState.STREAMING, manager.status.value.state)
        assertTrue(manager.status.value.recording.active)
        assertEquals(Recordings.DEFAULT_MAX_SECONDS, manager.status.value.recording.maxSeconds)
        assertEquals(true, manager.status.value.recording.audio)

        manager.perform(cmd(Action.STOP_RECORDING))

        val rec = manager.status.value.recording
        assertEquals(false, rec.active)
        assertEquals(name, rec.lastFile)
        assertEquals(1024L, rec.lastFileBytes)

        // Cổng tải video tự mở sau khi quay xong.
        val base = "http://127.0.0.1:$recPort"
        val list = withTimeout(5_000) {
            var r = runCatching { httpGet(base + Recordings.LIST_PATH, auth.headerValue()) }.getOrNull()
            while (r?.code != 200) {
                delay(50)
                r = runCatching { httpGet(base + Recordings.LIST_PATH, auth.headerValue()) }.getOrNull()
            }
            ProtocolJson.decodeRecordings(String(r.body))!!
        }
        assertEquals(listOf(name), list.map { it.name })
        val file = httpGet(base + Recordings.filePath(name), auth.headerValue())
        assertEquals(200, file.code)
        assertContentEquals(ByteArray(1024) { 7 }, file.body)
    }

    @Test
    fun `cong tai video bat buoc xac thuc va chan duong dan la`() = runBlocking<Unit> {
        val url = assertIs<ActionOutcome.Done>(manager.perform(cmd(Action.OPEN_RECORDINGS))).message!!
        assertEquals("http://127.0.0.1:$recPort", url)
        assertEquals(CameraState.OFF, manager.status.value.state, "mở cổng tải không cần bật camera")

        assertEquals(401, httpGet(url + Recordings.LIST_PATH, null).code)
        assertEquals(401, httpGet(url + Recordings.LIST_PATH, "sai").code)
        assertEquals(404, httpGet("$url/recordings/..%2F..%2Fetc%2Fpasswd", auth.headerValue()).code)
        assertEquals(404, httpGet("$url/recordings/khac.mp4", auth.headerValue()).code)
        assertEquals("[]", String(httpGet(url + Recordings.LIST_PATH, auth.headerValue()).body))
    }

    @Test
    fun `het thoi gian toi da thi tu dung va luu`() = runBlocking<Unit> {
        manager.perform(cmd(Action.START_RECORDING, CommandParams(maxSeconds = 1)))
        withTimeout(5_000) { manager.status.first { !it.recording.active && it.recording.lastFile != null } }
    }

    @Test
    fun `dang quay thi khong tu tat camera, dung quay roi moi tu tat`() = runBlocking<Unit> {
        manager.perform(cmd(Action.START_RECORDING, CommandParams(idleTimeoutSec = 30))) // 30 "giây" = 0,3 s
        delay(800)
        assertEquals(CameraState.STREAMING, manager.status.value.state, "đang quay được tính là người xem")

        manager.perform(cmd(Action.STOP_RECORDING))
        withTimeout(5_000) { manager.status.first { it.state == CameraState.OFF } }
    }

    @Test
    fun `tat camera khi dang quay thi clip van duoc luu`() = runBlocking<Unit> {
        manager.perform(cmd(Action.START_RECORDING))
        manager.perform(cmd(Action.STOP_STREAM))

        assertEquals(CameraState.OFF, manager.status.value.state)
        assertEquals(false, manager.status.value.recording.active)
        assertNotNull(manager.status.value.recording.lastFile)
    }

    @Test
    fun `may khong quay duoc thi bao loi, ban cu khong ho tro thi bao chua ho tro`() = runBlocking<Unit> {
        fake.recordingSupported = false
        assertEquals(ActionOutcome.Failed(CameraCodes.RECORDING_FAILED), manager.perform(cmd(Action.START_RECORDING)))
        assertEquals(CameraCodes.RECORDING_FAILED, manager.status.value.recording.error)

        val noStore = manager(recordings = null)
        try {
            assertEquals(ActionOutcome.Failed(CameraCodes.RECORDING_UNSUPPORTED), noStore.perform(cmd(Action.START_RECORDING)))
            assertEquals(false, noStore.recordingSupported())
        } finally {
            noStore.shutdown()
        }
    }

    @Test
    fun `giu tong dung luong duoi gioi han, xoa clip cu nhat`() {
        var now = 1_700_000_000_000L
        val small = RecordingStore(createTempDirectory("oncam-lim").toFile(), maxBytes = 2_500, nowMs = { now })
        val files = (0 until 4).map {
            now += 1_000
            small.newFile().apply {
                writeBytes(ByteArray(1_000))
                setLastModified(now)
            }
        }
        assertEquals(2, small.enforceLimit(keep = files.last()))
        assertEquals(files.takeLast(2).map { it.name }.reversed(), small.list().map { it.name })
        assertNull(small.file("../" + files.last().name))
        small.dir.deleteRecursively()
    }

    private fun cmd(action: Action, params: CommandParams = CommandParams()) =
        Command(id = "11111111-2222-3333-4444-555555555555", action = action, params = params, token = "t", timestamp = 0)
}
