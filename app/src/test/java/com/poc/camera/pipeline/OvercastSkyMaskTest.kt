package com.poc.camera.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit proofs for the pure pieces of [OvercastSkyMask]: the non-planar detail-energy texture
 * measure (zero on flat content AND on a linear ramp interior, high on texture), the
 * membership endpoints of each prior, the neutrality boundary that keeps every skin tone and
 * blue sky out, the shared position ramp exclusion of the lower image, and determinism.
 * Closed-form where possible, so deterministic.
 */
class OvercastSkyMaskTest {

    // --- texture measure: flat and ramps are smooth, texture is not ----------------

    @Test
    fun detailEnergyIsZeroOnFlatContent() {
        val flat = grayFrame(32, 32, 200)
        val energy = OvercastSkyMask.detailEnergy(flat)
        assertTrue("flat content must have exactly zero detail energy", energy.all { it == 0.0 })
    }

    @Test
    fun detailEnergyIsNearZeroInsideALinearRamp() {
        // The measure must be SLOPE-BLIND: a smooth gradient (an overcast sky) is not
        // texture. A plain box variance would read ~52 code^2 on this ramp (slope 1/row at
        // radius 12); the plane-residual energy reads ~0 in the interior.
        val w = 80
        val h = 80
        val out = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val v = (140 + y).coerceAtMost(255)
            out[y * w + x] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }
        val energy = OvercastSkyMask.detailEnergy(Frame(w, h, out, 0L))
        val interior = interiorMean(energy, w, h, margin = 2 * OvercastSkyMask.TEXTURE_RADIUS + 1)
        assertTrue("ramp interior detail energy must be near zero (was $interior)", interior < 2.0)
    }

    @Test
    fun detailEnergyIsHighOnTexturedContent() {
        val frame = checkerFrame(48, 48, block = 6, lo = 170, hi = 230)
        val energy = OvercastSkyMask.detailEnergy(frame)
        val mean = energy.average()
        assertTrue(
            "textured content must exceed the VAR_HI knee (mean energy $mean)",
            mean > OvercastSkyMask.VAR_HI,
        )
    }

    // --- membership endpoints ------------------------------------------------------

    @Test
    fun texturePriorHitsItsDocumentedEndpoints() {
        assertEquals("smooth (<= VAR_LO) reads full", 1.0, OvercastSkyMask.texturePrior(OvercastSkyMask.VAR_LO), 1e-9)
        assertEquals("textured (>= VAR_HI) reads zero", 0.0, OvercastSkyMask.texturePrior(OvercastSkyMask.VAR_HI), 1e-9)
        val mid = OvercastSkyMask.texturePrior((OvercastSkyMask.VAR_LO + OvercastSkyMask.VAR_HI) / 2.0)
        assertEquals("midpoint reads half", 0.5, mid, 1e-9)
        var prev = Double.MAX_VALUE
        var v = 0.0
        while (v <= OvercastSkyMask.VAR_HI * 1.5) {
            val p = OvercastSkyMask.texturePrior(v)
            assertTrue("texture prior must be non-increasing at $v", p <= prev + 1e-12)
            prev = p
            v += 1.0
        }
    }

    @Test
    fun brightPriorHasNoDuskFloor() {
        assertEquals("bright gray reads full", 1.0, OvercastSkyMask.brightPrior(210.0, 210.0, 210.0), 1e-9)
        assertEquals("mid gray reads exactly zero -- no dusk floor", 0.0, OvercastSkyMask.brightPrior(120.0, 120.0, 120.0), 0.0)
        val between = OvercastSkyMask.brightPrior(160.0, 160.0, 160.0)
        assertTrue("between the knees sits strictly inside (0, 1)", between > 0.0 && between < 1.0)
    }

    // --- neutrality boundary -------------------------------------------------------

    @Test
    fun neutralityFiresOnGrayAndRejectsBlueSky() {
        assertEquals("pure gray is fully neutral", 1.0, OvercastSkyMask.neutralityPrior(200.0, 200.0, 200.0), 1e-9)
        assertEquals("blue sky is rejected (SkyMask's territory)", 0.0, OvercastSkyMask.neutralityPrior(110.0, 155.0, 210.0), 0.0)
        assertEquals("foliage green is rejected", 0.0, OvercastSkyMask.neutralityPrior(60.0, 110.0, 45.0), 0.0)
    }

    @Test
    fun everySkinToneSitsAboveTheNeutralityCeiling() {
        // The skin-chart Fitzpatrick I-VI patches plus the landscape/backlit skin patches:
        // their opponent chroma magnitudes measure 32..67 codes, far above CHROMA_HI = 12,
        // so bright smooth skin can NEVER fire the overcast prior and no skin-mask
        // exclusion is needed (see the class doc).
        val skinTones = listOf(
            Triple(247.0, 214.0, 185.0), // light
            Triple(241.0, 194.0, 167.0), // fair
            Triple(222.0, 168.0, 128.0), // medium
            Triple(186.0, 129.0, 92.0), // olive
            Triple(135.0, 90.0, 62.0), // brown
            Triple(88.0, 58.0, 42.0), // deep
            Triple(206.0, 150.0, 120.0), // landscape skin patch
            Triple(54.0, 36.0, 26.0), // backlit skin patch
        )
        for ((r, g, b) in skinTones) {
            assertEquals(
                "skin tone ($r, $g, $b) must read exactly zero neutrality",
                0.0,
                OvercastSkyMask.neutralityPrior(r, g, b),
                0.0,
            )
        }
    }

    @Test
    fun brightSmoothSkinCannotFireTheMask() {
        // A flat frame of the brightest skin tone at the top of the image: texture, bright
        // and position all fire -- neutrality alone must keep the mask at exactly zero.
        val px = (0xFF shl 24) or (247 shl 16) or (214 shl 8) or 185
        val frame = Frame(32, 32, IntArray(32 * 32) { px }, 0L)
        val mask = OvercastSkyMask.compute(frame)
        assertTrue("a bright smooth skin frame must yield an all-zero mask", mask.all { it == 0.0 })
    }

    // --- compute: fires on gray sky, position/texture/bright exclusions ------------

    @Test
    fun brightFlatGrayFiresAtTheTopAndIsExcludedAtTheBottom() {
        // A frame that is bright flat gray everywhere: only the position prior separates
        // top from bottom, exactly as for the blue mask.
        val w = 32
        val h = 100
        val mask = OvercastSkyMask.compute(grayFrame(w, h, 205))
        val topMean = bandMean(mask, w, 0, 10)
        val bottomMean = bandMean(mask, w, 90, 100)
        assertTrue("top-of-image bright flat gray must fire (was $topMean)", topMean > 0.8)
        assertTrue("bottom-of-image must be excluded by position (was $bottomMean)", bottomMean < 0.05)
    }

    @Test
    fun rowOffsetShiftsThePositionPrior() {
        val w = 16
        val h = 16
        val tile = grayFrame(w, h, 205)
        val asTop = OvercastSkyMask.compute(tile, rowOffset = 0, imageHeight = 200)
        val asBottom = OvercastSkyMask.compute(tile, rowOffset = 180, imageHeight = 200)
        assertTrue("tile at the image top fires", bandMean(asTop, w, 0, h) > 0.8)
        assertTrue("same tile at the image bottom is excluded", bandMean(asBottom, w, 0, h) < 0.05)
    }

    @Test
    fun brightTexturedGrayIsExcludedByTheTexturePrior() {
        val frame = checkerFrame(64, 64, block = 6, lo = 170, hi = 230)
        val mask = OvercastSkyMask.compute(frame)
        assertTrue("bright textured content must stay near zero (mean ${mask.average()})", mask.average() < 0.02)
    }

    @Test
    fun darkGrayIsExcludedByTheBrightPrior() {
        val mask = OvercastSkyMask.compute(grayFrame(32, 32, 100))
        assertTrue("dark flat gray must yield an all-zero mask (no dusk floor)", mask.all { it == 0.0 })
    }

    @Test
    fun computeIsDeterministic() {
        val frame = checkerFrame(24, 24, block = 5, lo = 150, hi = 215)
        val a = OvercastSkyMask.compute(frame)
        val b = OvercastSkyMask.compute(frame)
        assertEquals(a.size, b.size)
        for (i in a.indices) assertEquals(a[i], b[i], 0.0)
    }

    // --- helpers -------------------------------------------------------------------

    private fun grayFrame(w: Int, h: Int, v: Int): Frame {
        val px = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        return Frame(w, h, IntArray(w * h) { px }, 0L)
    }

    private fun checkerFrame(w: Int, h: Int, block: Int, lo: Int, hi: Int): Frame {
        val out = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val v = if (((x / block) + (y / block)) % 2 == 0) lo else hi
            out[y * w + x] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }
        return Frame(w, h, out, 0L)
    }

    private fun interiorMean(plane: DoubleArray, w: Int, h: Int, margin: Int): Double {
        var sum = 0.0
        var n = 0
        for (y in margin until h - margin) for (x in margin until w - margin) {
            sum += plane[y * w + x]
            n++
        }
        return sum / n
    }

    private fun bandMean(mask: DoubleArray, width: Int, y0: Int, y1: Int): Double {
        var sum = 0.0
        var n = 0
        for (y in y0 until y1) for (x in 0 until width) {
            sum += mask[y * width + x]
            n++
        }
        return sum / n
    }
}
