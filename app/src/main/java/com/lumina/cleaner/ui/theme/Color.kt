package com.lumina.cleaner.ui.theme

import androidx.compose.ui.graphics.Color

// Mundru Light Mode: 瑞士美术馆宣纸白 + 纯炭墨黑 (100% 零蓝色，把全部色彩舞台让给相片本身)
val MundruLightBackground = Color(0xFFF7F7F5)
val MundruLightSurface = Color(0xFFFFFFFF)
val MundruLightSurfaceVariant = Color(0xFFEBEBE9)
val MundruLightTextPrimary = Color(0xFF141518)
val MundruLightTextSecondary = Color(0xFF6B6C74)
val MundruLightAccent = Color(0xFF141518) // 纯炭墨黑（与原版黑白小猫 Logo 同源）
val MundruLightTrash = Color(0xFFD9412C)  // 克制沉稳的朱砂红（仅用于清理/删除语义）

// Mundru Dark Mode: 深度曜石炭黑 + 纯象牙白高对比交互层 (零蓝色、零发灰)
val MundruDarkBackground = Color(0xFF0C0D10)
val MundruDarkSurface = Color(0xFF17181D)
val MundruDarkSurfaceVariant = Color(0xFF282A32)
val MundruDarkTextPrimary = Color(0xFFF4F4F6)
val MundruDarkTextSecondary = Color(0xFFA0A3AE)
val MundruDarkAccent = Color(0xFFF4F4F6)  // 纯象牙白（配合炭黑反色文字，暗房级高对比）
val MundruDarkTrash = Color(0xFFFF5A4E)

// 兼容别名
val LuminaLightBackground = MundruLightBackground
val LuminaLightSurface = MundruLightSurface
val LuminaLightSurfaceVariant = MundruLightSurfaceVariant
val LuminaLightTextPrimary = MundruLightTextPrimary
val LuminaLightTextSecondary = MundruLightTextSecondary
val LuminaLightAccent = MundruLightAccent
val LuminaLightTrash = MundruLightTrash

val LuminaDarkBackground = MundruDarkBackground
val LuminaDarkSurface = MundruDarkSurface
val LuminaDarkSurfaceVariant = MundruDarkSurfaceVariant
val LuminaDarkTextPrimary = MundruDarkTextPrimary
val LuminaDarkTextSecondary = MundruDarkTextSecondary
val LuminaDarkAccent = MundruDarkAccent
val LuminaDarkTrash = MundruDarkTrash
