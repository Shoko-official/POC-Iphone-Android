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
 * [TiledFinishing]. Each case forces a small tile ([TILE] core, [TiledFinishing.OVERLAP]
 * halo) so a 256 px frame is cut into many tiles including partial edge tiles, exercising
 * the stitching. Odd dimensions and frames smaller than one tile (single-tile path) are
 * covered too.
 *
 * Empirically this drift never crosses the final round-to-8-bit on these fixtures, so the
 * tiled cores are BYTE-IDENTICAL; the test asserts exact equality and additionally reports
 * the measured max deviation so any future regression is visible.
 */
class TiledFinishingBitIdentityTest {

    private val tileSize = TILE
    private val overlap = TiledFinishing.OVERLAP

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
        // DEFAULT (every stage on at ship strengths) plus a variant with every stage
        // pushed to a strong strength, so no stage is a silent no-op in the comparison.
        val frame = mixed(256, 256)
        assertByteIdentical("mixed-default", frame, FinishingParams.DEFAULT)
        val strong = FinishingParams.DEFAULT.copy(
            localContrast = 0.6,
            chromaDenoise = 0.9,
            detailEnhance = 0.7,
            whiteBalance = 1.0,
        )
        assertByteIdentical("mixed-strong", frame, strong)
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
    }
}
