// Phỏng theo tailscale-android (App.kt phần libtailscale.AppContext), giấy phép BSD-3-Clause.
// Copyright (c) Tailscale Inc & AUTHORS. Xem on-demand-camera/vpn/NOTICE.
//
// Đây là các "móc" (hook) mà lõi Tailscale (Go) gọi ngược về phía Android: lưu trạng thái
// (mã hoá), đọc thông tin máy/mạng, bind socket. OnCam chỉ cần join 1 tailnet nên các phần
// doanh nghiệp (MDM/syspolicy, hardware-attestation, DNS nền tảng) được rút gọn an toàn:
// báo "không có" để Tailscale tự dùng mặc định.
package vn.cnldg.oncam.vpn

import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Base64
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import java.net.NetworkInterface
import java.security.KeyStore
import java.util.Collections
import org.json.JSONArray
import org.json.JSONObject

/**
 * Cài đặt [libtailscale.AppContext] cho OnCam.
 *
 * @param appContext applicationContext của app (không giữ Activity để tránh rò rỉ).
 */
class OncamAppContext(
    private val appContext: Context,
) : libtailscale.AppContext {

    private val prefs by lazy {
        val masterKey = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
        EncryptedSharedPreferences.create(
            "oncam_tailscale_store",
            masterKey,
            appContext,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    override fun log(tag: String, line: String) {
        Log.d(tag, line)
    }

    // --- Lưu trạng thái Tailscale (mã hoá tại chỗ) ---

    override fun encryptToPref(key: String, value: String) {
        prefs.edit().putString(key, value).commit()
    }

    override fun decryptFromPref(key: String): String? = prefs.getString(key, null)

    override fun getStateStoreKeysJSON(): String {
        val prefix = "statestore-"
        val keys = prefs.all.keys.filter { it.startsWith(prefix) }.map { it.removePrefix(prefix) }
        return JSONArray(keys).toString()
    }

    // --- Thông tin máy ---

    override fun getOSVersion(): String = Build.VERSION.RELEASE ?: ""

    override fun getSDKInt(): Long = Build.VERSION.SDK_INT.toLong()

    override fun getDeviceName(): String {
        val manu = Build.MANUFACTURER ?: ""
        val model = Build.MODEL ?: ""
        return "$manu $model".trim()
    }

    override fun getInstallSource(): String = "unknown"

    override fun shouldUseGoogleDNSFallback(): Boolean = false

    override fun isChromeOS(): Boolean =
        appContext.packageManager.hasSystemFeature("android.hardware.type.pc")

    override fun isClientLoggingEnabled(): Boolean = false

    // --- Mạng ---

    override fun getInterfacesAsJson(): String {
        val arr = JSONArray()
        val nifs = try {
            Collections.list(NetworkInterface.getNetworkInterfaces())
        } catch (_: Exception) {
            emptyList<NetworkInterface>()
        }
        for (nif in nifs) {
            try {
                val addrs = JSONArray()
                for (ia in nif.interfaceAddresses) {
                    val host = ia.address?.hostAddress ?: continue
                    addrs.put(JSONObject().put("ip", host).put("prefixLen", ia.networkPrefixLength.toInt()))
                }
                arr.put(
                    JSONObject()
                        .put("name", nif.name)
                        .put("index", nif.index)
                        .put("mtu", nif.mtu)
                        .put("up", nif.isUp)
                        .put("broadcast", nif.supportsMulticast())
                        .put("loopback", nif.isLoopback)
                        .put("pointToPoint", nif.isPointToPoint)
                        .put("multicast", nif.supportsMulticast())
                        .put("addrs", addrs),
                )
            } catch (_: Exception) {
                continue
            }
        }
        return arr.toString()
    }

    // Để trống → Tailscale dùng cấu hình DNS mặc định của nó.
    override fun getPlatformDNSConfig(): String = ""

    override fun bindSocketToNetwork(fd: Int): Boolean {
        val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val net = cm.activeNetwork ?: return false
        return try {
            ParcelFileDescriptor.fromFd(fd).use { pfd -> net.bindSocket(pfd.fileDescriptor) }
            true
        } catch (e: Exception) {
            Log.w(TAG, "bindSocketToNetwork fd=$fd lỗi: $e")
            false
        }
    }

    override fun getUserCACertsPEM(): ByteArray {
        return try {
            val ks = KeyStore.getInstance("AndroidCAStore")
            ks.load(null)
            val sb = StringBuilder()
            for (alias in ks.aliases()) {
                if (!alias.startsWith("user:")) continue
                val cert = ks.getCertificate(alias) ?: continue
                sb.append("-----BEGIN CERTIFICATE-----\n")
                Base64.encodeToString(cert.encoded, Base64.NO_WRAP).chunked(64).forEach {
                    sb.append(it).append('\n')
                }
                sb.append("-----END CERTIFICATE-----\n")
            }
            sb.toString().toByteArray(Charsets.UTF_8)
        } catch (_: Exception) {
            ByteArray(0)
        }
    }

    // --- MDM/syspolicy: OnCam không dùng. Ném lỗi = "không đặt key này". ---

    override fun getSyspolicyStringValue(key: String): String = throw NoSuchKey(key)

    override fun getSyspolicyBooleanValue(key: String): Boolean = throw NoSuchKey(key)

    override fun getSyspolicyStringArrayJSONValue(key: String): String = throw NoSuchKey(key)

    // --- Hardware attestation: OnCam không dùng. ---

    override fun hardwareAttestationKeySupported(): Boolean = false

    override fun hardwareAttestationKeyCreate(): String = throw UnsupportedOperationException()

    override fun hardwareAttestationKeyRelease(id: String) = throw UnsupportedOperationException()

    override fun hardwareAttestationKeyPublic(id: String): ByteArray = throw UnsupportedOperationException()

    override fun hardwareAttestationKeySign(id: String, data: ByteArray): ByteArray =
        throw UnsupportedOperationException()

    override fun hardwareAttestationKeyLoad(id: String) = throw UnsupportedOperationException()

    private class NoSuchKey(key: String) : Exception("syspolicy: không có key \"$key\"")

    companion object {
        private const val TAG = "OncamTailscale"
    }
}
