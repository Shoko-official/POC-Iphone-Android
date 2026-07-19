package com.poc.camera.pipeline

import org.junit.Assert.assertEquals
import org.junit.Test

class FrameMergerTest {

    private fun argb(r: Int, g: Int, b: Int): Int =
        (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    private fun constantFrame(width: Int, height: Int, color: Int): Frame =
        Frame(width, height, IntArray(width * height) { color }, timestampMillis = 0L)

    private fun Frame.at(x: Int, y: Int): Triple<Int, Int, Int> {
        val pixel = argb[y * width + x]
        return Triple((pixel shr 16) and 0xFF, (pixel shr 8) and 0xFF, pixel and 0xFF)
    }

    @Test
    fun averagesFullyOverlappingFramesPerChannel() {
        val reference = constantFrame(4, 4, argb(100, 150, 200))
        val second = constantFrame(4, 4, argb(50, 250, 0))

        val merged = FrameMerger.merge(
            frames = listOf(reference, second),
            offsets = listOf(0 to 0, 0 to 0),
            accepted = listOf(true, true),
        )

        // Each channel averaged independently: (100+50)/2, (150+250)/2, (200+0)/2.
        for (y in 0 until 4) {
            for (x in 0 until 4) {
                assertEquals(Triple(75, 200, 100), merged.at(x, y))
            }
        }
        assertEquals(reference.timestampMillis, merged.timestampMillis)
    }

    @Test
    fun nonOverlapRegionsUseOnlyContributingFrames() {
        val reference = constantFrame(4, 4, argb(200, 200, 200))
        // Sampled at (x+2, y): only columns 0 and 1 have a valid frame sample.
        val second = constantFrame(4, 4, argb(0, 0, 0))

        val merged = FrameMerger.merge(
            frames = listOf(reference, second),
            offsets = listOf(0 to 0, 2 to 0),
            accepted = listOf(true, true),
        )

        for (y in 0 until 4) {
            // Overlap: average of 200 and 0 = 100.
            assertEquals(Triple(100, 100, 100), merged.at(0, y))
            assertEquals(Triple(100, 100, 100), merged.at(1, y))
            // No second-frame contribution: reference value preserved.
            assertEquals(Triple(200, 200, 200), merged.at(2, y))
            assertEquals(Triple(200, 200, 200), merged.at(3, y))
        }
    }

    @Test
    fun rejectedFramesAreExcludedFromTheAverage() {
        val reference = constantFrame(2, 2, argb(80, 80, 80))
        val accepted = constantFrame(2, 2, argb(120, 120, 120))
        val rejected = constantFrame(2, 2, argb(0, 0, 0))

        val merged = FrameMerger.merge(
            frames = listOf(reference, accepted, rejected),
            offsets = listOf(0 to 0, 0 to 0, 0 to 0),
            accepted = listOf(true, true, false),
        )

        // Only reference and the accepted frame count: (80+120)/2 = 100.
        assertEquals(Triple(100, 100, 100), merged.at(0, 0))
    }
}
