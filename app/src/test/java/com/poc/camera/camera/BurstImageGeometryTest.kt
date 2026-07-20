package com.poc.camera.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BurstImageGeometryTest {

    @Test
    fun twelveMegapixelSensorDecodesAtNativeUnderTheDefaultBound() {
        // 4032x3024 (~12.19 MP) <= 12.5 MP native bound -> inSampleSize 1 (full resolution).
        // This is the issue #54 change: with tiled finishing removing the float peak, a
        // 12 MP sensor now merges at native resolution on a high-memory device.
        val sample = BurstImageGeometry.inSampleSizeFor(4032, 3024, BurstImageGeometry.MAX_BURST_PIXELS)
        assertEquals(1, sample)
        assertTrue((4032 / sample).toLong() * (3024 / sample) <= BurstImageGeometry.MAX_BURST_PIXELS)
    }

    @Test
    fun twelveMegapixelSensorStillHalvesUnderTheConservativeBound() {
        // Low-memory devices keep the ~8 MP bound: 12.19 MP overshoots it, so /2 -> ~3.0 MP.
        val sample = BurstImageGeometry.inSampleSizeFor(4032, 3024, BurstImageGeometry.CONSERVATIVE_MAX_BURST_PIXELS)
        assertEquals(2, sample)
        assertTrue((4032 / sample).toLong() * (3024 / sample) <= BurstImageGeometry.CONSERVATIVE_MAX_BURST_PIXELS)
    }

    @Test
    fun fiftyMegapixelSensorDownsamplesByFour() {
        // 8160x6144 (~50.1 MP): /2 -> 4080x3072 (~12.53 MP, just over the 12.5 MP bound),
        // /4 -> 2040x1536 (~3.1 MP) <= bound.
        val sample = BurstImageGeometry.inSampleSizeFor(8160, 6144, BurstImageGeometry.MAX_BURST_PIXELS)
        assertEquals(4, sample)
        assertTrue((8160 / sample).toLong() * (6144 / sample) <= BurstImageGeometry.MAX_BURST_PIXELS)
    }

    @Test
    fun memoryClassSelectsNativeBoundOnlyAboveTheThreshold() {
        // Below the high-memory threshold -> conservative ~8 MP bound.
        assertEquals(
            BurstImageGeometry.CONSERVATIVE_MAX_BURST_PIXELS,
            BurstImageGeometry.maxBurstPixelsFor(BurstImageGeometry.HIGH_MEMORY_CLASS_MB - 1),
        )
        // At or above it -> native ~12.5 MP bound.
        assertEquals(
            BurstImageGeometry.MAX_BURST_PIXELS,
            BurstImageGeometry.maxBurstPixelsFor(BurstImageGeometry.HIGH_MEMORY_CLASS_MB),
        )
        assertEquals(
            BurstImageGeometry.MAX_BURST_PIXELS,
            BurstImageGeometry.maxBurstPixelsFor(512),
        )
    }

    @Test
    fun nonPositiveMemoryClassFallsBackToConservativeBound() {
        // An unknown / unreported memory class must not unlock the native bound.
        assertEquals(BurstImageGeometry.CONSERVATIVE_MAX_BURST_PIXELS, BurstImageGeometry.maxBurstPixelsFor(0))
        assertEquals(BurstImageGeometry.CONSERVATIVE_MAX_BURST_PIXELS, BurstImageGeometry.maxBurstPixelsFor(-1))
    }

    @Test
    fun imageAlreadyWithinBoundIsNotDownsampled() {
        // 1920x1080 (~2.07 MP) is under the bound, so no downscale.
        assertEquals(1, BurstImageGeometry.inSampleSizeFor(1920, 1080, BurstImageGeometry.MAX_BURST_PIXELS))
    }

    @Test
    fun sampleSizeIsAlwaysAPowerOfTwo() {
        // A very small bound forces several doublings; the result stays a power of two.
        val sample = BurstImageGeometry.inSampleSizeFor(4032, 3024, maxPixels = 100_000)
        assertTrue("expected power of two but was $sample", sample > 0 && (sample and (sample - 1)) == 0)
        assertTrue((4032 / sample).toLong() * (3024 / sample) <= 100_000)
    }

    @Test
    fun chosenSampleIsTheSmallestThatFits() {
        // The next-smaller sample (half) must overshoot the bound, proving minimality.
        val sample = BurstImageGeometry.inSampleSizeFor(4032, 3024, BurstImageGeometry.MAX_BURST_PIXELS)
        if (sample > 1) {
            val smaller = sample / 2
            assertTrue((4032 / smaller).toLong() * (3024 / smaller) > BurstImageGeometry.MAX_BURST_PIXELS)
        }
    }

    @Test
    fun nonPositiveDimensionsFallBackToNoDownsample() {
        assertEquals(1, BurstImageGeometry.inSampleSizeFor(0, 0, BurstImageGeometry.MAX_BURST_PIXELS))
        assertEquals(1, BurstImageGeometry.inSampleSizeFor(-4, 100, BurstImageGeometry.MAX_BURST_PIXELS))
    }

    @Test
    fun rejectsNonPositiveMaxPixels() {
        assertThrows(IllegalArgumentException::class.java) {
            BurstImageGeometry.inSampleSizeFor(4032, 3024, maxPixels = 0)
        }
    }

    @Test
    fun quarterTurnsSwapDimensions() {
        assertTrue(BurstImageGeometry.swapsDimensions(90))
        assertTrue(BurstImageGeometry.swapsDimensions(270))
        // Negative and over-360 multiples normalize the same way.
        assertTrue(BurstImageGeometry.swapsDimensions(-90))
        assertTrue(BurstImageGeometry.swapsDimensions(450))
    }

    @Test
    fun uprightAndHalfTurnsKeepDimensions() {
        assertFalse(BurstImageGeometry.swapsDimensions(0))
        assertFalse(BurstImageGeometry.swapsDimensions(180))
        assertFalse(BurstImageGeometry.swapsDimensions(360))
        assertFalse(BurstImageGeometry.swapsDimensions(-180))
    }
}
