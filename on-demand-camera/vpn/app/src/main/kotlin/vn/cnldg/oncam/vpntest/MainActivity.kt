package vn.cnldg.oncam.vpntest

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import vn.cnldg.oncam.vpn.OncamTailscale
import vn.cnldg.oncam.vpn.OncamVpnService

/**
 * Màn hình thử nghiệm: dán Tailscale auth key → bật lõi Tailscale → đăng nhập.
 * Mục tiêu kiểm trên máy thật: máy có hiện trong Tailscale admin console (join tailnet) không.
 */
class MainActivity : Activity() {

    private lateinit var keyInput: EditText
    private lateinit var status: TextView
    private var pendingKey: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        root.addView(TextView(this).apply {
            text = "OnCam VPN Test — nhúng Tailscale"
            textSize = 18f
        })
        root.addView(TextView(this).apply {
            text = "Dán auth key (tskey-auth-…) rồi bấm Kết nối. Sau đó mở Tailscale admin " +
                "console xem thiết bị có join tailnet không."
            setPadding(0, pad / 2, 0, pad / 2)
        })

        keyInput = EditText(this).apply {
            hint = "tskey-auth-…"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        root.addView(keyInput)

        root.addView(Button(this).apply {
            text = "Kết nối"
            setOnClickListener { connect() }
        })

        status = TextView(this).apply {
            setPadding(0, pad, 0, 0)
            text = "Trạng thái: chờ."
        }
        root.addView(status)

        val scroll = ScrollView(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            setGravity(Gravity.TOP)
            addView(root)
        }
        setContentView(scroll)
    }

    private fun connect() {
        pendingKey = keyInput.text.toString().trim()
        if (pendingKey.isEmpty()) {
            setStatus("Chưa nhập auth key.")
            return
        }
        // Xin quyền lập VPN (1 lần). prepare() trả Intent nếu cần hỏi người dùng.
        val prep = VpnService.prepare(this)
        if (prep != null) {
            startActivityForResult(prep, REQ_VPN)
        } else {
            afterConsent()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_VPN) {
            if (resultCode == RESULT_OK) {
                afterConsent()
            } else {
                setStatus("Người dùng chưa cho phép VPN.")
            }
        }
    }

    private fun afterConsent() {
        setStatus("Đang khởi động lõi Tailscale…")
        val key = pendingKey
        Thread {
            try {
                OncamTailscale.start(applicationContext)
                setStatus("Đã khởi động lõi. Đang đăng nhập bằng auth key…")
                OncamTailscale.loginWithAuthKey(key)
                startService(Intent(this, OncamVpnService::class.java))
                setStatus(
                    "Đã gửi lệnh đăng nhập + bật VPN.\n" +
                        "→ Mở Tailscale admin console (Machines) xem thiết bị mới có xuất hiện không.\n" +
                        "Nếu có: phần nhúng Tailscale CHẠY. Nếu không: chụp màn hình gửi lại.",
                )
            } catch (e: Throwable) {
                setStatus("Lỗi: ${e.javaClass.simpleName}: ${e.message}")
            }
        }.start()
    }

    private fun setStatus(s: String) = runOnUiThread { status.text = "Trạng thái: $s" }

    companion object {
        private const val REQ_VPN = 1001
    }
}
