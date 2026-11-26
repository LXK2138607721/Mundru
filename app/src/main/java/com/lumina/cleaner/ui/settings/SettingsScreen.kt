package com.lumina.cleaner.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Policy
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material.icons.rounded.Vibration
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.lumina.cleaner.R
import com.lumina.cleaner.core.util.HapticFeedback
import com.lumina.cleaner.core.util.HapticIntensity
import com.lumina.cleaner.core.util.formatFileSize
import com.lumina.cleaner.ui.glass.LiquidGlassSurface
import com.lumina.cleaner.ui.glass.MundruBottomSheet
import com.lumina.cleaner.ui.glass.liquidBackdropSource
import com.lumina.cleaner.ui.glass.rememberLiquidBackdropState
import com.lumina.cleaner.ui.theme.AppThemeMode
import java.text.NumberFormat
import java.util.Locale
import kotlinx.coroutines.delay

private enum class SettingsSheet {
    PRIVACY,
    SPONSOR,
    ABOUT
}

/**
 * Mundru 旗舰级偏好设置与数据统计中心 (SettingsScreen)
 *
 * 品牌释义：Mundru 源自格陵兰语（Greenlandic），意为「擦掉 / 擦除」。
 * 设计语言：90% 沉静中性玻璃/纸质层级 + 10% 克制主题点缀，与主舞台真·液态玻璃浑然一体。
 * 1. 精简顶栏：仅保留左上角液态玻璃返回圆钮与居中液态玻璃标题胶囊，去除冗余重复关闭键；
 * 2. Hero 仪表主卡：等宽数字 (tnum) 统计 + 相册探索进度微光条 + 100% 纯离线徽章；
 * 3. Q 弹水银滑动分段控件：外观主题、批次大小、触觉反馈全部改用连续凹槽 + 弹性滑动指示胶囊；
 * 4. 统一样式图标底托、右对齐引导箭头、齐字缩进细分隔线与自绘精巧胶囊开关；
 * 5. 二级弹窗全部升级为现代圆角 ModalBottomSheet。
 */
