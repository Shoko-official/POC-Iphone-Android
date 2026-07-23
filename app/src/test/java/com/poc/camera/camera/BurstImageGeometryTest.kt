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
    fun twelveMegapixelSensorHalvesUnderAnEightMegapixelBound() {
        // A tighter budget (e.g. ~8 MP) halves a 12.19 MP sensor to ~3.0 MP.
        val sample = BurstImageGeometry.inSampleSizeFor(4032, 3024, 8_000_000)
        assertEquals(2, sample)
        assertTrue((4032 / sample).toLong() * (3024 / sample) <= 8_000_000)
    }

    @Test
    fun fiftyMegapixelSensorDownsamplesByFour() {
        // 8160x6144 (~50.1 MP): /2 -> 4080x3072 (~12.53 MP, just over the 12.5 MP bound),
        // /4 -> 2040x1536 (~3.1 MP) <= bound.
        val sample = BurstImageGeometry.inSampleSizeFor(8160, 6144, BurstImageGeometry.MAX_BURST_PIXELS)
        assertEquals(4, sample)
        assertTrue((8160 / sample).toLong() * (6144 / sample) <= BurstImageGeometry.MAX_BURST_PIXELS)
    }

    // budget = (heapMb / 2) MB / (4 * frames + 32) B/px, clamped to [MIN, MAX] - the exact
    // contract of maxBurstPixelsFor. Recomputed here so each case asserts the real number.
    private fun budget(heapMb: Int, frames: Int): Long =
        heapMb.toLong() * 1024 * 1024 / 2 / (4L * frames + BurstImageGeometry.MERGE_ACCUMULATOR_BYTES_PER_PIXEL)

    @Test
    fun budgetScalesDownWithFrameCount() {
        // 512 MB large heap: 6-frame burst ~4.79 MP, 9-frame ~3.94 MP, 12-frame night ~3.35 MP.
        assertEquals(budget(512, 6).toInt(), BurstImageGeometry.maxBurstPixelsFor(512, 6))
        assertEquals(budget(512, 9).toInt(), BurstImageGeometry.maxBurstPixelsFor(512, 9))
        assertEquals(budget(512, 12).toInt(), BurstImageGeometry.maxBurstPixelsFor(512, 12))
        assertTrue(BurstImageGeometry.maxBurstPixelsFor(512, 12) < BurstImageGeometry.maxBurstPixelsFor(512, 9))
        assertTrue(BurstImageGeometry.maxBurstPixelsFor(512, 9) < BurstImageGeometry.maxBurstPixelsFor(512, 6))
        // The regression this bound fixes (issue #171): night's 12-frame merge must fit in
        // half the heap - inputs + accumulators at the returned bound stay under 256 MB.
        val nightPixels = BurstImageGeometry.maxBurstPixelsFor(512, 12).toLong()
        assertTrue(nightPixels * (4 * 12 + 32) <= 512L * 1024 * 1024 / 2)
    }

    @Test
    fun hugeHeapIsCappedAtTheNativeCeiling() {
        // A 4 GB heap would budget far past 12.5 MP; the native ceiling caps it.
        assertEquals(BurstImageGeometry.MAX_BURST_PIXELS, BurstImageGeometry.maxBurstPixelsFor(4096, 6))
    }

    @Test
    fun tinyHeapIsFlooredAtTheMinimumUsableResolution() {
        // A 128 MB heap with a 12-frame night burst budgets under 2 MP; the floor holds.
        assertEquals(BurstImageGeometry.MIN_BURST_PIXELS, BurstImageGeometry.maxBurstPixelsFor(128, 12))
    }

    @Test
    fun nonPositiveMemoryClassFallsBackToTheConservativeHeap() {
        // An unknown memory class budgets as a 192 MB standard heap, never the caller's zero.
        assertEquals(
            BurstImageGeometry.maxBurstPixelsFor(BurstImageGeometry.FALLBACK_MEMORY_CLASS_MB, 6),
            BurstImageGeometry.maxBurstPixelsFor(0, 6),
        )
        assertEquals(
            BurstImageGeometry.maxBurstPixelsFor(BurstImageGeometry.FALLBACK_MEMORY_CLASS_MB, 6),
            BurstImageGeometry.maxBurstPixelsFor(-1, 6),
        )
    }

    @Test
    fun nonPositiveFrameCountIsTreatedAsOne() {
        assertEquals(BurstImageGeometry.maxBurstPixelsFor(512, 1), BurstImageGeometry.maxBurstPixelsFor(512, 0))
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
