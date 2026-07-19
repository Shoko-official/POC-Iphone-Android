package com.poc.camera.camera

enum class CameraMode {
    Photo,
    Video,
    Cinematic,
}

/**
 * Video and Cinematic share the record/stop shutter, elapsed timer and audio permission
 * flow; only the bound use cases differ (see CameraPreview).
 */
val CameraMode.isVideoLike: Boolean
    get() = this == CameraMode.Video || this == CameraMode.Cinematic
