package com.poc.camera.camera

/**
 * Colour looks selectable in Cinematic mode. [Neutral] binds without any GL
 * effect (zero overhead, safe fallback); [Cinematic] applies the skin-safe
 * teal-orange LUT (issue #134) to both preview and recording via
 * [LookCameraEffect].
 */
enum class VideoLook {
    Neutral,
    Cinematic,
}
