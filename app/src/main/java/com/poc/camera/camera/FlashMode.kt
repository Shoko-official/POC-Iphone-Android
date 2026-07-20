package com.poc.camera.camera

import androidx.camera.core.ImageCapture

/**
 * Photo-mode flash control: cycles Off -> Auto -> On -> Off via the top-status chip. The
 * enum itself carries no CameraX dependency; [toImageCaptureFlashMode] below is the thin
 * mapping layer that translates a mode into the `ImageCapture.FLASH_MODE_*` constant
 * CameraPreview applies to the bound [ImageCapture] use case.
 */
enum class FlashMode {
    Off,
    Auto,
    On,
}

/** Cycles the flash control button: Off -> Auto -> On -> Off. */
fun FlashMode.next(): FlashMode = when (this) {
    FlashMode.Off -> FlashMode.Auto
    FlashMode.Auto -> FlashMode.On
    FlashMode.On -> FlashMode.Off
}

/** Maps to the `ImageCapture.FLASH_MODE_*` constant CameraPreview sets on the bound ImageCapture. */
fun FlashMode.toImageCaptureFlashMode(): Int = when (this) {
    FlashMode.Off -> ImageCapture.FLASH_MODE_OFF
    FlashMode.Auto -> ImageCapture.FLASH_MODE_AUTO
    FlashMode.On -> ImageCapture.FLASH_MODE_ON
}
