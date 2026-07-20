package com.poc.camera.camera

enum class CameraMode {
    Photo,
    Video,
    Cinematic,
    // Portrait mode (issue #80): binds like Photo (Preview + ImageCapture) and always
    // captures via the single-EV burst-merge path, then runs on-device subject
    // segmentation and mask-driven bokeh on the merged frame - see CameraScreen's
    // Portrait capture flow. Photo-like, so isVideoLike stays false for it below.
    Portrait,
}

/**
 * Video and Cinematic share the record/stop shutter, elapsed timer and audio permission
 * flow; only the bound use cases differ (see CameraPreview).
 */
val CameraMode.isVideoLike: Boolean
    get() = this == CameraMode.Video || this == CameraMode.Cinematic
