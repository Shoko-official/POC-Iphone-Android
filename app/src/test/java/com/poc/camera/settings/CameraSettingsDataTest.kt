package com.poc.camera.settings

import com.poc.camera.camera.VideoLook
import org.junit.Assert.assertEquals
import org.junit.Test

class CameraSettingsDataTest {

    @Test
    fun defaultUsesSixFrameBurstFinishingOnAndNeutralLook() {
        val default = CameraSettingsData.DEFAULT

        assertEquals(6, default.burstFrameCount)
        assertEquals(true, default.applyFinishingToMergedPhotos)
        assertEquals(VideoLook.Neutral, default.defaultCinematicLook)
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
        )

        val decoded = CameraSettingsData.fromRaw(
            burstFrameCount = original.burstFrameCount,
            applyFinishingToMergedPhotos = original.applyFinishingToMergedPhotos,
            defaultCinematicLookName = original.defaultCinematicLook.name,
        )

        assertEquals(original, decoded)
    }

    @Test
    fun fromRawRoundTripsEveryVideoLook() {
        for (look in VideoLook.entries) {
            val decoded = CameraSettingsData.fromRaw(
                burstFrameCount = 3,
                applyFinishingToMergedPhotos = true,
                defaultCinematicLookName = look.name,
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
        )

        assertEquals(CameraSettingsData.DEFAULT_BURST_FRAME_COUNT, decoded.burstFrameCount)
        assertEquals(false, decoded.applyFinishingToMergedPhotos)
        assertEquals(CameraSettingsData.DEFAULT.defaultCinematicLook, decoded.defaultCinematicLook)
    }

    @Test
    fun fromRawFallsBackToDefaultLookWhenNameIsNull() {
        val decoded = CameraSettingsData.fromRaw(
            burstFrameCount = 6,
            applyFinishingToMergedPhotos = true,
            defaultCinematicLookName = null,
        )

        assertEquals(CameraSettingsData.DEFAULT.defaultCinematicLook, decoded.defaultCinematicLook)
    }
}
