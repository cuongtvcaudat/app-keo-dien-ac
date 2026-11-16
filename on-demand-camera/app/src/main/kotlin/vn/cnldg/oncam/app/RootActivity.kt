package vn.cnldg.oncam.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import vn.cnldg.oncam.app.theme.OnCamAppTheme

/**
 * Màn hình mở đầu của app gộp: nếu đã chọn vai trò thì chuyển thẳng sang giao diện tương ứng; chưa thì hiện
 * màn hình chọn vai trò. Giữ nguyên 2 giao diện cũ (camera-node.MainActivity / viewer.MainActivity) — chỉ
 * điều hướng, không đụng vào chúng.
 */
class RootActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val role = RoleStore.role(this)
        if (role != null) {
            open(role)
            return
        }
        enableEdgeToEdge()
        setContent {
            OnCamAppTheme {
                RolePicker(onPick = { picked ->
                    RoleStore.setRole(this, picked)
                    open(picked)
                })
            }
        }
    }

    private fun open(role: AppRole) {
        val target = when (role) {
            AppRole.CAMERA -> vn.cnldg.oncam.node.MainActivity::class.java
            AppRole.VIEWER -> vn.cnldg.oncam.viewer.MainActivity::class.java
        }
        startActivity(Intent(this, target).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP))
        finish()
    }
}

/** Màn hình chọn vai trò lần đầu. */
@Composable
private fun RolePicker(onPick: (AppRole) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(stringResource(R.string.role_title), style = MaterialTheme.typography.headlineSmall)
        Text(stringResource(R.string.role_hint), style = MaterialTheme.typography.bodyMedium)

        Button(onClick = { onPick(AppRole.CAMERA) }, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.role_camera))
        }
        Text(stringResource(R.string.role_camera_desc), style = MaterialTheme.typography.bodySmall)

        OutlinedButton(onClick = { onPick(AppRole.VIEWER) }, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.role_viewer))
        }
        Text(stringResource(R.string.role_viewer_desc), style = MaterialTheme.typography.bodySmall)

        Text(stringResource(R.string.role_change_note), style = MaterialTheme.typography.bodySmall)
    }
}
