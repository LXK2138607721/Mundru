package com.lumina.cleaner.ui.trash

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.lumina.cleaner.core.database.LocalTrashEntity
import com.lumina.cleaner.core.util.HapticFeedback

/**
 * Mundru 真实回收站界面 (RecycleBinScreen)
 * 品牌释义：Mundru 源自格陵兰语（Greenlandic），意为「擦掉 / 擦除」。
 * 核心功能：回收站暂存查看、批量恢复与系统级 Scoped Storage 物理彻底销毁。
 *
 * 核心功能：
 * 1. 查看所有应用内暂存删除的照片；
 * 2. 多选 / 全选 / 恢复照片回相册；
 * 3. 彻底删除：单次调用 MediaStore.createDeleteRequest(uris) 唤起系统确认框，真实抹掉文件；
 * 4. 严格通过 PhotoId + URI 联合主键销毁，彻底根除幽灵残留条目 (Fix Defect A)。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecycleBinScreen(
    trashItems: List<LocalTrashEntity>,
    onRestore: (List<Long>) -> Unit,
    onPermanentDelete: (List<Long>, List<Uri>) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val selectedIds = remember { mutableStateListOf<Long>() }

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
                                .clickable {
                                    HapticFeedback.tick(context)
                                    if (isSelected) selectedIds.remove(item.photoId)
                                    else selectedIds.add(item.photoId)
                                }
                        ) {
                            AsyncImage(
                                model = uri,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )

                            // 勾选标识
                            Box(
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(6.dp)
                            ) {
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
    }
}

private fun formatSize(bytes: Long): String {
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> String.format("%.1f KB", bytes / 1024.0)
        bytes < 1024 * 1024 * 1024 -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
        else -> String.format("%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
    }
}
