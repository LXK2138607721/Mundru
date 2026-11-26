package com.lumina.cleaner.ui.glass

import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * Android 13+ (API 33+) AGSL 真·液态光学漩涡着色器 (Liquid Vortex Portal)
 *
 * 物理美学设计（杜绝廉价 2D 旋转，追求 VisionOS 级光学玻璃质感）：
 * 1. 基于高斯径向衰减场的平滑光学旋涡扭曲（中心柔卷、外围渐平，无锯齿折痕）；
 * 2. 向心透镜吸缩：随 progress 柔和将像素向中心汇聚；
 * 3. 克制的 R/G/B 棱镜色散沿着旋涡切向微分离，呈现液态水晶般的高级折光流彩；
 * 4. 越界透明输出，与硬件高斯模糊串联，使旧卡堆如液态水银般融化进中央光学奇点并反向旋绽出新批次。
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
internal const val LIQUID_VORTEX_AGSL = """
    uniform shader content;
    uniform float2 resolution;
    uniform float progress;
    uniform float direction;
    uniform float isDark;

    half4 evalClamped(float2 uv) {
        if (uv.x < 0.0 || uv.y < 0.0 || uv.x > resolution.x || uv.y > resolution.y) {
            return half4(0.0);
        }
        return content.eval(uv);
    }

    float2 swirlUv(float2 p, float2 center, float minDim, float angleScale, float pullScale) {
        float r2 = dot(p, p);
        float falloff = exp(-r2 * 2.85);
        float theta = angleScale * falloff;
        float s = sin(theta);
        float c = cos(theta);
        float2 rotated = float2(p.x * c - p.y * s, p.x * s + p.y * c);
        // 透镜向心收缩与展开
        float radialZoom = 1.0 + pullScale * falloff;
        return center + rotated * radialZoom * minDim;
    }

    half4 main(float2 fragCoord) {
        if (progress <= 0.001) {
            return content.eval(fragCoord);
        }

        float2 center = resolution * 0.5;
        float minDim = max(min(resolution.x, resolution.y), 1.0);
        float2 p = (fragCoord - center) / minDim;

        float pCurve = progress * progress;
        float baseAngle = direction * pCurve * 2.45;
        float basePull = progress * 0.44;

        // 棱镜微色散：沿旋涡切向微小分离红/绿/蓝折射角
        float disp = 0.085 * progress;
        half4 sampleR = evalClamped(swirlUv(p, center, minDim, baseAngle * (1.0 + disp), basePull * 1.04));
        half4 sampleG = evalClamped(swirlUv(p, center, minDim, baseAngle, basePull));
        half4 sampleB = evalClamped(swirlUv(p, center, minDim, baseAngle * (1.0 - disp), basePull * 0.96));

        half alpha = clamp(max(sampleG.a, max(sampleR.a, sampleB.a)), half(0.0), half(1.0));
        half3 rgb = half3(sampleR.r, sampleG.g, sampleB.b);

        // 奇点边缘极柔和的水晶高光晕染
        float r = length(p);
        float ringGlow = exp(-pow((r - (0.34 * (1.0 - progress * 0.55))) * 9.5, 2.0)) * progress * (1.0 - progress * 0.35);
        half glowWeight = half(ringGlow * (isDark > 0.5 ? 0.24 : 0.16)) * alpha;
        rgb += half3(glowWeight);

        return half4(rgb, alpha);
    }
"""

private val VortexInhaleEasing = CubicBezierEasing(0.45f, 0.0f, 0.82f, 0.30f)
private val VortexEmergeEasing = CubicBezierEasing(0.16f, 1.0f, 0.30f, 1.0f)

/**
 * 液态光学漩涡换批控制器 (LiquidVortexState)
 *
 * 两段式 120fps 单调平滑编排（全程约 490ms，0 转圈、0 过冲回缩抖动）：
 * 1. Inhale（0..195ms）：旧卡堆平滑加速旋卷收束至中心光学奇点，同时后台并发完成选图与首 3 张预解码；
 * 2. Singularity Swap：在 alpha=0 奇点瞬间提交新批次并静默等待 1 帧完成布局与首图绑定，消除首帧跳帧；
 * 3. Emerge（195..490ms）：以严格单调缓出曲线（<= 1.000x，绝不过冲回缩）反向旋展，新三层卡堆丝滑舒展至最大并沉静定格。
 * 静止时 [isActive] 为 false，RenderEffect 完全置空，0 GPU 额外开销。
 */
@Stable
class LiquidVortexState {
    val vortexProgress = Animatable(0f)
    val bloomProgress = Animatable(1f)
    var direction by mutableFloatStateOf(1f)
        private set
    var isActive by mutableStateOf(false)
        private set

