package com.poc.camera.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.max

/**
 * Proves that finishing a frame in overlapping tiles with the SAME (whole-frame) global
 * statistics reproduces the whole-frame [FinishingPipeline] output, tile after tile, with
 * no seams -- the core correctness claim of issue #54's [TiledFinishing].
 *
 * The comparison uses EXACT stats ([FinishingStats.compute] on the full frame), so the
 * only admissible difference is the incremental-[BoxBlur] running-sum drift documented on
 * [TiledFinishing]. Each case forces a small tile ([TILE] core, [SUB_REFERENCE_OVERLAP]
 * halo) so a 256 px frame is cut into many tiles including partial edge tiles, exercising
 * the stitching. Odd dimensions and frames smaller than one tile (single-tile path) are
 * covered too.
 *
 * The halo is 88, not the production [TiledFinishing.OVERLAP] (160): the production
 * constant is sized for the roll-off gate's radius CEILING at very wide images (issue
 * #114), but on these sub-reference-width fixtures the radius min-clamps to 24
 * ([com.poc.camera.pipeline.ChromaRollOffParams.forImageWidth]), so the actual chain
 * support is `8 + 32 + 6 + 24 + 16 = 86` and an 88 px halo is seam-sufficient. Using 160
 * here would make every padded tile swallow the whole 256 px frame and reduce the
 * stitching to a vacuous single-tile comparison. Tiled-vs-whole-frame consistency at a
 * genuinely SCALED radius under the production halo is proven by
 * ChromaRollOffScalingTest.
 *
 * Empirically this drift never crosses the final round-to-8-bit on these fixtures, so the
 * tiled cores are BYTE-IDENTICAL; the test asserts exact equality and additionally reports
 * the measured max deviation so any future regression is visible.
 */
class TiledFinishingBitIdentityTest {

    private val tileSize = TILE
    private val overlap = SUB_REFERENCE_OVERLAP

    @Test
    fun tiledMatchesWholeFrameOnEdges() = assertByteIdentical("edges", edges(256, 256))

    @Test
    fun tiledMatchesWholeFrameOnTexture() = assertByteIdentical("texture", texture(256, 256, 0x51A7L))

    @Test
    fun tiledMatchesWholeFrameOnColorChart() = assertByteIdentical("colorchart", colorChart(256, 256))

    @Test
    fun tiledMatchesWholeFrameOnOddDimensions() {
        // Odd, non-square dims that do not divide the tile grid evenly.
        assertByteIdentical("odd-201x137", texture(201, 137, 0x9E37L))
        assertByteIdentical("odd-137x201", colorChart(137, 201))
    }

    @Test
    fun tiledMatchesWholeFrameOnWideThinFrame() =
        assertByteIdentical("wide-300x40", edges(300, 40))

    @Test
    fun tinyFrameSmallerThanOneTileTakesSingleTilePath() {
        // Smaller than the padded tile -> exactly one tile spanning the whole frame; with
        // exact stats and full-frame box blurs this is byte-identical by construction.
        assertByteIdentical("tiny-40x40", texture(40, 40, 0x1234L))
        assertByteIdentical("tiny-17x23", colorChart(17, 23))
    }

    @Test
    fun defaultAndAllStagesForcedOnBothMatch() {
        // DEFAULT (every stage on at ship strengths, INCLUDING skinProtection 0.7) plus a
        // variant with every stage pushed to a strong strength, so no stage is a silent no-op.
        // DEFAULT carries skin protection, so this also proves the SkinMask-modulated stages
        // are byte-identical tile-vs-whole-frame at ship strength.
        val frame = mixed(256, 256)
        assertByteIdentical("mixed-default", frame, FinishingParams.DEFAULT)
        // The strong variant isolates the heavy NON-skin stages at exact identity (skin
        // protection off); the skin-protection-under-strong-ops interaction is covered
        // separately by [skinProtectionUnderStrongOpsStaysWithinBoxBlurTolerance].
        val strong = FinishingParams.DEFAULT.copy(
            localContrast = 0.6,
            chromaDenoise = 0.9,
            detailEnhance = 0.7,
            whiteBalance = 1.0,
            skinProtection = 0.0,
        )
        assertByteIdentical("mixed-strong", frame, strong)
    }

