package com.poc.camera.camera

/**
 * Combines the optional HDR-range label (see [VideoDynamicRangeResolver]) with the optional
 * quality-mismatch label (see [com.poc.camera.settings.VideoQualityLogic.showQualityChip])
 * into the single suffix shown by the Video-mode overlay chip and by
 * [CinematicOverlayText]'s `rangeLabel` slot - joined with the same "%1$s - %2$s" template
 * already used to append the HDR suffix, so a mismatch reads as e.g. "HLG10 - 1080p" on one
 * chip rather than two separate ones. Kept free of Android/Compose classes so it can be unit
 * tested without Robolectric.
 */
object VideoOverlayLabel {
    fun combine(rangeLabel: String?, qualityLabel: String?, joinTemplate: String): String? = when {
        rangeLabel != null && qualityLabel != null -> String.format(joinTemplate, rangeLabel, qualityLabel)
        rangeLabel != null -> rangeLabel
        qualityLabel != null -> qualityLabel
        else -> null
    }
}
