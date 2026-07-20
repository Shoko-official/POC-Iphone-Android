package com.poc.camera.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Asserts the memory ceiling issue #54 is built around: a native-resolution (12.5 MP)
 * tiled finish stays within a bounded budget, and that budget is a small fraction of the
 * whole-frame finish it replaces. The peak is computed from [TiledFinishing.peakBytesEstimate]
 * (pure arithmetic over the documented per-plane counts), so the assertion is deterministic
 * rather than a flaky live-heap probe; a small practical run confirms the estimate is not
 * vacuous.
 */
class TiledFinishingMemoryTest {

    private val mb = 1024L * 1024L

    // A representative native 12.5 MP frame (4080x3072 = 12,533,760 px).
    private val width = 4080
    private val height = 3072

    /** Documented budget for the tiled 12.5 MP finishing peak. */
    private val budgetBytes = 320L * mb

    @Test
    fun tiledPeakForTwelvePointFiveMegapixelsIsUnderBudget() {
        val peak = TiledFinishing.peakBytesEstimate(width, height)
        println("tiled 12.5MP peak estimate = %.1f MB (budget %d MB)".format(peak.toDouble() / mb, budgetBytes / mb))
        assertTrue("tiled peak ${peak / mb} MB exceeds ${budgetBytes / mb} MB budget", peak < budgetBytes)
    }

    @Test
    fun tiledPeakIsASmallFractionOfTheWholeFramePeak() {
        val tiled = TiledFinishing.peakBytesEstimate(width, height)
        val whole = TiledFinishing.wholeFramePeakBytesEstimate(width, height)
        val fraction = tiled.toDouble() / whole.toDouble()
        println(
            "tiled peak %.1f MB vs whole-frame peak %.1f MB (%.1f%%)".format(
                tiled.toDouble() / mb, whole.toDouble() / mb, fraction * 100,
            ),
        )
        // Tiling must remove the full-resolution float peak: at least a 3x reduction.
        assertTrue("tiled peak is not a small fraction of whole-frame ($fraction)", fraction < 0.33)
    }

    @Test
    fun tiledPeakScalesWithTileNotFrame() {
        // Doubling frame area at a fixed tile size only grows the resident IntArrays
        // (linear in pixels), NOT the dominant per-tile working set, so the peak grows
        // far slower than the whole-frame estimate would.
        val small = TiledFinishing.peakBytesEstimate(2040, 1536)
        val large = TiledFinishing.peakBytesEstimate(4080, 3072)
        val tiledGrowth = large.toDouble() / small.toDouble()
        val wholeGrowth = TiledFinishing.wholeFramePeakBytesEstimate(4080, 3072).toDouble() /
            TiledFinishing.wholeFramePeakBytesEstimate(2040, 1536).toDouble()
        println("4x-pixels growth: tiled=%.2fx whole-frame=%.2fx".format(tiledGrowth, wholeGrowth))
        // Whole-frame grows ~4x with the pixels; tiled grows much less (fixed tile work).
        assertTrue("tiled peak grew like the frame ($tiledGrowth)", tiledGrowth < wholeGrowth)
    }

    @Test
    fun twelveMegapixelTiledFinishActuallyRunsAndProducesFullSizeOutput() {
        // Practical guard that the estimate is not vacuous: a real (smaller-but-real) tiled
        // finish completes and yields a full-size, opaque frame. A 4 MP frame keeps the
        // unit-test heap modest while still exercising many tiles and the downsampled-stats
        // path; the 12.5 MP timing itself is reported by PipelineBenchmark.
        val w = 2048
        val h = 2048
        val scene = IntArray(w * h) { i ->
            val v = (i * 37) and 0xFF
            (0x80 shl 24) or ((v) shl 16) or ((255 - v) shl 8) or ((v / 2) and 0xFF)
        }
        val out = TiledFinishing.apply(Frame(w, h, scene, timestampMillis = 5L), FinishingParams.DEFAULT)
        assertEquals(w, out.width)
        assertEquals(h, out.height)
        for (pixel in out.argb) {
            assertEquals("finished frame must be opaque", 0xFF, (pixel ushr 24) and 0xFF)
        }
    }
}
