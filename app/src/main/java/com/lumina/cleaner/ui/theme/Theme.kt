package com.lumina.cleaner.ui.theme

import android.app.Activity
import android.graphics.drawable.ColorDrawable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BrightnessAuto
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
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
    background = MundruDarkBackground,
    surface = MundruDarkSurface,
    surfaceVariant = MundruDarkSurfaceVariant,
    onPrimary = Color.White,
    onBackground = MundruDarkTextPrimary,
    onSurface = MundruDarkTextPrimary,
    onSurfaceVariant = MundruDarkTextSecondary,
    error = MundruDarkTrash,
    onError = Color.White
)

private val LightColorScheme = lightColorScheme(
    primary = MundruLightAccent,
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
