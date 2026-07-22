package com.poc.camera.pipeline.quality

import com.poc.camera.pipeline.BurstMergePipeline
import com.poc.camera.pipeline.FinishingParams
import com.poc.camera.pipeline.FinishingPipeline
import com.poc.camera.pipeline.FoliageMask
import com.poc.camera.pipeline.Frame
import com.poc.camera.pipeline.OvercastSkyMask
import com.poc.camera.pipeline.PipelineParallel
import com.poc.camera.pipeline.SemanticRendering
import com.poc.camera.pipeline.SkyMask
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * Objective golden gate for semantic-region rendering (issue #98). Every claim is measured on
 * the synthetic "landscape" scene (blue sky band over textured foliage over neutral ground,
 * with a skin patch) and on the existing named scenes, so the numbers are deterministic and
 * reproducible across machines.
 *
 * The suite proves:
 *  (a) SKY CHROMA NOISE reduced: the sky-region chroma std drops with semanticRendering on
 *      vs off (the sky carries injected chroma noise the guided smoothing removes).
 *  (b) SKY BLUE DEEPENED, bounded + hue-stable: the mean opponent Cb (B - Y) in the sky lifts
 *      by [MIN_CB_SHIFT]..[MAX_CB_SHIFT] codes with the mean-chroma hue angle stable
 *      (< [MAX_HUE_SHIFT_DEG] deg).
 *  (c) FOLIAGE ENRICHED + LIFTED, bounded + hue-stable: the foliage chroma magnitude and luma
 *      both rise (bounded), hue stable.
 *  (d) NON-INTERFERENCE: the sky/foliage masks read < [MAX_OFF_REGION_MASK] mean on the ground
 *      and skin regions, so those regions are left near-identical on vs off (max channel diff
 *      <= [MAX_OFF_REGION_DIFF]) -- semantic rendering does not disturb skin or ground.
 *  (e) FALSE-POSITIVE MATRIX: both masks stay quiet (whole-image mean under a baked bound) on
 *      every existing named scene, including the adversarial "colorchart" whose blue/green
 *      patches partially fire the chroma prior -- the position prior (sky) and the multiplicative
 *      gates (foliage) keep the whole-image mean low.
 *  (f) DETERMINISM + parallel bit-identity.
 *  (g) grayscale no-op: the blue-sky/foliage masks are exactly 0 on ANY grayscale scene, and
 *      on a dark grayscale scene ("lowlight") the overcast mask is exactly 0 too, so the
 *      finish there is byte-identical with semanticRendering on vs off. Bright smooth
 *      grayscale content CAN fire the overcast prior (issue #106) but receives only the
 *      mean-preserving chroma smoothing -- see OvercastSkyGoldenTest for those bounds.
 *
 * Baselines were measured 2026-07-22 and are documented in the companion; bounds carry margin
 * so the test is a regression gate, not a brittle snapshot.
 */
class SemanticRenderingGoldenTest {

    private val clean = SyntheticScenes.landscapeClean()
    private val merged = BurstMergePipeline.merge(
        SyntheticScenes.landscapeBurst(SEED, BURST),
    ).merged
    private val on = FinishingPipeline.apply(merged, FinishingParams.RENDITION)
    private val off = FinishingPipeline.apply(merged, FinishingParams.RENDITION.copy(semanticRendering = 0.0))

    private val sky = inset(SyntheticScenes.landscapeSkyBounds(), 6)
    private val foliage = inset(SyntheticScenes.landscapeFoliageBounds(), 6)
    private val ground = inset(SyntheticScenes.landscapeGroundBounds(), 4)
    private val skin = inset(SyntheticScenes.landscapeSkinBounds(), 4)

    // --- (a) sky chroma noise reduced ----------------------------------------------

    @Test
    fun skyChromaNoiseIsReduced() {
        val before = chromaStd(off, sky)
        val after = chromaStd(on, sky)
        val ratio = after / before
        println("[semantic] sky chroma std off=${"%.2f".format(before)} on=${"%.2f".format(after)} (ratio ${"%.2f".format(ratio)})")
        assertTrue("sky chroma std must drop with semantic rendering on ($after vs $before)", after < before * MAX_SKY_STD_RATIO)
    }

    // --- (b) sky blue deepened, bounded + hue-stable -------------------------------

    @Test
    fun skyBlueDeepenedBoundedlyAndHueStable() {
        val cbOff = meanCb(off, sky)
        val cbOn = meanCb(on, sky)
        val shift = cbOn - cbOff
        val hueShift = abs(meanChromaAngleDeg(on, sky) - meanChromaAngleDeg(off, sky))
        println("[semantic] sky mean Cb off=${"%.2f".format(cbOff)} on=${"%.2f".format(cbOn)} (shift ${"%.2f".format(shift)}) hueShift=${"%.2f".format(hueShift)} deg")
        assertTrue("sky mean Cb shift $shift must be at least $MIN_CB_SHIFT codes", shift >= MIN_CB_SHIFT)
        assertTrue("sky mean Cb shift $shift must stay within $MAX_CB_SHIFT codes", shift <= MAX_CB_SHIFT)
        assertTrue("sky hue must stay within $MAX_HUE_SHIFT_DEG deg (was $hueShift)", hueShift < MAX_HUE_SHIFT_DEG)
    }

    // --- (c) foliage enriched + lifted, bounded + hue-stable -----------------------

    @Test
    fun foliageEnrichedAndLiftedBoundedlyAndHueStable() {
        val magOff = meanChromaMag(off, foliage)
        val magOn = meanChromaMag(on, foliage)
        val lumaOff = meanLuma(off, foliage)
        val lumaOn = meanLuma(on, foliage)
        val chromaShift = magOn - magOff
        val lumaShift = lumaOn - lumaOff
        val hueShift = abs(meanChromaAngleDeg(on, foliage) - meanChromaAngleDeg(off, foliage))
        println(
            "[semantic] foliage chroma off=${"%.2f".format(magOff)} on=${"%.2f".format(magOn)} (+${"%.2f".format(chromaShift)}) " +
                "luma off=${"%.2f".format(lumaOff)} on=${"%.2f".format(lumaOn)} (+${"%.2f".format(lumaShift)}) hueShift=${"%.2f".format(hueShift)} deg",
        )
        assertTrue("foliage chroma must enrich (+$chromaShift)", chromaShift >= MIN_FOLIAGE_CHROMA_SHIFT)
        assertTrue("foliage chroma enrichment must stay bounded (+$chromaShift)", chromaShift <= MAX_REGION_SHIFT)
        assertTrue("foliage luma must lift (+$lumaShift)", lumaShift >= MIN_FOLIAGE_LUMA_SHIFT)
        assertTrue("foliage luma lift must stay bounded (+$lumaShift)", lumaShift <= MAX_REGION_SHIFT)
        assertTrue("foliage hue must stay within $MAX_HUE_SHIFT_DEG deg (was $hueShift)", hueShift < MAX_HUE_SHIFT_DEG)
    }

    // --- (d) non-interference on ground + skin -------------------------------------

    @Test
    fun groundAndSkinAreNotDisturbed() {
        val skyMask = SkyMask.compute(clean)
        val folMask = FoliageMask.compute(clean)
        for ((label, b) in listOf("ground" to ground, "skin" to skin)) {
            val skyMean = regionMean(skyMask, b)
            val folMean = regionMean(folMask, b)
            val diff = maxChannelDiff(on, off, b)
            println("[semantic] $label skyMask=${"%.3f".format(skyMean)} foliageMask=${"%.3f".format(folMean)} on-vs-off maxDiff=$diff")
            assertTrue("$label sky mask mean $skyMean must stay under $MAX_OFF_REGION_MASK", skyMean < MAX_OFF_REGION_MASK)
            assertTrue("$label foliage mask mean $folMean must stay under $MAX_OFF_REGION_MASK", folMean < MAX_OFF_REGION_MASK)
            assertTrue("$label must be near-identical on vs off (maxDiff $diff)", diff <= MAX_OFF_REGION_DIFF)
        }
    }

    // --- (e) false-positive matrix on existing scenes ------------------------------

    @Test
    fun masksStayQuietOnEveryExistingScene() {
        for (name in SyntheticScenes.names) {
            val scene = SyntheticScenes.clean(name)
            val skyMean = SkyMask.compute(scene).average()
            val folMean = FoliageMask.compute(scene).average()
            println("[semantic] false-positive $name skyMask=${"%.4f".format(skyMean)} foliageMask=${"%.4f".format(folMean)}")
            assertTrue("$name whole-image sky mask mean $skyMean must stay under $MAX_SCENE_SKY_MASK", skyMean < MAX_SCENE_SKY_MASK)
            assertTrue("$name whole-image foliage mask mean $folMean must stay under $MAX_SCENE_FOLIAGE_MASK", folMean < MAX_SCENE_FOLIAGE_MASK)
        }
    }

    // --- (f) determinism + parallel bit-identity -----------------------------------

    @Test
    fun renderingIsDeterministicAndParallelMatchesSerial() {
        val again = FinishingPipeline.apply(merged, FinishingParams.RENDITION)
        assertTrue("pipeline finish must be deterministic", again.argb.contentEquals(on.argb))

        val skyMask = SkyMask.compute(merged)
        val overcastMask = OvercastSkyMask.compute(merged)
        val folMask = FoliageMask.compute(merged)
        val serial = SemanticRendering.apply(merged, skyMask, overcastMask, folMask, com.poc.camera.pipeline.SemanticRenderingParams.DEFAULT, PipelineParallel.SERIAL_CHUNKS)
        val parallel = SemanticRendering.apply(merged, skyMask, overcastMask, folMask, com.poc.camera.pipeline.SemanticRenderingParams.DEFAULT, PipelineParallel.parallelism)
        assertTrue("parallel semantic rendering must be bit-identical to serial", serial.argb.contentEquals(parallel.argb))
    }

    // --- (g) grayscale no-op -------------------------------------------------------

    @Test
    fun grayscaleSceneIsBitIdenticalWithAndWithoutSemanticRendering() {
        // The BLUE sky and foliage priors are exactly zero on ANY grayscale content; the
        // overcast prior (issue #106) deliberately fires on bright smooth neutral upper
        // regions, so the strict bit-identity proof uses "lowlight" -- too dark for the
        // overcast bright prior, so ALL three masks are exactly zero and the finish is
        // byte-identical. The gradients scene, whose bright smooth upper areas DO fire the
        // overcast prior (receiving only benign chroma smoothing), is measured in
        // OvercastSkyGoldenTest.
        val gradientsClean = SyntheticScenes.clean("gradients")
        assertTrue("grayscale sky mask must be exactly zero", SkyMask.compute(gradientsClean).all { it == 0.0 })
        assertTrue("grayscale foliage mask must be exactly zero", FoliageMask.compute(gradientsClean).all { it == 0.0 })

        val lowlightClean = SyntheticScenes.clean("lowlight")
        assertTrue("lowlight sky mask must be exactly zero", SkyMask.compute(lowlightClean).all { it == 0.0 })
        assertTrue("lowlight foliage mask must be exactly zero", FoliageMask.compute(lowlightClean).all { it == 0.0 })
        assertTrue("lowlight overcast mask must be exactly zero", OvercastSkyMask.compute(lowlightClean).all { it == 0.0 })

        val grayMerged = BurstMergePipeline.merge(
            SyntheticScenes.burst("lowlight", SEED, BURST),
        ).merged
        val withSemantic = FinishingPipeline.apply(grayMerged, FinishingParams.RENDITION)
        val withoutSemantic = FinishingPipeline.apply(grayMerged, FinishingParams.RENDITION.copy(semanticRendering = 0.0))
        assertTrue(
            "lowlight finish must be byte-identical with vs without semantic rendering",
            withSemantic.argb.contentEquals(withoutSemantic.argb),
        )
    }

    // --- helpers -------------------------------------------------------------------

    private fun inset(b: IntArray, by: Int): IntArray = intArrayOf(b[0] + by, b[1] + by, b[2] - by, b[3] - by)

    private fun regionMean(plane: DoubleArray, b: IntArray): Double {
        var sum = 0.0
        var n = 0
        for (y in b[1] until b[3]) for (x in b[0] until b[2]) {
            sum += plane[y * SyntheticScenes.SIZE + x]; n++
        }
        return sum / n
    }

    private fun meanCb(frame: Frame, b: IntArray): Double = meanChromaVec(frame, b).second

    /** Mean (Cr, Cb) opponent chroma vector over a region. */
    private fun meanChromaVec(frame: Frame, b: IntArray): Pair<Double, Double> {
        var sumCr = 0.0
        var sumCb = 0.0
        var n = 0
        for (y in b[1] until b[3]) for (x in b[0] until b[2]) {
            val px = frame.argb[y * frame.width + x]
            val r = ((px shr 16) and 0xFF).toDouble()
            val g = ((px shr 8) and 0xFF).toDouble()
            val bl = (px and 0xFF).toDouble()
            val yl = 0.299 * r + 0.587 * g + 0.114 * bl
            sumCr += r - yl; sumCb += bl - yl; n++
        }
        return (sumCr / n) to (sumCb / n)
    }

    private fun meanChromaAngleDeg(frame: Frame, b: IntArray): Double {
        val (cr, cb) = meanChromaVec(frame, b)
        return Math.toDegrees(atan2(cr, cb))
    }

    private fun meanChromaMag(frame: Frame, b: IntArray): Double {
        var sum = 0.0
        var n = 0
        for (y in b[1] until b[3]) for (x in b[0] until b[2]) {
            val px = frame.argb[y * frame.width + x]
            val r = ((px shr 16) and 0xFF).toDouble()
            val g = ((px shr 8) and 0xFF).toDouble()
            val bl = (px and 0xFF).toDouble()
            val yl = 0.299 * r + 0.587 * g + 0.114 * bl
            sum += sqrt((r - yl) * (r - yl) + (bl - yl) * (bl - yl)); n++
        }
        return sum / n
    }

    private fun meanLuma(frame: Frame, b: IntArray): Double {
        var sum = 0.0
        var n = 0
        for (y in b[1] until b[3]) for (x in b[0] until b[2]) {
            val px = frame.argb[y * frame.width + x]
            sum += 0.299 * ((px shr 16) and 0xFF) + 0.587 * ((px shr 8) and 0xFF) + 0.114 * (px and 0xFF); n++
        }
        return sum / n
    }

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

    private fun maxChannelDiff(a: Frame, b: Frame, bounds: IntArray): Int {
        var worst = 0
        for (y in bounds[1] until bounds[3]) for (x in bounds[0] until bounds[2]) {
            val i = y * a.width + x
            val pa = a.argb[i]
            val pb = b.argb[i]
            val dr = abs(((pa shr 16) and 0xFF) - ((pb shr 16) and 0xFF))
            val dg = abs(((pa shr 8) and 0xFF) - ((pb shr 8) and 0xFF))
            val db = abs((pa and 0xFF) - (pb and 0xFF))
            worst = maxOf(worst, dr, dg, db)
        }
        return worst
    }

    private operator fun IntArray.component1() = this[0]
    private operator fun IntArray.component2() = this[1]

    private companion object {
        const val SEED = 0x1A9D5CAFEL
        const val BURST = 8

        // MEASURED 2026-07-22 (seed 0x1A9D5CAFE, 8-frame merge). Actuals -> baked bound (margin):
        //   sky chroma std ratio (on/off) : 0.86        -> ceiling 0.92 (noise reduced)
        //     RE-MEASURED after the [ChromaRollOff] spatial gate (issue #107): both the on and
        //     off arms run RENDITION's roll-off, and the UNGATED shoulder used to compress the
        //     (uniformly saturated) sky in both, deflating its chroma std nonlinearly and
        //     inflating the smoothing's RELATIVE reduction to 0.74. With the gate the sky
        //     passes the roll-off untouched (off-arm std now 8.10), and the honest
        //     smoothing-only reduction is 0.86 -- still a clear (>= 8% guaranteed) drop.
        //   sky mean Cb shift             : +4.39 codes -> gate [2, 12] (bounded deepening)
        //   sky hue shift                 : 0.04 deg    -> ceiling 2.0
        //   foliage chroma shift          : +3.78 codes -> floor 2.0, ceiling 12
        //   foliage luma shift            : +2.42 codes -> floor 1.0, ceiling 12
        //   foliage hue shift             : 0.17 deg    -> ceiling 2.0
        //   ground/skin sky|foliage mask  : <= 0.036    -> ceiling 0.1 (non-interference)
        //   ground/skin on-vs-off maxDiff : <= 2 codes  -> ceiling 3
        //   colorchart sky|foliage mask   : 0.089|0.096 -> ceiling 0.15 (position/gate keep it low)
        //   every grayscale scene mask    : 0.0000      -> ceiling 0.15 (exact 0)
        const val MAX_SKY_STD_RATIO = 0.92
        const val MIN_CB_SHIFT = 2.0
        const val MAX_CB_SHIFT = 12.0
        const val MIN_FOLIAGE_CHROMA_SHIFT = 2.0
        const val MIN_FOLIAGE_LUMA_SHIFT = 1.0
        const val MAX_REGION_SHIFT = 12.0
        const val MAX_HUE_SHIFT_DEG = 2.0
        const val MAX_OFF_REGION_MASK = 0.1
        const val MAX_OFF_REGION_DIFF = 3
        const val MAX_SCENE_SKY_MASK = 0.15
        const val MAX_SCENE_FOLIAGE_MASK = 0.15
    }
}
