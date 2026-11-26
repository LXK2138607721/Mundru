package com.lumina.cleaner.ui.onboarding

import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.RenderEffect as ComposeRenderEffect
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

@Immutable
internal data class DemoArtCard(
    val serial: Int,
    val title: String,
    val spec: String,
    val badge: String?,
    val palette: List<Color>,
    val accentGlow: Color,
    val style: Int
)

private val demoArtTemplates = listOf(
    DemoArtCard(0, "落日岩壁", "35mm · f/1.8", "LIVE", listOf(Color(0xFF2B241F), Color(0xFF5D4433), Color(0xFFBA8A64)), Color(0xFFE9BF93), 0),
    DemoArtCard(1, "雾山晨光", "27mm · f/2.0", "HDR", listOf(Color(0xFF1D2021), Color(0xFF404645), Color(0xFFA2A8A3)), Color(0xFFE0E5DF), 1),
    DemoArtCard(2, "银盐暗房", "50mm · f/1.4", null, listOf(Color(0xFF141518), Color(0xFF33353A), Color(0xFF7D8088)), Color(0xFFD0D2D8), 2),
    DemoArtCard(3, "秋日暖窗", "24mm · f/1.9", "LIVE", listOf(Color(0xFF29211B), Color(0xFF6F5642), Color(0xFFD6B790)), Color(0xFFF3E2C6), 0),
    DemoArtCard(4, "苔原松影", "40mm · f/2.2", "HDR", listOf(Color(0xFF181E1B), Color(0xFF394840), Color(0xFF8B9A90)), Color(0xFFCDE0D3), 1),
    DemoArtCard(5, "暮色沙丘", "85mm · f/2.0", null, listOf(Color(0xFF241D1B), Color(0xFF5B453E), Color(0xFFB38E7D)), Color(0xFFE8C8B8), 2)
)

internal fun demoCardAt(virtualIndex: Int): DemoArtCard {
    val size = demoArtTemplates.size
    val mod = ((virtualIndex % size) + size) % size
    return demoArtTemplates[mod].copy(serial = virtualIndex)
}

internal class DemoBlurEffectCache(private val density: Float) {
    private val cache = HashMap<Int, ComposeRenderEffect>(16)

    fun effectForDp(blurDp: Float): ComposeRenderEffect? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || blurDp < 0.35f) return null
        val bucket = (blurDp * 2f).roundToInt().coerceIn(1, 16)
        return cache.getOrPut(bucket) {
            val px = (bucket * 0.5f) * density
            RenderEffect.createBlurEffect(px, px, Shader.TileMode.CLAMP).asComposeRenderEffect()
        }
    }
}

@Composable
internal fun DemoArtCardView(
    card: DemoArtCard,
    blurCache: DemoBlurEffectCache,
    blurDpProvider: () -> Float,
    trashGlowProvider: () -> Float,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val shape = remember { RoundedCornerShape(22.dp) }
    val trashRed = MaterialTheme.colorScheme.error
    val normalRimStroke = remember(density) { Stroke(width = with(density) { 0.75.dp.toPx() }) }
    val glowRimStroke = remember(density) { Stroke(width = with(density) { 2.dp.toPx() }) }

    Box(
        modifier = modifier
            .aspectRatio(0.76f)
            .shadow(
                elevation = 12.dp,
                shape = shape,
                clip = false,
                ambientColor = Color.Black.copy(alpha = 0.09f),
                spotColor = Color.Black.copy(alpha = 0.14f)
            )
            .clip(shape)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    compositingStrategy = CompositingStrategy.Offscreen
                    renderEffect = blurCache.effectForDp(blurDpProvider())
                }
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawRect(brush = Brush.verticalGradient(card.palette))
                val sunCenter = when (card.style) {
                    0 -> Offset(size.width * 0.68f, size.height * 0.30f)
                    1 -> Offset(size.width * 0.34f, size.height * 0.26f)
                    else -> Offset(size.width * 0.50f, size.height * 0.36f)
                }
                val glowRadius = size.width * 0.56f
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            card.accentGlow.copy(alpha = 0.74f),
                            card.accentGlow.copy(alpha = 0.16f),
                            Color.Transparent
                        ),
                        center = sunCenter,
                        radius = glowRadius
                    ),
                    radius = glowRadius,
                    center = sunCenter
                )
                val ridge = Path().apply {
                    when (card.style) {
                        0 -> {
                            moveTo(0f, size.height * 0.72f)
                            quadraticTo(size.width * 0.38f, size.height * 0.52f, size.width * 0.76f, size.height * 0.66f)
                            lineTo(size.width, size.height * 0.60f)
                            lineTo(size.width, size.height)
                            lineTo(0f, size.height)
                            close()
                        }
                        1 -> {
                            moveTo(0f, size.height * 0.65f)
                            quadraticTo(size.width * 0.50f, size.height * 0.50f, size.width, size.height * 0.74f)
                            lineTo(size.width, size.height)
                            lineTo(0f, size.height)
                            close()
                        }
                        else -> {
                            moveTo(size.width * 0.18f, size.height)
                            lineTo(size.width * 0.44f, size.height * 0.50f)
                            lineTo(size.width, size.height * 0.76f)
                            lineTo(size.width, size.height)
                            close()
                        }
                    }
                }
                drawPath(path = ridge, color = Color.Black.copy(alpha = 0.32f))
                drawRect(
                    brush = Brush.verticalGradient(
                        colorStops = arrayOf(
                            0.0f to Color.Transparent,
                            0.58f to Color.Transparent,
                            1.0f to Color.Black.copy(alpha = 0.58f)
                        )
                    )
                )
            }

            if (card.badge != null) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(10.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0x660E1016))
                        .border(0.5.dp, Color.White.copy(alpha = 0.20f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 7.dp, vertical = 3.dp)
                ) {
                    Text(
                        text = card.badge,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.5.sp,
                        color = Color.White.copy(alpha = 0.92f)
                    )
                }
            }

            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(horizontal = 13.dp, vertical = 12.dp)
            ) {
                Text(
                    text = card.title,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White.copy(alpha = 0.94f)
                )
                Text(
                    text = card.spec,
                    style = TextStyle(fontFeatureSettings = "tnum"),
                    fontSize = 10.sp,
                    color = Color.White.copy(alpha = 0.62f)
                )
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .drawBehind {
                    val glow = trashGlowProvider().coerceIn(0f, 1f)
                    val r = 22.dp.toPx()
                    if (glow > 0.02f) {
                        drawRect(trashRed.copy(alpha = glow * 0.14f))
                        drawRoundRect(
                            color = trashRed.copy(alpha = glow * 0.90f),
                            cornerRadius = CornerRadius(r, r),
                            style = glowRimStroke
                        )
                    } else {
                        drawRoundRect(
                            color = Color.White.copy(alpha = 0.18f),
                            cornerRadius = CornerRadius(r, r),
                            style = normalRimStroke
                        )
                    }
                }
        )
    }
}
