package com.poc.camera.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraSwitchLogicTest {

    // -- resetsForSwitch ----------------------------------------------------------------------

    @Test
    fun switchAlwaysResetsZoomToOneX() {
        assertEquals(1f, CameraSwitchLogic.resetsForSwitch().zoomRatio)
    }

    @Test
    fun switchAlwaysForcesTorchOff() {
        assertFalse(CameraSwitchLogic.resetsForSwitch().torchEnabled)
    }

    // -- shouldShowSwitchChip -------------------------------------------------------------------

    @Test
    fun chipShownWhenBothCamerasExist() {
        assertTrue(CameraSwitchLogic.shouldShowSwitchChip(hasBackCamera = true, hasFrontCamera = true))
    }

    @Test
    fun chipHiddenWithOnlyABackCamera() {
        assertFalse(CameraSwitchLogic.shouldShowSwitchChip(hasBackCamera = true, hasFrontCamera = false))
    }

    @Test
    fun chipHiddenWithOnlyAFrontCamera() {
        assertFalse(CameraSwitchLogic.shouldShowSwitchChip(hasBackCamera = false, hasFrontCamera = true))
    }

    @Test
    fun chipHiddenWithNeitherCameraReportedYet() {
        assertFalse(CameraSwitchLogic.shouldShowSwitchChip(hasBackCamera = false, hasFrontCamera = false))
    }

    // -- isSwitchEnabled ------------------------------------------------------------------------

    @Test
    fun switchEnabledWhenNotRecording() {
        assertTrue(CameraSwitchLogic.isSwitchEnabled(isRecording = false))
    }

    @Test
    fun switchDisabledWhileRecording() {
        assertFalse(CameraSwitchLogic.isSwitchEnabled(isRecording = true))
    }
}
