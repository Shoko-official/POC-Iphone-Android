package com.poc.camera.camera

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    // -- edge-trigger guard (issue #143) ------------------------------------------------------

    @Test
    fun sentinelCounterNeverFires() {
        // Initial composition: no press yet, baseline seeded from the same 0 sentinel.
        assertFalse(VolumeShutterPolicy.isFreshTrigger(current = 0, lastHandled = 0))
    }

    @Test
    fun advancingPastTheBaselineFires() {
        // A genuine press increments the counter beyond the last-handled baseline.
        assertTrue(VolumeShutterPolicy.isFreshTrigger(current = 1, lastHandled = 0))
        assertTrue(VolumeShutterPolicy.isFreshTrigger(current = 5, lastHandled = 4))
    }

    @Test
    fun bareRemountWithUnchangedCounterDoesNotFire() {
        // Nav round trip: the subtree is rebuilt, the consumer re-seeds its baseline from the
        // preserved counter, so counter == baseline and no phantom capture fires.
        assertFalse(VolumeShutterPolicy.isFreshTrigger(current = 3, lastHandled = 3))
    }

    @Test
    fun counterResetBelowBaselineDoesNotFire() {
        // Activity recreation (rotation / process death) resets the counter to 0 while a
        // stale baseline could still read high; the > 0 sentinel keeps that from firing.
        assertFalse(VolumeShutterPolicy.isFreshTrigger(current = 0, lastHandled = 2))
    }

    @Test
    fun rapidDoublePressAdvancingByTwoStillFires() {
        // A coalesced double-press increments by two between observations; it still reads as a
        // fresh trigger (fires once - see isFreshTrigger's documented coalescing limit).
        assertTrue(VolumeShutterPolicy.isFreshTrigger(current = 2, lastHandled = 0))
    }
}
