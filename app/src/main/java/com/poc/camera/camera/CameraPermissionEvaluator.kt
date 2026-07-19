package com.poc.camera.camera

/**
 * Derives the [CameraPermissionState] from the raw signals exposed by the Android
 * permission APIs, kept separate from Activity/Context so it can be unit tested.
 */
object CameraPermissionEvaluator {

    fun evaluate(
        isGranted: Boolean,
        hasRequestedBefore: Boolean,
        shouldShowRationale: Boolean,
    ): CameraPermissionState = when {
        isGranted -> CameraPermissionState.Granted
        !hasRequestedBefore -> CameraPermissionState.Denied
        shouldShowRationale -> CameraPermissionState.Denied
        else -> CameraPermissionState.PermanentlyDenied
    }
}
