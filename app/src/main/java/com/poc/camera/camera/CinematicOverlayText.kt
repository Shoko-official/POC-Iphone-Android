package com.poc.camera.camera

/**
 * Builds the small "24 fps - stabilized" style overlay shown in Cinematic mode. Takes the
 * resource strings as plain arguments so it stays free of Android framework classes and can
 * be unit tested without Robolectric.
 */
object CinematicOverlayText {

    fun format(
        config: CinematicConfig,
        fps24Label: String,
        fpsDefaultLabel: String,
        stabilizedTemplate: String,
        unstabilizedTemplate: String,
        // Both null (the default) omits the suffix entirely, matching pre-HDR overlay text -
        // callers only pass these once HDR video is enabled in settings, so a device/user with
        // it off never sees a "- SDR" suffix on every cinematic recording.
        rangeLabel: String? = null,
        rangeSuffixTemplate: String? = null,
    ): String {
        val fpsLabel = if (config.use24Fps) fps24Label else fpsDefaultLabel
        val template = if (config.stabilization == StabilizationChoice.ON) {
            stabilizedTemplate
        } else {
            unstabilizedTemplate
        }
        val base = String.format(template, fpsLabel)
        return if (rangeLabel != null && rangeSuffixTemplate != null) {
            String.format(rangeSuffixTemplate, base, rangeLabel)
        } else {
            base
        }
    }
}
