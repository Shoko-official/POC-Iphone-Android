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
) {
    companion object {
        const val DEFAULT_BURST_FRAME_COUNT = 6
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
        ): CameraSettingsData = CameraSettingsData(
            burstFrameCount = sanitizeBurstFrameCount(burstFrameCount),
            applyFinishingToMergedPhotos = applyFinishingToMergedPhotos,
            defaultCinematicLook = VideoLook.entries.firstOrNull { it.name == defaultCinematicLookName }
                ?: DEFAULT.defaultCinematicLook,
        )
    }
}