    /**
     * 执行完整的「漩涡吸入 -> 毫秒级无缝换批 -> 单调丝滑旋出定格」转场。
     */
    suspend fun runTransition(
        onStartHaptic: () -> Unit,
        onEmergeHaptic: () -> Unit,
        prepareNextBatch: suspend () -> (() -> Unit)
    ) {
        isActive = true
        direction = 1f
        onStartHaptic()

        try {
            var commitNextBatch: (() -> Unit)? = null
            // 阶段 1：漩涡吞噬退场 与 后台线程抽样+首 3 张预解码 严格并发执行
            coroutineScope {
                launch {
                    vortexProgress.snapTo(0f)
                    bloomProgress.snapTo(1f)
                    vortexProgress.animateTo(
                        targetValue = 1f,
                        animationSpec = tween(durationMillis = 195, easing = VortexInhaleEasing)
                    )
                }
                launch {
                    commitNextBatch = prepareNextBatch()
                }
            }

            // 奇点无感交接：在完全透明状态下提交新批次，并静默等待 2 帧完成新卡堆布局与 GPU 纹理上传后再旋出，消除旋展首帧尖峰
            direction = -1f
            bloomProgress.snapTo(0f)
            commitNextBatch?.invoke()
            withFrameNanos { }
            withFrameNanos { }
            onEmergeHaptic()

            // 阶段 2：单调临界缓出旋展（两动画严格同频、绝不过冲 1.0x、展开即丝滑静止）
            coroutineScope {
                launch {
                    vortexProgress.animateTo(
                        targetValue = 0f,
                        animationSpec = tween(durationMillis = 295, easing = VortexEmergeEasing)
                    )
                }
                launch {
                    bloomProgress.animateTo(
                        targetValue = 1f,
                        animationSpec = tween(durationMillis = 295, easing = VortexEmergeEasing)
                    )
                }
            }
        } finally {
            vortexProgress.snapTo(0f)
            bloomProgress.snapTo(1f)
            isActive = false
        }
    }
}

@Composable
fun rememberLiquidVortexState(): LiquidVortexState = remember { LiquidVortexState() }

/**
 * 挂载于卡片主舞台容器上的单通道 120fps AGSL 液态漩涡硬件滤镜修饰符：
 * - 静止时零开销（renderEffect = null，scale = 1f，alpha = 1f）；
 * - 转场激活时仅执行单通道 AGSL 光学旋涡与切向棱镜色散（彻底移除高耗全屏两通道高斯模糊串联），
 *   缩放严格单调 <= 1.000f，展开到最大后丝滑定格零回缩抖动。
 */
fun Modifier.liquidVortexEffect(
    state: LiquidVortexState,
    isDark: Boolean
): Modifier = composed {
    val perfTier = rememberDevicePerformanceTier()
    val useAgslVortex = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        perfTier != DevicePerformanceTier.LOW
    val shader = if (useAgslVortex) {
        remember { RuntimeShader(LIQUID_VORTEX_AGSL) }
    } else null

    this.graphicsLayer {
        val p = state.vortexProgress.value.coerceIn(0f, 1f)
        if (!state.isActive && p <= 0.002f) {
            scaleX = 1f
            scaleY = 1f
            rotationZ = 0f
            alpha = 1f
            renderEffect = null
            return@graphicsLayer
        }

        // 严格单调的空间汇聚与展开（始终 <= 1.0f，绝不过冲回缩）
        val p2 = p * p
        val s = (1f - 0.48f * p2).coerceIn(0.50f, 1f)
        scaleX = s
        scaleY = s
        rotationZ = state.direction * p2 * 14f
        alpha = (1f - ((p - 0.68f) / 0.32f).coerceIn(0f, 1f)).coerceIn(0f, 1f)

        // 中高端设备满血启用单通道 AGSL 光学旋涡折射；低端芯片自动回落为纯 GPU 几何汇聚，保障 60fps 流畅度
        if (useAgslVortex && shader != null && p > 0.008f && size.width > 4f && size.height > 4f) {
            shader.setFloatUniform("resolution", size.width, size.height)
            shader.setFloatUniform("progress", p)
            shader.setFloatUniform("direction", state.direction)
            shader.setFloatUniform("isDark", if (isDark) 1f else 0f)
            renderEffect = RenderEffect.createRuntimeShaderEffect(shader, "content").asComposeRenderEffect()
        } else {
            renderEffect = null
        }
    }
}

/**
 * 中央极简光学水晶奇点光环（仅在漩涡转场高潮期短暂浮现，Draw-Phase 零重组）。
 */
@Composable
fun LiquidVortexPortalRing(
    state: LiquidVortexState,
    accentColor: Color,
    isDark: Boolean,
    modifier: Modifier = Modifier
) {
    if (!state.isActive) return
    Canvas(modifier = modifier.fillMaxSize()) {
        val p = state.vortexProgress.value.coerceIn(0f, 1f)
        if (p < 0.12f) return@Canvas

        // 钟形能级脉冲：在中段到奇点处晶莹微亮，首尾自然隐去
        val intensity = ((p - 0.12f) / 0.88f).coerceIn(0f, 1f)
        val center = Offset(size.width * 0.5f, size.height * 0.5f)
        val baseRadius = size.minDimension * (0.28f - 0.16f * intensity)
        val spinDeg = state.direction * p * 140f

        // 柔和中心折射微晕
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    accentColor.copy(alpha = intensity * (if (isDark) 0.22f else 0.14f)),
                    Color.White.copy(alpha = intensity * (if (isDark) 0.10f else 0.20f)),
                    Color.Transparent
                ),
                center = center,
                radius = (baseRadius * 2.1f).coerceAtLeast(2f)
            ),
            radius = (baseRadius * 2.1f).coerceAtLeast(2f),
            center = center
        )

        // 精致双弧镜面高光环（随旋涡方向缓缓流转）
        rotate(degrees = spinDeg, pivot = center) {
            drawCircle(
                brush = Brush.sweepGradient(
                    colorStops = arrayOf(
                        0.00f to Color.Transparent,
                        0.22f to Color.White.copy(alpha = intensity * (if (isDark) 0.55f else 0.85f)),
                        0.42f to accentColor.copy(alpha = intensity * 0.45f),
                        0.55f to Color.Transparent,
                        0.76f to Color.White.copy(alpha = intensity * (if (isDark) 0.35f else 0.65f)),
                        1.00f to Color.Transparent
                    ),
                    center = center
                ),
                radius = baseRadius.coerceAtLeast(4f),
                center = center,
                style = Stroke(width = (2.2f * intensity + 0.8f).dp.toPx())
            )
        }
    }
}
