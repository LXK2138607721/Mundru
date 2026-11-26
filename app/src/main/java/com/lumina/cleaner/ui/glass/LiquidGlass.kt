package com.lumina.cleaner.ui.glass

import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RenderEffect as ComposeRenderEffect
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.CompositingStrategy as LayerCompositingStrategy
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * 实时液态玻璃背景捕获状态 (LiquidBackdropState)
 *
 * 借助 Compose 1.7 原生 GraphicsLayer，零拷贝录制底层主舞台（相片卡堆与背景）的 GPU 显示列表，
 * 供上层悬浮液态玻璃胶囊与菜单实时采样、硬件高斯模糊与 Android 13+ AGSL 边缘透镜折射。
 */
@Stable
class LiquidBackdropState internal constructor(
    internal val layer: GraphicsLayer
) {
    internal var originInRoot: Offset = Offset.Zero
    internal var sizePx: Size = Size.Zero
    internal var layoutCoords: LayoutCoordinates? = null
    internal var hasRecorded: Boolean = false

    /**
     * 轻量级活跃动态信号（由主舞台注入当前动画进度之和）。
     * 仅在玻璃绘制阶段 (Draw-Phase) 读取：静止时 0 重绘，卡片滑动或归位时驱动 HWUI 60/120fps 实时折射刷新。
     */
    internal var motionSignal: (() -> Float)? by mutableStateOf(null)

    fun bindMotionSignal(signal: (() -> Float)?) {
        motionSignal = signal
    }
}

@Composable
fun rememberLiquidBackdropState(): LiquidBackdropState {
    val graphicsLayer = rememberGraphicsLayer()
    return remember(graphicsLayer) { LiquidBackdropState(graphicsLayer) }
}

/**
 * 标记为液态玻璃的底层采样源（必须作为玻璃悬浮控件的兄弟底层，绝不可嵌套包含玻璃控件自身）。
 * [enabled] 为 false 时直通绘制底层内容，彻底跳过离屏 DisplayList 录制（用于大图进出场等纯动画阶段零额外开销）。
 */
fun Modifier.liquidBackdropSource(
    state: LiquidBackdropState,
    enabled: Boolean = true,
    pauseRecording: (() -> Boolean)? = null
): Modifier = this
    .onGloballyPositioned { coords ->
        state.layoutCoords = coords
        val newOrigin = coords.positionInRoot()
        if (kotlin.math.abs(newOrigin.x - state.originInRoot.x) > 0.5f ||
            kotlin.math.abs(newOrigin.y - state.originInRoot.y) > 0.5f
        ) {
            state.originInRoot = newOrigin
        }
        val newSize = Size(coords.size.width.toFloat(), coords.size.height.toFloat())
        if (newSize != state.sizePx) {
            state.sizePx = newSize
        }
    }
    .drawWithContent {
        if (enabled) {
            val shouldPause = pauseRecording?.invoke() == true
            if (!shouldPause || !state.hasRecorded) {
                state.layer.record {
                    this@drawWithContent.drawContent()
                }
                state.hasRecorded = true
                drawLayer(state.layer)
            } else {
                // 顶栏像素静止的纯水平高速滑卡期：直通绘制主舞台，保留已录制真实背景供顶栏玻璃零重录采样
                drawContent()
            }
        } else {
            drawContent()
        }
    }

