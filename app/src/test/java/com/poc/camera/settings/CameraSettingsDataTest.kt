package com.poc.camera.settings

import com.poc.camera.camera.VideoLook
import org.junit.Assert.assertEquals
import org.junit.Test

class CameraSettingsDataTest {

    @Test
    fun defaultUsesSixFrameBurstFinishingOnNeutralLookAndHdrOff() {
        val default = CameraSettingsData.DEFAULT

        assertEquals(6, default.burstFrameCount)
        assertEquals(true, default.applyFinishingToMergedPhotos)
        assertEquals(VideoLook.Neutral, default.defaultCinematicLook)
        assertEquals(false, default.hdrBurstEnabled)
        assertEquals(false, default.saveComparisonPair)
        assertEquals(false, default.nightModeEnabled)
        assertEquals(false, default.superResolutionEnabled)
        assertEquals(FinishingPreset.Natural, default.finishingPreset)
    }

    @Test
    fun sanitizeBurstFrameCountKeepsAllowedValues() {
        assertEquals(3, CameraSettingsData.sanitizeBurstFrameCount(3))
        assertEquals(6, CameraSettingsData.sanitizeBurstFrameCount(6))
        assertEquals(9, CameraSettingsData.sanitizeBurstFrameCount(9))
    }

    @Test
    fun sanitizeBurstFrameCountFallsBackToDefaultForDisallowedValues() {
        assertEquals(6, CameraSettingsData.sanitizeBurstFrameCount(0))
        assertEquals(6, CameraSettingsData.sanitizeBurstFrameCount(4))
        assertEquals(6, CameraSettingsData.sanitizeBurstFrameCount(-1))
        assertEquals(6, CameraSettingsData.sanitizeBurstFrameCount(100))
    }

    @Test
    fun fromRawRoundTripsValidValues() {
        val original = CameraSettingsData(
            burstFrameCount = 9,
            applyFinishingToMergedPhotos = false,
            defaultCinematicLook = VideoLook.Cinematic,
            hdrBurstEnabled = true,
            saveComparisonPair = true,
            nightModeEnabled = true,
            superResolutionEnabled = true,
            finishingPreset = FinishingPreset.Vivid,
        )

        val decoded = CameraSettingsData.fromRaw(
            burstFrameCount = original.burstFrameCount,
            applyFinishingToMergedPhotos = original.applyFinishingToMergedPhotos,
            defaultCinematicLookName = original.defaultCinematicLook.name,
            hdrBurstEnabled = original.hdrBurstEnabled,
            saveComparisonPair = original.saveComparisonPair,
            nightModeEnabled = original.nightModeEnabled,
            superResolutionEnabled = original.superResolutionEnabled,
            finishingPresetName = original.finishingPreset.name,
        )

        assertEquals(original, decoded)
    }

    @Test
    fun fromRawRoundTripsEveryFinishingPreset() {
        for (preset in FinishingPreset.entries) {
            val decoded = CameraSettingsData.fromRaw(
                burstFrameCount = 3,
                applyFinishingToMergedPhotos = true,
                defaultCinematicLookName = null,
                hdrBurstEnabled = false,
                saveComparisonPair = false,
                nightModeEnabled = false,
                superResolutionEnabled = false,
                finishingPresetName = preset.name,
            )

            assertEquals(preset, decoded.finishingPreset)
        }
    }

    @Test
    fun fromRawFallsBackToDefaultPresetForMissingOrInvalidName() {
        val missing = CameraSettingsData.fromRaw(
            burstFrameCount = 6,
            applyFinishingToMergedPhotos = true,
            defaultCinematicLookName = null,
            hdrBurstEnabled = false,
            saveComparisonPair = false,
            nightModeEnabled = false,
            superResolutionEnabled = false,
            finishingPresetName = null,
        )
        val invalid = CameraSettingsData.fromRaw(
            burstFrameCount = 6,
            applyFinishingToMergedPhotos = true,
            defaultCinematicLookName = null,
            hdrBurstEnabled = false,
            saveComparisonPair = false,
            nightModeEnabled = false,
            superResolutionEnabled = false,
            finishingPresetName = "not-a-real-preset",
        )

        assertEquals(CameraSettingsData.DEFAULT.finishingPreset, missing.finishingPreset)
        assertEquals(CameraSettingsData.DEFAULT.finishingPreset, invalid.finishingPreset)
    }

    @Test
    fun fromRawRoundTripsEveryVideoLook() {
        for (look in VideoLook.entries) {
            val decoded = CameraSettingsData.fromRaw(
                burstFrameCount = 3,
                applyFinishingToMergedPhotos = true,
                defaultCinematicLookName = look.name,
                hdrBurstEnabled = false,
                saveComparisonPair = false,
                nightModeEnabled = false,
                superResolutionEnabled = false,
            )

            assertEquals(look, decoded.defaultCinematicLook)
        }
    }

    @Test
    fun fromRawFallsBackToDefaultsForInvalidStoredValues() {
        val decoded = CameraSettingsData.fromRaw(
            burstFrameCount = 42,
            applyFinishingToMergedPhotos = false,
            defaultCinematicLookName = "not-a-real-look",
            hdrBurstEnabled = true,
            saveComparisonPair = true,
            nightModeEnabled = true,
            superResolutionEnabled = true,
        )

        assertEquals(CameraSettingsData.DEFAULT_BURST_FRAME_COUNT, decoded.burstFrameCount)
        assertEquals(false, decoded.applyFinishingToMergedPhotos)
        assertEquals(CameraSettingsData.DEFAULT.defaultCinematicLook, decoded.defaultCinematicLook)
        assertEquals(true, decoded.hdrBurstEnabled)
        assertEquals(true, decoded.saveComparisonPair)
        assertEquals(true, decoded.nightModeEnabled)
        assertEquals(true, decoded.superResolutionEnabled)
    }

    @Test
    fun fromRawFallsBackToDefaultLookWhenNameIsNull() {
        val decoded = CameraSettingsData.fromRaw(
            burstFrameCount = 6,
            applyFinishingToMergedPhotos = true,
            defaultCinematicLookName = null,
            hdrBurstEnabled = false,
            saveComparisonPair = false,
            nightModeEnabled = false,
            superResolutionEnabled = false,
        )

        assertEquals(CameraSettingsData.DEFAULT.defaultCinematicLook, decoded.defaultCinematicLook)
    }
}
