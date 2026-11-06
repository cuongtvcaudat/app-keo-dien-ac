package vn.cnldg.oncam.rtc

import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription

/** Lỗi bắt tay WebRTC (tạo/đặt SDP). */
class RtcException(message: String) : Exception(message)

/**
 * Theo dõi một PeerConnection: trạng thái kết nối và lúc gom xong ứng viên ICE.
 *
 * Lưu ý: các hàm callback chạy trên luồng nội bộ của WebRTC — **không** được gọi `close()`/`dispose()`
 * ngay trong callback (treo luồng); việc đó phải chuyển sang coroutine khác.
 */
internal class PeerWatcher : PeerConnection.Observer {
    private val _state = MutableStateFlow(PeerConnection.PeerConnectionState.NEW)

    /** Trạng thái kết nối (NEW → CONNECTING → CONNECTED → DISCONNECTED/FAILED/CLOSED). */
    val state: StateFlow<PeerConnection.PeerConnectionState> = _state.asStateFlow()

    /** Hoàn thành khi đã gom xong ứng viên ICE (SDP cục bộ đã có đủ địa chỉ). */
    val gathered = CompletableDeferred<Unit>()

    override fun onConnectionChange(newState: PeerConnection.PeerConnectionState?) {
        newState?.let { _state.value = it }
    }

    override fun onIceGatheringChange(newState: PeerConnection.IceGatheringState?) {
        if (newState == PeerConnection.IceGatheringState.COMPLETE) gathered.complete(Unit)
    }

    override fun onSignalingChange(newState: PeerConnection.SignalingState?) = Unit
    override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState?) = Unit
    override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
    override fun onIceCandidate(candidate: IceCandidate?) = Unit
    override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) = Unit
    override fun onAddStream(stream: MediaStream?) = Unit
    override fun onRemoveStream(stream: MediaStream?) = Unit
    override fun onDataChannel(channel: DataChannel?) = Unit
    override fun onRenegotiationNeeded() = Unit
}

internal suspend fun PeerConnection.createOfferAwait(): SessionDescription = createSdp(isOffer = true)

internal suspend fun PeerConnection.createAnswerAwait(): SessionDescription = createSdp(isOffer = false)

private suspend fun PeerConnection.createSdp(isOffer: Boolean): SessionDescription =
    suspendCancellableCoroutine { cont ->
        val observer = object : SdpObserver {
            override fun onCreateSuccess(desc: SessionDescription?) {
                if (desc != null) cont.resume(desc) else cont.resumeWithException(RtcException("SDP rỗng"))
            }

            override fun onCreateFailure(error: String?) = cont.resumeWithException(RtcException("Tạo SDP lỗi: $error"))
            override fun onSetSuccess() = Unit
            override fun onSetFailure(error: String?) = Unit
        }
        if (isOffer) createOffer(observer, MediaConstraints()) else createAnswer(observer, MediaConstraints())
    }

internal suspend fun PeerConnection.setLocalAwait(desc: SessionDescription) = setSdp(desc, local = true)

internal suspend fun PeerConnection.setRemoteAwait(desc: SessionDescription) = setSdp(desc, local = false)

private suspend fun PeerConnection.setSdp(desc: SessionDescription, local: Boolean): Unit =
    suspendCancellableCoroutine { cont ->
        val observer = object : SdpObserver {
            override fun onSetSuccess() = cont.resume(Unit)
            override fun onSetFailure(error: String?) = cont.resumeWithException(RtcException("Đặt SDP lỗi: $error"))
            override fun onCreateSuccess(desc: SessionDescription?) = Unit
            override fun onCreateFailure(error: String?) = Unit
        }
        if (local) setLocalDescription(observer, desc) else setRemoteDescription(observer, desc)
    }
