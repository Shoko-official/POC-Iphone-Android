package com.poc.camera.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GhostRejectorTest {

    @Test
    fun identicalPixelGetsFullWeight() {
        assertEquals(1.0, GhostRejector().weight(0.0, sigma = 5.0), 1e-12)
    }

    @Test
    fun grosslyDifferentPixelGetsZeroWeight() {
        assertEquals(0.0, GhostRejector().weight(1000.0, sigma = 5.0), 1e-12)
    }

    @Test
    fun rampMidpointIsHalf() {
        // lo = 3, hi = 8 at sigma 1; midpoint |diff| = 5.5 -> (8 - 5.5)/(8 - 3) = 0.5.
        val rejector = GhostRejector(loScale = 3.0, hiScale = 8.0, minSigma = 1.0)
        assertEquals(0.5, rejector.weight(5.5, sigma = 1.0), 1e-12)
        // Sign of the difference does not matter.
        assertEquals(0.5, rejector.weight(-5.5, sigma = 1.0), 1e-12)
    }

    @Test
    fun sigmaIsFlooredAtMinSigma() {
        // With sigma below the floor, thresholds use minSigma (1.0): lo = 3, hi = 8.
        val rejector = GhostRejector(loScale = 3.0, hiScale = 8.0, minSigma = 1.0)
        assertEquals(1.0, rejector.weight(2.9, sigma = 0.0), 1e-12)
        assertEquals(0.0, rejector.weight(8.1, sigma = 0.0), 1e-12)
    }

    @Test
    fun estimateSigmaIsRobustToOutliers() {
        // Symmetric background at +/-4 (median 0) plus sparse huge outliers. A robust
        // MAD estimate reflects the background (1.4826 * 4), ignoring the outliers.
        val diffs = DoubleArray(420)
        for (i in 0 until 200) diffs[i] = 4.0
        for (i in 200 until 400) diffs[i] = -4.0
        for (i in 400 until 410) diffs[i] = 10_000.0
        for (i in 410 until 420) diffs[i] = -10_000.0

        val sigma = GhostRejector().estimateSigma(diffs)

        assertEquals(1.4826 * 4.0, sigma, 1e-6)
        assertTrue("outliers must not inflate the noise estimate", sigma < 10.0)
    }

    @Test
    fun weightsRejectAMovedObjectButKeepStaticBackground() {
        // Reference is a flat mid-gray; the frame moves a bright block into one
        // corner. Sigma is estimated over the whole (mostly static) difference; the
        // block's large difference must be rejected while the background stays full.
        val size = 32
        val gray = 100
        val bright = 220
        val refLuma = 100.0
        val diffs = DoubleArray(size * size)
        for (y in 0 until size) {
            for (x in 0 until size) {
                val frameLuma = if (x < 6 && y < 6) bright else gray
                diffs[y * size + x] = frameLuma - refLuma
            }
        }
        val rejector = GhostRejector()
        val sigma = rejector.estimateSigma(diffs)

        // Background pixels agree with the reference exactly -> full weight.
        assertEquals(1.0, rejector.weight(0.0, sigma), 1e-12)
        // The moved block differs by 120 luma -> fully rejected.
        assertEquals(0.0, rejector.weight((bright - gray).toDouble(), sigma), 1e-12)
    }
}