/**
 * Android 13+ (API 33+) AGSL 真·液态玻璃光学折射着色器
 *
 * 物理光学模型：
 * 1. 基于圆角矩形有向距离场 (SDF) 计算曲面边缘外法线与透镜倒角曲率；
 * 2. 在玻璃边缘倒角区执行凸透镜折射位移 (Refraction)，实时弯曲背景照片纹理；
 * 3. 在高曲率边缘施加克制而晶莹的 R/G/B 棱镜微色散 (Chromatic Dispersion)；
 * 4. 叠加左上主光源高光、右下环境反光与边缘深度微阴影，呈现立体通透的液态水晶质感。
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private const val LIQUID_GLASS_AGSL = """
    uniform shader content;
    uniform float2 resolution;
    uniform float cornerRadius;
    uniform float refractionStrength;
    uniform float dispersion;
    uniform float isDark;

    float sdRoundRect(float2 p, float2 b, float r) {
        float2 q = abs(p) - b + float2(r);
        return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r;
    }

    half4 main(float2 fragCoord) {
        float2 halfSize = resolution * 0.5;
        float r = min(cornerRadius, min(halfSize.x, halfSize.y));
        float2 p = fragCoord - halfSize;

        float d = sdRoundRect(p, halfSize, r);
        float bezelWidth = clamp(min(halfSize.x, halfSize.y) * 0.46, 14.0, 42.0);
        // 0 在中心平坦区，1 在最外侧玻璃曲面倒角处
        float rimT = clamp(1.0 + d / bezelWidth, 0.0, 1.0);
        float lensCurve = rimT * rimT * (3.0 - 2.0 * rimT);

        // 数值梯度求圆角边缘外法线
        float eps = 1.25;
        float gx = sdRoundRect(p + float2(eps, 0.0), halfSize, r) - sdRoundRect(p - float2(eps, 0.0), halfSize, r);
        float gy = sdRoundRect(p + float2(0.0, eps), halfSize, r) - sdRoundRect(p - float2(0.0, eps), halfSize, r);
        float2 normal = normalize(float2(gx, gy) + float2(0.0001));

        // 曲面边缘透镜折射 + 棱镜微色散
        float2 shift = -normal * (lensCurve * refractionStrength);
        float2 uvR = clamp(fragCoord + shift * (1.0 + dispersion), float2(0.5), resolution - float2(0.5));
        float2 uvG = clamp(fragCoord + shift, float2(0.5), resolution - float2(0.5));
        float2 uvB = clamp(fragCoord + shift * (1.0 - dispersion), float2(0.5), resolution - float2(0.5));

        half rCh = content.eval(uvR).r;
        half gCh = content.eval(uvG).g;
        half bCh = content.eval(uvB).b;
        half3 glassRgb = half3(rCh, gCh, bCh);

        // 玻璃折射率导致的极轻饱和度增强，使透出的照片色彩如水晶般润泽
        half luma = dot(glassRgb, half3(0.299, 0.587, 0.114));
        glassRgb = mix(half3(luma), glassRgb, half(isDark > 0.5 ? 1.15 : 1.12));

        // 连续曲面光学渐变（彻底移除内环暗带，改为由左上光源沿整块玻璃平滑过渡至边缘的柔和折射高光）
        float minHalf = max(min(halfSize.x, halfSize.y), 1.0);
        float fullDepth = clamp(1.0 + d / minHalf, 0.0, 1.0);
        float2 lightDir = normalize(float2(-0.58, -0.81));
        float domeWash = clamp(dot(p / minHalf, lightDir) * 0.5 + 0.5, 0.0, 1.0);
        float smoothDome = pow(domeWash, 1.65) * (isDark > 0.5 ? 0.075 : 0.13);
        glassRgb += half3(smoothDome);

        // 宽羽化单段菲涅尔高光：从玻璃内部连续平滑抬升至边缘，绝不产生突兀内外双环
        float smoothEdge = smoothstep(0.32, 1.0, fullDepth);
        smoothEdge = smoothEdge * smoothEdge * (3.0 - 2.0 * smoothEdge);
        float topLeftSpec = pow(max(dot(normal, lightDir), 0.0), 1.55) * smoothEdge;
        float bottomRightSpec = pow(max(dot(normal, -lightDir), 0.0), 1.95) * smoothEdge * 0.48;
        float ambientRim = pow(smoothEdge, 1.8) * (isDark > 0.5 ? 0.18 : 0.28);

        float specAmount = topLeftSpec * (isDark > 0.5 ? 0.38 : 0.56) + bottomRightSpec * 0.30 + ambientRim;
        glassRgb = mix(glassRgb, half3(1.0), half(clamp(specAmount, 0.0, 0.72)));

        return half4(glassRgb, 1.0);
    }
"""

private class CachedGlassEffect {
    var keyW: Int = -1
    var keyH: Int = -1
    var keyBlur: Int = -1
    var keyCorner: Int = -1
    var keyRefract: Int = -1
    var keyDark: Boolean = false
    var effect: ComposeRenderEffect? = null
}

private class BakedGlassCacheState {
    var baked: Boolean = false
    var keyW: Int = -1
    var keyH: Int = -1
    var keyOriginX: Int = Int.MIN_VALUE
    var keyOriginY: Int = Int.MIN_VALUE
    var keyToken: Any? = null
}

/**
 * 真·液态玻璃容器 (LiquidGlassSurface)
 *
 * 分层渲染：
 * - Android 13+ (API 33+)：实时背景采样 -> 硬件高斯模糊 -> AGSL SDF 边缘透镜折射与微色散 -> 水晶着色与镜面高光边；
 * - Android 12 (API 31-32)：实时背景采样 -> 硬件高斯模糊 -> 多层高光玻璃边；
 * - Android 9-11 (API 28-30)：高质感多层梯度半透毛玻璃与镜面高光描边。
 */
