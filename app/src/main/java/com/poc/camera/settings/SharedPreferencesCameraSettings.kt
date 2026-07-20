package com.poc.camera.settings

import android.content.Context
import androidx.core.content.edit

/**
 * Thin SharedPreferences-backed [CameraSettings] adapter. All defaulting and validation
 * logic lives in [CameraSettingsData] so it can be unit tested without Android; this
 * class only wires that logic to the platform API and is intentionally left untested.
 */
class SharedPreferencesCameraSettings(context: Context) : CameraSettings {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun load(): CameraSettingsData = CameraSettingsData.fromRaw(
        burstFrameCount = prefs.getInt(KEY_BURST_FRAME_COUNT, CameraSettingsData.DEFAULT.burstFrameCount),
        applyFinishingToMergedPhotos = prefs.getBoolean(
            KEY_APPLY_FINISHING,
            CameraSettingsData.DEFAULT.applyFinishingToMergedPhotos,
        ),
        defaultCinematicLookName = prefs.getString(KEY_DEFAULT_LOOK, null),
        hdrBurstEnabled = prefs.getBoolean(KEY_HDR_BURST_ENABLED, CameraSettingsData.DEFAULT.hdrBurstEnabled),
        saveComparisonPair = prefs.getBoolean(
            KEY_SAVE_COMPARISON_PAIR,
            CameraSettingsData.DEFAULT.saveComparisonPair,
        ),
        nightModeEnabled = prefs.getBoolean(KEY_NIGHT_MODE_ENABLED, CameraSettingsData.DEFAULT.nightModeEnabled),
        finishingPresetName = prefs.getString(KEY_FINISHING_PRESET, null),
    )

    override fun save(data: CameraSettingsData) {
        prefs.edit {
            putInt(KEY_BURST_FRAME_COUNT, data.burstFrameCount)
            putBoolean(KEY_APPLY_FINISHING, data.applyFinishingToMergedPhotos)
            putString(KEY_DEFAULT_LOOK, data.defaultCinematicLook.name)
            putBoolean(KEY_HDR_BURST_ENABLED, data.hdrBurstEnabled)
            putBoolean(KEY_SAVE_COMPARISON_PAIR, data.saveComparisonPair)
            putBoolean(KEY_NIGHT_MODE_ENABLED, data.nightModeEnabled)
            putString(KEY_FINISHING_PRESET, data.finishingPreset.name)
        }
    }

    companion object {
        private const val PREFS_NAME = "camera_settings"
        private const val KEY_BURST_FRAME_COUNT = "burst_frame_count"
        private const val KEY_APPLY_FINISHING = "apply_finishing_to_merged_photos"
        private const val KEY_DEFAULT_LOOK = "default_cinematic_look"
        private const val KEY_HDR_BURST_ENABLED = "hdr_burst_enabled"
        private const val KEY_SAVE_COMPARISON_PAIR = "save_comparison_pair"
        private const val KEY_NIGHT_MODE_ENABLED = "night_mode_enabled"
        private const val KEY_FINISHING_PRESET = "finishing_preset"
    }
}
