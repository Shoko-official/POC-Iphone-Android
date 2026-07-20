package com.poc.camera.pipeline

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DetailEnhancerTest {

    private fun gray(value: Int): Int {
        val v = value.coerceIn(0, 255)
        return (0xFF shl 24) or (v shl 16) or (v shl 8) or v
    }

    private fun luma(pixel: Int): Double =
        0.299 * ((pixel shr 16) and 0xFF) +
            0.587 * ((pixel shr 8) and 0xFF) +
            0.114 * (pixel and 0xFF)

    // --- Coring curve ------------------------------------------------------------

    @Test
    fun coringSuppressesBelowThresholdDetail() {
        val t = 10.0
        // |d| well below the knee is collapsed toward zero (d * (d/t)^2 behaviour).
        val d = 2.0
        val out = DetailEnhancer.coring(d, t)
        assertTrue("small detail must be strongly suppressed: $d -> $out", abs(out) < 0.1 * abs(d))
    }

    @Test
    fun coringPassesLargeDetailNearlyUnchanged() {
        val t = 10.0
        val d = 120.0
        val out = DetailEnhancer.coring(d, t)
        assertTrue("large detail must pass nearly unchanged: $d -> $out", out > 0.98 * d)
        assertTrue("coring never amplifies on its own", out <= d)
    }

    @Test
    fun coringIsSignSymmetric() {
        val t = 7.5
        for (d in listOf(0.3, 1.0, 4.0, 12.0, 60.0)) {
            assertEquals(-DetailEnhancer.coring(d, t), DetailEnhancer.coring(-d, t), 1e-12)
        }
    }

    @Test
    fun coringIsSmoothAcrossTheKnee() {
        // No discontinuity at the threshold: sampling densely across d = t, successive
        // outputs differ by tiny amounts (bounded finite differences).
        val t = 10.0
        var prev = DetailEnhancer.coring(0.0, t)
        var maxStep = 0.0
        var d = 0.05
        while (d <= 30.0) {
            val cur = DetailEnhancer.coring(d, t)
            maxStep = maxOf(maxStep, abs(cur - prev))
            prev = cur
            d += 0.05
        }
        // Each 0.05 step in d moves the output by well under a code value.
        assertTrue("coring must be continuous across the knee (maxStep=$maxStep)", maxStep < 0.1)
    }

    @Test
    fun coringIsIdentityWithNoNoiseAndSafeAtZero() {
        assertEquals(42.0, DetailEnhancer.coring(42.0, 0.0), 1e-12)
        assertEquals(0.0, DetailEnhancer.coring(0.0, 0.0), 0.0)
        assertEquals(0.0, DetailEnhancer.coring(0.0, 5.0), 0.0)
    }

    // --- Halo / overshoot clamp --------------------------------------------------

    /** Vertical step edge: left flat [dark], right flat [bright]. */
    private fun stepEdge(width: Int, height: Int, dark: Int, bright: Int): Frame {
        val argb = IntArray(width * height) { i -> if ((i % width) < width / 2) gray(dark) else gray(bright) }
        return Frame(width, height, argb, 0L)
    }

    @Test
    fun overshootClampKeepsSharpenedLumaWithinLocalRangePlusAllowance() {
        // A hard step sharpened with an absurd gain WILL ring; the clamp must hold the
        // result inside the local 3x3 range of the ORIGINAL luma plus the allowance.
        val width = 64
        val height = 16
        val input = stepEdge(width, height, dark = 50, bright = 200)
        val allowance = 5.0
        // Absurd gain: the guided base keeps halos small on a hard edge, so a huge gain
        // is needed to force visible ringing -- exactly the case the clamp must catch.
        val params = DetailParams(radius = 3, gain = 40.0, coringSigmaFactor = 0.0, overshootAllowance = allowance)

        val output = DetailEnhancer.apply(input, params)

        // Recompute the local 3x3 min/max of the original luma independently.
        val lumaIn = DoubleArray(input.argb.size) { luma(input.argb[it]) }
        var maxViolation = 0.0
        for (y in 0 until height) {
            for (x in 0 until width) {
                var lo = Double.MAX_VALUE
                var hi = -Double.MAX_VALUE
                for (dy in -1..1) {
                    for (dx in -1..1) {
                        val ny = (y + dy).coerceIn(0, height - 1)
                        val nx = (x + dx).coerceIn(0, width - 1)
                        val v = lumaIn[ny * width + nx]
                        lo = minOf(lo, v)
                        hi = maxOf(hi, v)
                    }
                }
                val outLuma = luma(output.argb[y * width + x])
                maxViolation = maxOf(maxViolation, outLuma - (hi + allowance), (lo - allowance) - outLuma)
            }
        }
        // Allow a single code of rounding slack from the per-channel reapplication.
        assertTrue("sharpened luma escaped local range + allowance by $maxViolation", maxViolation <= 1.0)
    }

    @Test
    fun withoutClampTheSameSharpenOvershoots() {
        // Proves the clamp bites: with the allowance opened wide (clamp effectively
        // disabled) the identical absurd-gain sharpen rings past the local range.
        val width = 64
        val height = 16
        val input = stepEdge(width, height, dark = 50, bright = 200)
        val params = DetailParams(radius = 3, gain = 40.0, coringSigmaFactor = 0.0, overshootAllowance = 1e9)

        val output = DetailEnhancer.apply(input, params)

        val lumaIn = DoubleArray(input.argb.size) { luma(input.argb[it]) }
        var overshoot = 0.0
        for (y in 0 until height) {
            for (x in 0 until width) {
                var hi = -Double.MAX_VALUE
                for (dy in -1..1) {
                    for (dx in -1..1) {
                        val ny = (y + dy).coerceIn(0, height - 1)
                        val nx = (x + dx).coerceIn(0, width - 1)
                        hi = maxOf(hi, lumaIn[ny * width + nx])
                    }
                }
                val outLuma = luma(output.argb[y * width + x])
                overshoot = maxOf(overshoot, outLuma - hi)
            }
        }
        assertTrue("unclamped sharpen must visibly overshoot (measured $overshoot)", overshoot > 20.0)
    }

    // --- Texture vs noise --------------------------------------------------------

    @Test
    fun noiseGainsNoAmplitudeWhileTextureDoes() {
        // ONE image: a large flat field carrying low-amplitude noise, plus a small
        // high-frequency texture patch in a corner. A single uniformly-noisy or
        // uniformly-textured plane cannot be told apart from one frame; discrimination
        // works because the noise-dominated MAJORITY sets the coring knee low, so the
        // flat region's low-amplitude detail is cored away while the texture patch --
        // whose fine structure lands in the detail layer at a higher amplitude than the
        // noise -- survives and is amplified.
        val width = 96
        val height = 96
        val patch = 32 // texture occupies patch*patch (~11% of the frame)

        val rng = SyntheticImages.Lcg(0x0DDL)
        val texRng = SyntheticImages.Lcg(0x7E57L)
        // High-frequency texture: a fresh value per 2x2 cell, amplitude ~12 (moderate,
        // so it lands in the detail layer rather than being preserved in the base).
        val amp = 12
        val cell = 2
        val gw = patch / cell + 1
        val texGrid = IntArray(gw * gw) { (texRng.nextByte() % (2 * amp + 1)) - amp }
        val argb = IntArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val i = y * width + x
                argb[i] = if (x < patch && y < patch) {
                    gray(128 + texGrid[(y / cell) * gw + (x / cell)])
                } else {
                    // Flat mid-gray + small noise (amplitude ~3).
                    gray(128 + (rng.nextByte() % 7) - 3)
                }
            }
        }
        val input = Frame(width, height, argb, 0L)

        // Default coring/eps; wide overshoot allowance so the clamp does not muddy the
        // amplitude comparison.
        val params = DetailParams(overshootAllowance = 255.0)
        val output = DetailEnhancer.apply(input, params)

        // Compare detail amplitude (mean abs) against a shared base, in the flat region
        // vs the texture patch, before and after enhancement.
        val lumaIn = DoubleArray(input.argb.size) { luma(input.argb[it]) }
        val lumaOut = DoubleArray(output.argb.size) { luma(output.argb[it]) }
        val base = GuidedFilter.selfGuided(lumaIn, width, height, DetailParams.DEFAULT_RADIUS, DetailParams.DEFAULT_EPS)

        var flatIn = 0.0; var flatOut = 0.0; var flatN = 0
        var texIn = 0.0; var texOut = 0.0; var texN = 0
        for (y in 0 until height) {
            for (x in 0 until width) {
                val i = y * width + x
                val din = abs(lumaIn[i] - base[i])
                val dout = abs(lumaOut[i] - base[i])
                if (x < patch && y < patch) {
                    texIn += din; texOut += dout; texN++
                } else {
                    flatIn += din; flatOut += dout; flatN++
                }
            }
        }
        val noiseRatio = (flatOut / flatN) / (flatIn / flatN)
        val textureRatio = (texOut / texN) / (texIn / texN)

        assertTrue("noise must not gain amplitude (ratio=$noiseRatio)", noiseRatio <= 1.1)
        assertTrue("texture must gain clear amplitude (ratio=$textureRatio)", textureRatio >= 1.3)
        assertTrue("texture must gain more than noise", textureRatio > noiseRatio + 0.2)
    }

    // --- Edge sharpness (rise distance) -----------------------------------------

    @Test
    fun enhancementReducesEdgeRiseDistance() {
        // A blurred step (smooth sigmoid, from repeated box blur so it carries curvature
        // throughout, not just at two corners). Sharpening steepens the transition, so
        // the sub-pixel 10-90% rise distance shrinks. Coring is kept near-identity so
        // the test isolates the sharpening effect on a clean (noise-free) edge.
        val width = 96
        val height = 8
        val dark = 40
        val bright = 210
        val hard = DoubleArray(width * height) { i -> if ((i % width) < width / 2) dark.toDouble() else bright.toDouble() }
        var blurred = hard
        repeat(2) { blurred = BoxBlur.blur(blurred, width, height, radius = 3) }
        val argb = IntArray(width * height) { gray(blurred[it].toInt()) }
        val input = Frame(width, height, argb, 0L)

        val params = DetailParams(radius = 4, gain = 1.5, coringSigmaFactor = 0.05, overshootAllowance = 255.0)
        val output = DetailEnhancer.apply(input, params)

        val riseBefore = riseDistance(input, width, height / 2, dark.toDouble(), bright.toDouble())
        val riseAfter = riseDistance(output, width, height / 2, dark.toDouble(), bright.toDouble())
        assertTrue("rise distance should shrink: $riseBefore -> $riseAfter", riseAfter < riseBefore)
    }

    /** Sub-pixel 10-90% rise distance of the luma profile along row [row], in pixels. */
    private fun riseDistance(frame: Frame, width: Int, row: Int, lo: Double, hi: Double): Double {
        val p10 = lo + 0.1 * (hi - lo)
        val p90 = lo + 0.9 * (hi - lo)
        fun crossing(p: Double): Double {
            for (x in 1 until width) {
                val a = luma(frame.argb[row * width + x - 1])
                val b = luma(frame.argb[row * width + x])
                if (a < p && b >= p) return (x - 1) + (p - a) / (b - a)
            }
            return -1.0
        }
        return crossing(p90) - crossing(p10)
    }

    // --- Luma-only, dimensions, determinism -------------------------------------

    @Test
    fun preservesPerChannelRatiosWhereUnclamped() {
        // Coloured texture: detail enhancement maps luma only, one ratio per pixel, so
        // hue proxies survive where neither the [0,255] nor the overshoot clamp bites.
        val width = 48
        val height = 48
        val canvas = SyntheticImages.texturedCanvas(width, height, seed = 0x1234L)
        val argb = IntArray(canvas.size) { i ->
            val v = canvas[i] and 0xFF
            val r = (70 + v / 3).coerceIn(1, 254)
            val g = (100 + v / 4).coerceIn(1, 254)
            val b = (60 + v / 5).coerceIn(1, 254)
            (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        val input = Frame(width, height, argb, 0L)
        // Wide overshoot allowance so the clamp does not intentionally break ratios here.
        val output = DetailEnhancer.apply(input, DetailParams(overshootAllowance = 255.0))

        for (i in input.argb.indices) {
            val ip = input.argb[i]
            val op = output.argb[i]
            val ir = (ip shr 16) and 0xFF; val ig = (ip shr 8) and 0xFF; val ib = ip and 0xFF
            val or = (op shr 16) and 0xFF; val og = (op shr 8) and 0xFF; val ob = op and 0xFF
            if (or == 0 || or == 255 || og == 0 || og == 255 || ob == 0 || ob == 255) continue
            assertEquals("R/G hue proxy", ir.toDouble() / ig, or.toDouble() / og, 0.06)
            assertEquals("B/G hue proxy", ib.toDouble() / ig, ob.toDouble() / og, 0.06)
        }
    }

    @Test
    fun preservesDimensionsAndAlpha() {
        val input = Frame(
            width = 2,
            height = 1,
            argb = intArrayOf(gray(40), gray(200)),
            timestampMillis = 77L,
        )
        val output = DetailEnhancer.apply(input)
        assertEquals(2, output.width)
        assertEquals(1, output.height)
        assertEquals(77L, output.timestampMillis)
        for (pixel in output.argb) assertEquals(0xFF, (pixel ushr 24) and 0xFF)
    }

    @Test
    fun isDeterministic() {
        val width = 32
        val height = 32
        val canvas = SyntheticImages.texturedCanvas(width, height, seed = 0xABCDL)
        val input = Frame(width, height, canvas, 0L)
        val first = DetailEnhancer.apply(input)
        val second = DetailEnhancer.apply(input)
        assertTrue(first.argb.contentEquals(second.argb))
    }
}
