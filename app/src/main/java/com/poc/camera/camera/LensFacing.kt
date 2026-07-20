package com.poc.camera.camera

import androidx.camera.core.CameraSelector

/**
 * Physical camera facing the switch-camera chip toggles between (issue #71). The enum
 * itself carries no CameraX dependency, matching CameraMode/FlashMode; [toCameraSelector]
 * below is the thin mapping layer CameraPreview uses to pick the actual
 * [androidx.camera.core.CameraSelector] for every bind and for the `availableCameraInfos`
 * filter that resolves per-lens Camera2 characteristics (HDR dynamic range, Cinematic
 * fps/stabilization - see CinematicCameraCharacteristics) against whichever lens is
 * actually about to be bound.
 */
enum class LensFacing {
    Back,
    Front,
}

/** Cycles the switch-camera chip: Back -> Front -> Back. */
fun LensFacing.switched(): LensFacing = when (this) {
    LensFacing.Back -> LensFacing.Front
    LensFacing.Front -> LensFacing.Back
}

/** Maps to the CameraX default selector CameraPreview binds against for this facing. */
fun LensFacing.toCameraSelector(): CameraSelector = when (this) {
    LensFacing.Back -> CameraSelector.DEFAULT_BACK_CAMERA
    LensFacing.Front -> CameraSelector.DEFAULT_FRONT_CAMERA
}
