package vn.cnldg.oncam.node.rtc

import kotlinx.coroutines.flow.StateFlow
import vn.cnldg.oncam.node.core.RtcAnswer
import vn.cnldg.oncam.node.core.RtcPort
import vn.cnldg.oncam.rtc.CameraRtc
import vn.cnldg.oncam.rtc.CameraRtcAnswer
import vn.cnldg.oncam.shared.protocol.StreamQuality

/** Nối [CameraRtc] (module :rtc, Android) vào lõi camera-node ([RtcPort], test được trên JVM). */
class NodeRtc(private val rtc: CameraRtc, private val micAllowed: () -> Boolean) : RtcPort {
    override val sessions: StateFlow<Int> = rtc.sessionCount

    override fun micReady(): Boolean = micAllowed()

    override suspend fun answer(sessionId: String, offerSdp: String, quality: StreamQuality, audio: Boolean): RtcAnswer =
        when (val a = rtc.answer(sessionId, offerSdp, quality, audio)) {
            is CameraRtcAnswer.Ok -> RtcAnswer.Ok(a.sdp)
            is CameraRtcAnswer.Failed -> RtcAnswer.Failed(a.code)
        }

    override fun setQuality(quality: StreamQuality) = rtc.setQuality(quality)

    override fun hangup(sessionId: String): Boolean = rtc.hangup(sessionId)

    override fun closeAll() = rtc.closeAll()
}
