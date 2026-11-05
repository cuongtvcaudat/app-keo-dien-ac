package vn.cnldg.oncam.shared.client

import com.sun.net.httpserver.HttpServer
import java.io.ByteArrayInputStream
import java.io.EOFException
import java.net.InetSocketAddress
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import vn.cnldg.oncam.shared.protocol.StreamPaths
import vn.cnldg.oncam.shared.security.StreamAuth

class MjpegReaderTest {

    private val jpegA = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2, 3, 0xFF.toByte(), 0xD9.toByte())
    private val jpegB = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 9, 0xFF.toByte(), 0xD9.toByte())
    private var server: HttpServer? = null

    @AfterTest
    fun tearDown() {
        server?.stop(0)
    }

    private fun part(jpeg: ByteArray, ts: Long?, withLength: Boolean = true): ByteArray {
        val headers = buildString {
            append("--oncamframe\r\nContent-Type: image/jpeg\r\n")
            if (withLength) append("Content-Length: ${jpeg.size}\r\n")
            if (ts != null) append("${StreamPaths.TIMESTAMP_HEADER}: $ts\r\n")
            append("\r\n")
        }
        return headers.toByteArray() + jpeg + "\r\n".toByteArray()
    }

    @Test
    fun `doc tung khung kem thoi diem chup`() {
        val body = part(jpegA, 1_700_000_000_123) + part(jpegB, null) + "--oncamframe--\r\n".toByteArray()
        val reader = MjpegReader.fromStream(ByteArrayInputStream(body), "oncamframe")

        val first = reader.next()!!
        assertContentEquals(jpegA, first.jpeg)
        assertEquals(1_700_000_000_123, first.capturedAtMs)
        val second = reader.next()!!
        assertContentEquals(jpegB, second.jpeg)
        assertNull(second.capturedAtMs)
        assertNull(reader.next()) // ranh giới cuối
    }

    @Test
    fun `khong co Content-Length thi doc toi ma ket thuc JPEG`() {
        val body = part(jpegA, null, withLength = false) + part(jpegB, null, withLength = false)
        val reader = MjpegReader.fromStream(ByteArrayInputStream(body), "oncamframe")

        assertContentEquals(jpegA, reader.next()!!.jpeg)
        assertContentEquals(jpegB, reader.next()!!.jpeg)
    }

    @Test
    fun `luong bi cat giua khung bao loi`() {
        val full = part(jpegA, null)
        val body = full.copyOfRange(0, full.size - 5) // cắt mất 3 byte cuối của JPEG + CRLF
        val reader = MjpegReader.fromStream(ByteArrayInputStream(body), "oncamframe")

        assertFailsWith<EOFException> { reader.next() }
    }

    @Test
    fun `lay boundary tu Content-Type`() {
        assertEquals("oncamframe", MjpegReader.boundaryOf("multipart/x-mixed-replace; boundary=oncamframe"))
        assertEquals("abc", MjpegReader.boundaryOf("multipart/x-mixed-replace;boundary=\"--abc\""))
        assertNull(MjpegReader.boundaryOf("image/jpeg"))
        assertNull(MjpegReader.boundaryOf(null))
    }

    @Test
    fun `mo luong qua HTTP gui header xac thuc va bao ma loi`() {
        val auth = StreamAuth("test-secret-0123456789", "san-nha")
        val http = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).also { server = it }
        http.createContext(StreamPaths.STREAM) { ex ->
            if (!auth.isValid(ex.requestHeaders.getFirst(StreamAuth.HEADER))) {
                ex.sendResponseHeaders(401, -1)
                ex.close()
                return@createContext
            }
            ex.responseHeaders.add("Content-Type", "multipart/x-mixed-replace; boundary=${StreamPaths.BOUNDARY}")
            ex.sendResponseHeaders(200, 0) // chunked
            ex.responseBody.use { out ->
                repeat(3) { out.write(part(jpegA, 42)); out.flush() }
            }
        }
        http.start()
        val url = "http://127.0.0.1:${http.address.port}${StreamPaths.STREAM}"

        val err = assertFailsWith<HttpStatusException> { MjpegReader.open(url, "1.sai") }
        assertEquals(401, err.code)

        MjpegReader.open(url, auth.headerValue()).use { reader ->
            repeat(3) { assertContentEquals(jpegA, reader.next()!!.jpeg) }
            assertNull(reader.next())
        }
    }
}
