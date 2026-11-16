package vn.cnldg.oncam.app

import android.content.Context
import androidx.core.content.edit

/** Vai trò của máy này trong app gộp. */
enum class AppRole { CAMERA, VIEWER }

/** Lưu vai trò đã chọn (chọn một lần; muốn đổi thì xoá dữ liệu app hoặc cài lại). */
object RoleStore {
    private const val FILE = "oncam_app"
    private const val KEY = "role"

    /** Vai trò đã chọn, null nếu chưa chọn lần nào. */
    fun role(context: Context): AppRole? =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY, null)
            ?.let { runCatching { AppRole.valueOf(it) }.getOrNull() }

    /** Ghi vai trò. */
    fun setRole(context: Context, role: AppRole) =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit { putString(KEY, role.name) }
}
