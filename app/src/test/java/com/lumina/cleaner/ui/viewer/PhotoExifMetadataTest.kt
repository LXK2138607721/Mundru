package com.lumina.cleaner.ui.viewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotoExifMetadataTest {

    @Test
    fun formatCameraLabel_deduplicatesBrandAndHandlesApple() {
        assertEquals("HONOR LGE-AN00", PhotoExifMetadata.formatCameraLabel("HONOR", "LGE-AN00"))
        assertEquals("HONOR Magic6 Pro", PhotoExifMetadata.formatCameraLabel("HONOR", "HONOR Magic6 Pro"))
        assertEquals("iPhone 16 Pro", PhotoExifMetadata.formatCameraLabel("Apple", "iPhone 16 Pro"))
        assertEquals("LEICA Q3", PhotoExifMetadata.formatCameraLabel("Leica", "LEICA Q3"))
        assertEquals("Pixel 9 Pro", PhotoExifMetadata.formatCameraLabel(null, "  Pixel 9 Pro  "))
        assertNull(PhotoExifMetadata.formatCameraLabel("  ", null))
    }

    @Test
    fun formatFocalLength_prefers35mmEquivalentAndFallsBackToPhysical() {
        assertEquals("24mm", PhotoExifMetadata.formatFocalLength(24, 5.56))
        assertEquals("50mm", PhotoExifMetadata.formatFocalLength(50, 0.0))
        assertEquals("5.6mm", PhotoExifMetadata.formatFocalLength(0, 5.56))
        assertEquals("28mm", PhotoExifMetadata.formatFocalLength(0, 28.02))
        assertNull(PhotoExifMetadata.formatFocalLength(0, -1.0))
        assertNull(PhotoExifMetadata.formatFocalLength(0, 0.0))
    }

    @Test
    fun formatAperture_formatsWholeAndDecimalStops() {
        assertEquals("f/1.9", PhotoExifMetadata.formatAperture(1.89))
        assertEquals("f/2", PhotoExifMetadata.formatAperture(2.0))
        assertEquals("f/2.8", PhotoExifMetadata.formatAperture(2.8))
        assertNull(PhotoExifMetadata.formatAperture(-1.0))
        assertNull(PhotoExifMetadata.formatAperture(0.0))
    }

    @Test
    fun formatShutterSpeed_formatsFastFractionAndLongExposure() {
        assertEquals("1/120s", PhotoExifMetadata.formatShutterSpeed(1.0 / 120.0))
        assertEquals("1/1000s", PhotoExifMetadata.formatShutterSpeed(0.001))
        assertEquals("0.5s", PhotoExifMetadata.formatShutterSpeed(0.5))
        assertEquals("2s", PhotoExifMetadata.formatShutterSpeed(2.0))
        assertEquals("1.3s", PhotoExifMetadata.formatShutterSpeed(1.3))
        assertNull(PhotoExifMetadata.formatShutterSpeed(-1.0))
        assertNull(PhotoExifMetadata.formatShutterSpeed(0.0))
    }

    @Test
    fun formatAspectRatioLabel_identifiesCommonPhotographyRatios() {
        assertEquals("4:3", PhotoExifMetadata.formatAspectRatioLabel(4096, 3072))
        assertEquals("3:4", PhotoExifMetadata.formatAspectRatioLabel(3072, 4096))
        assertEquals("16:9", PhotoExifMetadata.formatAspectRatioLabel(3840, 2160))
        assertEquals("9:16", PhotoExifMetadata.formatAspectRatioLabel(1080, 1920))
        assertEquals("1:1", PhotoExifMetadata.formatAspectRatioLabel(2048, 2048))
        assertEquals("20:9", PhotoExifMetadata.formatAspectRatioLabel(2664, 1224))
        assertNull(PhotoExifMetadata.formatAspectRatioLabel(0, 1000))
    }

    @Test
    fun formatExtension_normalizesJpegAndFallbacks() {
        assertEquals("JPG", PhotoExifMetadata.formatExtension("IMG_20260412.jpeg"))
        assertEquals("JPG", PhotoExifMetadata.formatExtension("IMG_20260412.JPG"))
        assertEquals("HEIC", PhotoExifMetadata.formatExtension("IMG_001.heic"))
        assertEquals("PNG", PhotoExifMetadata.formatExtension("Screenshot_01.png"))
        assertEquals("IMG", PhotoExifMetadata.formatExtension("no_extension"))
    }

    @Test
    fun parseIsoValue_handlesMultiValueOemTagsAndFallbacks() {
        assertEquals(250, PhotoExifMetadata.parseIsoValue("250,0", null, null))
        assertEquals(400, PhotoExifMetadata.parseIsoValue("400", null, null))
        assertEquals(800, PhotoExifMetadata.parseIsoValue(null, "800 0", null))
        assertEquals(1600, PhotoExifMetadata.parseIsoValue("invalid", "0", "1600"))
        assertNull(PhotoExifMetadata.parseIsoValue(null, "", "0"))
    }

    @Test
    fun photoExifInfo_hasCameraSpecsReflectsPresence() {
        assertFalse(PhotoExifInfo().hasCameraSpecs)
        assertTrue(PhotoExifInfo(cameraLabel = "HONOR LGE-AN00").hasCameraSpecs)
        assertTrue(PhotoExifInfo(iso = "ISO 100").hasCameraSpecs)
    }
}
