package com.poc.camera.settings

/**
 * Decides the effective video recording quality given what the device's Recorder reports as
 * supported and what the user asked for in settings, and how the settings UI and the
 * in-camera overlay should present that decision. Kept free of Android/CameraX classes so it
 * can be unit tested without Robolectric, the same way [com.poc.camera.camera.VideoDynamicRangeResolver]
 * is for HDR; [com.poc.camera.camera.VideoQualityCapabilities] supplies the CameraX data.
 *
 * [VideoQualityChoice]'s declaration order (SD, HD, FHD, UHD) is itself ascending by
 * resolution, so ordinal comparison below doubles as a resolution comparison.
 */
object VideoQualityLogic {

    /**
     * Honest fallback ordering: the exact requested [choice] if the device supports it,
     * otherwise the nearest quality BELOW it that the device supports (never silently
     * request more than the user asked for), otherwise the nearest quality ABOVE it (so a
     * device that only supports higher qualities than requested still gets something rather
     * than nothing). An empty [supported] set means capability data wasn't available at all
     * (e.g. the query failed) - there's nothing honest to fall back to, so [choice] is
     * returned unchanged and left to CameraX's own belt-and-braces
     * `FallbackStrategy.higherQualityOrLowerThan` guard at Recorder-build time (see
     * CameraPreview).
     */
    fun resolve(supported: Set<VideoQualityChoice>, choice: VideoQualityChoice): VideoQualityChoice {
        if (supported.isEmpty() || choice in supported) return choice
        val nearestBelow = supported.filter { it.ordinal < choice.ordinal }.maxByOrNull { it.ordinal }
        if (nearestBelow != null) return nearestBelow
        return supported.filter { it.ordinal > choice.ordinal }.minByOrNull { it.ordinal } ?: choice
    }

    /**
     * Which of the four qualities [deviceSet] actually supports, in canonical (ascending
     * resolution) order regardless of the input set's iteration order. Intended for a
     * device-aware settings UI to disable unsupported options - not currently wired into
     * SettingsScreen, since that screen has no bound camera session to query in the first
     * place (see SettingsScreen's own doc comment on the video quality row).
     */
    fun supportedChoices(deviceSet: Set<VideoQualityChoice>): List<VideoQualityChoice> =
        VideoQualityChoice.entries.filter { it in deviceSet }

    /**
     * The in-camera overlay only ever needs a quality chip when the Recorder actually ended
     * up somewhere other than what the user picked - when [effective] matches [choice] the
     * chip would just be repeating a setting the user already knows, so it stays silent.
     */
    fun showQualityChip(choice: VideoQualityChoice, effective: VideoQualityChoice): Boolean =
        choice != effective
}
