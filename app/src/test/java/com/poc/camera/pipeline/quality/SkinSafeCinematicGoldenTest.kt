package com.poc.camera.pipeline.quality

import com.poc.camera.camera.LookCameraEffect
import com.poc.camera.pipeline.Looks
import com.poc.camera.pipeline.Lut3d
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * Objective gate for the skin-safe cinematic video look (issue #134). The video path's
 * teal-orange LUT ([Looks.cinematic]) is precomputed and sampled on-GPU per frame, so unlike
 * the still pipeline (SkinMask + FinishingPipeline, gated by SkinProtectionGoldenTest) its
 * skin protection must be baked into the LATTICE. [Looks.skinSafeCinematic] does exactly
 * that, and this suite proves it on the same six Fitzpatrick chart tones the still gate
 * uses ([SyntheticScenes.skinChartRegions]), each sampled through both LUTs via
 * [Lut3d.sample] -- the identical trilinear path the GL shader implements.
 *
 * Every skin claim is skin-safe-vs-raw on the identical input colour, so any difference is
 * the protection alone. "Added" hue rotation / chroma change is measured against the INPUT
 * colour (i.e. on top of [Looks.neutral], which is the identity): that is the honest
 * comparison for a LOOK, whereas the still pipeline's absolute bounds also absorb its tone
 * curve (its RENDITION rotates bright skin up to ~20 deg with or without protection).
 */
class SkinSafeCinematicGoldenTest {

    private val raw = Looks.cinematic(LookCameraEffect.LUT_SIZE)
    private val safe = Looks.skinSafeCinematic(LookCameraEffect.LUT_SIZE)

    /** The six Fitzpatrick chart tones (light..deep), shared with the still gate. */
    private val skinTones = SyntheticScenes.skinChartRegions.filter { it.skin && !it.textured }

    /**
     * Non-skin character colours: the chart's false-positive risks plus a warm gold
     * highlight (the orange the split-tone exists for, warmer than any skin tone).
     */
    private val nonSkinColours = listOf(
        "foliage" to intArrayOf(60, 110, 45),
        "sky" to intArrayOf(110, 155, 210),
        "fabric" to intArrayOf(200, 30, 40),
        "warm-gold" to intArrayOf(255, 180, 80),
    )

    // --- (a) added skin-hue rotation bounded, and always below the raw look --------

    @Test
    fun skinHueRotationIsBoundedAndAlwaysBelowTheRawLook() {
        println("[skin-safe-cinematic] added hue rotation (deg) vs input, raw vs skin-safe:")
        for (tone in skinTones) {
            val (r, g, b) = unpack(tone.rgb)
            val rawShift = hueShiftDeg(raw, r, g, b)
            val safeShift = hueShiftDeg(safe, r, g, b)
            println("  ${tone.name}: raw=${"%.2f".format(rawShift)} safe=${"%.2f".format(safeShift)}")
            assertTrue(
                "${tone.name} skin-safe hue rotation $safeShift must stay under $MAX_SKIN_HUE_DEG deg",
                safeShift < MAX_SKIN_HUE_DEG,
            )
            assertTrue(
                "${tone.name} skin-safe hue rotation $safeShift must be below the raw look's $rawShift",
                safeShift < rawShift,
            )
        }
    }

    // --- (b) skin chroma stays near truth, and closer than the raw look ------------

    @Test
    fun skinChromaStaysNearTruthAndCloserThanTheRawLook() {
        println("[skin-safe-cinematic] chroma-magnitude ratio (out/in), raw vs skin-safe:")
        for (tone in skinTones) {
            val (r, g, b) = unpack(tone.rgb)
            val rawRatio = chromaRatio(raw, r, g, b)
            val safeRatio = chromaRatio(safe, r, g, b)
            println("  ${tone.name}: raw=${"%.3f".format(rawRatio)} safe=${"%.3f".format(safeRatio)}")
            assertTrue(
                "${tone.name} skin-safe chroma ratio $safeRatio must stay within " +
                    "[$MIN_SKIN_CHROMA_RATIO, $MAX_SKIN_CHROMA_RATIO]",
                safeRatio in MIN_SKIN_CHROMA_RATIO..MAX_SKIN_CHROMA_RATIO,
            )
            assertTrue(
                "${tone.name} skin-safe chroma change ${abs(safeRatio - 1.0)} must be below " +
                    "the raw look's ${abs(rawRatio - 1.0)}",
                abs(safeRatio - 1.0) < abs(rawRatio - 1.0),
            )
        }
    }

    // --- (c) skin keeps the tonal grade (only hue/saturation are protected) --------

    @Test
    fun skinKeepsTheLumaCurve() {
        // The protection blends toward the TONE-ONLY colour, not the input: skin must still
        // receive the S-curve (cinema grades faces tonally). Proof: the skin-safe luma moves
        // from the input in the SAME direction as the raw look's, and stays within a small
        // band of it (the residual band is the split-tone's own warm luma lift, deliberately
        // removed on skin along with its chroma push).
        println("[skin-safe-cinematic] skin luma (codes): input, raw, skin-safe:")
        for (tone in skinTones) {
            val (r, g, b) = unpack(tone.rgb)
            val inLuma = luma255(r.toDouble(), g.toDouble(), b.toDouble())
            val rawLuma = outLuma(raw, r, g, b)
            val safeLuma = outLuma(safe, r, g, b)
            println(
                "  ${tone.name}: in=${"%.1f".format(inLuma)} raw=${"%.1f".format(rawLuma)} " +
                    "safe=${"%.1f".format(safeLuma)}",
            )
            assertTrue(
                "${tone.name} luma delta |${safeLuma - rawLuma}| vs raw must stay within " +
                    "$MAX_SKIN_LUMA_DELTA codes (S-curve preserved)",
                abs(safeLuma - rawLuma) <= MAX_SKIN_LUMA_DELTA,
            )
            assertTrue(
                "${tone.name} skin-safe luma must move from the input in the raw look's " +
                    "direction (in=$inLuma raw=$rawLuma safe=$safeLuma)",
                (safeLuma - inLuma) * (rawLuma - inLuma) > 0.0,
            )
        }
    }

    // --- (d) non-skin character preserved ------------------------------------------

    @Test
    fun nonSkinColoursSampleIdenticallyThroughBothLuts() {
        // Every lattice corner these colours interpolate sits outside the skin chroma
        // ellipse, so the protection never touches them: the teal-orange character off
        // skin is preserved essentially exactly.
        println("[skin-safe-cinematic] non-skin max channel delta (codes), skin-safe vs raw:")
        for ((name, rgb) in nonSkinColours) {
            val rawOut = raw.sample(rgb[0] / 255f, rgb[1] / 255f, rgb[2] / 255f)
            val safeOut = safe.sample(rgb[0] / 255f, rgb[1] / 255f, rgb[2] / 255f)
            var maxDelta = 0.0
            for (c in 0..2) maxDelta = maxOf(maxDelta, abs(safeOut[c] - rawOut[c]) * 255.0)
            println("  $name: ${"%.3f".format(maxDelta)}")
            assertTrue(
                "$name channel delta $maxDelta codes must stay under $NON_SKIN_MAX_DELTA",
                maxDelta < NON_SKIN_MAX_DELTA,
            )
        }
    }

    @Test
    fun grayAxisIsPreserved() {
        // Neutral colours sit far outside the skin ellipse, so gray LATTICE points are
        // bit-identical; between lattice points a gray sample may touch off-axis corners
        // with partial skin membership, so the fine ramp is allowed a sub-code delta.
        val n1 = LookCameraEffect.LUT_SIZE - 1
        for (i in 0..n1) {
            val v = i.toFloat() / n1
            val rawOut = raw.sample(v, v, v)
            val safeOut = safe.sample(v, v, v)
            for (c in 0..2) {
                assertEquals(
                    "gray lattice point $i channel $c must be identical",
                    rawOut[c],
                    safeOut[c],
                    0f,
                )
            }
        }
        var maxDelta = 0.0
        for (step in 0..255) {
            val v = step / 255f
            val rawOut = raw.sample(v, v, v)
            val safeOut = safe.sample(v, v, v)
            for (c in 0..2) maxDelta = maxOf(maxDelta, abs(safeOut[c] - rawOut[c]) * 255.0)
        }
        println("[skin-safe-cinematic] gray ramp max channel delta = ${"%.3f".format(maxDelta)} codes")
        assertTrue(
            "gray ramp delta $maxDelta codes must stay under $GRAY_MAX_DELTA",
            maxDelta < GRAY_MAX_DELTA,
        )
    }

    // --- measurement helpers -------------------------------------------------------

    /** Hue rotation the look adds on top of neutral (identity), in degrees, [0, 180]. */
    private fun hueShiftDeg(lut: Lut3d, r: Int, g: Int, b: Int): Double {
        val (inCb, inCr) = chroma(r.toDouble(), g.toDouble(), b.toDouble())
        val (outCb, outCr) = sampledChroma(lut, r, g, b)
        return angularDelta(atan2(outCr, outCb), atan2(inCr, inCb))
    }

    /** Chroma-magnitude ratio (out / in) through [lut]. */
    private fun chromaRatio(lut: Lut3d, r: Int, g: Int, b: Int): Double {
        val (inCb, inCr) = chroma(r.toDouble(), g.toDouble(), b.toDouble())
        val (outCb, outCr) = sampledChroma(lut, r, g, b)
        return hypot(outCb, outCr) / hypot(inCb, inCr)
    }

    private fun outLuma(lut: Lut3d, r: Int, g: Int, b: Int): Double {
        val out = lut.sample(r / 255f, g / 255f, b / 255f)
        return luma255(out[0] * 255.0, out[1] * 255.0, out[2] * 255.0)
    }

    private fun sampledChroma(lut: Lut3d, r: Int, g: Int, b: Int): Pair<Double, Double> {
        val out = lut.sample(r / 255f, g / 255f, b / 255f)
        return chroma(out[0] * 255.0, out[1] * 255.0, out[2] * 255.0)
    }

    /** Chroma vector (Cb - 128, Cr - 128) in JFIF axes, inputs in [0, 255]. */
    private fun chroma(r: Double, g: Double, b: Double): Pair<Double, Double> {
        val cb = (128.0 - 0.168736 * r - 0.331264 * g + 0.5 * b) - 128.0
        val cr = (128.0 + 0.5 * r - 0.418688 * g - 0.081312 * b) - 128.0
        return cb to cr
    }

    private fun luma255(r: Double, g: Double, b: Double): Double =
        0.299 * r + 0.587 * g + 0.114 * b

    /** Absolute angular difference in DEGREES, wrapped to [0, 180]. */
    private fun angularDelta(a: Double, b: Double): Double {
        var d = Math.toDegrees(a - b)
        while (d > 180.0) d -= 360.0
        while (d < -180.0) d += 360.0
        return abs(d)
    }

    private fun unpack(rgb: Int): Triple<Int, Int, Int> =
        Triple((rgb shr 16) and 0xFF, (rgb shr 8) and 0xFF, rgb and 0xFF)

    private companion object {
        // MEASURED BASELINES, 2026-07-23, LUT size 17 (LookCameraEffect.LUT_SIZE), sampled
        // through Lut3d.sample. Per tone (raw cinematic / skin-safe), added hue rotation in
        // degrees vs input and chroma-magnitude ratio out/in:
        //   tone     hue raw   hue safe   chroma raw   chroma safe   lumaDelta safe-raw
        //   light     10.74      3.51       1.117         0.965           -4.10
        //   fair       7.23      1.48       1.107         1.002           -4.63
        //   medium     4.22      0.65       1.030         1.004           -3.93
        //   olive      3.28      0.53       0.931         0.987           -2.40
        //   brown      2.04      0.25       0.801         0.970           -0.83
        //   deep       1.95      0.25       0.573         0.917           +0.45
        // Non-skin (foliage/sky/fabric/warm-gold): 0.000 codes delta -- every corner their
        // samples interpolate is outside the skin ellipse. Gray ramp: max 0.535 codes.
        //
        // Notes on the bounds:
        //  - MAX_SKIN_HUE_DEG (4.0): worst safe rotation is "light" (3.51). Its residual is
        //    NOT split-tone leakage: near white the S-curve lift clamps the red channel at
        //    1.0, which rotates chroma -- a tone effect the look deliberately keeps on skin.
        //    The raw look rotates the same tone 10.74 deg; the still RENDITION tone curve
        //    rotates it ~20 deg (SkinProtectionGoldenTest baselines) for comparison.
        //  - MIN/MAX_SKIN_CHROMA_RATIO (0.88 / 1.05): worst safe ratio is "deep" (0.917) --
        //    the raw look grays deep skin to 0.573 (a 43% chroma loss, the sallow-face
        //    failure this look exists to fix); protection recovers it to an 8% loss.
        //  - MAX_SKIN_LUMA_DELTA (6.0): the safe look removes the split-tone's own warm
        //    luma lift on skin along with its chroma (worst "fair", -4.63 codes); the
        //    S-curve itself is preserved, proven by the direction assertion.
        //  - NON_SKIN_MAX_DELTA (0.25) / GRAY_MAX_DELTA (1.0): measured 0.000 and 0.535.
        const val MAX_SKIN_HUE_DEG = 4.0
        const val MIN_SKIN_CHROMA_RATIO = 0.88
        const val MAX_SKIN_CHROMA_RATIO = 1.05
        const val MAX_SKIN_LUMA_DELTA = 6.0
        const val NON_SKIN_MAX_DELTA = 0.25
        const val GRAY_MAX_DELTA = 1.0
    }
}
