package com.poc.camera.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoQualityLogicTest {

    @Test
    fun resolveReturnsExactChoiceWhenSupported() {
        val result = VideoQualityLogic.resolve(
            supported = setOf(VideoQualityChoice.SD, VideoQualityChoice.HD, VideoQualityChoice.FHD),
            choice = VideoQualityChoice.HD,
        )

        assertEquals(VideoQualityChoice.HD, result)
    }

    @Test
    fun resolveFallsBackToNearestSupportedBelow() {
        val result = VideoQualityLogic.resolve(
            supported = setOf(VideoQualityChoice.SD, VideoQualityChoice.HD),
            choice = VideoQualityChoice.UHD,
        )

        assertEquals(VideoQualityChoice.HD, result)
    }

    @Test
    fun resolveFallsBackToNearestSupportedAboveWhenNothingIsBelow() {
        val result = VideoQualityLogic.resolve(
            supported = setOf(VideoQualityChoice.FHD, VideoQualityChoice.UHD),
            choice = VideoQualityChoice.SD,
        )

        assertEquals(VideoQualityChoice.FHD, result)
    }

    @Test
    fun resolvePrefersBelowOverAboveWhenBothExist() {
        // Ordering test: SD (below) and UHD (above) both supported for a FHD request - the
        // honest-fallback rule always prefers not exceeding what the user asked for.
        val result = VideoQualityLogic.resolve(
            supported = setOf(VideoQualityChoice.SD, VideoQualityChoice.UHD),
            choice = VideoQualityChoice.FHD,
        )

        assertEquals(VideoQualityChoice.SD, result)
    }

    @Test
    fun resolveReturnsRequestedChoiceWhenSupportedSetIsEmpty() {
        // No capability data at all (e.g. the query failed) - default FHD attempt is
        // returned unchanged, left to CameraX's own FallbackStrategy guard.
        val result = VideoQualityLogic.resolve(
            supported = emptySet(),
            choice = VideoQualityChoice.FHD,
        )

        assertEquals(VideoQualityChoice.FHD, result)
    }

    @Test
    fun supportedChoicesReturnsCanonicalAscendingOrderRegardlessOfInputOrder() {
        val result = VideoQualityLogic.supportedChoices(
            deviceSet = setOf(VideoQualityChoice.UHD, VideoQualityChoice.SD, VideoQualityChoice.FHD),
        )

        assertEquals(listOf(VideoQualityChoice.SD, VideoQualityChoice.FHD, VideoQualityChoice.UHD), result)
    }

    @Test
    fun supportedChoicesIsEmptyWhenDeviceSetIsEmpty() {
        assertEquals(emptyList<VideoQualityChoice>(), VideoQualityLogic.supportedChoices(emptySet()))
    }

    @Test
    fun showQualityChipIsFalseWhenEffectiveMatchesChoice() {
        assertFalse(VideoQualityLogic.showQualityChip(VideoQualityChoice.FHD, VideoQualityChoice.FHD))
    }

    @Test
    fun showQualityChipIsTrueWhenEffectiveDiffersFromChoice() {
        assertTrue(VideoQualityLogic.showQualityChip(VideoQualityChoice.FHD, VideoQualityChoice.HD))
    }
}
