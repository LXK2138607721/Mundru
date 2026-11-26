package com.lumina.cleaner.ui.settings

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 系统级半透明微光斜角玻璃卡片容器 (SettingsGlassCard)
 *
 * 材质工艺（100% 瑞士黑白曜石零蓝色秩序）：
 * 1. 微半透明磨砂渐变底面（暗色 78~84% 烟熏曜石、浅色 82~92% 磨砂宣瓷），透出底层中性影棚柔光；
 * 2. 左上柔光穹顶漫反射，赋予大面积卡片柔和立体光学呼吸感；
 * 3. 五段平滑渐隐微亮斜角边框（左上清透微光 -> 侧边半透隐入 -> 右下柔和环境反光），0 突兀硬线、0 逐帧分配。
 */
@Composable
internal fun SettingsGlassCard(
    isDark: Boolean,
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 22.dp,
    content: @Composable ColumnScope.() -> Unit
) {
    val shape = remember(cornerRadius) { RoundedCornerShape(cornerRadius) }
    val rimColors = remember(isDark) {
        if (isDark) {
            listOf(
                Color.White.copy(alpha = 0.34f),
                Color.White.copy(alpha = 0.16f),
                Color.White.copy(alpha = 0.06f),
                Color.White.copy(alpha = 0.11f),
                Color.White.copy(alpha = 0.22f)
            )
        } else {
            listOf(
                Color.White.copy(alpha = 0.98f),
                Color.White.copy(alpha = 0.62f),
                Color.Black.copy(alpha = 0.075f),
                Color.Black.copy(alpha = 0.045f),
                Color.White.copy(alpha = 0.84f)
            )
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .shadow(
                elevation = if (isDark) 0.dp else 6.dp,
                shape = shape,
                clip = false,
                ambientColor = Color.Black.copy(alpha = 0.035f),
                spotColor = Color.Black.copy(alpha = 0.065f)
            )
            .clip(shape)
            .drawWithCache {
                val w = size.width.coerceAtLeast(1f)
                val h = size.height.coerceAtLeast(1f)
                val r = cornerRadius.toPx().coerceAtMost(size.minDimension * 0.5f)
                val corner = CornerRadius(r, r)

                val bodyBrush = Brush.linearGradient(
                    colors = if (isDark) {
                        listOf(Color(0xD224262F), Color(0xC6171920), Color(0xD0121318))
                    } else {
                        listOf(Color(0xEBFFFFFF), Color(0xDCF8F8F7), Color(0xD0F1F1F0))
                    },
                    start = Offset(0f, 0f),
                    end = Offset(w, h)
                )
                val topSheenBrush = Brush.radialGradient(
                    colors = listOf(
                        Color.White.copy(alpha = if (isDark) 0.085f else 0.52f),
                        Color.White.copy(alpha = if (isDark) 0.02f else 0.14f),
                        Color.Transparent
                    ),
                    center = Offset(w * 0.18f, 0f),
                    radius = w * 0.82f
                )
                val rimBrush = Brush.linearGradient(
                    colors = rimColors,
                    start = Offset(0f, 0f),
                    end = Offset(w, h)
                )
                val rimStroke = Stroke(width = 0.95.dp.toPx())

                onDrawWithContent {
                    drawRoundRect(brush = bodyBrush, cornerRadius = corner)
                    drawRoundRect(brush = topSheenBrush, cornerRadius = corner)
                    drawContent()
                    drawRoundRect(
                        brush = rimBrush,
                        cornerRadius = corner,
                        style = rimStroke
                    )
                }
            }
    ) {
        Column(modifier = Modifier.fillMaxWidth(), content = content)
    }
}

/**
 * 统一规格的系统级半透明微光圆角图标底托 (34dp Squircle)
 */
@Composable
internal fun SettingsIconBadge(
    icon: ImageVector,
    tint: Color,
    isDark: Boolean,
    modifier: Modifier = Modifier
) {
    val rimColors = remember(isDark) {
        if (isDark) {
            listOf(
                Color.White.copy(alpha = 0.28f),
                Color.White.copy(alpha = 0.09f),
                Color.White.copy(alpha = 0.16f)
            )
        } else {
            listOf(
                Color.White.copy(alpha = 0.96f),
                Color.Black.copy(alpha = 0.07f),
                Color.White.copy(alpha = 0.72f)
            )
        }
    }

    Box(
        modifier = modifier
            .size(34.dp)
            .drawWithCache {
                val r = 10.dp.toPx()
                val corner = CornerRadius(r, r)
                val bodyBrush = Brush.linearGradient(
                    colors = if (isDark) {
                        listOf(tint.copy(alpha = 0.20f), tint.copy(alpha = 0.09f))
                    } else {
                        listOf(Color.White.copy(alpha = 0.82f), tint.copy(alpha = 0.08f))
                    },
                    start = Offset.Zero,
                    end = Offset(size.width, size.height)
                )
                val rimBrush = Brush.linearGradient(
                    colors = rimColors,
                    start = Offset.Zero,
                    end = Offset(size.width, size.height)
                )
                val rimStroke = Stroke(width = 0.75.dp.toPx())
                onDrawWithContent {
                    drawRoundRect(brush = bodyBrush, cornerRadius = corner)
                    drawContent()
                    drawRoundRect(
                        brush = rimBrush,
                        cornerRadius = corner,
                        style = rimStroke
                    )
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(18.dp)
        )
    }
}

/**
 * 系统级半透明微光槽道 + 悬浮玻璃水银滑块分段控件 (LiquidSlidingSegmentedControl)
 */
@Composable
internal fun <T> LiquidSlidingSegmentedControl(
    items: List<T>,
    selectedItem: T,
    isDark: Boolean,
    onSelect: (T) -> Unit,
    labelProvider: (T) -> String,
    iconProvider: ((T) -> ImageVector)? = null,
    modifier: Modifier = Modifier
) {
    if (items.isEmpty()) return
    val density = LocalDensity.current
    val selectedIndex = items.indexOf(selectedItem).coerceAtLeast(0)
    var innerWidthPx by remember { mutableIntStateOf(0) }

    val pillShape = remember { RoundedCornerShape(11.dp) }
    val pillRimColors = remember(isDark) {
        if (isDark) {
            listOf(
                Color.White.copy(alpha = 0.40f),
                Color.White.copy(alpha = 0.16f),
                Color.White.copy(alpha = 0.07f),
                Color.White.copy(alpha = 0.14f),
                Color.White.copy(alpha = 0.26f)
            )
        } else {
            listOf(
                Color.White.copy(alpha = 1.0f),
                Color.White.copy(alpha = 0.65f),
                Color.Black.copy(alpha = 0.07f),
                Color.White.copy(alpha = 0.48f),
                Color.White.copy(alpha = 0.88f)
            )
        }
    }

    val indicatorProgress by animateFloatAsState(
        targetValue = selectedIndex.toFloat(),
        animationSpec = spring(dampingRatio = 0.74f, stiffness = 460f),
        label = "segmented_indicator_progress"
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(42.dp)
            .drawWithCache {
                val r = 14.dp.toPx()
                val corner = CornerRadius(r, r)
                val trackBrush = Brush.verticalGradient(
                    colors = if (isDark) {
                        listOf(Color(0xC20A0B0F), Color(0xB814161D))
                    } else {
                        listOf(Color(0xBCE6E6EA), Color(0xB4F0F0F3))
                    }
                )
                val trackRim = Brush.verticalGradient(
                    colors = if (isDark) {
                        listOf(Color.Black.copy(alpha = 0.34f), Color.White.copy(alpha = 0.13f))
                    } else {
                        listOf(Color.Black.copy(alpha = 0.085f), Color.White.copy(alpha = 0.88f))
                    }
                )
                val stroke = Stroke(width = 0.75.dp.toPx())
                onDrawWithContent {
                    drawRoundRect(brush = trackBrush, cornerRadius = corner)
                    drawContent()
                    drawRoundRect(brush = trackRim, cornerRadius = corner, style = stroke)
                }
            }
            .padding(3.dp)
            .onSizeChanged { innerWidthPx = it.width }
    ) {
        val segmentWidthPx = if (innerWidthPx > 0) innerWidthPx.toFloat() / items.size else 0f
        val segmentWidthDp = with(density) { segmentWidthPx.toDp() }

        if (segmentWidthPx > 1f) {
            Box(
                modifier = Modifier
                    .width(segmentWidthDp)
                    .fillMaxHeight()
                    .graphicsLayer {
                        translationX = segmentWidthPx * indicatorProgress
                    }
                    .shadow(
                        elevation = if (isDark) 3.dp else 4.dp,
                        shape = pillShape,
                        clip = false,
                        ambientColor = Color.Black.copy(alpha = if (isDark) 0.42f else 0.08f),
                        spotColor = Color.Black.copy(alpha = if (isDark) 0.48f else 0.12f)
                    )
                    .drawWithCache {
                        val w = size.width.coerceAtLeast(1f)
                        val h = size.height.coerceAtLeast(1f)
                        val r = 11.dp.toPx()
                        val corner = CornerRadius(r, r)
                        val pillBody = Brush.linearGradient(
                            colors = if (isDark) {
                                listOf(Color(0xEC353844), Color(0xE8242731))
                            } else {
                                listOf(Color(0xFAFFFFFF), Color(0xF0F4F4F6))
                            },
                            start = Offset.Zero,
                            end = Offset(w, h)
                        )
                        val pillSheen = Brush.radialGradient(
                            colors = listOf(
                                Color.White.copy(alpha = if (isDark) 0.12f else 0.62f),
                                Color.Transparent
                            ),
                            center = Offset(w * 0.28f, 0f),
                            radius = w * 0.78f
                        )
                        val rimBrush = Brush.linearGradient(
                            colors = pillRimColors,
                            start = Offset.Zero,
                            end = Offset(w, h)
                        )
                        val stroke = Stroke(width = 0.85.dp.toPx())
                        onDrawWithContent {
                            drawRoundRect(brush = pillBody, cornerRadius = corner)
                            drawRoundRect(brush = pillSheen, cornerRadius = corner)
                            drawContent()
                            drawRoundRect(brush = rimBrush, cornerRadius = corner, style = stroke)
                        }
                    }
            )
        }

        Row(
            modifier = Modifier.fillMaxSize(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            items.forEach { item ->
                val isSelected = item == selectedItem
                val textColor by animateColorAsState(
                    targetValue = if (isSelected) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    animationSpec = tween(160),
                    label = "segmented_text_color"
                )
                val iconTint by animateColorAsState(
                    targetValue = if (isSelected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    animationSpec = tween(160),
                    label = "segmented_icon_tint"
                )

                Row(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) { onSelect(item) },
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (iconProvider != null) {
                        Icon(
                            imageVector = iconProvider(item),
                            contentDescription = null,
                            tint = iconTint,
                            modifier = Modifier.size(15.dp)
                        )
                        Spacer(modifier = Modifier.width(5.dp))
                    }
                    Text(
                        text = labelProvider(item),
                        fontSize = 12.sp,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                        color = textColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

/**
 * 系统级渐变微光胶囊开关 (LiquidToggleSwitch)
 */
@Composable
internal fun LiquidToggleSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    isDark: Boolean,
    modifier: Modifier = Modifier
) {
    val trackTop by animateColorAsState(
        targetValue = when {
            checked && isDark -> Color(0xFFF6F6F8)
            checked -> Color(0xFF23252C)
            isDark -> Color(0xC622252F)
            else -> Color(0xCCD5D6DC)
        },
        animationSpec = tween(180),
        label = "liquid_switch_track_top"
    )
    val trackBottom by animateColorAsState(
        targetValue = when {
            checked && isDark -> Color(0xFFE2E2E6)
            checked -> Color(0xFF121318)
            isDark -> Color(0xCE2F3340)
            else -> Color(0xDCE5E6EB)
        },
        animationSpec = tween(180),
        label = "liquid_switch_track_bottom"
    )
    val thumbTop by animateColorAsState(
        targetValue = when {
            checked && isDark -> Color(0xFF24262E)
            else -> Color(0xFFFFFFFF)
        },
        animationSpec = tween(180),
        label = "liquid_switch_thumb_top"
    )
    val thumbBottom by animateColorAsState(
        targetValue = when {
            checked && isDark -> Color(0xFF101116)
            else -> Color(0xFFEEEEF1)
        },
        animationSpec = tween(180),
        label = "liquid_switch_thumb_bottom"
    )
    val thumbProgress by animateFloatAsState(
        targetValue = if (checked) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.64f, stiffness = 520f),
        label = "liquid_switch_thumb"
    )

    Box(
        modifier = modifier
            .size(width = 48.dp, height = 28.dp)
            .clip(CircleShape)
            .drawWithCache {
                val r = size.height * 0.5f
                val bgBrush = Brush.linearGradient(
                    colors = listOf(trackTop, trackBottom),
                    start = Offset.Zero,
                    end = Offset(size.width, size.height)
                )
                val rimBrush = Brush.linearGradient(
                    colors = if (isDark) {
                        listOf(Color.White.copy(alpha = 0.30f), Color.White.copy(alpha = 0.08f), Color.White.copy(alpha = 0.20f))
                    } else {
                        listOf(Color.White.copy(alpha = 0.85f), Color.Black.copy(alpha = 0.10f), Color.White.copy(alpha = 0.72f))
                    },
                    start = Offset.Zero,
                    end = Offset(size.width, size.height)
                )
                val stroke = Stroke(width = 0.8.dp.toPx())
                onDrawWithContent {
                    drawRect(brush = bgBrush)
                    drawContent()
                    drawRoundRect(brush = rimBrush, cornerRadius = CornerRadius(r, r), style = stroke)
                }
            }
            .toggleable(
                value = checked,
                role = Role.Switch,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onValueChange = onCheckedChange
            )
            .padding(3.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        Box(
            modifier = Modifier
                .graphicsLayer { translationX = thumbProgress * 20.dp.toPx() }
                .size(22.dp)
                .shadow(
                    elevation = 3.dp,
                    shape = CircleShape,
                    clip = false,
                    ambientColor = Color.Black.copy(alpha = 0.16f),
                    spotColor = Color.Black.copy(alpha = 0.24f)
                )
                .clip(CircleShape)
                .drawWithCache {
                    val r = size.minDimension * 0.5f
                    val fillBrush = Brush.linearGradient(
                        colors = listOf(thumbTop, thumbBottom),
                        start = Offset.Zero,
                        end = Offset(size.width, size.height)
                    )
                    val rimBrush = Brush.linearGradient(
                        colors = listOf(
                            Color.White.copy(alpha = if (checked && isDark) 0.28f else 0.95f),
                            Color.Transparent
                        ),
                        start = Offset.Zero,
                        end = Offset(size.width, size.height)
                    )
                    val stroke = Stroke(width = 0.7.dp.toPx())
                    onDrawWithContent {
                        drawRect(brush = fillBrush)
                        drawRoundRect(brush = rimBrush, cornerRadius = CornerRadius(r, r), style = stroke)
                    }
                }
        )
    }
}

/**
 * 系统级渐变微光玻璃交互按钮（用于设置底部浮层、回收站浮岛与确认浮层的主/次/危险操作键）
 */
@Composable
internal fun SettingsGlassButton(
    text: String,
    isPrimary: Boolean,
    isDark: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isDanger: Boolean = false,
    leadingIcon: ImageVector? = null
) {
    val shape = remember { RoundedCornerShape(16.dp) }
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.96f else 1f,
        animationSpec = spring(dampingRatio = 0.56f, stiffness = 560f),
        label = "settings_btn_scale"
    )
    val contentColor = when {
        isDanger -> Color.White
        isPrimary -> MaterialTheme.colorScheme.onPrimary
        else -> MaterialTheme.colorScheme.onSurface
    }

    Box(
        modifier = modifier
            .height(46.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(shape)
            .drawWithCache {
                val w = size.width.coerceAtLeast(1f)
                val h = size.height.coerceAtLeast(1f)
                val r = 16.dp.toPx()
                val bgBrush = when {
                    isDanger -> Brush.linearGradient(
                        colors = listOf(Color(0xFFF25548), Color(0xFFD63228)),
                        start = Offset.Zero,
                        end = Offset(w, h)
                    )
                    isPrimary && isDark -> Brush.linearGradient(
                        colors = listOf(Color(0xFFF8F8FA), Color(0xFFE4E4E8)),
                        start = Offset.Zero,
                        end = Offset(w, h)
                    )
                    isPrimary -> Brush.linearGradient(
                        colors = listOf(Color(0xFF23252C), Color(0xFF111216)),
                        start = Offset.Zero,
                        end = Offset(w, h)
                    )
                    isDark -> Brush.linearGradient(
                        colors = listOf(Color(0xD82C2F3A), Color(0xCE1E2028)),
                        start = Offset.Zero,
                        end = Offset(w, h)
                    )
                    else -> Brush.linearGradient(
                        colors = listOf(Color(0xF0FFFFFF), Color(0xE0ECECEF)),
                        start = Offset.Zero,
                        end = Offset(w, h)
                    )
                }
                val rimBrush = Brush.linearGradient(
                    colors = when {
                        isDanger -> listOf(
                            Color.White.copy(alpha = 0.36f),
                            Color.White.copy(alpha = 0.08f),
                            Color.White.copy(alpha = 0.18f)
                        )
                        isPrimary && !isDark -> listOf(
                            Color.White.copy(alpha = 0.28f),
                            Color.White.copy(alpha = 0.06f),
                            Color.White.copy(alpha = 0.16f)
                        )
                        isDark -> listOf(
                            Color.White.copy(alpha = 0.36f),
                            Color.White.copy(alpha = 0.10f),
                            Color.White.copy(alpha = 0.22f)
                        )
                        else -> listOf(
                            Color.White.copy(alpha = 0.96f),
                            Color.Black.copy(alpha = 0.08f),
                            Color.White.copy(alpha = 0.80f)
                        )
                    },
                    start = Offset.Zero,
                    end = Offset(w, h)
                )
                val stroke = Stroke(width = 0.85.dp.toPx())
                onDrawWithContent {
                    drawRect(brush = bgBrush)
                    drawContent()
                    drawRoundRect(brush = rimBrush, cornerRadius = CornerRadius(r, r), style = stroke)
                }
            }
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier.padding(horizontal = 12.dp)
        ) {
            if (leadingIcon != null) {
                Icon(
                    imageVector = leadingIcon,
                    contentDescription = null,
                    tint = contentColor,
                    modifier = Modifier.size(17.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
            }
            Text(
                text = text,
                fontSize = 13.5.sp,
                fontWeight = FontWeight.SemiBold,
                color = contentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * 标准系统级导航列表行（统一图标底托 + 右侧精致引导箭头）
 */
@Composable
internal fun SettingsActionRow(
    icon: ImageVector,
    iconTint: Color,
    title: String,
    isDark: Boolean,
    onClick: () -> Unit,
    subtitle: String? = null,
    trailingText: String? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SettingsIconBadge(icon = icon, tint = iconTint, isDark = isDark)
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (subtitle != null) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        if (trailingText != null) {
            Text(
                text = trailingText,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = 4.dp)
            )
        }
        Icon(
            imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
            modifier = Modifier.size(19.dp)
        )
    }
}

/** 与文本左边界严格对齐的精致系统级半透细分隔线 */
@Composable
internal fun SettingsInsetDivider(isDark: Boolean) {
    HorizontalDivider(
        color = if (isDark) Color.White.copy(alpha = 0.07f) else Color.Black.copy(alpha = 0.055f),
        thickness = 0.6.dp,
        modifier = Modifier.padding(start = 62.dp, end = 16.dp)
    )
}

/** 精致微半透明微光胶囊标签（用于 Hero 统计卡右上角「纯离线」与「关于」版本徽章） */
@Composable
internal fun SettingsCrystalBadge(
    text: String,
    accent: Color,
    isDark: Boolean
) {
    Box(
        modifier = Modifier
            .clip(CircleShape)
            .drawWithCache {
                val r = size.height * 0.5f
                val bgBrush = Brush.linearGradient(
                    colors = if (isDark) {
                        listOf(accent.copy(alpha = 0.20f), accent.copy(alpha = 0.08f))
                    } else {
                        listOf(Color.White.copy(alpha = 0.86f), accent.copy(alpha = 0.08f))
                    },
                    start = Offset.Zero,
                    end = Offset(size.width, size.height)
                )
                val rimBrush = Brush.linearGradient(
                    colors = if (isDark) {
                        listOf(Color.White.copy(alpha = 0.34f), Color.White.copy(alpha = 0.10f), Color.White.copy(alpha = 0.22f))
                    } else {
                        listOf(Color.White.copy(alpha = 0.96f), Color.Black.copy(alpha = 0.09f), Color.White.copy(alpha = 0.78f))
                    },
                    start = Offset.Zero,
                    end = Offset(size.width, size.height)
                )
                val stroke = Stroke(width = 0.75.dp.toPx())
                onDrawWithContent {
                    drawRect(brush = bgBrush)
                    drawContent()
                    drawRoundRect(brush = rimBrush, cornerRadius = CornerRadius(r, r), style = stroke)
                }
            }
            .padding(horizontal = 10.dp, vertical = 3.5.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = accent
        )
    }
}
