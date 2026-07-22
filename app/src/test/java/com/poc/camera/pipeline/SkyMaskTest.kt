package com.poc.camera.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit proofs for the pure pieces of [SkyMask]: the blue-cyan chroma-sector membership, the
 * dusk-tolerant luma prior, the vertical position ramp endpoints, and the invariants that a
 * grayscale frame yields an all-zero mask and that the position prior excludes the lower
 * image (reflections/water by design). Closed-form where possible, so deterministic.
 */
class SkyMaskTest {

    // --- chroma sector membership --------------------------------------------------

    @Test
    fun chromaFiresOnBlueAndCyanAndRejectsEverythingElse() {
        // Clear blue sky and cyan sit inside the sector (Cb positive, Cr non-positive).
        assertTrue("blue sky must score high", SkyMask.chromaLikelihood(110.0, 155.0, 210.0) > 0.9)
        assertTrue("cyan must score high", SkyMask.chromaLikelihood(120.0, 210.0, 215.0) > 0.7)
        // Green (Cb negative), red (Cb negative) and purple (Cb positive but Cr positive) are out.
        assertTrue("green must be rejected", SkyMask.chromaLikelihood(60.0, 110.0, 45.0) < 0.05)
        assertTrue("red must be rejected", SkyMask.chromaLikelihood(200.0, 30.0, 40.0) < 0.05)
        assertTrue("purple must be rejected (positive Cr)", SkyMask.chromaLikelihood(90.0, 25.0, 120.0) < 0.05)
        // A neutral pixel has zero chroma -> exactly zero on the Cb term.
        assertEquals("neutral must be exactly 0", 0.0, SkyMask.chromaLikelihood(128.0, 128.0, 128.0), 0.0)
    }

    // --- luma prior ----------------------------------------------------------------

    @Test
    fun lumaPriorRewardsBrightAndKeepsDuskAtTheFloor() {
        val bright = SkyMask.lumaPrior(210.0, 210.0, 210.0)
        val dusk = SkyMask.lumaPrior(50.0, 50.0, 50.0)
        assertEquals("bright sky must read full", 1.0, bright, 1e-9)
        assertEquals("dusk sky must keep the floor, not zero", SkyMask.LUMA_FLOOR, dusk, 1e-9)
        assertTrue("luma prior must never floor below the dusk floor", dusk >= SkyMask.LUMA_FLOOR - 1e-9)
        assertTrue("mid luma sits between floor and full", SkyMask.lumaPrior(120.0, 120.0, 120.0) in (SkyMask.LUMA_FLOOR + 1e-6)..(1.0 - 1e-6))
    }

    // --- position ramp endpoints ---------------------------------------------------

    @Test
    fun positionRampHitsItsDocumentedEndpoints() {
        assertEquals("top of image is full sky weight", 1.0, SkyMask.positionWeight(0.0), 1e-9)
        assertEquals(
            "mid fraction decays to the documented mid weight",
            SkyMask.POSITION_MID_WEIGHT,
            SkyMask.positionWeight(SkyMask.POSITION_MID_FRACTION),
            1e-9,
        )
        assertEquals("zero fraction is exactly 0", 0.0, SkyMask.positionWeight(SkyMask.POSITION_ZERO_FRACTION), 1e-9)
        assertEquals("below the zero fraction stays 0", 0.0, SkyMask.positionWeight(0.95), 1e-9)
    }

    @Test
    fun positionRampIsMonotonicallyNonIncreasing() {
        var prev = Double.MAX_VALUE
        var f = 0.0
        while (f <= 1.0) {
            val w = SkyMask.positionWeight(f)
            assertTrue("position weight must be non-increasing at f=$f", w <= prev + 1e-12)
            prev = w
            f += 0.02
        }
    }

    // --- compute: grayscale zero, position exclusion, determinism ------------------

    @Test
    fun grayscaleFrameYieldsAllZeroMask() {
        val gray = grayFrame(32, 32, 150)
        val mask = SkyMask.compute(gray)
        assertTrue("a grayscale frame must produce an all-zero sky mask", mask.all { it == 0.0 })
    }

    @Test
    fun positionPriorExcludesTheLowerImage() {
        // A frame that is BLUE SKY everywhere: only the position prior separates top from bottom,
        // so the top band must fire strongly and the bottom band (a sky reflection in water, by
        // design) must read ~0.
        val w = 32
        val h = 100
        val blue = (0xFF shl 24) or (110 shl 16) or (155 shl 8) or 210
        val mask = SkyMask.compute(Frame(w, h, IntArray(w * h) { blue }, 0L))
        val topMean = bandMean(mask, w, 0, 10)
        val bottomMean = bandMean(mask, w, 90, 100)
        assertTrue("top-of-image blue must fire (was $topMean)", topMean > 0.8)
        assertTrue("bottom-of-image blue must be excluded by position (was $bottomMean)", bottomMean < 0.05)
    }

    @Test
    fun rowOffsetShiftsThePositionPrior() {
        // The same blue tile read as the TOP of the image fires; read as the BOTTOM (via a large
        // rowOffset into a tall image) is excluded -- this is exactly how a tiled finish keeps the
        // position prior correct per tile.
        val w = 16
        val h = 16
        val blue = (0xFF shl 24) or (110 shl 16) or (155 shl 8) or 210
        val tile = Frame(w, h, IntArray(w * h) { blue }, 0L)
        val asTop = SkyMask.compute(tile, rowOffset = 0, imageHeight = 200)
        val asBottom = SkyMask.compute(tile, rowOffset = 180, imageHeight = 200)
        assertTrue("tile at the image top fires", bandMean(asTop, w, 0, h) > 0.8)
        assertTrue("same tile at the image bottom is excluded", bandMean(asBottom, w, 0, h) < 0.05)
    }

    @Test
    fun computeIsDeterministic() {
        val frame = mixedFrame()
        val a = SkyMask.compute(frame)
        val b = SkyMask.compute(frame)
        assertEquals(a.size, b.size)
        for (i in a.indices) assertEquals(a[i], b[i], 0.0)
    }

    // --- helpers -------------------------------------------------------------------

    private fun grayFrame(w: Int, h: Int, v: Int): Frame {
        val px = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        return Frame(w, h, IntArray(w * h) { px }, 0L)
    }

    private fun mixedFrame(): Frame {
        val w = 24
        val h = 24
        val out = IntArray(w * h)
        for (i in out.indices) {
            val r = (i * 7) and 0xFF
            val g = (i * 5 + 40) and 0xFF
            val b = (i * 3 + 120) and 0xFF
            out[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        return Frame(w, h, out, 0L)
    }

    private fun bandMean(mask: DoubleArray, width: Int, y0: Int, y1: Int): Double {
        var sum = 0.0
        var n = 0
        for (y in y0 until y1) for (x in 0 until width) {
            sum += mask[y * width + x]; n++
        }
        return sum / n
    }
}
