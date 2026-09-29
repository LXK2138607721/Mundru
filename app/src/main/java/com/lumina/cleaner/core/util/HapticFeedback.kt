package com.lumina.cleaner.core.util

import android.content.Context
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * 触觉强度档位
 */
enum class HapticIntensity(val level: Int, val title: String, val scale: Float) {
    OFF(0, "关闭", 0f),
    LIGHT(1, "轻柔", 0.6f),
    MEDIUM(2, "适中", 1.0f),
    STRONG(3, "强劲", 1.4f);

    companion object {
        fun fromLevel(level: Int): HapticIntensity =
            entries.find { it.level == level } ?: MEDIUM
    }
}

/**
 * Mundru 极简硬件原生触觉反馈引擎 (HapticFeedback)
 * 品牌释义：Mundru 源自格陵兰语（Greenlandic），意为「擦掉 / 擦除」。
 * 交互哲学：用最细腻克制的马达震感，赋能每一次卡片滑动与照片擦除。
 *
 * 核心设计规范：
 * 1. 深度适配 Android 9 (API 28) ~ Android 15 (API 35)，Android 12+ 优先走 VibratorManager 与 VibrationAttributes.USAGE_TOUCH；
 * 2. 动静隔离：若硬件不支持 Predefined Effects（部分 OEM 裁剪），自动平滑降级至精确微秒级波形（OneShot/Waveform）；
 * 3. 硬件阻尼与动态标定：根据用户偏好强度（轻柔/适中/强劲）对振幅与时序进行自适应缩放；
 * 4. 纳秒/毫秒级节流：内置智能滑动节流器，杜绝高频拖拽导致马达线圈发热发闷与卡顿。
 */
object HapticFeedback {

    private var vibrator: Vibrator? = null
    private var lastVibrateTime = 0L
    private const val MIN_INTERVAL_MS = 35L

    // 全局设置状态
    var isEnabled: Boolean = true
        private set
    var intensity: HapticIntensity = HapticIntensity.MEDIUM
        private set
    var isSwipeHapticsEnabled: Boolean = true
        private set

    /**
     * 更新全局触觉反馈配置
     */
    fun updateSettings(level: Int, swipeEnabled: Boolean) {
        val target = HapticIntensity.fromLevel(level)
        this.intensity = target
        this.isEnabled = target != HapticIntensity.OFF
        this.isSwipeHapticsEnabled = swipeEnabled
    }

    private fun getVibrator(context: Context): Vibrator? {
        if (vibrator == null) {
            val appContext = context.applicationContext ?: context
            vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val manager = appContext.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                manager?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                appContext.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
        }
        return vibrator
    }

    private fun canVibrate(): Boolean {
        return isEnabled && intensity != HapticIntensity.OFF
    }

    private fun resolveAmplitude(baseAmplitude: Int, customScale: Float? = null): Int {
        val s = customScale ?: intensity.scale
        return (baseAmplitude * s).toInt().coerceIn(1, 255)
    }

    private fun resolveDuration(baseDurationMs: Long, customScale: Float? = null): Long {
        val s = customScale ?: intensity.scale
        return (baseDurationMs * (0.8f + s * 0.2f)).toLong().coerceAtLeast(1L)
    }