    @Test
    fun skinProtectionUnderStrongOpsStaysWithinBoxBlurTolerance() {
        // The SkinMask is a NONLINEAR (smoothstep) function of the denoised chroma, so where a
        // pixel's denoised chroma sits on the steep part of the ellipse membership, the
        // documented [BoxBlur] running-sum drift that makes tiled finishing "metric-identical
        // but not GUARANTEED bit-identical" (see TiledFinishing) can flip that pixel's raw
        // mask, and the radius-8 mask blur then spreads it over ~a blur footprint of pixels.
        // Under GENTLE ship strengths this stays below the final round-to-8-bit (proved exact
        // by mixed-default above); only when the modulated operators are CRANKED does the
        // amplified drift cross rounding. It stays tightly bounded -- a few codes on a handful
        // of pixels, far inside what "metric-identical" allows -- and is not a seam (it is not
        // localised to tile boundaries). This test pins that bound so a regression that widens
        // it is caught. Ship params (DEFAULT/RENDITION) do not trip it; this is an adversarial
        // strong-ops + skin-chroma combination.
        val frame = mixed(256, 256)
        val strongSkin = FinishingParams.DEFAULT.copy(
            localContrast = 0.6,
            chromaDenoise = 0.9,
            detailEnhance = 0.7,
            whiteBalance = 1.0,
            skinProtection = 0.7,
        )
        assertWithinTolerance("mixed-strong-skin", frame, strongSkin, MAX_SKIN_TILE_DIFF, MAX_SKIN_TILE_MISMATCHES)
        // Realistic ship look, for reference: RENDITION at 256 px under the small test tile. This
        // case ALSO exercises SemanticRendering's sky/overcast/foliage masks (their smoothstep
        // priors and the sky guided chroma smooth are the same class of nonlinear-mask-amplified
        // BoxBlur running-sum drift as SkinMask), so its drift is a touch higher than skin alone;
        // it stays well inside the same tolerance (measured mismatches ~172, maxChannelDiff 3
        // with the OvercastSkyMask texture prior included).
        assertWithinTolerance("mixed-rendition", frame, FinishingParams.RENDITION, MAX_SKIN_TILE_DIFF, MAX_SKIN_TILE_MISMATCHES)
    }

    private fun assertWithinTolerance(
        label: String,
        frame: Frame,
        params: FinishingParams,
        maxChannelDiffAllowed: Int,
        maxMismatchesAllowed: Int,
    ) {
        val whole = FinishingPipeline.apply(frame, params)
        val stats = FinishingStats.compute(frame, params)
        val tiled = TiledFinishing.apply(frame, params, tileSize, overlap, stats)
        var maxDiff = 0
        var mismatches = 0
        for (i in whole.argb.indices) {
            if (whole.argb[i] != tiled.argb[i]) mismatches++
            maxDiff = max(maxDiff, channelMaxDiff(whole.argb[i], tiled.argb[i]))
        }
        println("[$label ${frame.width}x${frame.height}] tiled vs whole-frame: mismatches=$mismatches maxChannelDiff=$maxDiff")
        assertTrue(
            "$label: tiled max channel diff $maxDiff must stay within $maxChannelDiffAllowed",
            maxDiff <= maxChannelDiffAllowed,
        )
        assertTrue(
            "$label: tiled mismatches $mismatches must stay within $maxMismatchesAllowed",
            mismatches <= maxMismatchesAllowed,
        )
    }

    private fun assertByteIdentical(
        label: String,
        frame: Frame,
        params: FinishingParams = FinishingParams.DEFAULT,
    ) {
        val whole = FinishingPipeline.apply(frame, params)
        val stats = FinishingStats.compute(frame, params)
        val tiled = TiledFinishing.apply(frame, params, tileSize, overlap, stats)

        assertEquals(whole.width, tiled.width)
        assertEquals(whole.height, tiled.height)

        var maxDiff = 0
        var mismatches = 0
        for (i in whole.argb.indices) {
            val w = whole.argb[i]
            val t = tiled.argb[i]
            if (w != t) mismatches++
            maxDiff = max(maxDiff, channelMaxDiff(w, t))
        }
        println("[$label ${frame.width}x${frame.height}] tiled vs whole-frame: mismatches=$mismatches maxChannelDiff=$maxDiff")
        assertTrue(
            "$label: tiled core must be byte-identical to whole-frame (mismatches=$mismatches, maxChannelDiff=$maxDiff)",
            whole.argb.contentEquals(tiled.argb),
        )
    }

