package com.lumina.cleaner.ui.trash

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.BrokenImage
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.SubcomposeAsyncImage
import com.lumina.cleaner.core.database.LocalTrashEntity
import com.lumina.cleaner.core.model.PhotoItem
import com.lumina.cleaner.core.util.HapticFeedback
import com.lumina.cleaner.ui.viewer.PhotoViewerOverlay
import kotlinx.coroutines.launch

/**
 * Mundru 真实回收站界面 (RecycleBinScreen)
 * 品牌释义：Mundru 源自格陵兰语（Greenlandic），意为「擦净 / 擦除」。
 *
 * 核心功能：
 * 1. 查看所有应用内暂存删除的照片，支持原图大图沉浸式预览与详情查看；
 * 2. 勾选与大图浏览完全解耦：轻触卡片查看大图，长按或点击右上角 40dp 专属热区进行多选；
 * 3. 稳健防黑屏与死图防护：失效 Uri 自动呈现深灰占位与断裂图符；
 * 4. 彻底删除：单次调用 MediaStore.createDeleteRequest(uris) 唤起系统确认框，真实抹掉文件；
 * 5. 严格通过 PhotoId + URI 联合主键销毁，彻底根除幽灵残留条目。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun RecycleBinScreen(
    trashItems: List<LocalTrashEntity>,
    onRestore: (List<Long>) -> Unit,
    onPermanentDelete: (List<Long>, List<Uri>) -> Unit,
    onBack: () -> Unit,
    onResolvePhotoItem: (suspend (Long) -> PhotoItem?)? = null
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val selectedIds = remember { mutableStateListOf<Long>() }
    var previewPhoto by remember { mutableStateOf<PhotoItem?>(null) }

    // 拦截系统返回键/手势，平滑返回上一级主界面
    BackHandler(onBack = onBack)

    val totalBytes = trashItems.sumOf { it.size }
    val formattedSize = formatSize(totalBytes)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // 顶部导航栏
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .clickable {
                            HapticFeedback.click(context)
                            onBack()
                        }
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = "返回",
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                Column(modifier = Modifier.weight(1f).padding(start = 8.dp)) {
                    Text(
                        text = "相册回收站",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Text(
                        text = "${trashItems.size} 张照片 · 共 $formattedSize",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // 全选 / 取消全选
                Text(
                    text = if (selectedIds.size == trashItems.size && trashItems.isNotEmpty()) "取消全选" else "全选",
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .clickable {
                            HapticFeedback.click(context)
                            if (selectedIds.size == trashItems.size) {
                                selectedIds.clear()
                            } else {
                                selectedIds.clear()
                                selectedIds.addAll(trashItems.map { it.photoId })
                            }
                        }
                        .padding(8.dp)
                )
            }

            // 空回收站提示
            if (trashItems.isEmpty()) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Rounded.DeleteOutline,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                            modifier = Modifier.size(52.dp)
                        )
                        Spacer(modifier = Modifier.height(14.dp))
                        Text(
                            text = "回收站是空的",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "整理时上滑删除的照片会暂存在这里",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                // 照片网格
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(trashItems) { item ->
                        val isSelected = item.photoId in selectedIds
                        val uri = Uri.parse(item.uriString)

                        Box(
                            modifier = Modifier
                                .aspectRatio(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .combinedClickable(
                                    onClick = {
                                        HapticFeedback.click(context)
                                        coroutineScope.launch {
                                            val resolved = onResolvePhotoItem?.invoke(item.photoId)
                                            previewPhoto = resolved ?: item.toPhotoItem()
                                        }
                                    },
                                    onLongClick = {
                                        HapticFeedback.click(context)
                                        if (isSelected) selectedIds.remove(item.photoId)
                                        else selectedIds.add(item.photoId)
                                    }
                                )
                        ) {
                            SubcomposeAsyncImage(
                                model = uri,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize(),
                                error = {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .background(MaterialTheme.colorScheme.surfaceVariant),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Column(
                                            horizontalAlignment = Alignment.CenterHorizontally,
                                            verticalArrangement = Arrangement.Center,
                                            modifier = Modifier.padding(4.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Rounded.BrokenImage,
                                                contentDescription = "已失效",
                                                tint = MaterialTheme.colorScheme.error.copy(alpha = 0.7f),
                                                modifier = Modifier.size(24.dp)
                                            )
                                            Spacer(modifier = Modifier.height(2.dp))
                                            Text(
                                                text = "原图已失效",
                                                fontSize = 9.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }
                            )

                            // 右上角独立 40dp 勾选触控热区
                            Box(
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .size(40.dp)
                                    .clickable {
                                        HapticFeedback.tick(context)
                                        if (isSelected) selectedIds.remove(item.photoId)
                                        else selectedIds.add(item.photoId)
                                    },
                                contentAlignment = Alignment.TopEnd
                            ) {
                                Box(modifier = Modifier.padding(6.dp)) {
                                    if (isSelected) {
                                        Icon(
                                            Icons.Rounded.CheckCircle,
                                            contentDescription = "Selected",
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier
                                                .size(22.dp)
                                                .background(Color.White, CircleShape)
                                        )
                                    } else {
                                        Surface(
                                            shape = CircleShape,
                                            color = Color.Black.copy(alpha = 0.35f),
                                            modifier = Modifier.size(20.dp)
                                        ) { }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // 底部操作栏
            if (trashItems.isNotEmpty()) {
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    shadowElevation = 8.dp,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // 恢复按钮
                        OutlinedButton(
                            onClick = {
                                HapticFeedback.click(context)
                                val toRestore = if (selectedIds.isEmpty()) trashItems.map { it.photoId } else selectedIds.toList()
                                onRestore(toRestore)
                                selectedIds.clear()
                            },
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Rounded.Restore, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.size(6.dp))
                            Text(if (selectedIds.isEmpty()) "恢复全部" else "恢复(${selectedIds.size})")
                        }

                        // 彻底删除按钮
                        Button(
                            onClick = {
                                HapticFeedback.heavy(context)
                                val targets = if (selectedIds.isEmpty()) {
                                    trashItems
                                } else {
                                    trashItems.filter { it.photoId in selectedIds }
                                }
                                val toDeleteIds = targets.map { it.photoId }
                                val toDeleteUris = targets.map { Uri.parse(it.uriString) }
                                onPermanentDelete(toDeleteIds, toDeleteUris)
                                selectedIds.clear()
                            },
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                        ) {
                            Icon(Icons.Rounded.DeleteForever, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onError)
                            Spacer(modifier = Modifier.size(6.dp))
                            Text(if (selectedIds.isEmpty()) "彻底删除全部" else "彻底删除(${selectedIds.size})")
                        }
                    }
                }
            }
        }

        // 大图沉浸预览查看器
        if (previewPhoto != null) {
            PhotoViewerOverlay(
                photo = previewPhoto!!,
                onDismiss = { previewPhoto = null }
            )
        }
    }
}

private fun LocalTrashEntity.toPhotoItem(): PhotoItem = PhotoItem(
    id = photoId,
    uri = Uri.parse(uriString),
    displayName = "已删除照片 #$photoId",
    dateModified = deletedAt,
    dateTaken = deletedAt,
    size = size,
    width = 0,
    height = 0,
    bucketName = "相册回收站"
)

private fun formatSize(bytes: Long): String {
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> String.format("%.1f KB", bytes / 1024.0)
        bytes < 1024 * 1024 * 1024 -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
        else -> String.format("%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
    }
}
