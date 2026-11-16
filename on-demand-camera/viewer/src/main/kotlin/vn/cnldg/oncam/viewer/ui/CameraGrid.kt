package vn.cnldg.oncam.viewer.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import vn.cnldg.oncam.shared.protocol.CameraState
import vn.cnldg.oncam.viewer.R
import vn.cnldg.oncam.viewer.mqtt.CameraEntry

/**
 * Xem nhiều camera dạng lưới 2×2 / 3×3 (spec 8.4): mỗi ô là ảnh chụp gần nhất + trạng thái online/offline,
 * chạm vào ô để mở xem trực tiếp. Lưới chỉ là bản xem tổng — KHÔNG mở tất cả camera cùng lúc (giữ đúng thiết kế
 * "chỉ bật camera khi cần" để các máy camera đỡ nóng, đỡ hao pin).
 */
@Composable
fun CameraGrid(
    cameras: List<CameraEntry>,
    nameOf: (CameraEntry) -> String,
    lastSnapshot: Map<String, ByteArray>,
    onOpenLive: (CameraEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    // ≤ 4 camera: lưới 2 cột (2×2). Nhiều hơn: 3 cột (3×3) cho đỡ phải cuộn.
    val columns = if (cameras.size > 4) 3 else 2
    LazyVerticalGrid(
        columns = GridCells.Fixed(columns),
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(cameras, key = { it.saved.deviceId }) { entry ->
            CameraTile(
                name = nameOf(entry),
                online = entry.view.online,
                streaming = entry.view.status?.cameraState == CameraState.STREAMING,
                snapshot = lastSnapshot[entry.saved.deviceId],
                onClick = { onOpenLive(entry) },
            )
        }
    }
}

/** Một ô camera trong lưới. */
@Composable
private fun CameraTile(
    name: String,
    online: Boolean,
    streaming: Boolean,
    snapshot: ByteArray?,
    onClick: () -> Unit,
) {
    val image = remember(snapshot) { snapshot?.let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() } }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(4f / 3f)
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xFF1C1C1C)),
            contentAlignment = Alignment.Center,
        ) {
            if (image != null) {
                Image(
                    bitmap = image,
                    contentDescription = name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Text(
                    stringResource(if (online) R.string.grid_tap_to_view else R.string.offline),
                    color = Color.White.copy(alpha = 0.7f),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(8.dp),
                )
            }
            // Chấm trạng thái góc trên trái.
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(6.dp)
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(if (online) Color(0xFF2E7D32) else Color(0xFFC62828)),
            )
            if (streaming) {
                Text(
                    "●",
                    color = Color(0xFFE53935),
                    modifier = Modifier.align(Alignment.TopEnd).padding(4.dp),
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
    }
}