@Composable
fun SettingsScreen(
    currentBatchSize: Int,
    onBatchSizeChange: (Int) -> Unit,
    hapticIntensityLevel: Int,
    onHapticIntensityChange: (Int) -> Unit,
    swipeHapticsEnabled: Boolean,
    onSwipeHapticsEnabledChange: (Boolean) -> Unit,
    backgroundBlurEnabled: Boolean = true,
    onBackgroundBlurEnabledChange: (Boolean) -> Unit = {},
    showManageMediaEntry: Boolean = false,
    canManageMedia: Boolean = false,
    onOpenManageMediaSettings: () -> Unit = {},
    currentThemeMode: AppThemeMode = AppThemeMode.FOLLOW_SYSTEM,
    onThemeModeChange: (AppThemeMode) -> Unit = {},
    totalReviewed: Long,
    trashBytes: Long,
    totalPhotos: Int,
    onShowTutorial: () -> Unit,
    onBack: () -> Unit,
    isActive: Boolean = true
) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val settingsBackdrop = rememberLiquidBackdropState()
    var activeSheet by remember { mutableStateOf<SettingsSheet?>(null) }

    LaunchedEffect(isActive) {
        if (!isActive) {
            activeSheet = null
            delay(310L)
            if (scrollState.value > 0) {
                scrollState.scrollTo(0)
            }
        }
    }

    val versionLabel = remember(context) {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull().takeUnless { it.isNullOrBlank() }?.let { "v$it" } ?: "v1.6"
    }

    DisposableEffect(settingsBackdrop, scrollState) {
        settingsBackdrop.bindMotionSignal { scrollState.value.toFloat() }
        onDispose { settingsBackdrop.bindMotionSignal(null) }
    }

    BackHandler(enabled = isActive, onBack = onBack)

    val bgColor = MaterialTheme.colorScheme.background
    val primaryColor = MaterialTheme.colorScheme.primary
    // 全站 100% 去蓝：统一瑞士黑白曜石单色秩序（仅赞赏爱心保留极克制暖红）
    val accentBlue = primaryColor
    val accentTeal = primaryColor
    val accentCoral = MaterialTheme.colorScheme.error
    val accentAmber = MaterialTheme.colorScheme.onSurface
    val accentNeutral = MaterialTheme.colorScheme.onSurfaceVariant

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(bgColor)
    ) {
        // 第 1 层：全屏滚动内容（始终启用实时玻璃背景采样，确保顶栏按钮从滑入首帧起即呈现真实液态玻璃色泽，零先暗后跳色）
        Box(
            modifier = Modifier
                .fillMaxSize()
                .liquidBackdropSource(settingsBackdrop)
                .background(bgColor)
        ) {
            // 中性黑白影棚柔光环境底幕（严格裁剪在页面边界内，杜绝柔光圆在水平推拉转场时越界溢涂到主界面顶栏造成泛白变色）
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clipToBounds()
                    .drawWithCache {
                        val w = size.width.coerceAtLeast(1f)
                        val h = size.height.coerceAtLeast(1f)
                        val topCenter = Offset(w * 0.82f, h * 0.12f)
                        val topRadius = w * 0.85f
                        val topBrush = Brush.radialGradient(
                            colors = listOf(
                                if (isDark) Color.White.copy(alpha = 0.085f) else Color.White.copy(alpha = 0.92f),
                                Color.Transparent
                            ),
                            center = topCenter,
                            radius = topRadius
                        )
                        val midCenter = Offset(w * 0.14f, h * 0.56f)
                        val midRadius = w * 0.78f
                        val midBrush = Brush.radialGradient(
                            colors = listOf(
                                if (isDark) Color.White.copy(alpha = 0.055f) else Color.Black.copy(alpha = 0.045f),
                                Color.Transparent
                            ),
                            center = midCenter,
                            radius = midRadius
                        )
                        onDrawBehind {
                            drawCircle(brush = topBrush, center = topCenter, radius = topRadius)
                            drawCircle(brush = midBrush, center = midCenter, radius = midRadius)
                        }
                    }
            )

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .verticalScroll(scrollState)
                    .padding(horizontal = 20.dp)
            ) {
                Spacer(modifier = Modifier.height(74.dp))

                // 1. 回忆整理成果 · Hero 玻璃仪表主卡
                HeroStatsBentoCard(
                    totalReviewed = totalReviewed,
                    trashBytes = trashBytes,
                    totalPhotos = totalPhotos,
                    isDark = isDark,
                    accentBlue = accentBlue,
                    accentTeal = accentTeal
                )

                Spacer(modifier = Modifier.height(26.dp))

                // 2. 外观与整理偏好
                SectionHeader("外观与整理")

                SettingsGlassCard(isDark = isDark) {
                    // 外观模式（选项直白，省去冗余说明小字）
                    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 15.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            SettingsIconBadge(
                                icon = Icons.Rounded.Palette,
                                tint = accentBlue,
                                isDark = isDark
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = "外观",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        LiquidSlidingSegmentedControl(
                            items = listOf(
                                AppThemeMode.FOLLOW_SYSTEM,
                                AppThemeMode.LIGHT,
                                AppThemeMode.DARK
                            ),
                            selectedItem = currentThemeMode,
                            isDark = isDark,
                            onSelect = { mode ->
                                if (mode != currentThemeMode) {
                                    HapticFeedback.click(context)
                                    onThemeModeChange(mode)
                                }
                            },
                            labelProvider = { mode ->
                                when (mode) {
                                    AppThemeMode.FOLLOW_SYSTEM -> "跟随系统"
                                    AppThemeMode.LIGHT -> "浅色"
                                    AppThemeMode.DARK -> "暗色"
                                }
                            },
                            iconProvider = { it.icon }
                        )
                    }

                    SettingsInsetDivider(isDark = isDark)

                    // 每批照片数（选项直白，省去冗余说明小字）
                    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 15.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            SettingsIconBadge(
                                icon = Icons.Rounded.Speed,
                                tint = accentTeal,
                                isDark = isDark
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = "每批照片数",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        LiquidSlidingSegmentedControl(
                            items = listOf(5, 10, 15, 20, 30),
                            selectedItem = currentBatchSize,
                            isDark = isDark,
                            onSelect = { size ->
                                if (size != currentBatchSize) {
                                    HapticFeedback.click(context)
                                    onBatchSizeChange(size)
                                }
                            },
                            labelProvider = { "$it 张" }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // 3. 震动反馈
                SectionHeader("震动反馈")

                SettingsGlassCard(isDark = isDark) {
                    val selectedIntensity = HapticIntensity.entries.firstOrNull { it.level == hapticIntensityLevel }
                        ?: HapticIntensity.MEDIUM

                    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 15.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            SettingsIconBadge(
                                icon = Icons.Rounded.Vibration,
                                tint = if (selectedIntensity != HapticIntensity.OFF) accentAmber else accentNeutral,
                                isDark = isDark
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = "触感强度",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        LiquidSlidingSegmentedControl(
                            items = listOf(
                                HapticIntensity.OFF,
                                HapticIntensity.LIGHT,
                                HapticIntensity.MEDIUM,
                                HapticIntensity.STRONG
                            ),
                            selectedItem = selectedIntensity,
                            isDark = isDark,
                            onSelect = { intensity ->
                                onHapticIntensityChange(intensity.level)
                                if (intensity != HapticIntensity.OFF) {
                                    HapticFeedback.preview(context, intensity)
                                }
                            },
                            labelProvider = { it.title }
                        )
                    }

                    AnimatedVisibility(
                        visible = hapticIntensityLevel != HapticIntensity.OFF.level,
                        enter = expandVertically() + fadeIn(),
                        exit = shrinkVertically() + fadeOut()
                    ) {
                        Column {
                            SettingsInsetDivider(isDark = isDark)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    SettingsIconBadge(
                                        icon = Icons.Rounded.TouchApp,
                                        tint = if (swipeHapticsEnabled) accentBlue else accentNeutral,
                                        isDark = isDark
                                    )
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column(modifier = Modifier.padding(end = 12.dp)) {
                                        Text(
                                            text = "滑卡触发提示",
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.Medium,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(
                                            text = "滑动达到切换位置时轻震",
                                            fontSize = 11.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }

                                LiquidToggleSwitch(
                                    checked = swipeHapticsEnabled,
                                    onCheckedChange = { checked ->
                                        onSwipeHapticsEnabledChange(checked)
                                        if (checked) {
                                            HapticFeedback.tick(context, force = true)
                                        }
                                    },
                                    isDark = isDark
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // 4. 隐私与其他
                SectionHeader("隐私与其他")

                SettingsGlassCard(isDark = isDark) {
                    // 后台隐私遮挡（保留一句克制说明，让用户明确作用）
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            SettingsIconBadge(
                                icon = Icons.Rounded.Security,
                                tint = accentTeal,
                                isDark = isDark
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.padding(end = 12.dp)) {
                                Text(
                                    text = "后台隐私遮挡",
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = "切到后台时自动隐藏相册画面",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        LiquidToggleSwitch(
                            checked = backgroundBlurEnabled,
                            onCheckedChange = { checked ->
                                onBackgroundBlurEnabledChange(checked)
                                HapticFeedback.click(context)
                            },
                            isDark = isDark
                        )
                    }

                    SettingsInsetDivider(isDark = isDark)

                    if (showManageMediaEntry) {
                        SettingsActionRow(
                            icon = Icons.Rounded.VerifiedUser,
                            iconTint = accentBlue,
                            title = "免弹窗彻底删除",
                            trailingText = if (canManageMedia) "已开启" else "去开启",
                            isDark = isDark,
                            onClick = {
                                HapticFeedback.click(context)
                                onOpenManageMediaSettings()
                            }
                        )

                        SettingsInsetDivider(isDark = isDark)
                    }

                    SettingsActionRow(
                        icon = Icons.AutoMirrored.Rounded.MenuBook,
                        iconTint = accentBlue,
                        title = "手势指南",
                        isDark = isDark,
                        onClick = {
                            HapticFeedback.click(context)
                            onShowTutorial()
                        }
                    )

                    SettingsInsetDivider(isDark = isDark)

                    SettingsActionRow(
                        icon = Icons.Rounded.Policy,
                        iconTint = accentTeal,
                        title = "隐私说明",
                        isDark = isDark,
                        onClick = {
                            HapticFeedback.click(context)
                            activeSheet = SettingsSheet.PRIVACY
                        }
                    )

                    SettingsInsetDivider(isDark = isDark)

                    SettingsActionRow(
                        icon = Icons.Rounded.Favorite,
                        iconTint = accentCoral,
                        title = "赞赏支持",
                        isDark = isDark,
                        onClick = {
                            HapticFeedback.click(context)
                            activeSheet = SettingsSheet.SPONSOR
                        }
                    )

                    SettingsInsetDivider(isDark = isDark)

                    SettingsActionRow(
                        icon = Icons.Rounded.Info,
                        iconTint = accentNeutral,
                        title = "关于 Mundru",
                        trailingText = versionLabel,
                        isDark = isDark,
                        onClick = {
                            HapticFeedback.click(context)
                            activeSheet = SettingsSheet.ABOUT
                        }
                    )
                }

                // 底部极简品牌落款
                Spacer(modifier = Modifier.height(24.dp))
                Text(
                    text = "Mundru · $versionLabel",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.50f),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(28.dp))
            }
        }

        // 第 2 层：极简顶部悬浮真·液态玻璃导航栏
        Row(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 20.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            LiquidGlassSurface(
                backdrop = settingsBackdrop,
                isDark = isDark,
                cornerRadius = 21.dp,
                blurRadius = 18.dp,
                refractionStrengthDp = 12.dp,
                onClick = {
                    HapticFeedback.click(context)
                    onBack()
                },
                modifier = Modifier.size(42.dp)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = "返回",
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(20.dp)
                )
            }

            LiquidGlassSurface(
                backdrop = settingsBackdrop,
                isDark = isDark,
                cornerRadius = 21.dp,
                blurRadius = 18.dp,
                refractionStrengthDp = 12.dp,
                pressBounce = false
            ) {
                Text(
                    text = "设置",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp)
                )
            }

            // 对称占位，确保标题胶囊视觉绝对居中且无重复关闭按钮
            Spacer(modifier = Modifier.size(42.dp))
        }

        // 统一现代化底部浮层（彻底淘汰老旧 AlertDialog）
        when (activeSheet) {
            SettingsSheet.PRIVACY -> PrivacyBottomSheet(
                isDark = isDark,
                accentTeal = accentTeal,
                onDismiss = { activeSheet = null }
            )
            SettingsSheet.SPONSOR -> SponsorBottomSheet(
                isDark = isDark,
                accentCoral = accentCoral,
                onDismiss = { activeSheet = null }
            )
            SettingsSheet.ABOUT -> AboutBottomSheet(
                versionLabel = versionLabel,
                isDark = isDark,
                accentBlue = accentBlue,
                onOpenSponsor = { activeSheet = SettingsSheet.SPONSOR },
                onDismiss = { activeSheet = null }
            )
            null -> Unit
        }
    }
}

@Composable
private fun HeroStatsBentoCard(
    totalReviewed: Long,
    trashBytes: Long,
    totalPhotos: Int,
    isDark: Boolean,
    accentBlue: Color,
    accentTeal: Color
) {
    val numberFmt = remember { NumberFormat.getIntegerInstance(Locale.US) }
    val reviewedText = remember(totalReviewed) { numberFmt.format(totalReviewed) }
    val totalText = remember(totalPhotos) { numberFmt.format(totalPhotos) }
    val trashSizeText = remember(trashBytes) { formatFileSize(trashBytes) }

    val progressFraction = remember(totalReviewed, totalPhotos) {
        if (totalPhotos <= 0) 0f
        else (totalReviewed.toFloat() / totalPhotos.toFloat()).coerceIn(0f, 1f)
    }
    val percentText = remember(progressFraction) {
        val pct = progressFraction * 100f
        when {
            pct <= 0f -> "0%"
            pct < 1f -> String.format(Locale.US, "%.1f%%", pct)
            else -> "${pct.toInt()}%"
        }
    }

    SettingsGlassCard(
        isDark = isDark,
        cornerRadius = 24.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp)
        ) {
            // 卡片顶栏：标题 + 纯离线徽章
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SettingsIconBadge(
                        icon = Icons.Rounded.AutoAwesome,
                        tint = accentBlue,
                        isDark = isDark
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "回忆整理成果",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                SettingsCrystalBadge(
                    text = "纯离线",
                    accent = accentTeal,
                    isDark = isDark
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            // 三列等宽数据仪表盘（tabular numerals + 极细竖向分隔线）
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                HeroStatColumn(
                    label = "累计审阅",
                    value = reviewedText,
                    unit = "张",
                    valueColor = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                HeroVerticalHairline(isDark = isDark)
                HeroStatColumn(
                    label = "待释放空间",
                    value = trashSizeText,
                    unit = null,
                    valueColor = if (trashBytes > 0L) accentBlue else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                HeroVerticalHairline(isDark = isDark)
                HeroStatColumn(
                    label = "相册总量",
                    value = totalText,
                    unit = "张",
                    valueColor = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(modifier = Modifier.height(18.dp))

            // 底部微光探索进度条
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "相册探索覆盖度",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = percentText,
                    style = TextStyle(fontFeatureSettings = "tnum"),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = accentBlue
                )
            }
            Spacer(modifier = Modifier.height(7.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(5.dp)
                    .clip(CircleShape)
                    .background(if (isDark) Color.White.copy(alpha = 0.08f) else Color.Black.copy(alpha = 0.06f))
            ) {
                val visualWidth = if (totalReviewed > 0L) progressFraction.coerceAtLeast(0.035f) else 0f
                if (visualWidth > 0f) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(visualWidth)
                            .fillMaxHeight()
                            .clip(CircleShape)
                            .background(
                                Brush.horizontalGradient(
                                    colors = listOf(
                                        accentBlue.copy(alpha = 0.72f),
                                        accentBlue
                                    )
                                )
                            )
                    )
                }
            }
        }
    }
}

@Composable
private fun HeroStatColumn(
    label: String,
    value: String,
    unit: String?,
    valueColor: Color,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = value,
                style = TextStyle(fontFeatureSettings = "tnum"),
                fontSize = 19.sp,
                fontWeight = FontWeight.Bold,
                color = valueColor,
                maxLines = 1
            )
            if (unit != null) {
                Spacer(modifier = Modifier.width(2.dp))
                Text(
                    text = unit,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 2.dp)
                )
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = label,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1
        )
    }
}

@Composable
private fun HeroVerticalHairline(isDark: Boolean) {
    Box(
        modifier = Modifier
            .width(0.6.dp)
            .height(28.dp)
            .background(if (isDark) Color.White.copy(alpha = 0.08f) else Color.Black.copy(alpha = 0.07f))
    )
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.4.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 6.dp, bottom = 10.dp)
    )
}

