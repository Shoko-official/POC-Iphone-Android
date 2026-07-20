package com.poc.camera.camera

import androidx.camera.core.CameraSelector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class LensFacingTest {

    // -- switched() cycle ---------------------------------------------------------------------

    @Test
    fun backSwitchesToFront() {
        assertEquals(LensFacing.Front, LensFacing.Back.switched())
    }

    @Test
    fun frontSwitchesToBack() {
        assertEquals(LensFacing.Back, LensFacing.Front.switched())
    }

    @Test
    fun switchingTwiceReturnsToTheStartingFacing() {
        val start = LensFacing.Back
        val afterTwoSwitches = start.switched().switched()

        assertEquals(start, afterTwoSwitches)
    }

    // -- toCameraSelector mapping --------------------------------------------------------------

    @Test
    fun backMapsToDefaultBackCameraSelector() {
        assertEquals(CameraSelector.DEFAULT_BACK_CAMERA, LensFacing.Back.toCameraSelector())
    }

    @Test
    fun frontMapsToDefaultFrontCameraSelector() {
        assertEquals(CameraSelector.DEFAULT_FRONT_CAMERA, LensFacing.Front.toCameraSelector())
    }

    @Test
    fun everyFacingMapsToADistinctSelector() {
        val mapped = LensFacing.entries.map { it.toCameraSelector() }

        assertNotEquals(mapped[0], mapped[1])
    }
}
