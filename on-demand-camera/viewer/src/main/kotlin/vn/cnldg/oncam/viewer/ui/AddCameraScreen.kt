package vn.cnldg.oncam.viewer.ui

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import vn.cnldg.oncam.viewer.R
import vn.cnldg.oncam.viewer.data.SavedCamera
import vn.cnldg.oncam.viewer.data.SavedCameraProblem

/**
 * Thêm/sửa camera: nhập 3 thông tin hiện ở mục "Nhập vào app OnCam Xem" trên máy camera
 * (địa chỉ broker, mã camera, token bí mật). Có nút Dán để khỏi gõ token 43 ký tự.
 */
@Composable
fun AddCameraScreen(
    initial: SavedCamera?,
    onSave: (SavedCamera) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var brokerUri by rememberSaveable { mutableStateOf(initial?.brokerUri.orEmpty()) }
    var deviceId by rememberSaveable { mutableStateOf(initial?.deviceId.orEmpty()) }
    var secret by rememberSaveable { mutableStateOf(initial?.secret.orEmpty()) }
    var label by rememberSaveable { mutableStateOf(initial?.label.orEmpty()) }
    var externalBroker by rememberSaveable { mutableStateOf(initial?.brokerUsername?.isNotBlank() == true) }
    var brokerUser by rememberSaveable { mutableStateOf(initial?.brokerUsername.orEmpty()) }
    var brokerPass by rememberSaveable { mutableStateOf(initial?.brokerPassword.orEmpty()) }
    var problems by remember { mutableStateOf(emptyList<SavedCameraProblem>()) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            stringResource(if (initial == null) R.string.add_title else R.string.edit_title),
            style = MaterialTheme.typography.headlineSmall,
        )
        Text(stringResource(R.string.add_intro), style = MaterialTheme.typography.bodyMedium)

        PasteField(
            value = brokerUri,
            onValueChange = { brokerUri = it.trim() },
            label = stringResource(R.string.field_broker_uri),
            placeholder = stringResource(R.string.field_broker_uri_placeholder),
            isError = SavedCameraProblem.INVALID_BROKER_URI in problems,
            onPaste = { pasteText(context)?.let { brokerUri = it.trim() } },
        )
        OutlinedTextField(
            value = deviceId,
            onValueChange = { deviceId = it.trim().lowercase() },
            label = { Text(stringResource(R.string.field_device_id)) },
            placeholder = { Text(stringResource(R.string.field_device_id_placeholder)) },
            singleLine = true,
            isError = SavedCameraProblem.INVALID_DEVICE_ID in problems,
            modifier = Modifier.fillMaxWidth(),
        )
        PasteField(
            value = secret,
            onValueChange = { secret = it.trim() },
            label = stringResource(R.string.field_secret),
            placeholder = null,
            isError = SavedCameraProblem.WEAK_SECRET in problems,
            onPaste = { pasteText(context)?.let { secret = it.trim() } },
        )
        OutlinedTextField(
            value = label,
            onValueChange = { label = it },
            label = { Text(stringResource(R.string.field_label)) },
            supportingText = { Text(stringResource(R.string.field_label_hint)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = externalBroker, onCheckedChange = { externalBroker = it })
            Text(stringResource(R.string.field_external_broker), style = MaterialTheme.typography.bodyMedium)
        }
        if (externalBroker) {
            OutlinedTextField(
                value = brokerUser,
                onValueChange = { brokerUser = it.trim() },
                label = { Text(stringResource(R.string.field_broker_user)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = brokerPass,
                onValueChange = { brokerPass = it },
                label = { Text(stringResource(R.string.field_broker_pass)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        problems.forEach {
            Text(stringResource(it.messageRes()), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                val camera = SavedCamera(
                    deviceId = deviceId,
                    brokerUri = brokerUri,
                    secret = secret,
                    label = label.trim(),
                    brokerUsername = if (externalBroker) brokerUser else "",
                    brokerPassword = if (externalBroker) brokerPass else "",
                )
                problems = camera.problems()
                if (problems.isEmpty()) onSave(camera)
            }) { Text(stringResource(R.string.action_save)) }
            TextButton(onClick = onCancel) { Text(stringResource(R.string.action_cancel)) }
        }
    }
}

@Composable
private fun PasteField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    placeholder: String?,
    isError: Boolean,
    onPaste: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(label) },
            placeholder = if (placeholder != null) {
                { Text(placeholder) }
            } else {
                null
            },
            singleLine = true,
            isError = isError,
            modifier = Modifier.fillMaxWidth(),
        )
        TextButton(onClick = onPaste) { Text(stringResource(R.string.action_paste)) }
    }
}

private fun pasteText(context: Context): String? =
    context.getSystemService(ClipboardManager::class.java)
        ?.primaryClip
        ?.takeIf { it.itemCount > 0 }
        ?.getItemAt(0)
        ?.coerceToText(context)
        ?.toString()

private fun SavedCameraProblem.messageRes(): Int = when (this) {
    SavedCameraProblem.INVALID_DEVICE_ID -> R.string.problem_device_id
    SavedCameraProblem.INVALID_BROKER_URI -> R.string.problem_broker_uri
    SavedCameraProblem.WEAK_SECRET -> R.string.problem_secret
}
