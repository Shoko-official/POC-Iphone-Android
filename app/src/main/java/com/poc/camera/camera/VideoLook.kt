package com.poc.camera.camera

/**
 * Colour looks selectable in Cinematic mode. [Neutral] binds without any GL
 * effect (zero overhead, safe fallback); [Cinematic] applies the teal-orange
 * LUT to both preview and recording via [LookCameraEffect].
 */
enum class VideoLook {
    Neutral,
    Cinematic,
}