@Composable
fun LiquidGlassSurface(
    backdrop: LiquidBackdropState?,
    isDark: Boolean,
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 22.dp,
    blurRadius: Dp = 16.dp,
    refractionStrengthDp: Dp = 12.dp,
    tintColor: Color = if (isDark) Color(0x4C1A1C24) else Color(0x74FFFFFF),
    elevation: Dp = if (isDark) 8.dp else 10.dp,
    onClick: (() -> Unit)? = null,
    pressBounce: Boolean = onClick != null,
    originInRootOverride: (() -> Offset)? = null,
    staticBackdropCache: Boolean = false,
    backdropCacheKey: Any? = null,
    liveBackdropUpdates: (() -> Boolean)? = null,
    contentAlignment: Alignment = Alignment.Center,
    content: @Composable BoxScope.() -> Unit
) {
    val shape = remember(cornerRadius) { RoundedCornerShape(cornerRadius) }
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    // Q 弹果冻按压缩放反馈（按下轻陷、松手带清脆回弹；延后至 graphicsLayer 绘制期读取，按压零重组）
    val pressScaleState = animateFloatAsState(
        targetValue = if (pressBounce && isPressed) 0.91f else 1f,
        animationSpec = spring(
            dampingRatio = 0.52f,
            stiffness = 540f
        ),
        label = "liquid_glass_press"
    )

    val perfTier = rememberDevicePerformanceTier()
    val useAgslShader = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        perfTier != DevicePerformanceTier.LOW

    var selfOriginInRoot by remember { mutableStateOf(Offset.Zero) }
    var relativeToBackdrop by remember { mutableStateOf<Offset?>(null) }
    val selfCoordsRef = remember { arrayOfNulls<LayoutCoordinates>(1) }
    val agslShader = if (backdrop != null && useAgslShader) {
        remember { RuntimeShader(LIQUID_GLASS_AGSL) }
    } else null
    val effectCache = remember { CachedGlassEffect() }
    val sourceGlassLayer = if (staticBackdropCache && backdrop != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        rememberGraphicsLayer()
    } else null
    val bakedGlassLayer = if (staticBackdropCache && backdrop != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        rememberGraphicsLayer()
    } else null
    val bakedCacheState = remember(staticBackdropCache) { BakedGlassCacheState() }

    val rimStops = remember(isDark) {
        if (isDark) {
            listOf(
                Color.White.copy(alpha = 0.42f),
                Color.White.copy(alpha = 0.18f),
                Color.White.copy(alpha = 0.07f),
                Color.White.copy(alpha = 0.12f),
                Color.White.copy(alpha = 0.24f)
            )
        } else {
            listOf(
                Color.White.copy(alpha = 0.96f),
                Color.White.copy(alpha = 0.52f),
                Color.Black.copy(alpha = 0.065f),
                Color.White.copy(alpha = 0.32f),
                Color.White.copy(alpha = 0.78f)
            )
        }
    }

    Box(
        modifier = modifier
            .graphicsLayer {
                val s = pressScaleState.value
                scaleX = s
                scaleY = s
            }
            .then(
                if (originInRootOverride == null) {
                    Modifier.onGloballyPositioned { coords ->
                        selfCoordsRef[0] = coords
                        val bgCoords = backdrop?.layoutCoords
                        if (bgCoords != null && bgCoords.isAttached && coords.isAttached) {
                            // 直接求相对底层采样源的局部坐标，对父页面水平推拉 translationX 完全免疫，转场全程零采样偏移与变色
                            val rel = bgCoords.localPositionOf(coords, Offset.Zero)
                            val prev = relativeToBackdrop
                            if (prev == null ||
                                kotlin.math.abs(rel.x - prev.x) > 0.5f ||
                                kotlin.math.abs(rel.y - prev.y) > 0.5f
                            ) {
                                relativeToBackdrop = rel
                            }
                        } else {
                            val newOrigin = coords.positionInRoot()
                            if (kotlin.math.abs(newOrigin.x - selfOriginInRoot.x) > 0.5f ||
                                kotlin.math.abs(newOrigin.y - selfOriginInRoot.y) > 0.5f
                            ) {
                                selfOriginInRoot = newOrigin
                            }
                        }
                    }
                } else Modifier
            )
            .then(
                if (elevation > 0.dp) {
                    Modifier.shadow(
                        elevation = elevation,
                        shape = shape,
                        clip = false,
                        ambientColor = Color.Black.copy(alpha = if (isDark) 0.36f else 0.09f),
                        spotColor = Color.Black.copy(alpha = if (isDark) 0.52f else 0.15f)
                    )
                } else Modifier
            )
            .clip(shape)
            .then(
                if (onClick != null) {
                    Modifier.clickable(
                        interactionSource = interactionSource,
                        indication = null,
                        onClick = onClick
                    )
                } else Modifier
            ),
        contentAlignment = contentAlignment
    ) {
        // 第 1 层：实时背景采样 + 硬件模糊 + Android 13+ AGSL 真液态折射着色器
        if (backdrop != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (staticBackdropCache && sourceGlassLayer != null && bakedGlassLayer != null) {
                // 硬件分级离屏预烘焙模式：
                // - HIGH（旗舰/中高端）：小胶囊/角标 1.0x 原生全分辨率 AGSL 折射；大浮卡 0.65x 高精 AGSL 折射（视觉无损、单次烘焙 ~3ms）；
                // - MEDIUM（主流中端）：小件 0.65x / 大卡 0.42x AGSL 折射；
                // - LOW（如骁龙 680 / 低内存）：小件 0.35x / 大卡 0.25x 纯硬件高斯模糊（跳过重型 AGSL），确保低端机同样流畅不掉帧。
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .drawWithContent {
                            val w = size.width
                            val h = size.height
                            if (w > 2f && h > 2f) {
                                val isLargeSurface = w >= 480f
                                val downscale = when (perfTier) {
                                    DevicePerformanceTier.HIGH -> if (isLargeSurface) 0.65f else 1.0f
                                    DevicePerformanceTier.MEDIUM -> if (isLargeSurface) 0.42f else 0.65f
                                    DevicePerformanceTier.LOW -> if (isLargeSurface) 0.25f else 0.35f
                                }
                                val sw = (w * downscale).coerceAtLeast(16f)
                                val sh = (h * downscale).coerceAtLeast(16f)
                                val blurPx = (blurRadius.toPx() * downscale).coerceAtLeast(1f)
                                val cornerPx = cornerRadius.toPx() * downscale
                                val refractPx = refractionStrengthDp.toPx() * downscale

                                val iw = sw.roundToInt().coerceAtLeast(16)
                                val ih = sh.roundToInt().coerceAtLeast(16)
                                val ib = blurPx.roundToInt()
                                val ic = cornerPx.roundToInt()
                                val ir = refractPx.roundToInt()

                                val rel = if (originInRootOverride == null) {
                                    relativeToBackdrop ?: run {
                                        val bg = backdrop.layoutCoords
                                        val self = selfCoordsRef[0]
                                        if (bg != null && bg.isAttached && self != null && self.isAttached) {
                                            bg.localPositionOf(self, Offset.Zero).also { relativeToBackdrop = it }
                                        } else null
                                    }
                                } else null
                                val dx: Float
                                val dy: Float
                                val ox: Int
                                val oy: Int
                                if (rel != null) {
                                    dx = -rel.x
                                    dy = -rel.y
                                    ox = rel.x.roundToInt()
                                    oy = rel.y.roundToInt()
                                } else {
                                    val effectiveOrigin = originInRootOverride?.invoke() ?: selfOriginInRoot
                                    dx = backdrop.originInRoot.x - effectiveOrigin.x
                                    dy = backdrop.originInRoot.y - effectiveOrigin.y
                                    ox = effectiveOrigin.x.roundToInt()
                                    oy = effectiveOrigin.y.roundToInt()
                                }

                                val liveUpdate = liveBackdropUpdates?.invoke() == true
                                if (liveUpdate) {
                                    backdrop.motionSignal?.invoke()
                                }

                                val effectChanged = effectCache.effect == null ||
                                    effectCache.keyW != iw ||
                                    effectCache.keyH != ih ||
                                    effectCache.keyBlur != ib ||
                                    effectCache.keyCorner != ic ||
                                    effectCache.keyRefract != ir ||
                                    effectCache.keyDark != isDark

                                if (effectChanged) {
                                    val blurEffect = RenderEffect.createBlurEffect(
                                        blurPx, blurPx, Shader.TileMode.CLAMP
                                    )
                                    effectCache.effect = if (useAgslShader && agslShader != null) {
                                        agslShader.setFloatUniform("resolution", iw.toFloat(), ih.toFloat())
                                        agslShader.setFloatUniform("cornerRadius", cornerPx)
                                        agslShader.setFloatUniform("refractionStrength", refractPx)
                                        agslShader.setFloatUniform("dispersion", if (isDark) 0.18f else 0.14f)
                                        agslShader.setFloatUniform("isDark", if (isDark) 1f else 0f)
                                        val refractEffect = RenderEffect.createRuntimeShaderEffect(agslShader, "content")
                                        RenderEffect.createChainEffect(refractEffect, blurEffect).asComposeRenderEffect()
                                    } else {
                                        blurEffect.asComposeRenderEffect()
                                    }
                                    effectCache.keyW = iw
                                    effectCache.keyH = ih
                                    effectCache.keyBlur = ib
                                    effectCache.keyCorner = ic
                                    effectCache.keyRefract = ir
                                    effectCache.keyDark = isDark
                                }

                                val needsBake = !bakedCacheState.baked ||
                                    liveUpdate ||
                                    effectChanged ||
                                    bakedCacheState.keyW != iw ||
                                    bakedCacheState.keyH != ih ||
                                    bakedCacheState.keyOriginX != ox ||
                                    bakedCacheState.keyOriginY != oy ||
                                    bakedCacheState.keyToken != backdropCacheKey

                                if (needsBake) {
                                    val intSize = IntSize(iw, ih)
                                    val sx = iw.toFloat() / w
                                    val sy = ih.toFloat() / h
                                    sourceGlassLayer.renderEffect = effectCache.effect
                                    sourceGlassLayer.clip = true
                                    sourceGlassLayer.record(size = intSize) {
                                        if (downscale < 0.99f) {
                                            scale(scaleX = sx, scaleY = sy, pivot = Offset.Zero) {
                                                translate(left = dx, top = dy) {
                                                    drawLayer(backdrop.layer)
                                                }
                                            }
                                        } else {
                                            translate(left = dx, top = dy) {
                                                drawLayer(backdrop.layer)
                                            }
                                        }
                                    }
                                    bakedGlassLayer.compositingStrategy = LayerCompositingStrategy.Offscreen
                                    bakedGlassLayer.renderEffect = null
                                    bakedGlassLayer.record(size = intSize) {
                                        drawLayer(sourceGlassLayer)
                                    }
                                    bakedCacheState.baked = true
                                    bakedCacheState.keyW = iw
                                    bakedCacheState.keyH = ih
                                    bakedCacheState.keyOriginX = ox
                                    bakedCacheState.keyOriginY = oy
                                    bakedCacheState.keyToken = backdropCacheKey
                                }

                                if (downscale < 0.99f) {
                                    scale(
                                        scaleX = w / iw.toFloat(),
                                        scaleY = h / ih.toFloat(),
                                        pivot = Offset.Zero
                                    ) {
                                        drawLayer(bakedGlassLayer)
                                    }
                                } else {
                                    drawLayer(bakedGlassLayer)
                                }
                            }
                        }
                )
            } else {
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .graphicsLayer {
                            val w = size.width
                            val h = size.height
                            if (w > 2f && h > 2f) {
                                val blurPx = blurRadius.toPx().coerceAtLeast(1f)
                                val cornerPx = cornerRadius.toPx()
                                val refractPx = refractionStrengthDp.toPx()

                                val iw = w.roundToInt()
                                val ih = h.roundToInt()
                                val ib = blurPx.roundToInt()
                                val ic = cornerPx.roundToInt()
                                val ir = refractPx.roundToInt()

                                if (effectCache.effect == null ||
                                    effectCache.keyW != iw ||
                                    effectCache.keyH != ih ||
                                    effectCache.keyBlur != ib ||
                                    effectCache.keyCorner != ic ||
                                    effectCache.keyRefract != ir ||
                                    effectCache.keyDark != isDark
                                ) {
                                    val blurEffect = RenderEffect.createBlurEffect(
                                        blurPx, blurPx, Shader.TileMode.CLAMP
                                    )
                                    effectCache.effect = if (useAgslShader && agslShader != null) {
                                        agslShader.setFloatUniform("resolution", w, h)
                                        agslShader.setFloatUniform("cornerRadius", cornerPx)
                                        agslShader.setFloatUniform("refractionStrength", refractPx)
                                        agslShader.setFloatUniform("dispersion", if (isDark) 0.18f else 0.14f)
                                        agslShader.setFloatUniform("isDark", if (isDark) 1f else 0f)
                                        val refractEffect = RenderEffect.createRuntimeShaderEffect(agslShader, "content")
                                        RenderEffect.createChainEffect(refractEffect, blurEffect).asComposeRenderEffect()
                                    } else {
                                        blurEffect.asComposeRenderEffect()
                                    }
                                    effectCache.keyW = iw
                                    effectCache.keyH = ih
                                    effectCache.keyBlur = ib
                                    effectCache.keyCorner = ic
                                    effectCache.keyRefract = ir
                                    effectCache.keyDark = isDark
                                }
                                renderEffect = effectCache.effect
                            }
                        }
                        .drawWithContent {
                            // 仅在 Draw-Phase 读取动态信号，确保滑卡时玻璃实时折射最新画面，绝不截断模糊邻域导致发暗变色
                            backdrop.motionSignal?.invoke()
                            val rel = if (originInRootOverride == null) {
                                relativeToBackdrop ?: run {
                                    val bg = backdrop.layoutCoords
                                    val self = selfCoordsRef[0]
                                    if (bg != null && bg.isAttached && self != null && self.isAttached) {
                                        bg.localPositionOf(self, Offset.Zero).also { relativeToBackdrop = it }
                                    } else null
                                }
                            } else null
                            val dx: Float
                            val dy: Float
                            if (rel != null) {
                                dx = -rel.x
                                dy = -rel.y
                            } else {
                                val effectiveOrigin = originInRootOverride?.invoke() ?: selfOriginInRoot
                                dx = backdrop.originInRoot.x - effectiveOrigin.x
                                dy = backdrop.originInRoot.y - effectiveOrigin.y
                            }
                            translate(left = dx, top = dy) {
                                drawLayer(backdrop.layer)
                            }
                        }
                )
            }
        }

        // 第 2 层：平滑连续曲面光感渐变 + 单层柔光斜角镜面边（彻底告别突兀内外双圈）
        Box(
            modifier = Modifier
                .matchParentSize()
                .drawWithCache {
                    val w = size.width.coerceAtLeast(1f)
                    val h = size.height.coerceAtLeast(1f)
                    val r = cornerRadius.toPx().coerceAtMost(size.minDimension * 0.5f)
                    val corner = CornerRadius(r, r)

                    // 1. 柔和连续的对角玻璃体渐变（左上通透受光 -> 中段自然过渡 -> 右下沉静收尾）
                    val bodyBrush = if (backdrop != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        Brush.linearGradient(
                            colorStops = arrayOf(
                                0.00f to tintColor.copy(alpha = (tintColor.alpha * 1.16f).coerceAtMost(0.90f)),
                                0.45f to tintColor.copy(alpha = (tintColor.alpha * 0.88f).coerceAtLeast(0.14f)),
                                1.00f to tintColor.copy(alpha = (tintColor.alpha * 0.72f).coerceAtLeast(0.12f))
                            ),
                            start = Offset(0f, 0f),
                            end = Offset(w, h)
                        )
                    } else {
                        Brush.linearGradient(
                            colors = if (isDark) {
                                listOf(Color(0xDE2A2D38), Color(0xD21D1F28), Color(0xD615171E))
                            } else {
                                listOf(Color(0xF0FFFFFF), Color(0xE2F7F7F9), Color(0xDCF0F1F4))
                            },
                            start = Offset(0f, 0f),
                            end = Offset(w, h)
                        )
                    }

                    // 2. 左上曲面径向柔光穹顶漫反射，使圆形与胶囊液态玻璃自然呈现出丝滑球面渐变
                    val domeGlowBrush = Brush.radialGradient(
                        colors = listOf(
                            Color.White.copy(alpha = if (isDark) 0.14f else 0.46f),
                            Color.White.copy(alpha = if (isDark) 0.04f else 0.14f),
                            Color.Transparent
                        ),
                        center = Offset(w * 0.28f, h * 0.16f),
                        radius = maxOf(w, h) * 0.92f
                    )

                    // 3. 单层五段平滑渐隐镜面斜角描边（绝不画第二圈硬内环）
                    val rimBrush = Brush.linearGradient(
                        colors = rimStops,
                        start = Offset(0f, 0f),
                        end = Offset(w, h)
                    )
                    val rimStroke = Stroke(width = 0.85.dp.toPx())

                    onDrawWithContent {
                        drawRect(brush = bodyBrush)
                        drawRect(brush = domeGlowBrush)
                        drawContent()
                        drawRoundRect(
                            brush = rimBrush,
                            cornerRadius = corner,
                            style = rimStroke
                        )
                    }
                }
        )

        // 第 3 层：前景图标 / 文案内容
        content()
    }
}
