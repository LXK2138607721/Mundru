package com.lumina.cleaner.ui.glass

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import java.io.File

/**
 * 纯本地离线设备渲染性能分级（0 联网、0 权限，进程级缓存一次）：
 * - [HIGH]：旗舰 / 中高端芯片（如骁龙 8 Gen 1/2/3/Elite、骁龙 7+ Gen 2/3、天玑 8300+/9000+、麒麟 9000s+），
 *   满血启用高精 AGSL 液态玻璃折射、棱镜色散与光学漩涡，绝不牺牲旗舰观感；
 * - [MEDIUM]：主流中端芯片（如骁龙 778G / 7 Gen 1、天玑 7050 等），保持真 AGSL 折射并适度压缩大卡预烘焙分辨率；
 * - [LOW]：入门能效芯片或低内存设备（如骁龙 680 SM6225、Helio G 系列、≤4GB 内存或旧系统），
 *   自动下调离屏烘焙倍率并改用轻量硬件模糊/纯 GPU 几何转场，确保低端机型同样稳满帧。
 */
enum class DevicePerformanceTier {
    HIGH,
    MEDIUM,
    LOW
}

internal data class DeviceHardwareSignals(
    val sdkInt: Int,
    val isLowRamDevice: Boolean,
    val totalRamMb: Long,
    val cpuCores: Int,
    val maxCpuFreqMHz: Int,
    val mediaPerformanceClass: Int,
    val socModel: String
)

object DevicePerformanceClassifier {
    @Volatile
    private var cachedTier: DevicePerformanceTier? = null

    private val knownLowSocTokens = listOf(
        "SM6225", // Snapdragon 680 / 685
        "SM6115", // Snapdragon 662
        "SM6125", // Snapdragon 665
        "SM4350", // Snapdragon 480
        "SM4375", // Snapdragon 4 Gen 1
        "SM4450", // Snapdragon 4 Gen 2
        "SDM660",
        "SDM450",
        "MT6761",
        "MT6762",
        "MT6765", // Helio P35 / G35
        "MT6768", // Helio G70 / G80 / G85
        "MT6769",
        "MT6781", // Helio G96
        "MT6789", // Helio G99
        "T606",
        "T612",
        "T616",
        "SC9863"
    )

    private val knownHighSocTokens = listOf(
        "SM8350", // Snapdragon 888
        "SM8450", // Snapdragon 8 Gen 1
        "SM8475", // Snapdragon 8+ Gen 1
        "SM8550", // Snapdragon 8 Gen 2
        "SM8650", // Snapdragon 8 Gen 3
        "SM8635", // Snapdragon 8s Gen 3
        "SM8750", // Snapdragon 8 Elite
        "SM8850", // Snapdragon 8 Elite Gen 2
        "SM7475", // Snapdragon 7+ Gen 2
        "SM7675", // Snapdragon 7+ Gen 3
        "MT6983", // Dimensity 9000
        "MT6985", // Dimensity 9200
        "MT6989", // Dimensity 9300
        "MT6991", // Dimensity 9400
        "MT6895", // Dimensity 8100 / 8200
        "MT6897", // Dimensity 8300
        "MT6899", // Dimensity 8400
        "KIRIN9000",
        "KIRIN9010",
        "KIRIN9020",
        "TENSOR G3",
        "TENSOR G4",
        "EXYNOS 2200",
        "EXYNOS 2400",
        "S5E9925",
        "S5E9945"
    )

    fun tier(context: Context): DevicePerformanceTier {
        cachedTier?.let { return it }
        val resolved = classify(readSignals(context.applicationContext))
        cachedTier = resolved
        return resolved
    }

    internal fun classify(signals: DeviceHardwareSignals): DevicePerformanceTier {
        val soc = signals.socModel.trim().uppercase()

        if (signals.isLowRamDevice || signals.totalRamMb in 1L..4200L || signals.cpuCores in 1..5) {
            return DevicePerformanceTier.LOW
        }
        if (soc.isNotEmpty() && knownLowSocTokens.any { soc.contains(it) }) {
            return DevicePerformanceTier.LOW
        }
        if (signals.mediaPerformanceClass >= 33) {
            return DevicePerformanceTier.HIGH
        }
        if (soc.isNotEmpty() && knownHighSocTokens.any { soc.contains(it) } && signals.totalRamMb >= 5600L) {
            return DevicePerformanceTier.HIGH
        }
        if (signals.sdkInt >= Build.VERSION_CODES.S &&
            signals.totalRamMb >= 6800L &&
            signals.maxCpuFreqMHz >= 2750 &&
            signals.cpuCores >= 8
        ) {
            return DevicePerformanceTier.HIGH
        }
        if (signals.sdkInt >= Build.VERSION_CODES.S &&
            signals.totalRamMb >= 5200L &&
            signals.maxCpuFreqMHz >= 2200 &&
            signals.cpuCores >= 6
        ) {
            return DevicePerformanceTier.MEDIUM
        }
        return DevicePerformanceTier.LOW
    }

    private fun readSignals(context: Context): DeviceHardwareSignals {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        val totalRamMb = if (am != null) {
            am.getMemoryInfo(memInfo)
            memInfo.totalMem / (1024L * 1024L)
        } else {
            0L
        }
        val isLowRam = am?.isLowRamDevice == true
        val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        val maxFreqMHz = readMaxCpuFreqMHz(cores)
        val perfClass = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Build.VERSION.MEDIA_PERFORMANCE_CLASS
        } else {
            0
        }
        val socModel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Build.SOC_MODEL.orEmpty()
        } else {
            Build.HARDWARE.orEmpty()
        }
        return DeviceHardwareSignals(
            sdkInt = Build.VERSION.SDK_INT,
            isLowRamDevice = isLowRam,
            totalRamMb = totalRamMb,
            cpuCores = cores,
            maxCpuFreqMHz = maxFreqMHz,
            mediaPerformanceClass = perfClass,
            socModel = socModel
        )
    }

    private fun readMaxCpuFreqMHz(cores: Int): Int {
        var maxKhz = 0
        for (i in 0 until cores.coerceAtMost(16)) {
            val file = File("/sys/devices/system/cpu/cpu$i/cpufreq/cpuinfo_max_freq")
            val khz = runCatching {
                if (file.canRead()) file.readText().trim().toIntOrNull() else null
            }.getOrNull() ?: continue
            if (khz > maxKhz) maxKhz = khz
        }
        return maxKhz / 1000
    }
}

@Composable
internal fun rememberDevicePerformanceTier(): DevicePerformanceTier {
    val context = LocalContext.current
    return remember(context) { DevicePerformanceClassifier.tier(context) }
}
