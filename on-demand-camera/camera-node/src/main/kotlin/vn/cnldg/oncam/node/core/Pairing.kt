package vn.cnldg.oncam.node.core

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import vn.cnldg.oncam.shared.client.PairingCode

/**
 * Nội dung mã QR cho app Xem.
 *
 * Broker nhúng: app Xem tự suy mật khẩu broker từ token nên không đưa vào QR.
 * Broker riêng: kèm tên đăng nhập/mật khẩu broker đang dùng.
 *
 * @param brokerUri địa chỉ broker hiện tại ([NodeState.brokerUri]); null khi chưa có IP Tailscale → không tạo được mã.
 */
fun NodeConfig.pairingCode(brokerUri: String?): String? {
    val uri = brokerUri ?: return null
    val external = brokerMode == BrokerMode.EXTERNAL
    return PairingCode.encode(
        PairingCode.Info(
            deviceId = deviceId,
            brokerUri = uri,
            secret = secret,
            displayName = displayName,
            brokerUsername = externalUsername.takeIf { external },
            brokerPassword = externalPassword.takeIf { external },
        ),
    )
}

/**
 * Nội dung mã QR "chỉ xem" (0.13): mang token KHÁCH (không phải token chủ) và cờ khách; app Xem quét mã này chỉ
 * xem được hình. null nếu chưa bật quyền chỉ xem, đang dùng broker riêng, hoặc chưa có IP Tailscale.
 */
fun NodeConfig.guestPairingCode(brokerUri: String?): String? {
    val uri = brokerUri ?: return null
    val guestSecret = activeGuestSecret ?: return null
    return PairingCode.encode(
        PairingCode.Info(deviceId = deviceId, brokerUri = uri, secret = guestSecret, displayName = displayName, guest = true),
    )
}

/** Vẽ mã QR (thư viện zxing core, thuần Java). */
object QrCodes {
    /**
     * Ma trận QR, mỗi ô 1 điểm ảnh (giao diện phóng to không làm mờ), lề 2 ô.
     * Mức sửa lỗi M (~15%): đủ khi quét từ màn hình, mã nhỏ gọn hơn mức H.
     */
    fun encode(text: String): BitMatrix = QRCodeWriter().encode(
        text,
        BarcodeFormat.QR_CODE,
        0,
        0,
        mapOf(
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.MARGIN to 2,
            EncodeHintType.CHARACTER_SET to "UTF-8",
        ),
    )
}
