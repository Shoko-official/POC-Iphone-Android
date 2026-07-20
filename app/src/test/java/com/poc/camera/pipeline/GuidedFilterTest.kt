package com.poc.camera.pipeline

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class GuidedFilterTest {

    private val eps = (0.03 * 255.0) * (0.03 * 255.0)

    @Test
    fun flatImageIsPreservedExactly() {
        // A flat window has zero variance, so a = 0 and base collapses to the local
        // mean, which for a flat image is the image itself: detail is exactly zero.
        val image = DoubleArray(32 * 32) { 137.0 }
        val base = GuidedFilter.selfGuided(image, 32, 32, 8, eps)
        for (i in image.indices) assertEquals(image[i], base[i], 1e-6)
    }

    @Test
    fun strongStepEdgeIsPreservedWithoutHalo() {
        // THE halo test: a hard vertical step must survive the base/detail split with
        // no smearing and no over/undershoot on either side. An unguided box blur of
        // radius 16 would ramp the step over ~32 px (deviations up to ~0.5*amplitude).
        val width = 128
        val height = 24
        val low = 50.0
        val high = 200.0
        val amplitude = high - low
        val image = DoubleArray(width * height) { i -> if ((i % width) < width / 2) low else high }

        val base = GuidedFilter.selfGuided(image, width, height, 16, eps)

        var maxDeviation = 0.0
        for (i in image.indices) {
            maxDeviation = maxOf(maxDeviation, abs(base[i] - image[i]))
            // No overshoot beyond the true value range (within float tolerance).
            assertTrue("base ${base[i]} overshoots below $low", base[i] >= low - 0.01)
            assertTrue("base ${base[i]} overshoots above $high", base[i] <= high + 0.01)
        }
        // Guided filter keeps the step nearly intact: measured ~1% of amplitude.
        assertTrue(
            "max deviation $maxDeviation should stay well under 6% of the $amplitude step",
            maxDeviation < 0.06 * amplitude,
        )
    }

    @Test
    fun smoothGradientIsFollowed() {
        // A gentle linear ramp carries no edges, so the base should track it closely
        // away from the clamped borders.
        val width = 128
        val height = 16
        val image = DoubleArray(width * height) { i -> (i % width).toDouble() / (width - 1) * 255.0 }

        val base = GuidedFilter.selfGuided(image, width, height, 16, eps)

        var maxDeviation = 0.0
        for (y in 0 until height) {
            // Skip the radius-wide clamped border where the mean is intentionally off.
            for (x in 16 until width - 16) {
                maxDeviation = maxOf(maxDeviation, abs(base[y * width + x] - image[y * width + x]))
            }
        }
        assertTrue("base should follow the ramp, max deviation $maxDeviation", maxDeviation < 2.0)
    }

    @Test
    fun isDeterministic() {
        var state = 999L
        val image = DoubleArray(40 * 40) {
            state = (state * 1664525L + 1013904223L) and 0xFFFFFFFFL
            ((state ushr 8) and 0xFF).toDouble()
        }
        val first = GuidedFilter.selfGuided(image, 40, 40, 12, eps)
        val second = GuidedFilter.selfGuided(image, 40, 40, 12, eps)
        assertTrue(first.contentEquals(second))
    }

    @Test
    fun nonPositiveEpsIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            GuidedFilter.selfGuided(DoubleArray(16), 4, 4, 1, 0.0)
        }
    }
}
