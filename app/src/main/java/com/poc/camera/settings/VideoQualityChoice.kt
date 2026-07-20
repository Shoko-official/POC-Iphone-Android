package com.poc.camera.settings

/**
 * User-facing video recording quality choice (issue #72). Mirrors the four
 * androidx.camera.video.Quality constants this project cares about (SD/HD/FHD/UHD);
 * [displayLabel] is a plain string rather than a string resource because these are
 * technical resolution labels, not translatable prose - "4K" and "1080p" read the same
 * in every locale, the same way "24 fps" is already a hardcoded literal elsewhere in this
 * app. Kept free of Android/CameraX classes, like [VideoQualityLogic], so both can be unit
 * tested without Robolectric; [com.poc.camera.camera.VideoQualityCapabilities] is the
 * bridging layer that maps a bound camera's reported qualities into this shape and back.
 */
enum class VideoQualityChoice(val displayLabel: String) {
    SD("480p"),
    HD("720p"),
    FHD("1080p"),
    UHD("4K"),
}