@Composable
private fun PrivacyBottomSheet(
    isDark: Boolean,
    accentTeal: Color,
    onDismiss: () -> Unit
) {
    MundruBottomSheet(onDismiss = onDismiss) { dismissSmoothly ->
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp, vertical = 8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SettingsIconBadge(icon = Icons.Rounded.VerifiedUser, tint = accentTeal, isDark = isDark)
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = "隐私说明",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "照片始终只留在你的手机里",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(18.dp))

            PrivacyPledgeItem("完全离线", "不申请联网权限，不会上传或同步你的任何照片。")
            Spacer(modifier = Modifier.height(12.dp))
            PrivacyPledgeItem("仅存本机", "整理进度与回收站记录只保存在手机本地。")
            Spacer(modifier = Modifier.height(12.dp))
            PrivacyPledgeItem("删图需系统确认", "彻底删除会交由系统相册再次确认，绝不私自删图。")
            Spacer(modifier = Modifier.height(12.dp))
            PrivacyPledgeItem("切后台自动遮挡", "开启隐私遮挡后，切回桌面或切换应用时自动隐藏画面。")

            Spacer(modifier = Modifier.height(22.dp))

            SettingsGlassButton(
                text = "知道了",
                isPrimary = true,
                isDark = isDark,
                onClick = { dismissSmoothly(null) },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(14.dp))
        }
    }
}

