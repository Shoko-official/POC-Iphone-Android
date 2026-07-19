package com.poc.camera.camera

/**
 * Decides whether cinematic mode can use 24 fps and video stabilization, given what the
 * device reports as supported. Kept free of Android/Camera2 classes so it can be unit
 * tested without Robolectric; [CinematicCameraCharacteristics] supplies the Camera2 data.
 */
object CinematicConfigResolver {

    const val TARGET_FPS = 24

    // Matches android.hardware.camera2.CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON.
    const val STABILIZATION_MODE_ON = 1

    fun resolve(
        supportedFpsRanges: List<IntRange>,
        supportedStabilizationModes: List<Int>,
    ): CinematicConfig {
        val use24Fps = supportedFpsRanges.any { TARGET_FPS in it }
        val stabilization = if (supportedStabilizationModes.contains(STABILIZATION_MODE_ON)) {
            StabilizationChoice.ON
        } else {
            StabilizationChoice.OFF
        }
        return CinematicConfig(use24Fps = use24Fps, stabilization = stabilization)
    }
}