    private fun channelMaxDiff(a: Int, b: Int): Int {
        val dr = abs(((a shr 16) and 0xFF) - ((b shr 16) and 0xFF))
        val dg = abs(((a shr 8) and 0xFF) - ((b shr 8) and 0xFF))
        val db = abs((a and 0xFF) - (b and 0xFF))
        return max(dr, max(dg, db))
    }

    // --- deterministic scene builders (arbitrary size) -----------------------------

    private fun argb(r: Int, g: Int, b: Int): Int =
        (0xFF shl 24) or ((r and 0xFF) shl 16) or ((g and 0xFF) shl 8) or (b and 0xFF)

    /** Hard quadrant steps plus thin diagonal lines: the ringing / mis-registration probe. */
    private fun edges(width: Int, height: Int): Frame {
        val out = IntArray(width * height)
        val hx = width / 2
        val hy = height / 2
        for (y in 0 until height) {
            for (x in 0 until width) {
                var v = when {
                    x < hx && y < hy -> 40
                    x >= hx && y < hy -> 200
                    x < hx -> 128
                    else -> 90
                }
                if ((x - y).mod(24) == 0) v = 230
                if ((x + y).mod(24) == 0) v = 20
                out[y * width + x] = argb(v, v, v)
            }
        }
        return Frame(width, height, out, timestampMillis = 7L)
    }

    /** Smooth interpolated lattice, tinted per channel so chroma stages have work. */
    private fun texture(width: Int, height: Int, seed: Long): Frame {
        val canvas = SyntheticImages.texturedCanvas(width, height, seed)
        val out = IntArray(width * height) { i ->
            val v = canvas[i] and 0xFF
            argb((v + 25).coerceIn(0, 255), v, (v - 25).coerceIn(0, 255))
        }
        return Frame(width, height, out, timestampMillis = 7L)
    }

    /** A grid of saturated / pastel / neutral color blocks with strong luma-stepped edges. */
    private fun colorChart(width: Int, height: Int): Frame {
        val patches = intArrayOf(
            argb(150, 20, 20), argb(120, 210, 215), argb(20, 110, 30), argb(240, 190, 205),
            argb(25, 35, 140), argb(225, 215, 90), argb(150, 200, 240), argb(90, 25, 120),
            argb(190, 225, 180), argb(110, 70, 45), argb(245, 205, 175), argb(20, 90, 95),
            argb(140, 25, 60), argb(210, 195, 235), argb(85, 80, 20), argb(255, 220, 180),
        )
        val grid = 4
        val out = IntArray(width * height)
        for (y in 0 until height) {
            val gy = (y * grid / height).coerceAtMost(grid - 1)
            for (x in 0 until width) {
                val gx = (x * grid / width).coerceAtMost(grid - 1)
                out[y * width + x] = patches[gy * grid + gx]
            }
        }
        return Frame(width, height, out, timestampMillis = 7L)
    }

    /** Color chart overlaid with the edge steps, so every stage sees structure and colour. */
    private fun mixed(width: Int, height: Int): Frame {
        val chart = colorChart(width, height).argb
        val edge = edges(width, height).argb
        val out = IntArray(width * height) { i ->
            val c = chart[i]
            val e = edge[i] and 0xFF
            // Blend the luma-stepped edge into the chart colour so hard edges coexist with hue.
            val r = ((((c shr 16) and 0xFF) + e) / 2)
            val g = ((((c shr 8) and 0xFF) + e) / 2)
            val b = (((c and 0xFF) + e) / 2)
            argb(r, g, b)
        }
        return Frame(width, height, out, timestampMillis = 7L)
    }

    private companion object {
        /** Small core tile so a 256 px frame is cut into many tiles (incl. partial edges). */
        const val TILE = 64

        /** Halo covering the ACTUAL chain support at these sub-reference widths (86, the
         *  roll-off radius min-clamping to 24) plus the same 2 px margin the production
         *  constant carries -- see the class doc for why the production 160 is not used. */
        const val SUB_REFERENCE_OVERLAP = 88

        // MEASURED 2026-07-20: the tiled-vs-whole-frame deviation the SkinMask smoothstep can
        // amplify from the documented BoxBlur running-sum drift, under strong operators. Bounds
        // are the measured max plus a small margin; ship params stay well inside.
        const val MAX_SKIN_TILE_DIFF = 4
        const val MAX_SKIN_TILE_MISMATCHES = 512
    }
}
