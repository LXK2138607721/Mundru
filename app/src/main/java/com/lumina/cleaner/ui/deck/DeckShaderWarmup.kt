package com.lumina.cleaner.ui.deck

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.HardwareRenderer
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.hardware.HardwareBuffer
import android.media.ImageReader
import android.os.Build
import android.os.Trace
import androidx.annotation.RequiresApi
import com.lumina.cleaner.ui.glass.DevicePerformanceClassifier
import com.lumina.cleaner.ui.glass.DevicePerformanceTier
import com.lumina.cleaner.ui.glass.LIQUID_VORTEX_AGSL
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Prime HWUI's blur & AGSL vortex programs with a synthetic image, never a user's photo.
 * Called from a background coroutine while the first library batch is loading or settling.
 */
@RequiresApi(Build.VERSION_CODES.S)
internal object DeckShaderWarmup {
    private val attempted = AtomicBoolean(false)
    private val vortexAttempted = AtomicBoolean(false)
    private const val SIDE = 128
    private const val COLUMNS = 4
    private val radiiDp = floatArrayOf(0.6f, 0.9f, 1.2f, 1.8f, 2.6f, 3.8f, 5.1f, 6.8f, 8.8f, 10.3f, 12f, 16f)

    /**
     * 每进程静默离屏预热一次 AGSL 漩涡着色器管线（32×32 合成纯色图，绝不触碰用户相片），
     * 消除杀后台冷启动后首次切换模式时 RenderThread 同步编译 AGSL 引发的 15~20ms 首帧尖峰。
     */
    fun prepareVortexShader(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (DevicePerformanceClassifier.tier(context) == DevicePerformanceTier.LOW) return
        if (!vortexAttempted.compareAndSet(false, true)) return

        Trace.beginSection("Mundru:warmup-vortex-agsl")
        try {
            val size = 32
            val picture = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            Canvas(picture).drawColor(0xFF202228.toInt())
            val reader = ImageReader.newInstance(
                size, size, PixelFormat.RGBA_8888, 2,
                HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or HardwareBuffer.USAGE_GPU_COLOR_OUTPUT
            )
            try {
                val renderer = HardwareRenderer()
                try {
                    renderer.setSurface(reader.surface)
                    val root = RenderNode("MundruVortexWarmupRoot")
                    root.setPosition(0, 0, size, size)
                    val shader = RuntimeShader(LIQUID_VORTEX_AGSL).apply {
                        setFloatUniform("resolution", size.toFloat(), size.toFloat())
                        setFloatUniform("progress", 0.45f)
                        setFloatUniform("direction", 1f)
                        setFloatUniform("isDark", 1f)
                    }
                    root.setRenderEffect(RenderEffect.createRuntimeShaderEffect(shader, "content"))
                    root.beginRecording().apply {
                        drawBitmap(picture, 0f, 0f, null)
                    }
                    root.endRecording()
                    renderer.setContentRoot(root)
                    val result = renderer.createRenderRequest().setWaitForPresent(true).syncAndDraw()
                    if (result == HardwareRenderer.SYNC_OK) {
                        reader.acquireNextImage()?.close()
                    }
                } finally {
                    renderer.destroy()
                }
            } finally {
                reader.close()
                picture.recycle()
            }
        } catch (_: RuntimeException) {
            // 离屏预热失败不影响主渲染管线
        } finally {
            Trace.endSection()
        }
    }

    fun prepare(context: Context, density: Float, stillLoading: () -> Boolean) {
        if (!stillLoading() || !attempted.compareAndSet(false, true)) return
        val prefs = context.getSharedPreferences("mundru_render_cache", Context.MODE_PRIVATE)
        val updated = try {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
        } catch (_: PackageManager.NameNotFoundException) {
            return
        }
        val stamp = "$updated:${Build.FINGERPRINT}:$density"
        if (prefs.getString("blur_warmup", null) == stamp || !stillLoading()) return

        Trace.beginSection("Mundru:warmup-offscreen")
        try {
            // Do not re-pay a partial warmup on the next launch if loading finished early.
            if (renderSamples(density, stillLoading)) {
                prefs.edit().putString("blur_warmup", stamp).apply()
            }
        } catch (_: RuntimeException) {
            // Offscreen surfaces are optional; the regular window renderer remains in use.
        } finally {
            Trace.endSection()
        }
    }

    private fun renderSamples(density: Float, stillLoading: () -> Boolean): Boolean {
        val picture = Bitmap.createBitmap(SIDE, SIDE, Bitmap.Config.ARGB_8888)
        Canvas(picture).apply {
            drawColor(0xff5c2438.toInt())
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xfff2f5f3.toInt() }
            drawRect(15f, 36f, 94f, 94f, paint)
        }
        val reader = ImageReader.newInstance(SIDE * COLUMNS, SIDE, PixelFormat.RGBA_8888, 2,
            HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or HardwareBuffer.USAGE_GPU_COLOR_OUTPUT)
        try {
            val renderer = HardwareRenderer()
            try {
                renderer.setSurface(reader.surface)
                val root = RenderNode("MundruBlurWarmupRoot")
                root.setPosition(0, 0, SIDE * COLUMNS, SIDE)
                renderer.setContentRoot(root)
                var rendered = false
                for (start in radiiDp.indices step COLUMNS) {
                    if (!stillLoading()) break
                    val canvas = root.beginRecording()
                    canvas.drawColor(Color.WHITE)
                    for (column in 0 until COLUMNS) {
                        val node = RenderNode("MundruBlurWarmupFace")
                        val x = column * SIDE
                        node.setPosition(x, 0, x + SIDE, SIDE)
                        val radius = radiiDp[start + column] * density
                        node.setRenderEffect(RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP))
                        node.setOutline(Outline().apply { setRoundRect(0, 0, SIDE, SIDE, 24f * density) })
                        node.clipToOutline = true
                        node.scaleX = 0.82f
                        node.scaleY = 0.82f
                        node.rotationZ = 7f
                        node.beginRecording().drawBitmap(picture, 0f, 0f, null)
                        node.endRecording()
                        canvas.drawRenderNode(node)
                    }
                    root.endRecording()
                    if (!stillLoading()) break
                    val result = renderer.createRenderRequest().setWaitForPresent(true).syncAndDraw()
                    if (result != HardwareRenderer.SYNC_OK) break
                    rendered = true
                    // A submitted buffer must be consumed before the next group.
                    val frame = reader.acquireNextImage() ?: break
                    frame.close()
                }
                return rendered
            } finally {
                renderer.destroy()
            }
        } finally {
            reader.close()
        }
    }
}
