package com.poc.camera.camera

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TorchLogicTest {

    // -- toggle -----------------------------------------------------------------------------

    @Test
    fun toggleTurnsOffTorchOn() {
        assertTrue(TorchLogic.toggle(current = false))
    }

    @Test
    fun toggleTurnsOnTorchOff() {
        assertFalse(TorchLogic.toggle(current = true))
    }

    // -- torchEnabledAfterModeChange ----------------------------------------------------------

    @Test
    fun leavingVideoForPhotoForcesTorchOff() {
        val result = TorchLogic.torchEnabledAfterModeChange(
            newMode = CameraMode.Photo,
            previousTorchEnabled = true,
        )

        assertFalse(result)
    }

    @Test
    fun leavingCinematicForPhotoForcesTorchOff() {
        val result = TorchLogic.torchEnabledAfterModeChange(
            newMode = CameraMode.Photo,
            previousTorchEnabled = true,
        )

        assertFalse(result)
    }

    @Test
    fun switchingBetweenVideoAndCinematicPreservesAnEnabledTorch() {
        val result = TorchLogic.torchEnabledAfterModeChange(
            newMode = CameraMode.Cinematic,
            previousTorchEnabled = true,
        )

        assertTrue(result)
    }

    @Test
    fun switchingBetweenVideoAndCinematicPreservesADisabledTorch() {
        val result = TorchLogic.torchEnabledAfterModeChange(
            newMode = CameraMode.Video,
            previousTorchEnabled = false,
        )

        assertFalse(result)
    }

    @Test
    fun stayingOnPhotoLeavesTorchOff() {
        val result = TorchLogic.torchEnabledAfterModeChange(
            newMode = CameraMode.Photo,
            previousTorchEnabled = false,
        )

        assertFalse(result)
    }
}
