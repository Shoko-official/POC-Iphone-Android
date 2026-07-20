package com.poc.camera.camera

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Test

class VolumeShutterPolicyTest {

    // -- volume keys only ---------------------------------------------------------------------

    @Test
    fun volumeUpTriggersWhenCameraDestinationAndPermissionGranted() {
        val action = VolumeShutterPolicy.decide(
            keyCode = KeyEvent.KEYCODE_VOLUME_UP,
            repeatCount = 0,
            isCameraDestination = true,
            permissionGranted = true,
        )

        assertEquals(VolumeShutterPolicy.Action.TriggerShutter, action)
    }

    @Test
    fun volumeDownTriggersWhenCameraDestinationAndPermissionGranted() {
        val action = VolumeShutterPolicy.decide(
            keyCode = KeyEvent.KEYCODE_VOLUME_DOWN,
            repeatCount = 0,
            isCameraDestination = true,
            permissionGranted = true,
        )

        assertEquals(VolumeShutterPolicy.Action.TriggerShutter, action)
    }

    @Test
    fun nonVolumeKeyIsIgnored() {
        val action = VolumeShutterPolicy.decide(
            keyCode = KeyEvent.KEYCODE_BACK,
            repeatCount = 0,
            isCameraDestination = true,
            permissionGranted = true,
        )

        assertEquals(VolumeShutterPolicy.Action.Ignore, action)
    }

    @Test
    fun volumeMuteKeyIsIgnored() {
        val action = VolumeShutterPolicy.decide(
            keyCode = KeyEvent.KEYCODE_VOLUME_MUTE,
            repeatCount = 0,
            isCameraDestination = true,
            permissionGranted = true,
        )

        assertEquals(VolumeShutterPolicy.Action.Ignore, action)
    }

    // -- repeat suppressed ---------------------------------------------------------------------

    @Test
    fun heldVolumeKeyRepeatIsIgnored() {
        val action = VolumeShutterPolicy.decide(
            keyCode = KeyEvent.KEYCODE_VOLUME_UP,
            repeatCount = 1,
            isCameraDestination = true,
            permissionGranted = true,
        )

        assertEquals(VolumeShutterPolicy.Action.Ignore, action)
    }

    @Test
    fun laterRepeatsAreAlsoIgnored() {
        val action = VolumeShutterPolicy.decide(
            keyCode = KeyEvent.KEYCODE_VOLUME_DOWN,
            repeatCount = 5,
            isCameraDestination = true,
            permissionGranted = true,
        )

        assertEquals(VolumeShutterPolicy.Action.Ignore, action)
    }

    // -- non-camera destination ignored -----------------------------------------------------

    @Test
    fun volumeKeyIgnoredOffTheCameraDestination() {
        val action = VolumeShutterPolicy.decide(
            keyCode = KeyEvent.KEYCODE_VOLUME_UP,
            repeatCount = 0,
            isCameraDestination = false,
            permissionGranted = true,
        )

        assertEquals(VolumeShutterPolicy.Action.Ignore, action)
    }

    // -- no permission ignored ----------------------------------------------------------------

    @Test
    fun volumeKeyIgnoredWithoutCameraPermission() {
        val action = VolumeShutterPolicy.decide(
            keyCode = KeyEvent.KEYCODE_VOLUME_UP,
            repeatCount = 0,
            isCameraDestination = true,
            permissionGranted = false,
        )

        assertEquals(VolumeShutterPolicy.Action.Ignore, action)
    }

    @Test
    fun offDestinationAndNoPermissionIsStillJustIgnored() {
        val action = VolumeShutterPolicy.decide(
            keyCode = KeyEvent.KEYCODE_VOLUME_DOWN,
            repeatCount = 0,
            isCameraDestination = false,
            permissionGranted = false,
        )

        assertEquals(VolumeShutterPolicy.Action.Ignore, action)
    }
}
