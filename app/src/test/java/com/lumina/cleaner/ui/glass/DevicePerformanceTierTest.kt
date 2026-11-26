package com.lumina.cleaner.ui.glass

import org.junit.Assert.assertEquals
import org.junit.Test

class DevicePerformanceTierTest {

    @Test
    fun `snapdragon 8 gen 1 flagship classifies as high`() {
        val signals = DeviceHardwareSignals(
            sdkInt = 35,
            isLowRamDevice = false,
            totalRamMb = 7297L,
            cpuCores = 8,
            maxCpuFreqMHz = 2995,
            mediaPerformanceClass = 31,
            socModel = "SM8450"
        )
        assertEquals(DevicePerformanceTier.HIGH, DevicePerformanceClassifier.classify(signals))
    }

    @Test
    fun `snapdragon 680 classifies as low even with 8gb ram`() {
        val signals = DeviceHardwareSignals(
            sdkInt = 33,
            isLowRamDevice = false,
            totalRamMb = 7400L,
            cpuCores = 8,
            maxCpuFreqMHz = 2400,
            mediaPerformanceClass = 0,
            socModel = "SM6225"
        )
        assertEquals(DevicePerformanceTier.LOW, DevicePerformanceClassifier.classify(signals))
    }

    @Test
    fun `midrange snapdragon 778g classifies as medium`() {
        val signals = DeviceHardwareSignals(
            sdkInt = 33,
            isLowRamDevice = false,
            totalRamMb = 7400L,
            cpuCores = 8,
            maxCpuFreqMHz = 2400,
            mediaPerformanceClass = 0,
            socModel = "SM7325"
        )
        assertEquals(DevicePerformanceTier.MEDIUM, DevicePerformanceClassifier.classify(signals))
    }

    @Test
    fun `unknown future flagship chip with high clock and ram classifies as high`() {
        val signals = DeviceHardwareSignals(
            sdkInt = 35,
            isLowRamDevice = false,
            totalRamMb = 11500L,
            cpuCores = 8,
            maxCpuFreqMHz = 3300,
            mediaPerformanceClass = 0,
            socModel = "FUTURE_SOC_X1"
        )
        assertEquals(DevicePerformanceTier.HIGH, DevicePerformanceClassifier.classify(signals))
    }

    @Test
    fun `4gb low ram device classifies as low`() {
        val signals = DeviceHardwareSignals(
            sdkInt = 34,
            isLowRamDevice = false,
            totalRamMb = 3800L,
            cpuCores = 8,
            maxCpuFreqMHz = 2600,
            mediaPerformanceClass = 0,
            socModel = "UNKNOWN"
        )
        assertEquals(DevicePerformanceTier.LOW, DevicePerformanceClassifier.classify(signals))
    }
}