@Composable
private fun PrivacyPledgeItem(title: String, desc: String) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = title,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = desc,
            fontSize = 12.sp,
            lineHeight = 17.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun SponsorBottomSheet(
    isDark: Boolean,
    accentCoral: Color,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var paymentMethod by remember { mutableIntStateOf(0) } // 0: 微信, 1: 支付宝
    val qrResId = if (paymentMethod == 0) R.drawable.qr_wechat else R.drawable.qr_alipay
    val qrRequest = remember(qrResId) {
        ImageRequest.Builder(context)
            .data(qrResId)
            .size(512, 512)
            .crossfade(false)
            .build()
    }

    MundruBottomSheet(onDismiss = onDismiss) { dismissSmoothly ->
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                SettingsIconBadge(icon = Icons.Rounded.Favorite, tint = accentCoral, isDark = isDark)
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = "赞赏支持",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "免费、无广告的个人作品",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            Text(
                text = "如果 Mundru 让整理相册变得更轻松，欢迎请开发者喝杯咖啡。",
                fontSize = 13.sp,
                lineHeight = 19.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(16.dp))

            LiquidSlidingSegmentedControl(
                items = listOf(0, 1),
                selectedItem = paymentMethod,
                isDark = isDark,
                onSelect = { idx ->
                    if (idx != paymentMethod) {
                        HapticFeedback.click(context)
                        paymentMethod = idx
                    }
                },
                labelProvider = { if (it == 0) "微信支付" else "支付宝" }
            )

            Spacer(modifier = Modifier.height(18.dp))

            Surface(
                shape = RoundedCornerShape(20.dp),
                color = Color.White,
                shadowElevation = 4.dp,
                modifier = Modifier.size(216.dp)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    AsyncImage(
                        model = qrRequest,
                        contentDescription = if (paymentMethod == 0) "微信赞赏码" else "支付宝赞赏码",
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            SettingsGlassButton(
                text = "关闭",
                isPrimary = false,
                isDark = isDark,
                onClick = { dismissSmoothly(null) },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(14.dp))
        }
    }
}

@Composable
private fun AboutBottomSheet(
    versionLabel: String,
    isDark: Boolean,
    accentBlue: Color,
    onOpenSponsor: () -> Unit,
    onDismiss: () -> Unit
) {
    MundruBottomSheet(onDismiss = onDismiss) { dismissSmoothly ->
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(if (isDark) Color(0xFF1F222C) else Color.White)
                    .border(
                        width = 0.8.dp,
                        color = if (isDark) Color.White.copy(alpha = 0.14f) else Color.Black.copy(alpha = 0.08f),
                        shape = RoundedCornerShape(18.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_mundru_logo),
                    contentDescription = "Mundru Logo",
                    tint = if (isDark) Color(0xFFF3F5FA) else Color(0xFF14151A),
                    modifier = Modifier.size(40.dp)
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = "Mundru",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(6.dp))
            SettingsCrystalBadge(text = versionLabel, accent = accentBlue, isDark = isDark)

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "Mundru 来自格陵兰语，意为「擦去」。\n\n一款纯粹、离线、没有打扰的本地相册整理工具，希望帮你轻松留住值得珍藏的画面。",
                fontSize = 13.sp,
                lineHeight = 20.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(22.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                SettingsGlassButton(
                    text = "赞赏支持",
                    isPrimary = true,
                    isDark = isDark,
                    onClick = { dismissSmoothly { onOpenSponsor() } },
                    modifier = Modifier.weight(1f)
                )
                SettingsGlassButton(
                    text = "关闭",
                    isPrimary = false,
                    isDark = isDark,
                    onClick = { dismissSmoothly(null) },
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(modifier = Modifier.height(14.dp))
        }
    }
}
