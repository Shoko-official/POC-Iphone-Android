package com.poc.camera.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Proofs for the resolution-adaptive chroma-gate radius (issue #114):
 * [ChromaRollOffParams.forImageWidth] and its wiring through [FinishingPipeline] /
 * [TiledFinishing].
 *
 *  1. SCALING LAW: the radius is linear in width and EXACTLY the validated 24 at the
 *     [ChromaRollOffParams.REFERENCE_WIDTH] anchor (the 1542 px real-pair development).
 *  2. CLAMP ENDPOINTS: sub-reference widths floor at the validated radius itself (the
 *     128 px golden fixtures were validated at 24 directly -- see the forImageWidth KDoc
 *     for why the floor is NOT proportional), very wide images cap at
 *     [ChromaRollOffParams.MAX_NEIGHBORHOOD_RADIUS].
 *  3. INTENSITY-DOMAIN INVARIANCE: knee/soft/isolationFactor/strength describe chroma
 *     magnitudes, not distances, so resolution never changes them.
 *  4. SUPPORT CHAIN: [TiledFinishing.SUPPORT_RADIUS]/[TiledFinishing.OVERLAP] are sized
 *     for the radius CEILING, kept in sync by assertion, and the width-adaptive halo
 *     ([TiledFinishing.overlapFor], issue #121) tracks the ACTUAL scaled radius while
 *     never exceeding that ceiling.
 *  5. SCALE CONSISTENCY (the honest invariance proof): the same scene rendered at width W
 *     with radius r and at 2W with radius 2r produces the same gating decision -- the 2W
 *     output, downsampled back, matches the W output within a small tolerance -- while the
 *     UNSCALED radius at 2W visibly diverges (the #114 escape: the doubled spot fills its
 *     fixed-radius neighbourhood and under-compresses).
 *  6. TILED-VS-WHOLE-FRAME at a genuinely scaled radius (48 at width 3084) under the
 *     production width-adaptive halo, within the existing tiled drift tolerance.
 *
 * All fixtures are deterministic and closed-form; measured baselines are documented at
 * the baked bounds.
 */
class ChromaRollOffScalingTest {

    // --- scaling law ---------------------------------------------------------------

    @Test
    fun referenceWidthYieldsTheValidatedRadiusExactly() {
        val params = ChromaRollOffParams.forImageWidth(ChromaRollOffParams.REFERENCE_WIDTH)
        assertEquals("anchor must reproduce the validated radius", 24, params.neighborhoodRadius)
        assertEquals("anchor params must be DEFAULT in full", ChromaRollOffParams.DEFAULT, params)
    }

    @Test
    fun defaultRadiusStaysTheValidatedTwentyFour() {
        // The operator contract at the reference scale: direct callers (and every
        // operator-level golden) keep the validated radius; forImageWidth is the
        // CALL-SITE policy, not a change to the operator defaults.
        assertEquals(24, ChromaRollOffParams.DEFAULT.neighborhoodRadius)
    }

    @Test
    fun radiusScalesLinearlyWithWidthAboveTheReference() {
        // round(24 * width / 1542) for each width.
        assertEquals(31, ChromaRollOffParams.forImageWidth(2016).neighborhoodRadius) // 3 MP working
        assertEquals(48, ChromaRollOffParams.forImageWidth(3084).neighborhoodRadius) // exactly 2x reference
        assertEquals(63, ChromaRollOffParams.forImageWidth(4032).neighborhoodRadius) // 12 MP 4:3
        assertEquals(73, ChromaRollOffParams.forImageWidth(4714).neighborhoodRadius) // 12.5 MP 16:9
    }

    // --- clamp endpoints -----------------------------------------------------------

    @Test
    fun subReferenceWidthsFloorAtTheValidatedRadius() {
        // The floor is the validated radius itself, not a proportional minimum: the
        // documented #107 escape exists only ABOVE the reference width, and the 128 px
        // golden fixtures pin radius-24 behaviour (zero re-bakes). See forImageWidth KDoc.
        for (width in intArrayOf(1, 64, 128, 512, 1024, ChromaRollOffParams.REFERENCE_WIDTH)) {
            assertEquals("width $width must floor at 24", 24, ChromaRollOffParams.forImageWidth(width).neighborhoodRadius)
        }
    }

    @Test
    fun veryWideImagesCapAtTheMaxRadius() {
        // 24 * 6168 / 1542 = 96 exactly: the last scale-exact width.
        assertEquals(96, ChromaRollOffParams.forImageWidth(6168).neighborhoodRadius)
        for (width in intArrayOf(6200, 10_000, 100_000)) {
            assertEquals(
                "width $width must cap at MAX_NEIGHBORHOOD_RADIUS",
                ChromaRollOffParams.MAX_NEIGHBORHOOD_RADIUS,
                ChromaRollOffParams.forImageWidth(width).neighborhoodRadius,
            )
        }
    }

    @Test
    fun forImageWidthRejectsNonPositiveWidths() {
        assertThrows(IllegalArgumentException::class.java) { ChromaRollOffParams.forImageWidth(0) }
        assertThrows(IllegalArgumentException::class.java) { ChromaRollOffParams.forImageWidth(-1) }
    }

    // --- intensity-domain params are resolution-independent ------------------------

    @Test
    fun intensityDomainTuningIsUnchangedByResolution() {
        val default = ChromaRollOffParams.DEFAULT
        for (width in intArrayOf(128, ChromaRollOffParams.REFERENCE_WIDTH, 4032, 20_000)) {
            val scaled = ChromaRollOffParams.forImageWidth(width)
            assertEquals("knee at $width", default.knee, scaled.knee, 0.0)
            assertEquals("soft at $width", default.soft, scaled.soft, 0.0)
            assertEquals("isolationFactor at $width", default.isolationFactor, scaled.isolationFactor, 0.0)
            assertEquals("strength at $width", default.strength, scaled.strength, 0.0)
        }
    }

    // --- support chain sized for the ceiling ----------------------------------------

    @Test
    fun tiledSupportChainIsSizedForTheRadiusCeiling() {
        // chroma 8 + local-tone 32 + detail 6 + roll-off ceiling + sky-smooth 16 (see the
        // TiledFinishing class doc). Kept in sync by this assertion: raising the radius
        // ceiling without resizing the halo would silently break tile seam-freedom.
        assertEquals(
            "SUPPORT_RADIUS must cover the worst-case roll-off radius",
            8 + 32 + 6 + ChromaRollOffParams.MAX_NEIGHBORHOOD_RADIUS + 16,
            TiledFinishing.SUPPORT_RADIUS,
        )
        assertTrue("OVERLAP must cover SUPPORT_RADIUS", TiledFinishing.OVERLAP >= TiledFinishing.SUPPORT_RADIUS)
    }

    @Test
    fun dynamicOverlapTracksTheScaledRadiusUnderTheCeiling() {
        // supportRadiusFor = the fixed 62 px stage chain + the width-scaled gate radius;
        // overlapFor adds the 2 px house margin (issue #121).
        assertEquals(62, TiledFinishing.FIXED_STAGES_SUPPORT)
        assertEquals(88, TiledFinishing.overlapFor(128)) // radius floor 24
        assertEquals(88, TiledFinishing.overlapFor(ChromaRollOffParams.REFERENCE_WIDTH))
        assertEquals(95, TiledFinishing.overlapFor(2016)) // radius 31 (3 MP working)
        assertEquals(127, TiledFinishing.overlapFor(4032)) // radius 63: 12 MP pays 127, not the 160 ceiling
        assertEquals(137, TiledFinishing.overlapFor(4714)) // radius 73 (12.5 MP 16:9)
        // At the radius cap the dynamic halo meets the compile-time ceiling exactly.
        assertEquals(TiledFinishing.OVERLAP, TiledFinishing.overlapFor(6168))
        // The ceiling bounds the dynamic halo at EVERY width, and the margin is constant.
        val widths = intArrayOf(1, 64, 128, 256, ChromaRollOffParams.REFERENCE_WIDTH, 2016, 3084, 4032, 4714, 6168, 10_000, 100_000)
        for (width in widths) {
            val support = TiledFinishing.supportRadiusFor(width)
            val overlap = TiledFinishing.overlapFor(width)
            assertEquals("overlapFor($width) must be supportRadiusFor + margin", support + TiledFinishing.OVERLAP_MARGIN, overlap)
            assertTrue("supportRadiusFor($width) = $support must stay under SUPPORT_RADIUS", support <= TiledFinishing.SUPPORT_RADIUS)
            assertTrue("overlapFor($width) = $overlap must stay under OVERLAP", overlap <= TiledFinishing.OVERLAP)
        }
    }

    // --- scale consistency ----------------------------------------------------------

    @Test
    fun operatorIsScaleConsistentWhenTheRadiusScalesWithWidth() {
        val base = spotScene()
        val up = upscale2xNearest(base)
        val baseParams = ChromaRollOffParams.DEFAULT.copy(neighborhoodRadius = BASE_RADIUS)

        val baseOut = ChromaRollOff.apply(base, baseParams)
        // Not vacuous: the isolated spot must genuinely compress at the base scale.
        val spotBefore = chromaMag(base, SPOT_CENTER, SPOT_CENTER)
        val spotAfter = chromaMag(baseOut, SPOT_CENTER, SPOT_CENTER)
        assertTrue("base spot must compress ($spotBefore -> $spotAfter)", spotAfter < spotBefore * 0.7)

        // The scale-invariance claim: 2x the resolution with 2x the radius is the same look.
        val scaledDown = downsample2xMean(ChromaRollOff.apply(up, baseParams.copy(neighborhoodRadius = 2 * BASE_RADIUS)))
        // The #114 escape, reproduced: 2x the resolution with the UNSCALED radius lets the
        // (now twice as large) spot fill its neighbourhood and under-compress.
        val unscaledDown = downsample2xMean(ChromaRollOff.apply(up, baseParams))

        val scaledDelta = delta(baseOut, scaledDown)
        val unscaledDelta = delta(baseOut, unscaledDown)
        println(
            "[scaling] scale-consistency delta: scaled radius mean=%.4f max=%d, unscaled radius mean=%.4f max=%d".format(
                scaledDelta.mean, scaledDelta.max, unscaledDelta.mean, unscaledDelta.max,
            ),
        )
        assertTrue(
            "scaled-radius mean delta ${scaledDelta.mean} must stay under $MAX_SCALED_MEAN_DELTA",
            scaledDelta.mean < MAX_SCALED_MEAN_DELTA,
        )
        assertTrue(
            "scaled-radius max channel delta ${scaledDelta.max} must stay under $MAX_SCALED_MAX_DELTA",
            scaledDelta.max <= MAX_SCALED_MAX_DELTA,
        )
        // The unscaled radius must diverge by clearly more than the scaled one does --
        // the measured proof that scaling the radius is what preserves the look.
        assertTrue(
            "unscaled radius must diverge more (unscaled max ${unscaledDelta.max} vs scaled max ${scaledDelta.max})",
            unscaledDelta.max >= MIN_UNSCALED_MAX_DELTA && unscaledDelta.max > 2 * scaledDelta.max,
        )
    }

    // --- tiled vs whole-frame at a scaled radius -------------------------------------

    @Test
    fun tiledMatchesWholeFrameAtAScaledRadiusUnderTheProductionOverlap() {
        val frame = wideScene(3084, 220)
        val params = FinishingParams.DEFAULT.copy(chromaRollOff = 1.0)
        // At width 3084 the call-site radius is genuinely scaled (48), not the floor, and
        // the production width-adaptive halo is 112 (62 + 48 + 2, issue #121) -- the
        // default overlap the apply call below takes.
        assertEquals(48, ChromaRollOffParams.forImageWidth(frame.width).neighborhoodRadius)
        assertEquals(112, TiledFinishing.overlapFor(frame.width))

        val whole = FinishingPipeline.apply(frame, params)
        // Not vacuous: the roll-off must engage at this radius on this scene.
        val rollOffDisabled = FinishingPipeline.apply(frame, FinishingParams.DEFAULT.copy(chromaRollOff = 0.0))
        assertFalse(
            "the roll-off must change the wide scene (otherwise this proves nothing)",
            whole.argb.contentEquals(rollOffDisabled.argb),
        )

        val stats = FinishingStats.compute(frame, params)
        val tiled = TiledFinishing.apply(frame, params, tileSize = 256, stats = stats)

        var mismatches = 0
        var maxDiff = 0
        for (i in whole.argb.indices) {
            if (whole.argb[i] != tiled.argb[i]) mismatches++
            maxDiff = max(maxDiff, channelMaxDiff(whole.argb[i], tiled.argb[i]))
        }
        println("[scaling] tiled vs whole-frame at radius 48 (3084x220): mismatches=$mismatches maxChannelDiff=$maxDiff")
        assertTrue(
            "tiled max channel diff $maxDiff must stay within $MAX_TILED_CHANNEL_DIFF",
            maxDiff <= MAX_TILED_CHANNEL_DIFF,
        )
        assertTrue(
            "tiled mismatches $mismatches must stay within $MAX_TILED_MISMATCHES",
            mismatches <= MAX_TILED_MISMATCHES,
        )
    }

    // --- fixtures --------------------------------------------------------------------

    /**
     * A [SCENE_SIZE]-square base scene: an isolated [SPOT_SIZE]-square extreme-chroma spot
     * (the lips colour, magnitude ~85) centred at ([SPOT_CENTER], [SPOT_CENTER]) on a
     * near-gray surround, plus a uniformly saturated block in the top-left corner (well
     * clear of the spot's window at either scale) that must pass through at every scale.
     */
    private fun spotScene(): Frame {
        val surround = argb(120, 118, 116)
        val out = IntArray(SCENE_SIZE * SCENE_SIZE) { surround }
        fillRect(out, SCENE_SIZE, 8, 8, 48, 48, argb(30, 80, 200))
        val half = SPOT_SIZE / 2
        fillRect(out, SCENE_SIZE, SPOT_CENTER - half, SPOT_CENTER - half, SPOT_CENTER + half, SPOT_CENTER + half, SPOT_ARGB)
        return Frame(SCENE_SIZE, SCENE_SIZE, out, timestampMillis = 1L)
    }

    /**
     * A wide low-chroma smooth-ramp scene with six isolated extreme-chroma spots spread
     * across the width and one large saturated block that fills its own radius-48 window
     * (so the gate must pass it through). Deterministic, no noise.
     */
    private fun wideScene(width: Int, height: Int): Frame {
        val out = IntArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val r = 105 + x * 30 / width
                val g = 105 + y * 30 / height
                val b = 105 + (x + y) * 30 / (width + height)
                out[y * width + x] = argb(r, g, b)
            }
        }
        var i = 0
        for (sx in intArrayOf(200, 700, 1200, 1700, 2200, 2700)) {
            val sy = 40 + (i % 3) * 50
            fillRect(out, width, sx, sy, sx + 20, sy + 20, SPOT_ARGB)
            i++
        }
        fillRect(out, width, 2900, 20, 3050, 180, argb(30, 80, 200))
        return Frame(width, height, out, timestampMillis = 1L)
    }

    /** Nearest-neighbour 2x upscale: each source pixel becomes a 2x2 block. */
    private fun upscale2xNearest(frame: Frame): Frame {
        val w = frame.width
        val h = frame.height
        val out = IntArray(w * 2 * h * 2)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val px = frame.argb[y * w + x]
                val row0 = (2 * y) * (2 * w) + 2 * x
                val row1 = (2 * y + 1) * (2 * w) + 2 * x
                out[row0] = px
                out[row0 + 1] = px
                out[row1] = px
                out[row1 + 1] = px
            }
        }
        return Frame(2 * w, 2 * h, out, frame.timestampMillis)
    }

    /** 2x2 box-mean downsample (rounded per channel), the inverse of [upscale2xNearest]. */
    private fun downsample2xMean(frame: Frame): Frame {
        val w = frame.width / 2
        val h = frame.height / 2
        val out = IntArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                var r = 0
                var g = 0
                var b = 0
                for (dy in 0..1) {
                    for (dx in 0..1) {
                        val px = frame.argb[(2 * y + dy) * frame.width + (2 * x + dx)]
                        r += (px shr 16) and 0xFF
                        g += (px shr 8) and 0xFF
                        b += px and 0xFF
                    }
                }
                out[y * w + x] = argb((r / 4.0).roundToInt(), (g / 4.0).roundToInt(), (b / 4.0).roundToInt())
            }
        }
        return Frame(w, h, out, frame.timestampMillis)
    }

    private class Delta(val mean: Double, val max: Int)

    /** Mean and max per-channel absolute difference over two same-size frames. */
    private fun delta(a: Frame, b: Frame): Delta {
        var sum = 0L
        var maxDiff = 0
        for (i in a.argb.indices) {
            val pa = a.argb[i]
            val pb = b.argb[i]
            val dr = abs(((pa shr 16) and 0xFF) - ((pb shr 16) and 0xFF))
            val dg = abs(((pa shr 8) and 0xFF) - ((pb shr 8) and 0xFF))
            val db = abs((pa and 0xFF) - (pb and 0xFF))
            sum += (dr + dg + db).toLong()
            maxDiff = max(maxDiff, max(dr, max(dg, db)))
        }
        return Delta(sum.toDouble() / (a.argb.size * 3L), maxDiff)
    }

    private fun channelMaxDiff(a: Int, b: Int): Int {
        val dr = abs(((a shr 16) and 0xFF) - ((b shr 16) and 0xFF))
        val dg = abs(((a shr 8) and 0xFF) - ((b shr 8) and 0xFF))
        val db = abs((a and 0xFF) - (b and 0xFF))
        return max(dr, max(dg, db))
    }

    private fun chromaMag(frame: Frame, x: Int, y: Int): Double {
        val px = frame.argb[y * frame.width + x]
        val r = ((px shr 16) and 0xFF).toDouble()
        val g = ((px shr 8) and 0xFF).toDouble()
        val b = (px and 0xFF).toDouble()
        val luma = 0.299 * r + 0.587 * g + 0.114 * b
        val cr = r - luma
        val cb = b - luma
        return sqrt(cr * cr + cb * cb)
    }

    private fun argb(r: Int, g: Int, b: Int): Int =
        (0xFF shl 24) or ((r and 0xFF) shl 16) or ((g and 0xFF) shl 8) or (b and 0xFF)

    private fun fillRect(out: IntArray, stride: Int, x0: Int, y0: Int, x1: Int, y1: Int, px: Int) {
        for (y in y0 until y1) {
            for (x in x0 until x1) {
                out[y * stride + x] = px
            }
        }
    }

    private companion object {
        const val SCENE_SIZE = 128
        const val SPOT_CENTER = 64
        const val SPOT_SIZE = 8
        const val BASE_RADIUS = 12

        /** The lips colour rgb(190, 75, 82): chroma magnitude ~84.6, far above the knee. */
        val SPOT_ARGB = (0xFF shl 24) or (190 shl 16) or (75 shl 8) or 82

        // MEASURED 2026-07-22 on the deterministic fixtures. Actuals -> baked bound (with
        // margin) -- documented at the assertion sites:
        //   scale-consistency, scaled radius   : mean 0.0125 / max 2 -> 0.05 / 4 (the
        //     residual is the half-pixel window mismatch at region borders: a radius-2r
        //     window at 2W covers 4r+1 samples per axis where exact correspondence would
        //     need 4r+2, plus 8-bit re-rounding)
        //   scale-consistency, unscaled radius : mean 0.2489 / max 29 -> floor 20, and > 2x
        //     the scaled max (the #114 escape made measurable: ~20x the mean deviation)
        //   tiled-vs-whole at radius 48        : mismatches 0 / maxChannelDiff 0
        //     (re-measured 2026-07-23 under the width-adaptive 112 px halo, issue #121),
        //     i.e. byte-identical on this fixture -> ceilings 4 / 512, the same tolerance
        //     class TiledFinishingBitIdentityTest allows the documented BoxBlur
        //     running-sum drift under nonlinear masks (DEFAULT carries skinProtection 0.7)
        const val MAX_SCALED_MEAN_DELTA = 0.05
        const val MAX_SCALED_MAX_DELTA = 4
        const val MIN_UNSCALED_MAX_DELTA = 20
        const val MAX_TILED_CHANNEL_DIFF = 4
        const val MAX_TILED_MISMATCHES = 512
    }
}
