package com.lumina.cleaner

import android.app.Application
import android.os.Build
import coil.Coil
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import com.lumina.cleaner.core.database.PhotoWriteQueue
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
 * 3. 深度注入 Coil GIF 动图解码管线（原生 ImageDecoderDecoder + 全版本 GifDecoder），支持 GIF 动图流畅播放；
 * 4. 极速冷启动，无冗余 SDK 拦截。
 */
class MundruApp : Application(), ImageLoaderFactory {
    // Accepted database writes outlive Activity recreation; no UI work belongs to this scope.
    internal val photoWrites = PhotoWriteQueue(CoroutineScope(SupervisorJob() + Dispatchers.IO))

    override fun onCreate() {
        super.onCreate()
        // 双重确保全局 ImageLoader 立即挂载并支持 GIF 动图解析播放
        Coil.setImageLoader(this)
    }

    /**
     * 构建全局 Coil ImageLoader，注入 GIF 动图解码器组件
     */
    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            .components {
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
