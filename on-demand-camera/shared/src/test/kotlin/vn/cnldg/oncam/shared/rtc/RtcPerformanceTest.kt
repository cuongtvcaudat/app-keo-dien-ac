package vn.cnldg.oncam.shared.rtc

import java.math.BigInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RtcPerformanceTest {

    private val names = listOf("VP8", "rtx", "VP9", "H264", "H264", "rtx", "AV1", "red", "ulpfec")

    @Test
    fun `uu tien chuan co phan cung H264 truoc VP8`() {
        val ordered = CodecPreference.order(names, setOf("H264", "VP8")).map { names[it] }
        assertEquals(listOf("H264", "H264", "VP8", "VP9", "AV1", "rtx", "rtx", "red", "ulpfec"), ordered)
    }

    @Test
    fun `chi co VP8 phan cung thi VP8 dung dau`() {
        val ordered = CodecPreference.order(names, setOf("vp8")).map { names[it] }
        assertEquals("VP8", ordered.first())
    }

    @Test
    fun `khong co phan cung thi dung VP8 phan mem truoc`() {
        val list = listOf("AV1", "VP9", "VP8", "rtx")
        val ordered = CodecPreference.order(list, emptySet()).map { list[it] }
        assertEquals(listOf("VP8", "VP9", "AV1", "rtx"), ordered)
    }

    @Test
    fun `doc thong ke WebRTC ra chuan nen do phan giai va kbit_s`() {
        fun report(videoBytes: Long, audioBytes: Long) = mapOf(
            "IT01V" to (
                "inbound-rtp" to mapOf<String, Any?>(
                    "kind" to "video",
                    "bytesReceived" to BigInteger.valueOf(videoBytes),
                    "frameWidth" to 1280L,
                    "frameHeight" to 720L,
                    "codecId" to "CIT01_96",
                )
                ),
            "IT01A" to ("inbound-rtp" to mapOf<String, Any?>("kind" to "audio", "bytesReceived" to BigInteger.valueOf(audioBytes))),
            "CIT01_96" to ("codec" to mapOf<String, Any?>("mimeType" to "video/H264")),
            "T01" to ("transport" to mapOf<String, Any?>("bytesReceived" to BigInteger.valueOf(999_999))),
        )

        val first = RtcStatsSummary.from(report(100_000, 4_000), null, 0)
        // 2 giây sau: thêm 225 000 byte hình + 8 000 byte tiếng = 233 000 byte → 932 kbit/s.
        val second = RtcStatsSummary.from(report(325_000, 12_000), first, 2_000)

        assertNull(first.kbps)
        assertEquals("H264", second.codec)
        assertEquals(1280 to 720, second.width to second.height)
        assertEquals(932, second.kbps)
        assertEquals(337_000L, second.totalBytes)
    }
}
