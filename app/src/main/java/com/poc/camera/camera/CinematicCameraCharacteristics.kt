package com.poc.camera.camera

import android.hardware.camera2.CameraCharacteristics
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraInfo

/**
 * Reads the Camera2 characteristics [CinematicConfigResolver] needs, isolating the
 * Camera2Interop/CameraCharacteristics calls from the pure decision logic.
 */
object CinematicCameraCharacteristics {

    // ExperimentalCamera2Interop is marked with androidx.annotation.RequiresOptIn (not
    // Kotlin's), so it must be opted into via androidx.annotation.OptIn, not kotlin.OptIn.
    @OptIn(ExperimentalCamera2Interop::class)
    fun resolve(cameraInfo: CameraInfo): CinematicConfig {
        val characteristics = Camera2CameraInfo.from(cameraInfo)

        val fpsRanges = characteristics
            .getCameraCharacteristic(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
            ?.map { it.lower..it.upper }
            .orEmpty()

        val stabilizationModes = characteristics
            .getCameraCharacteristic(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES)
            ?.toList()
            .orEmpty()

        return CinematicConfigResolver.resolve(fpsRanges, stabilizationModes)
    }
}
