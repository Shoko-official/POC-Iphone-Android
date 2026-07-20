package com.poc.camera.settings

import com.poc.camera.camera.VideoLook

/**
 * User-configurable capture behaviour. Kept free of Android dependencies (no
 * SharedPreferences, no Context) so defaults, validation and raw (de)serialization
 * can be unit tested without Robolectric; [SharedPreferencesCameraSettings] is the
 * only place this is wired to the platform.
 */
data class CameraSettingsData(
    val burstFrameCount: Int = DEFAULT_BURST_FRAME_COUNT,
    val applyFinishingToMergedPhotos: Boolean = true,
    val defaultCinematicLook: VideoLook = VideoLook.Neutral,
    val hdrBurstEnabled: Boolean = DEFAULT_HDR_BURST_ENABLED,
    val saveComparisonPair: Boolean = DEFAULT_SAVE_COMPARISON_PAIR,
    val nightModeEnabled: Boolean = DEFAULT_NIGHT_MODE_ENABLED,
) {
    companion object {
        const val DEFAULT_BURST_FRAME_COUNT = 6
        const val DEFAULT_HDR_BURST_ENABLED = false
        const val DEFAULT_SAVE_COMPARISON_PAIR = false
        const val DEFAULT_NIGHT_MODE_ENABLED = false
        val ALLOWED_BURST_FRAME_COUNTS = listOf(3, 6, 9)
        val DEFAULT = CameraSettingsData()

        /** Falls back to the default frame count when [value] is outside [ALLOWED_BURST_FRAME_COUNTS]. */
        fun sanitizeBurstFrameCount(value: Int): Int =
            if (value in ALLOWED_BURST_FRAME_COUNTS) value else DEFAULT_BURST_FRAME_COUNT

        /**
         * Decodes settings from raw primitive values (as stored in SharedPreferences),
         * substituting defaults for any missing or invalid field instead of throwing.
         */
        fun fromRaw(
            burstFrameCount: Int,
            applyFinishingToMergedPhotos: Boolean,
            defaultCinematicLookName: String?,
            hdrBurstEnabled: Boolean,
            saveComparisonPair: Boolean,
            nightModeEnabled: Boolean,
        ): CameraSettingsData = CameraSettingsData(
            burstFrameCount = sanitizeBurstFrameCount(burstFrameCount),
            applyFinishingToMergedPhotos = applyFinishingToMergedPhotos,
            defaultCinematicLook = VideoLook.entries.firstOrNull { it.name == defaultCinematicLookName }
                ?: DEFAULT.defaultCinematicLook,
            hdrBurstEnabled = hdrBurstEnabled,
            saveComparisonPair = saveComparisonPair,
            nightModeEnabled = nightModeEnabled,
        )
    }
}
