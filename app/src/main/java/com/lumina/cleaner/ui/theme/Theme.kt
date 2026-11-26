package com.lumina.cleaner.ui.theme

import android.app.Activity
import android.app.ActivityManager
import android.app.UiModeManager
import android.graphics.drawable.ColorDrawable
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BrightnessAuto
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/**
 * Mundru 外观主题模式枚举
 *
 * 0: 跟随系统 (默认)
 * 1: 白天模式 (浅色)
 * 2: 夜间模式 (深色)
 */
enum class AppThemeMode(val code: Int, val title: String, val icon: ImageVector) {
    FOLLOW_SYSTEM(0, "跟随系统", Icons.Rounded.BrightnessAuto),
    LIGHT(1, "白天模式", Icons.Rounded.LightMode),
    DARK(2, "夜间模式", Icons.Rounded.DarkMode);

    companion object {
        fun fromCode(code: Int): AppThemeMode = entries.find { it.code == code } ?: FOLLOW_SYSTEM
    }
}

/**
 * Mundru 应用程序主题 (Material 3 Theme)
 *
 * 品牌释义：Mundru 源自格陵兰语（Greenlandic），意为「擦掉 / 擦除」。
 * 采用纯净纸质白与深度空灵炭黑双重冷暖中性色板，打造纯粹沉浸且高对比度的相册整理氛围。
 */
private val DarkColorScheme = darkColorScheme(
    primary = MundruDarkAccent,
    primaryContainer = Color(0xFF282A33),
    onPrimaryContainer = Color(0xFFF4F4F6),
    background = MundruDarkBackground,
    surface = MundruDarkSurface,
    surfaceVariant = MundruDarkSurfaceVariant,
    onPrimary = Color(0xFF111216),
    onBackground = MundruDarkTextPrimary,
    onSurface = MundruDarkTextPrimary,
    onSurfaceVariant = MundruDarkTextSecondary,
    error = MundruDarkTrash,
    onError = Color.White
)

private val LightColorScheme = lightColorScheme(
    primary = MundruLightAccent,
    primaryContainer = Color(0xFFE9E9EC),
    onPrimaryContainer = Color(0xFF141518),
    background = MundruLightBackground,
    surface = MundruLightSurface,
    surfaceVariant = MundruLightSurfaceVariant,
    onPrimary = Color.White,
    onBackground = MundruLightTextPrimary,
    onSurface = MundruLightTextPrimary,
    onSurfaceVariant = MundruLightTextSecondary,
    error = MundruLightTrash,
    onError = Color.White
)

@Composable
fun MundruTheme(
    themeMode: AppThemeMode = AppThemeMode.FOLLOW_SYSTEM,
    darkTheme: Boolean = when (themeMode) {
        AppThemeMode.FOLLOW_SYSTEM -> isSystemInDarkTheme()
        AppThemeMode.LIGHT -> false
        AppThemeMode.DARK -> true
    },
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    val view = LocalView.current
    if (!view.isInEditMode) {
        // 同步应用级系统夜间模式（Android 12+），使后台防窥多任务卡片与系统相册删除窗随应用内主题同步变黑/变白
        LaunchedEffect(themeMode) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val uiModeManager = view.context.getSystemService(UiModeManager::class.java)
                val targetNightMode = when (themeMode) {
                    AppThemeMode.FOLLOW_SYSTEM -> UiModeManager.MODE_NIGHT_AUTO
                    AppThemeMode.LIGHT -> UiModeManager.MODE_NIGHT_NO
                    AppThemeMode.DARK -> UiModeManager.MODE_NIGHT_YES
                }
                runCatching { uiModeManager?.setApplicationNightMode(targetNightMode) }
            }
        }

        // 同步系统多任务 TaskDescription 背景色，杜绝 FLAG_SECURE 开启时黑夜模式切后台露出白色隐私底卡
        LaunchedEffect(darkTheme) {
            val activity = view.context as? Activity ?: return@LaunchedEffect
            val recentsBgColor = if (darkTheme) 0xFF0C0D10.toInt() else 0xFFFFFFFF.toInt()
            val taskDesc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ActivityManager.TaskDescription.Builder()
                    .setPrimaryColor(recentsBgColor)
                    .setBackgroundColor(recentsBgColor)
                    .setStatusBarColor(recentsBgColor)
                    .setNavigationBarColor(recentsBgColor)
                    .build()
            } else {
                @Suppress("DEPRECATION")
                ActivityManager.TaskDescription(null, 0, recentsBgColor)
            }
            runCatching { activity.setTaskDescription(taskDesc) }
        }

        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            val insetsController = WindowCompat.getInsetsController(window, view)
            insetsController.isAppearanceLightStatusBars = !darkTheme
            insetsController.isAppearanceLightNavigationBars = !darkTheme

            // 将底层系统 Window 和 DecorView 背景实时强绑定至当前主题背景色
            // 彻底杜绝黑夜模式下页面转场滑动或重绘时露出系统默认白色 Window 背景的闪白与白边缺陷
            val bgIntColor = colorScheme.background.toArgb()
            window.setBackgroundDrawable(ColorDrawable(bgIntColor))
            window.decorView.setBackgroundColor(bgIntColor)
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}

// 兼容旧调用别名
@Composable
fun LuminaTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) = MundruTheme(darkTheme = darkTheme, content = content)

/**
 * 沉浸式无跳动状态栏安全区占位：
 * 在大图查看器临时隐藏系统状态栏时维持底层主舞台、顶栏与角标的物理高度恒定，
 * 彻底消除状态栏显隐切换造成的全屏布局重排与源卡片坐标错位（兼容 Android 9 ~ 15）。
 */
@OptIn(ExperimentalLayoutApi::class)
fun Modifier.stableStatusBarsPadding(): Modifier = composed {
    val density = LocalDensity.current
    val orientation = LocalConfiguration.current.orientation
    // 仅订阅 statusBarsIgnoringVisibility，彻底避免隐藏/恢复状态栏时唤醒主舞台重组
    val ignoringTopPx = WindowInsets.statusBarsIgnoringVisibility.getTop(density)
    var lockedTopPx by remember(orientation) { mutableIntStateOf(ignoringTopPx) }
    if (ignoringTopPx > lockedTopPx) {
        lockedTopPx = ignoringTopPx
    }
    if (lockedTopPx > 0) {
        this.padding(top = with(density) { lockedTopPx.toDp() })
    } else {
        this.windowInsetsPadding(WindowInsets.statusBarsIgnoringVisibility)
    }
}
