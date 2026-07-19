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
    ): String {
        val fpsLabel = if (config.use24Fps) fps24Label else fpsDefaultLabel
        val template = if (config.stabilization == StabilizationChoice.ON) {
            stabilizedTemplate
        } else {
            unstabilizedTemplate
        }
        return String.format(template, fpsLabel)
    }
}
