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
        assertEquals(false, default.hdrVideoEnabled)
        assertEquals(VideoQualityChoice.FHD, default.videoQuality)
        assertEquals(false, default.verboseTimings)
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
            hdrVideoEnabled = true,
            videoQuality = VideoQualityChoice.UHD,
            verboseTimings = true,
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
            hdrVideoEnabled = original.hdrVideoEnabled,
            videoQualityName = original.videoQuality.name,
            verboseTimings = original.verboseTimings,
        )

        assertEquals(original, decoded)
    }

    @Test
    fun fromRawFallsBackToVerboseTimingsDisabledWhenMissing() {
        val decoded = CameraSettingsData.fromRaw(
            burstFrameCount = 6,
            applyFinishingToMergedPhotos = true,
            defaultCinematicLookName = null,
            hdrBurstEnabled = false,
            saveComparisonPair = false,
            nightModeEnabled = false,
            superResolutionEnabled = false,
        )

        assertEquals(CameraSettingsData.DEFAULT.verboseTimings, decoded.verboseTimings)
    }

    @Test
    fun fromRawFallsBackToHdrVideoDisabledWhenMissing() {
        val decoded = CameraSettingsData.fromRaw(
            burstFrameCount = 6,
            applyFinishingToMergedPhotos = true,
            defaultCinematicLookName = null,
            hdrBurstEnabled = false,
            saveComparisonPair = false,
            nightModeEnabled = false,
            superResolutionEnabled = false,
        )

        assertEquals(CameraSettingsData.DEFAULT.hdrVideoEnabled, decoded.hdrVideoEnabled)
    }

    @Test
    fun fromRawFallsBackToDefaultVideoQualityWhenMissing() {
        val decoded = CameraSettingsData.fromRaw(
            burstFrameCount = 6,
            applyFinishingToMergedPhotos = true,
            defaultCinematicLookName = null,
            hdrBurstEnabled = false,
            saveComparisonPair = false,
            nightModeEnabled = false,
            superResolutionEnabled = false,
        )

        assertEquals(CameraSettingsData.DEFAULT.videoQuality, decoded.videoQuality)
    }

    @Test
    fun fromRawFallsBackToDefaultVideoQualityForInvalidName() {
        val decoded = CameraSettingsData.fromRaw(
            burstFrameCount = 6,
            applyFinishingToMergedPhotos = true,
            defaultCinematicLookName = null,
            hdrBurstEnabled = false,
            saveComparisonPair = false,
            nightModeEnabled = false,
            superResolutionEnabled = false,
            videoQualityName = "not-a-real-quality",
        )

        assertEquals(CameraSettingsData.DEFAULT.videoQuality, decoded.videoQuality)
    }

    @Test
    fun fromRawRoundTripsEveryVideoQuality() {
        for (quality in VideoQualityChoice.entries) {
            val decoded = CameraSettingsData.fromRaw(
                burstFrameCount = 3,
                applyFinishingToMergedPhotos = true,
                defaultCinematicLookName = null,
                hdrBurstEnabled = false,
                saveComparisonPair = false,
                nightModeEnabled = false,
                superResolutionEnabled = false,
                videoQualityName = quality.name,
            )

            assertEquals(quality, decoded.videoQuality)
        }
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
