package com.poc.camera.camera

import org.junit.Assert.assertEquals
import org.junit.Test

class CinematicConfigResolverTest {

    @Test
    fun usesTwentyFourFpsWhenExactRangeIsSupported() {
        val result = CinematicConfigResolver.resolve(
            supportedFpsRanges = listOf(15..15, 24..24, 30..30),
            supportedStabilizationModes = emptyList(),
        )

        assertEquals(true, result.use24Fps)
    }

    @Test
    fun usesTwentyFourFpsWhenAContainingRangeIsSupported() {
        val result = CinematicConfigResolver.resolve(
            supportedFpsRanges = listOf(15..30),
            supportedStabilizationModes = emptyList(),
        )

        assertEquals(true, result.use24Fps)
    }

    @Test
    fun fallsBackWhenTwentyFourFpsIsNotSupported() {
        val result = CinematicConfigResolver.resolve(
            supportedFpsRanges = listOf(30..30, 60..60),
            supportedStabilizationModes = emptyList(),
        )

        assertEquals(false, result.use24Fps)
    }

    @Test
    fun fallsBackWhenNoFpsRangesAreReported() {
        val result = CinematicConfigResolver.resolve(
            supportedFpsRanges = emptyList(),
            supportedStabilizationModes = emptyList(),
        )

        assertEquals(false, result.use24Fps)
    }

    @Test
    fun enablesStabilizationWhenOnModeIsSupported() {
        val result = CinematicConfigResolver.resolve(
            supportedFpsRanges = emptyList(),
            supportedStabilizationModes = listOf(0, 1),
        )

        assertEquals(StabilizationChoice.ON, result.stabilization)
    }

    @Test
    fun fallsBackToUnstabilizedWhenOnlyOffModeIsSupported() {
        val result = CinematicConfigResolver.resolve(
            supportedFpsRanges = emptyList(),
            supportedStabilizationModes = listOf(0),
        )

        assertEquals(StabilizationChoice.OFF, result.stabilization)
    }

    @Test
    fun fallsBackToUnstabilizedWhenNoModesAreReported() {
        val result = CinematicConfigResolver.resolve(
            supportedFpsRanges = emptyList(),
            supportedStabilizationModes = emptyList(),
        )

        assertEquals(StabilizationChoice.OFF, result.stabilization)
    }

    @Test
    fun resolvesBothTwentyFourFpsAndStabilizationWhenFullySupported() {
        val result = CinematicConfigResolver.resolve(
            supportedFpsRanges = listOf(24..24),
            supportedStabilizationModes = listOf(0, 1),
        )

        assertEquals(CinematicConfig(use24Fps = true, stabilization = StabilizationChoice.ON), result)
    }
}
