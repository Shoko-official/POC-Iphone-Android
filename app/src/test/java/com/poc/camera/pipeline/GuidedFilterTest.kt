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

    @Test
    fun crossGuidedWithGuideEqualToInputMatchesSelfGuided() {
        // The cross-guided estimator reduces to the self-guided one when guide == input
        // (cov(I,I) = var(I)), so the two must agree to within float rounding.
        var state = 12345L
        val image = DoubleArray(48 * 40) {
            state = (state * 1664525L + 1013904223L) and 0xFFFFFFFFL
            ((state ushr 8) and 0xFF).toDouble()
        }
        val self = GuidedFilter.selfGuided(image, 48, 40, 8, eps)
        val cross = GuidedFilter.guided(image, image, 48, 40, 8, eps)
        for (i in image.indices) assertEquals(self[i], cross[i], 1e-6)
    }

    @Test
    fun flatGuideReducesToBoxMeanOfInput() {
        // With a flat guide, varG = 0 and covIG = 0 everywhere, so a = 0 and b = meanI:
        // the output carries no guide structure and collapses to boxMean(b) =
        // boxMean(boxMean(input)) (the standard guided-filter form, mean of a and b).
        val width = 40
        val height = 32
        var state = 777L
        val input = DoubleArray(width * height) {
            state = (state * 1664525L + 1013904223L) and 0xFFFFFFFFL
            ((state ushr 8) and 0xFF).toDouble()
        }
        val flatGuide = DoubleArray(width * height) { 91.0 }
        val out = GuidedFilter.guided(input, flatGuide, width, height, 6, eps)
        val doubleMean = BoxBlur.blur(BoxBlur.blur(input, width, height, 6), width, height, 6)
        for (i in input.indices) assertEquals(doubleMean[i], out[i], 1e-6)
    }

    @Test
    fun lumaGuidedChromaEdgeIsPreserved() {
        // A colour edge whose chroma step co-occurs with a luma step: the luma guide
        // steps, so the guided filter must carry the chroma step through nearly intact
        // (the same edge-preservation the self-guided halo test asserts).
        val width = 128
        val height = 24
        val half = width / 2
        // Luma steps 60 -> 180; chroma steps +80 -> -80 across the same boundary.
        val luma = DoubleArray(width * height) { i -> if ((i % width) < half) 60.0 else 180.0 }
        val chroma = DoubleArray(width * height) { i -> if ((i % width) < half) 80.0 else -80.0 }
        val chromaStep = 160.0

        val out = GuidedFilter.guided(chroma, luma, width, height, 16, eps)

        // Interior of each side (away from the boundary) keeps its chroma value, and no
        // sample overshoots the true chroma range.
        for (i in out.indices) {
            assertTrue("chroma ${out[i]} overshoots below -80", out[i] >= -80.0 - 0.01)
            assertTrue("chroma ${out[i]} overshoots above 80", out[i] <= 80.0 + 0.01)
        }
        // The full step survives across the boundary (sampled a few px on each side).
        val y = height / 2
        val leftChroma = out[y * width + (half - 4)]
        val rightChroma = out[y * width + (half + 4)]
        assertTrue(
            "preserved chroma step ${leftChroma - rightChroma} should keep >=94% of $chromaStep",
            (leftChroma - rightChroma) >= 0.94 * chromaStep,
        )
    }
}
