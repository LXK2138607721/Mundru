package com.lumina.cleaner.ui.album

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lumina.cleaner.core.model.MonthBucket
import com.lumina.cleaner.core.util.HapticFeedback
import com.lumina.cleaner.ui.glass.MundruBottomSheet

/**
 * Mundru 月份专清选择抽屉 (MonthBottomSheet)
 * - 基于零窗口 IPC 的 120fps 页内 MundruBottomSheet；
 * - 选中月份先平滑收拢抽屉，再无缝衔接主舞台漩涡换批。
 */
@Composable
fun MonthBottomSheet(
    months: List<MonthBucket>,
    selectedMonth: MonthBucket? = null,
    onSelectMonth: (MonthBucket) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f

    val rowBaseBg = if (isDark) Color.White.copy(alpha = 0.055f) else Color.Black.copy(alpha = 0.032f)
    val rowSelectedBg = if (isDark) Color.White.copy(alpha = 0.12f) else Color.Black.copy(alpha = 0.075f)
    val rowBorderBrush = remember(isDark) {
        Brush.linearGradient(
            colors = if (isDark) {
                listOf(Color.White.copy(alpha = 0.14f), Color.White.copy(alpha = 0.04f))
            } else {
                listOf(Color.White.copy(alpha = 0.90f), Color.Black.copy(alpha = 0.06f))
            }
        )
    }

    MundruBottomSheet(onDismiss = onDismiss) { dismissSmoothly ->
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 18.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "选择月份",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "${months.size} 个月份",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            if (months.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(160.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "暂无可选月份",
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 400.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(bottom = 4.dp)
                ) {
                    items(months, key = { it.year * 100 + it.month }) { monthBucket ->
                        val isSelected = selectedMonth != null &&
                            selectedMonth.year == monthBucket.year &&
                            selectedMonth.month == monthBucket.month
                        val rowShape = RoundedCornerShape(16.dp)

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(rowShape)
                                .background(if (isSelected) rowSelectedBg else rowBaseBg)
                                .border(
                                    width = if (isSelected) 0.85.dp else 0.6.dp,
                                    brush = if (isSelected) {
                                        Brush.linearGradient(
                                            listOf(
                                                MaterialTheme.colorScheme.onSurface.copy(alpha = if (isDark) 0.34f else 0.22f),
                                                MaterialTheme.colorScheme.onSurface.copy(alpha = if (isDark) 0.14f else 0.10f)
                                            )
                                        )
                                    } else rowBorderBrush,
                                    shape = rowShape
                                )
                                .clickable {
                                    HapticFeedback.click(context)
                                    dismissSmoothly { onSelectMonth(monthBucket) }
                                }
                                .padding(horizontal = 13.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Surface(
                                shape = RoundedCornerShape(11.dp),
                                color = if (isDark) Color.White.copy(alpha = 0.08f) else Color.Black.copy(alpha = 0.05f),
                                modifier = Modifier.size(40.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        Icons.Rounded.CalendarMonth,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.width(13.dp))

                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = monthBucket.displayName,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Spacer(modifier = Modifier.height(1.dp))
                                Text(
                                    text = "${monthBucket.count} 张照片",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            if (isSelected) {
                                Spacer(modifier = Modifier.width(8.dp))
                                Icon(
                                    imageVector = Icons.Rounded.Check,
                                    contentDescription = "已选择",
                                    tint = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
