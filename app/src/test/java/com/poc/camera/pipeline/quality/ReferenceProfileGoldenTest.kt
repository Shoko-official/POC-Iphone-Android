package com.poc.camera.pipeline.quality

import com.poc.camera.pipeline.BurstMergePipeline
import com.poc.camera.pipeline.FinishingParams
import com.poc.camera.pipeline.FinishingPipeline
import com.poc.camera.pipeline.Frame
import com.poc.camera.pipeline.SkinMask
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * Objective golden gate for the reference-matched profile [FinishingParams.REFERENCE]
 * (issue #99), whose four retuned global strengths (saturation 1.15, chromaDenoise 1.0,
 * shadowsLift 0.18, skinProtection 0.85) were FITTED against a real iPhone-HEIC rendition of
 * the same scene. REFERENCE is a SHIPPING preset ([com.poc.camera.settings.FinishingPreset.Natural]),
 * evaluated here on its own axis -- it does NOT replace [FinishingParams.RENDITION] on the
 * rendition axis ([RenditionTargets] stays anchored on RENDITION), so this suite is the only
 * thing that gates it and it moves no existing floor.
 *
 * The profile differs from RENDITION only in global-stage STRENGTHS, so the suite proves it
 * tracks the measured iPhone-signature DIRECTION relative to RENDITION on the still fidelity
 * colour scenes (landscape + colorchart):
 *  (a) SATURATION: REFERENCE renders a richer global colour than RENDITION (mean-sat codes
 *      up) but BOUNDED (<= [MAX_SAT_DELTA] codes) so it never runs away.
 *  (b) CHROMA NOISE: with the denoiser at full strength (1.0 vs RENDITION's 0.6) the
 *      chroma speckle in a noisy burst is EQUAL-OR-BETTER than RENDITION's, even after the
 *      stronger saturation inflates the residual.
 *  (c) SHADOW LIFT: the deeper toe (0.18 vs 0.12) lifts the shadow luma percentiles (p25 up)
 *      but BOUNDED (<= [MAX_SHADOW_LIFT] codes).
 *  (d) SKIN FAIRNESS: at the stronger skinProtection 0.85 the SkinProtectionGoldenTest
 *      guarantees still hold -- the mask fires on every Fitzpatrick tone, protected skin is
 *      never rotated in hue meaningfully more than unprotected and never over-saturated past
 *      unprotected, and skin luma barely moves.
 *  (e) DETERMINISM.
 *
 * Baselines were measured 2026-07-22 and are documented in the companion; bounds carry margin
 * so the test is a regression gate, not a brittle snapshot.
 */
class ReferenceProfileGoldenTest {

    private val reference = FinishingParams.REFERENCE
    private val rendition = FinishingParams.RENDITION

    private val landscapeMerged = BurstMergePipeline.merge(
        SyntheticScenes.landscapeBurst(SEED, BURST),
    ).merged
    private val colorchartMerged = BurstMergePipeline.merge(
        SyntheticScenes.burst("colorchart", SEED, BURST),
    ).merged

    /** The two colour still-fidelity scenes the direction is measured on. */
    private val scenes = listOf(
        "landscape" to landscapeMerged,
        "colorchart" to colorchartMerged,
    )

    private fun referenceOut(merged: Frame) = FinishingPipeline.apply(merged, reference)
    private fun renditionOut(merged: Frame) = FinishingPipeline.apply(merged, rendition)

    // --- (a) richer saturation than RENDITION, bounded ------------------------------

    @Test
    fun referenceRendersRicherSaturationThanRenditionBounded() {
        println("[reference] mean saturation (codes), reference vs rendition:")
        for ((name, merged) in scenes) {
            val ref = meanSat(referenceOut(merged))
            val ren = meanSat(renditionOut(merged))
            val delta = ref - ren
            println("  $name: rendition=${"%.2f".format(ren)} reference=${"%.2f".format(ref)} (delta +${"%.2f".format(delta)})")
            assertTrue(
                "$name: REFERENCE mean sat $ref must exceed RENDITION $ren (richer colour)",
                ref > ren,
            )
            assertTrue(
                "$name: REFERENCE saturation lift $delta must stay within $MAX_SAT_DELTA codes",
                delta <= MAX_SAT_DELTA,
            )
        }
    }

    // --- (b) equal-or-better chroma noise on a noisy burst --------------------------

    @Test
    fun referenceChromaNoiseIsEqualOrBetterThanRendition() {
        // Chroma speckle (opponent-chroma std) inside a uniform region of the noisy merge.
        // The denoiser at 1.0 removes more speckle before saturation than RENDITION's 0.6,
        // so the residual is equal-or-better even though REFERENCE's stronger saturation
        // inflates whatever survives.
        val sky = inset(SyntheticScenes.landscapeSkyBounds(), 6)
        val patch = COLORCHART_FLAT_PATCH
        val regions = listOf(
            "landscape-sky" to (landscapeMerged to sky),
            "colorchart-patch" to (colorchartMerged to patch),
        )
        println("[reference] chroma noise std (codes), reference vs rendition:")
        for ((name, pair) in regions) {
            val (merged, region) = pair
            val ref = chromaStd(referenceOut(merged), region)
            val ren = chromaStd(renditionOut(merged), region)
            val ratio = ref / ren
            println("  $name: rendition=${"%.3f".format(ren)} reference=${"%.3f".format(ref)} (ratio ${"%.3f".format(ratio)})")
            assertTrue(
                "$name: REFERENCE chroma noise $ref must be equal-or-better than RENDITION $ren (ratio $ratio <= $MAX_NOISE_RATIO)",
                ratio <= MAX_NOISE_RATIO,
            )
        }
    }

    // --- (c) shadow percentiles lifted, bounded ------------------------------------

    @Test
    fun referenceLiftsShadowPercentilesBounded() {
        println("[reference] shadow luma p25 (codes), reference vs rendition:")
        for ((name, merged) in scenes) {
            val refP25 = lumaPercentile(referenceOut(merged), 25)
            val renP25 = lumaPercentile(renditionOut(merged), 25)
            val delta = refP25 - renP25
            println("  $name: rendition p25=$renP25 reference p25=$refP25 (delta +$delta)")
            assertTrue(
                "$name: REFERENCE shadow p25 $refP25 must lift above RENDITION $renP25",
                refP25 > renP25,
            )
            assertTrue(
                "$name: REFERENCE shadow lift $delta must stay within $MAX_SHADOW_LIFT codes",
                delta <= MAX_SHADOW_LIFT,
            )
        }
    }

    // --- (d) skin fairness holds at skinProtection 0.85 ----------------------------

    @Test
    fun skinFairnessHoldsAtReferenceSkinProtection() {
        // Mirror SkinProtectionGoldenTest at REFERENCE's stronger skinProtection (0.85): the
        // skin chart merged from a noisy burst, finished twice with the SAME REFERENCE look
        // except protection on (0.85) vs off (0.0). Every claim is protected-vs-unprotected on
        // the identical merged input, so any difference is the protection alone.
        val clean = SyntheticScenes.skinChartClean()
        val merged = BurstMergePipeline.merge(
            SyntheticScenes.burstOf(clean, SKIN_SEED, SKIN_BURST),
        ).merged
        val protectedOut = FinishingPipeline.apply(merged, reference) // skinProtection 0.85
        val unprotectedOut = FinishingPipeline.apply(merged, reference.copy(skinProtection = 0.0))
        val mask = SkinMask.compute(clean)

        println("[reference] skin fairness at skinProtection 0.85 (hue deg / chroma inflation / luma delta):")
        for (name in SKIN_PATCHES) {
            val r = region(name)
            val maskMean = meanOverInterior(mask, r)
            assertTrue("skin patch $name mask mean $maskMean must exceed 0.5", maskMean > 0.5)

            val (cCb, cCr) = meanChroma(clean, r)
            val (pCb, pCr) = meanChroma(protectedOut, r)
            val (uCb, uCr) = meanChroma(unprotectedOut, r)
            val cleanMag = mag(cCb, cCr)
            val protShift = angularDelta(atan2(pCr, pCb), atan2(cCr, cCb))
            val unprotShift = angularDelta(atan2(uCr, uCb), atan2(cCr, cCb))
            val protInfl = mag(pCb, pCr) / cleanMag
            val unprotInfl = mag(uCb, uCr) / cleanMag
            val lumaDelta = meanLumaOverInterior(protectedOut, r) - meanLumaOverInterior(unprotectedOut, r)
            println(
                "  $name: hue prot=${"%.2f".format(protShift)} unprot=${"%.2f".format(unprotShift)} | " +
                    "chromaInfl prot=${"%.3f".format(protInfl)} unprot=${"%.3f".format(unprotInfl)} | luma delta=${"%.2f".format(lumaDelta)}",
            )
            assertTrue(
                "$name protected hue shift $protShift must stay under $MAX_HUE_SHIFT_DEG deg",
                protShift < MAX_HUE_SHIFT_DEG,
            )
            assertTrue(
                "$name protected hue shift $protShift must not exceed unprotected $unprotShift by more than $HUE_EPS deg",
                protShift <= unprotShift + HUE_EPS,
            )
            assertTrue(
                "$name protected chroma inflation $protInfl must not exceed unprotected $unprotInfl by more than $CHROMA_BAND",
                protInfl <= unprotInfl + CHROMA_BAND,
            )
            assertTrue(
                "$name protected chroma inflation $protInfl must stay under $MAX_CHROMA_INFL",
                protInfl < MAX_CHROMA_INFL,
            )
            assertTrue(
                "$name skin luma delta ${abs(lumaDelta)} must stay within $MAX_LUMA_DELTA codes",
                abs(lumaDelta) <= MAX_LUMA_DELTA,
            )
        }
    }

    // --- (e) determinism -----------------------------------------------------------

    @Test
    fun referenceProfileIsDeterministic() {
        for ((name, merged) in scenes) {
            val a = FinishingPipeline.apply(merged, reference)
            val b = FinishingPipeline.apply(merged, reference)
            assertTrue("$name REFERENCE finish must be deterministic", a.argb.contentEquals(b.argb))
        }
    }

    // --- measurement helpers -------------------------------------------------------

    /** Mean per-pixel saturation (mean of |channel - luma|), the fitting-harness signature. */
    private fun meanSat(frame: Frame): Double {
        var sum = 0.0
        val n = frame.argb.size
        for (px in frame.argb) {
            val r = (px shr 16) and 0xFF
            val g = (px shr 8) and 0xFF
            val b = px and 0xFF
            val y = (299 * r + 587 * g + 114 * b) / 1000
            sum += (abs(r - y) + abs(g - y) + abs(b - y)) / 3.0
        }
        return sum / n
    }

    /** The [p]th luma percentile (integer Rec.601), the fitting-harness signature axis. */
    private fun lumaPercentile(frame: Frame, p: Int): Int {
        val n = frame.argb.size
        val luma = IntArray(n)
        for (i in 0 until n) {
            val px = frame.argb[i]
            val r = (px shr 16) and 0xFF
            val g = (px shr 8) and 0xFF
            val b = px and 0xFF
            luma[i] = (299 * r + 587 * g + 114 * b) / 1000
        }
        luma.sort()
        return luma[(n - 1) * p / 100]
    }

    /** Opponent-chroma std (sqrt(var(cr) + var(cb))) over a region: the speckle magnitude. */
    private fun chromaStd(frame: Frame, b: IntArray): Double {
        val crs = ArrayList<Double>()
        val cbs = ArrayList<Double>()
        for (y in b[1] until b[3]) for (x in b[0] until b[2]) {
            val px = frame.argb[y * frame.width + x]
            val r = ((px shr 16) and 0xFF).toDouble()
            val g = ((px shr 8) and 0xFF).toDouble()
            val bl = (px and 0xFF).toDouble()
            val yl = 0.299 * r + 0.587 * g + 0.114 * bl
            crs.add(r - yl); cbs.add(bl - yl)
        }
        return sqrt(variance(crs) + variance(cbs))
    }

    private fun variance(v: List<Double>): Double {
        val m = v.average()
        return v.sumOf { (it - m) * (it - m) } / v.size
    }

    private fun inset(b: IntArray, by: Int): IntArray = intArrayOf(b[0] + by, b[1] + by, b[2] - by, b[3] - by)

    // --- skin-fairness helpers (measured inline; SkinProtectionGoldenTest's are private) ---

    private fun region(name: String): SyntheticScenes.SkinChartRegion =
        SyntheticScenes.skinChartRegions.first { it.name == name }

    private fun interiorBounds(r: SyntheticScenes.SkinChartRegion): IntArray =
        intArrayOf(r.x0 + INSET, r.y0 + INSET, r.x1 - INSET, r.y1 - INSET)

    private fun meanOverInterior(plane: DoubleArray, r: SyntheticScenes.SkinChartRegion): Double {
        val (x0, y0, x1, y1) = interiorBounds(r)
        var sum = 0.0
        var n = 0
        for (y in y0 until y1) for (x in x0 until x1) {
            sum += plane[y * SyntheticScenes.SIZE + x]; n++
        }
        return sum / n
    }

    private fun meanLumaOverInterior(frame: Frame, r: SyntheticScenes.SkinChartRegion): Double {
        val (x0, y0, x1, y1) = interiorBounds(r)
        var sum = 0.0
        var n = 0
        for (y in y0 until y1) for (x in x0 until x1) {
            sum += luma(frame.argb[y * SyntheticScenes.SIZE + x]); n++
        }
        return sum / n
    }

    /** Mean chroma vector (Cb-128, Cr-128) over a region interior, JFIF axes. */
    private fun meanChroma(frame: Frame, r: SyntheticScenes.SkinChartRegion): Pair<Double, Double> {
        val (x0, y0, x1, y1) = interiorBounds(r)
        var sumCb = 0.0
        var sumCr = 0.0
        var n = 0
        for (y in y0 until y1) for (x in x0 until x1) {
            val p = frame.argb[y * SyntheticScenes.SIZE + x]
            val rr = ((p shr 16) and 0xFF).toDouble()
            val gg = ((p shr 8) and 0xFF).toDouble()
            val bb = (p and 0xFF).toDouble()
            sumCb += (128.0 - 0.168736 * rr - 0.331264 * gg + 0.5 * bb) - 128.0
            sumCr += (128.0 + 0.5 * rr - 0.418688 * gg - 0.081312 * bb) - 128.0
            n++
        }
        return (sumCb / n) to (sumCr / n)
    }

    private fun mag(cb: Double, cr: Double): Double = sqrt(cb * cb + cr * cr)

    /** Absolute angular difference in DEGREES, wrapped to [0, 180]. */
    private fun angularDelta(a: Double, b: Double): Double {
        var d = Math.toDegrees(a - b)
        while (d > 180.0) d -= 360.0
        while (d < -180.0) d += 360.0
        return abs(d)
    }

    private fun luma(pixel: Int): Double =
        0.299 * ((pixel shr 16) and 0xFF) + 0.587 * ((pixel shr 8) and 0xFF) + 0.114 * (pixel and 0xFF)

    private operator fun IntArray.component1() = this[0]
    private operator fun IntArray.component2() = this[1]
    private operator fun IntArray.component3() = this[2]
    private operator fun IntArray.component4() = this[3]

    private companion object {
        const val SEED = 0xC0FFEEL
        const val BURST = 8
        const val SKIN_SEED = 0xC0FFEEL
        const val SKIN_BURST = 8
        const val INSET = 10

        val SKIN_PATCHES = listOf("light", "fair", "medium", "olive", "brown", "deep")

        /** A flat interior window inside colorchart patch (0,0) -- the saturated red patch. */
        val COLORCHART_FLAT_PATCH = intArrayOf(3, 3, 18, 18)

        // MEASURED BASELINES, 2026-07-22 (seed 0xC0FFEE, 8-frame merge), REFERENCE vs RENDITION.
        // RE-MEASURED the same day after the [ChromaRollOff] shoulder gained its spatial
        // isolation gate (issue #107): both profiles share the roll-off, so gating it moves
        // several actuals below -- each re-measure is annotated.
        //
        // (a) mean saturation (codes), REFERENCE - RENDITION:
        //       landscape  +0.98   colorchart +0.93
        //     Both POSITIVE (REFERENCE renders richer colour). Pre-gate these were +0.20 /
        //     +0.06: the UNGATED whole-frame roll-off compressed most of the extra chroma the
        //     1.15 saturation pushed past its knee, eating the profile's richer look -- exactly
        //     the "we remove more colour than the reference keeps" problem issue #107 fixes.
        //     With the gate, uniformly saturated content keeps the boost and the measured
        //     delta grows ~5x toward the fitted intent. MAX_SAT_DELTA (6) is the committed
        //     "does not run away" ceiling from the issue, still with wide margin.
        // (b) chroma noise std ratio (REFERENCE / RENDITION) on a noisy burst:
        //       landscape-sky 0.972   colorchart-patch 0.841  (both < 1 -> equal-or-better)
        //     Pre-gate these were 0.766 / 0.839 -- but that landscape-sky figure was partly an
        //     artifact of the ungated shoulder: compressing the (uniformly saturated) sky
        //     crushed its chroma VARIANCE along with its magnitude, and REFERENCE's stronger
        //     saturation pushed the sky deeper into the flat part of the shoulder, deflating
        //     its std the most. With the gate the sky passes through and the honest
        //     denoiser-vs-saturation balance remains: the denoiser at 1.0 still beats
        //     RENDITION's 0.6 after the stronger saturation inflates the residual, narrowly on
        //     the high-chroma sky (0.972) and clearly on the colorchart patch (0.841).
        //     MAX_NOISE_RATIO (0.99) bakes the equal-or-better ceiling under margin.
        // (c) shadow luma p25 lift (codes), REFERENCE - RENDITION:
        //       landscape +2   colorchart +2   (both POSITIVE -> shadows opened by the deeper toe)
        //     MAX_SHADOW_LIFT (12) is the "bounded" ceiling, with margin.
        // (d) skin fairness at skinProtection 0.85 (protected / unprotected), skin chart,
        //     re-measured with the gated roll-off (the mid/deep patches' chroma sits near the
        //     knee, so gating shifts their inflation slightly):
        //       patch   hue-prot  hue-unprot  chromaInfl-prot  chromaInfl-unprot  luma-delta
        //       light    17.37     17.80        0.273            0.251             +0.61
        //       fair     13.78     15.21        0.462            0.496             +3.17
        //       medium    3.04      2.74        0.619            0.671             +2.14
        //       olive     1.57      1.83        0.682            0.746             +0.88
        //       brown     0.10      0.26        0.739            0.833             -1.25
        //       deep      0.42      1.54        0.766            0.878             -2.07
        //     Mask mean 1.000 on every skin tone. The bounds mirror SkinProtectionGoldenTest:
        //       MAX_HUE_SHIFT_DEG (22): dominated by the tone curve on BRIGHT skin (light/fair
        //         rotate ~14-17 deg WITH OR WITHOUT protection); actual max 17.37 (light).
        //       HUE_EPS (2.5): protected may not rotate more than unprotected beyond this; worst
        //         is medium (+0.30, low chroma makes the angle rounding-sensitive).
        //       CHROMA_BAND (0.05): protected may not be MORE saturated than unprotected beyond
        //         this; only near-white "light" edges up (+0.022, tone curve not saturation sets
        //         its chroma). MAX_CHROMA_INFL (1.1) guards a gross over-saturation regression
        //         (actual max 0.766). At 0.85 the mid/deep patches are bounded harder than at 0.7.
        //       MAX_LUMA_DELTA (4.0): |protected - unprotected| skin mean luma (actual max 3.17);
        //         a touch higher than the 0.7 baseline because the stronger protection reduces the
        //         (now stronger-saturation) operators by more, so protected sits nearer the merged
        //         truth. Protection only REDUCES operators; it never lightens/darkens/shifts skin.
        const val MAX_SAT_DELTA = 6.0
        const val MAX_NOISE_RATIO = 0.99
        const val MAX_SHADOW_LIFT = 12
        const val MAX_HUE_SHIFT_DEG = 22.0
        const val HUE_EPS = 2.5
        const val CHROMA_BAND = 0.05
        const val MAX_CHROMA_INFL = 1.1
        const val MAX_LUMA_DELTA = 4.0
    }
}
