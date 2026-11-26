package com.lumina.cleaner

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.os.Build
import coil.Coil
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import coil.memory.MemoryCache
import com.lumina.cleaner.core.database.MundruDatabase
import com.lumina.cleaner.core.database.PhotoWriteQueue
import com.lumina.cleaner.core.database.ReviewWriteCoalescer
import com.lumina.cleaner.ui.deck.DeckFastThumbInterceptor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Mundru 应用程序主入口 (Application)
 *
 * 品牌释义：Mundru 源自格陵兰语（Greenlandic），意为「擦掉 / 擦除」。
 * 核心契约：
 * 1. 坚守 0 联网权限（AndroidManifest.xml 完全不声明 INTERNET 权限）；
 * 2. 100% 纯本地离线相册极简整理与实况照片管理；
 * 3. 深度注入 Coil GIF 动图解码管线与显存封顶策略，杜绝连续快滑多批导致硬件纹理暴涨与 GC 抖动；
 * 4. 极速冷启动，无冗余 SDK 拦截。
 */
class MundruApp : Application(), ImageLoaderFactory {
    // Accepted database writes outlive Activity recreation; no UI work belongs to this scope.
    private val appWriteScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    internal val photoWrites = PhotoWriteQueue(appWriteScope)
    internal val reviewCoalescer: ReviewWriteCoalescer by lazy {
        val dao = MundruDatabase.getInstance(this).photoDao()
        ReviewWriteCoalescer(
            writeQueue = photoWrites,
            scope = appWriteScope,
            commitBatch = { events -> dao.markViewedEvents(events) }
        )
    }

    override fun onCreate() {
        super.onCreate()
        // 双重确保全局 ImageLoader 立即挂载并支持 GIF 动图解析播放
        Coil.setImageLoader(this)
    }

    /**
     * 构建全局 Coil ImageLoader：
     * - 显式将强引用显存缓存封顶在 32MB~64MB（可同时驻留 10~14 张完整卡片，涵盖当前栈 + 撤回 + 预解码），
     *   并关闭 weakReferencesEnabled，彻底避免旧批次 Hardware Bitmap 滞留 Gralloc 涨破 260MB 触发 200ms Explicit GC；
     * - 限制并发 BitmapFactory 解码数为 2，保障极速连滑期间 UI/RenderThread 核心不被后台解码抢满。
     */
    override fun newImageLoader(): ImageLoader {
        val am = getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val memClassMb = (am?.memoryClass ?: 256).coerceAtLeast(128)
        val maxCacheBytes = (memClassMb * 1024L * 1024L / 5L)
            .coerceIn(32L * 1024L * 1024L, 64L * 1024L * 1024L)
            .toInt()

        return ImageLoader.Builder(this)
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizeBytes(maxCacheBytes)
                    .weakReferencesEnabled(false)
                    .build()
            }
            .bitmapFactoryMaxParallelism(2)
            .components {
                add(DeckFastThumbInterceptor(this@MundruApp))
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    add(ImageDecoderDecoder.Factory())
                } else {
                    add(GifDecoder.Factory())
                }
            }
            .respectCacheHeaders(false) // 纯本地相册媒体库无需 HTTP 缓存头协商
            .build()
    }
}

// 兼容别名
typealias LuminaApp = MundruApp
