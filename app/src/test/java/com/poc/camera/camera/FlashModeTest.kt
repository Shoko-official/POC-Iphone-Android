package com.poc.camera.camera

import androidx.camera.core.ImageCapture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class FlashModeTest {

    // -- next() cycle -----------------------------------------------------------------------

    @Test
    fun offCyclesToAuto() {
        assertEquals(FlashMode.Auto, FlashMode.Off.next())
    }

    @Test
    fun autoCyclesToOn() {
        assertEquals(FlashMode.On, FlashMode.Auto.next())
    }

    @Test
    fun onCyclesBackToOff() {
        assertEquals(FlashMode.Off, FlashMode.On.next())
    }

    @Test
    fun cyclingThreeTimesReturnsToTheStartingMode() {
        val start = FlashMode.Off
        val afterThreeCycles = start.next().next().next()

        assertEquals(start, afterThreeCycles)
    }

    // -- toImageCaptureFlashMode mapping ------------------------------------------------------

    @Test
    fun offMapsToImageCaptureFlashModeOff() {
        assertEquals(ImageCapture.FLASH_MODE_OFF, FlashMode.Off.toImageCaptureFlashMode())
    }

    @Test
    fun autoMapsToImageCaptureFlashModeAuto() {
        assertEquals(ImageCapture.FLASH_MODE_AUTO, FlashMode.Auto.toImageCaptureFlashMode())
    }

    @Test
    fun onMapsToImageCaptureFlashModeOn() {
        assertEquals(ImageCapture.FLASH_MODE_ON, FlashMode.On.toImageCaptureFlashMode())
    }

    @Test
    fun everyFlashModeMapsToADistinctImageCaptureConstant() {
        val mapped = FlashMode.entries.map { it.toImageCaptureFlashMode() }

        assertEquals(FlashMode.entries.size, mapped.toSet().size)
    }

    @Test
    fun mappingNeverProducesTheScreenFlashConstant() {
        // FLASH_MODE_SCREEN is a distinct ImageCapture mode this control never drives.
        FlashMode.entries.forEach { mode ->
            assertNotEquals(ImageCapture.FLASH_MODE_SCREEN, mode.toImageCaptureFlashMode())
        }
    }
}
