package com.poc.camera.camera

import android.view.KeyEvent

/**
 * Pure decision for whether a hardware volume-key press should trigger the camera shutter
 * (issue #73), kept free of Activity/KeyEvent-dispatch machinery the same way
 * CameraPermissionEvaluator/CameraSwitchLogic are, so the key/repeat/destination/permission
 * matrix is unit-testable without an instrumented Activity. MainActivity.onKeyDown only acts
 * on [decide]'s result; it owns no gating logic of its own.
 */
object VolumeShutterPolicy {

    sealed interface Action {
        data object TriggerShutter : Action
        data object Ignore : Action
    }

    /**
     * [repeatCount] > 0 means the key is being held down - Android resends
     * KeyEvent.ACTION_DOWN with an increasing repeatCount while a key stays physically
     * down, rather than sending discrete presses - so only the initial press
     * (repeatCount == 0) ever triggers; holding volume must not machine-gun captures.
     * Held-key repeats fall through to the platform's own volume handling (MainActivity
     * returns false to `super.onKeyDown` for them), so the device's normal volume UI still
     * works once past the first press.
     *
     * [isCameraDestination] mirrors the on-screen shutter only ever existing on the Camera
     * destination; [permissionGranted] mirrors CameraCaptureScreen only ever being composed
     * once camera permission is [CameraPermissionState.Granted] (see [CameraScreen]). Both
     * gates exist so a volume press on Settings/Compare, or before permission is granted,
     * falls through to ordinary volume behaviour instead of silently doing nothing.
     */
    fun decide(
        keyCode: Int,
        repeatCount: Int,
        isCameraDestination: Boolean,
        permissionGranted: Boolean,
    ): Action = when {
        keyCode != KeyEvent.KEYCODE_VOLUME_UP && keyCode != KeyEvent.KEYCODE_VOLUME_DOWN -> Action.Ignore
        repeatCount > 0 -> Action.Ignore
        !isCameraDestination -> Action.Ignore
        !permissionGranted -> Action.Ignore
        else -> Action.TriggerShutter
    }
}
