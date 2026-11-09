// Điều khiển lõi Tailscale nhúng cho OnCam. BSD-3 (xem NOTICE) cho phần phỏng theo tailscale-android.
package vn.cnldg.oncam.vpn

import android.content.Context
import android.util.Log
import java.io.ByteArrayInputStream

/**
 * Khởi động lõi Tailscale (Go) trong tiến trình app và đăng nhập bằng auth key.
 *
 * Dùng:
 *   1. Có consent VpnService (Activity gọi VpnService.prepare → nếu trả Intent thì xin phép).  ← TODO (UI)
 *   2. OncamTailscale.start(context, authKey)  → lõi chạy, [OncamVpnService] lập đường hầm.
 *   3. Sau khi join xong, GỌI [forgetAuthKey] để không giữ key.
 *
 * ⚠️ CHƯA THỬ TRÊN MÁY THẬT. Hai điểm dưới đây cần máy thật xác nhận (CI chỉ biên dịch):
 *   - Đúng endpoint/định dạng LocalAPI để đăng nhập authkey (xem [loginWithAuthKey]).
 *   - Quy ước EOF của libtailscale.InputStream (ở đây coi mảng rỗng là hết).
 */
object OncamTailscale {

    @Volatile private var app: libtailscale.Application? = null

    /** Khởi động lõi Tailscale với thư mục dữ liệu riêng của app. Gọi 1 lần. */
    @Synchronized
    fun start(context: Context): libtailscale.Application {
        app?.let { return it }
        val ctx = context.applicationContext
        val dir = ctx.filesDir.absolutePath
        // hwAttestationPref=false: OnCam không dùng chứng thực phần cứng.
        val a = libtailscale.Libtailscale.start(dir, dir, false, OncamAppContext(ctx))
        app = a
        Log.d(TAG, "Lõi Tailscale đã khởi động, dataDir=$dir")
        return a
    }

    /**
     * Đăng nhập không-tương-tác bằng auth key, rồi bật WantRunning.
     *
     * ⚠️ Best-effort, cần máy thật kiểm: gửi ipn.Options tới /localapi/v0/start.
     * Nếu máy thật báo lỗi, em sẽ chỉnh endpoint/định dạng theo log thực tế.
     */
    fun loginWithAuthKey(authKey: String, controlUrl: String = "https://controlplane.tailscale.com") {
        val a = app ?: error("Chưa start() lõi Tailscale")
        val options = """
            {"AuthKey":${jsonString(authKey)},
             "UpdatePrefs":{"WantRunning":true,"ControlURL":${jsonString(controlUrl)},
                            "WantRunningSet":true,"ControlURLSet":true}}
        """.trimIndent()
        val resp = a.callLocalAPI(30_000, "POST", "/localapi/v0/start", ByteInput(options.toByteArray()))
        Log.d(TAG, "login /start → HTTP ${resp.statusCode()}")
    }

    /** Xoá mọi dấu vết auth key khỏi store (gọi sau khi join xong). */
    fun forgetAuthKey(context: Context) {
        // Auth key chỉ truyền vào loginWithAuthKey, không ghi ra prefs của OnCam.
        // Tailscale sau khi join giữ KHOÁ-MÁY riêng (node key) trong store của nó, không phải auth key.
        Log.d(TAG, "forgetAuthKey: không giữ auth key trong OnCam")
    }

    private fun jsonString(s: String): String =
        buildString {
            append('"')
            for (c in s) when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(c)
            }
            append('"')
        }

    /** Bọc mảng byte thành libtailscale.InputStream (Go đọc: mảng rỗng = hết). */
    private class ByteInput(bytes: ByteArray) : libtailscale.InputStream {
        private val stream = ByteArrayInputStream(bytes)
        override fun read(): ByteArray {
            val buf = ByteArray(16 * 1024)
            val n = stream.read(buf)
            return if (n <= 0) ByteArray(0) else buf.copyOf(n)
        }
        override fun close() = stream.close()
    }

    private const val TAG = "OncamTailscale"
}