    /**
     * 极轻触觉微顿挫（用于拖拽卡片划过触发判定线、刻度滑动）
     */
    fun tick(context: Context, force: Boolean = false) {
        if (!force && (!canVibrate() || !isSwipeHapticsEnabled)) return

        val now = System.currentTimeMillis()
        if (now - lastVibrateTime < MIN_INTERVAL_MS) return
        lastVibrateTime = now

        executeHaptic(
            context = context,
            predefinedEffect = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) VibrationEffect.EFFECT_TICK else null,
            fallbackDurationMs = 12L,
            fallbackAmplitude = 65
        )
    }

    /**
     * 标准物理机械轻击（用于卡片保留、切换整理模式、常规按钮、图集归类）
     */
    fun click(context: Context) {
        if (!canVibrate()) return

        val now = System.currentTimeMillis()
        if (now - lastVibrateTime < MIN_INTERVAL_MS) return
        lastVibrateTime = now

        executeHaptic(
            context = context,
            predefinedEffect = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) VibrationEffect.EFFECT_CLICK else null,
            fallbackDurationMs = 20L,
            fallbackAmplitude = 125
        )
    }

    /**
     * 重感物理阻尼冲击（用于上滑删除照片、清空回收站等不可逆重要操作）
     */
    fun heavy(context: Context) {
        if (!canVibrate()) return

        val now = System.currentTimeMillis()
        if (now - lastVibrateTime < MIN_INTERVAL_MS) return
        lastVibrateTime = now

        executeHaptic(
            context = context,
            predefinedEffect = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) VibrationEffect.EFFECT_HEAVY_CLICK else null,
            fallbackDurationMs = 38L,
            fallbackAmplitude = 230
        )
    }

    /**
     * 连续双重清脆弹跳脉冲（用于批次整理完毕结算、撤销成功、全选）
     */
    fun doublePulse(context: Context) {
        if (!canVibrate()) return

        val now = System.currentTimeMillis()
        if (now - lastVibrateTime < 80L) return
        lastVibrateTime = now

        try {
            val v = getVibrator(context) ?: return
            if (!v.hasVibrator()) return

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && v.areEffectsSupported(VibrationEffect.EFFECT_DOUBLE_CLICK).firstOrNull() == Vibrator.VIBRATION_EFFECT_SUPPORT_YES) {
                performVibrate(v, VibrationEffect.createPredefined(VibrationEffect.EFFECT_DOUBLE_CLICK))
                return
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val amp1 = resolveAmplitude(110)
                val amp2 = resolveAmplitude(170)
                val timings = longArrayOf(0, 16, 50, 20)
                val amplitudes = intArrayOf(0, amp1, 0, amp2)
                performVibrate(v, VibrationEffect.createWaveform(timings, amplitudes, -1))
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(longArrayOf(0, 16, 50, 20), -1)
            }
        } catch (_: Exception) { }
    }

    /**
     * 错误/警告微震反馈（用于空列表操作、失败提示）
     */
    fun error(context: Context) {
        if (!canVibrate()) return

        try {
            val v = getVibrator(context) ?: return
            if (!v.hasVibrator()) return

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val amp = resolveAmplitude(140)
                val timings = longArrayOf(0, 25, 40, 25)
                val amplitudes = intArrayOf(0, amp, 0, amp)
                performVibrate(v, VibrationEffect.createWaveform(timings, amplitudes, -1))
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(longArrayOf(0, 25, 40, 25), -1)
            }
        } catch (_: Exception) { }
    }

    /**
     * 设置项调节时的即时预览触感
     */
    fun preview(context: Context, previewIntensity: HapticIntensity) {
        if (previewIntensity == HapticIntensity.OFF) return

        try {
            val v = getVibrator(context) ?: return
            if (!v.hasVibrator()) return

            val duration = resolveDuration(24L, previewIntensity.scale)
            val amplitude = resolveAmplitude(130, previewIntensity.scale)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                performVibrate(v, VibrationEffect.createOneShot(duration, amplitude))
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(duration)
            }
        } catch (_: Exception) { }
    }

    private fun executeHaptic(
        context: Context,
        predefinedEffect: Int?,
        fallbackDurationMs: Long,
        fallbackAmplitude: Int
    ) {
        try {
            val v = getVibrator(context) ?: return
            if (!v.hasVibrator()) return

            // 优先检查硬件是否原生支持该预设效果（仅在强度为适中标准时优先走系统原味预设）
            if (predefinedEffect != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && intensity == HapticIntensity.MEDIUM) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    val supported = v.areEffectsSupported(predefinedEffect).firstOrNull()
                    if (supported == Vibrator.VIBRATION_EFFECT_SUPPORT_YES) {
                        performVibrate(v, VibrationEffect.createPredefined(predefinedEffect))
                        return
                    }
                } else {
                    try {
                        performVibrate(v, VibrationEffect.createPredefined(predefinedEffect))
                        return
                    } catch (_: Exception) { }
                }
            }

            // 走经强度自适应校准的 OneShot 波形
            val duration = resolveDuration(fallbackDurationMs)
            val amplitude = resolveAmplitude(fallbackAmplitude)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                performVibrate(v, VibrationEffect.createOneShot(duration, amplitude))
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(duration)
            }
        } catch (_: Exception) { }
    }

    private fun performVibrate(vibrator: Vibrator, effect: VibrationEffect) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val attributes = VibrationAttributes.Builder()
                .setUsage(VibrationAttributes.USAGE_TOUCH)
                .build()
            vibrator.vibrate(effect, attributes)
        } else {
            vibrator.vibrate(effect)
        }
    }
}
