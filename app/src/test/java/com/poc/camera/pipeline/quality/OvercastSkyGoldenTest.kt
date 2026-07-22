package com.poc.camera.pipeline.quality

import com.poc.camera.pipeline.BurstMergePipeline
import com.poc.camera.pipeline.FinishingParams
import com.poc.camera.pipeline.FinishingPipeline
import com.poc.camera.pipeline.Frame
import com.poc.camera.pipeline.OvercastSkyMask
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Objective golden gate for overcast-sky detection (issue #106). Every claim is measured on
 * the synthetic "overcastscene" (a neutral gray gradient sky band over an equally bright but
 * TEXTURED gray wall band over neutral mid-gray ground -- see
 * [SyntheticScenes.overcastClean]) and on the existing named scenes, so the numbers are
 * deterministic and reproducible across machines.
 *
 * The suite proves:
 *  (a) DETECTION: the [OvercastSkyMask] membership is high (> [MIN_SKY_MASK] mean) on the
 *      overcast sky band -- a gray sky now earns the sky smoothing it used to be denied.
 *  (b) TEXTURE FALSE-POSITIVE GATE: the equally bright, equally neutral wall band stays low
 *      (< [MAX_WALL_MASK]) -- only the texture prior separates it -- and the mildly textured
 *      backlitportrait background stays under its own measured ceiling
 *      ([MAX_BACKLIT_BG_MASK]); the ground band stays quiet.
 *  (c) GRAY STAYS GRAY: with RENDITION, the overcast band's chroma noise drops (std ratio
 *      under [MAX_SKY_STD_RATIO]) while its MEAN chroma moves by less than
 *      [MAX_MEAN_CHROMA_SHIFT] code and its mean luma by less than [MAX_MEAN_LUMA_SHIFT] --
 *      the overcast path smooths but never tints or deepens.
 *  (d) BLUE PATH UNTOUCHED: the overcast mask reads ~0 on the landscape scene (blue sky is
 *      [com.poc.camera.pipeline.SkyMask]'s territory, rejected here by the neutrality
 *      prior), so `max(blue, overcast)` degenerates to the blue mask and every existing
 *      landscape number holds (gated by SemanticRenderingGoldenTest in this same suite).
 *  (e) FALSE-POSITIVE MATRIX: the overcast mask stays under a baked per-scene ceiling on
 *      every existing named scene AND on the skin chart. The honest part: "gradients" and
 *      "highcontrast" fire PARTIALLY -- their bright smooth neutral upper areas are
 *      indistinguishable from an overcast sky by design -- which is benign because the only
 *      consequence is the mean-preserving chroma smoothing, proved bounded by (f).
 *  (f) BENIGN GRAYSCALE EFFECT: on the merged "gradients" scene the RENDITION finish with
 *      semantic rendering on vs off differs by at most [MAX_GRADIENTS_DIFF] codes per
 *      channel with mean chroma/luma shifts under 1 code -- residual speckle smoothing, not
 *      a look change (the rendition floors in RenditionGoldenRegressionTest stay green).
 *  (g) DETERMINISM.
 *
 * Baselines were measured 2026-07-22 and are documented in the companion; bounds carry
 * margin so the test is a regression gate, not a brittle snapshot.
 */
class OvercastSkyGoldenTest {

    private val clean = SyntheticScenes.overcastClean()
    private val merged = BurstMergePipeline.merge(
        SyntheticScenes.overcastBurst(SEED, BURST),
    ).merged
    private val on = FinishingPipeline.apply(merged, FinishingParams.RENDITION)
    private val off = FinishingPipeline.apply(merged, FinishingParams.RENDITION.copy(semanticRendering = 0.0))

    private val sky = inset(SyntheticScenes.overcastSkyBounds(), 6)
    private val wall = inset(SyntheticScenes.overcastWallBounds(), 6)
    private val ground = inset(SyntheticScenes.overcastGroundBounds(), 4)

    // --- (a) overcast sky band fires -----------------------------------------------

    @Test
    fun overcastSkyBandFires() {
        val mask = OvercastSkyMask.compute(clean)
        val skyMean = regionMean(mask, sky)
        println("[overcast] sky band mask mean=${"%.4f".format(skyMean)}")
        assertTrue("overcast sky band mask mean $skyMean must exceed $MIN_SKY_MASK", skyMean > MIN_SKY_MASK)
    }

    // --- (b) texture false-positive gate -------------------------------------------

    @Test
    fun brightTexturedWallAndBacklitBackgroundStayLow() {
        val mask = OvercastSkyMask.compute(clean)
        val wallMean = regionMean(mask, wall)
        val groundMean = regionMean(mask, ground)
        println("[overcast] wall band mask mean=${"%.4f".format(wallMean)} ground=${"%.4f".format(groundMean)}")
        assertTrue("wall band mask mean $wallMean must stay under $MAX_WALL_MASK", wallMean < MAX_WALL_MASK)
        assertTrue("ground band mask mean $groundMean must stay under $MAX_GROUND_MASK", groundMean < MAX_GROUND_MASK)

        // The backlitportrait background: bright, neutral, in the upper image and only MILDLY
        // textured -- the hardest real false-positive fixture. Measured on the band above the
        // subject (rows 0..20), where the position prior is strongest.
        val backlit = OvercastSkyMask.compute(SyntheticScenes.backlitPortraitClean())
        val bgMean = regionMean(backlit, intArrayOf(4, 0, SyntheticScenes.SIZE - 4, 20))
        println("[overcast] backlit background band mask mean=${"%.4f".format(bgMean)}")
        assertTrue(
            "backlit background mask mean $bgMean must stay under $MAX_BACKLIT_BG_MASK",
            bgMean < MAX_BACKLIT_BG_MASK,
        )
    }

    // --- (c) chroma noise reduced, gray stays gray ----------------------------------

    @Test
    fun overcastSkyChromaNoiseIsReducedWithoutColourShift() {
        val stdBefore = chromaStd(off, sky)
        val stdAfter = chromaStd(on, sky)
        val ratio = stdAfter / stdBefore
        val (crOff, cbOff) = meanChromaVec(off, sky)
        val (crOn, cbOn) = meanChromaVec(on, sky)
        val crShift = abs(crOn - crOff)
        val cbShift = abs(cbOn - cbOff)
        val lumaShift = abs(meanLuma(on, sky) - meanLuma(off, sky))
        println(
            "[overcast] sky chroma std off=${"%.2f".format(stdBefore)} on=${"%.2f".format(stdAfter)} " +
                "(ratio ${"%.2f".format(ratio)}) meanShift Cr=${"%.3f".format(crShift)} Cb=${"%.3f".format(cbShift)} " +
                "luma=${"%.3f".format(lumaShift)}",
        )
        assertTrue("overcast sky chroma std must drop (ratio $ratio)", ratio < MAX_SKY_STD_RATIO)
        assertTrue("overcast sky mean Cr shift $crShift must stay under $MAX_MEAN_CHROMA_SHIFT (gray stays gray)", crShift < MAX_MEAN_CHROMA_SHIFT)
        assertTrue("overcast sky mean Cb shift $cbShift must stay under $MAX_MEAN_CHROMA_SHIFT (gray stays gray)", cbShift < MAX_MEAN_CHROMA_SHIFT)
        assertTrue("overcast sky mean luma shift $lumaShift must stay under $MAX_MEAN_LUMA_SHIFT", lumaShift < MAX_MEAN_LUMA_SHIFT)
    }

    // --- (d) blue-sky path untouched ------------------------------------------------

    @Test
    fun blueSkyLandscapeReadsZeroOvercast() {
        val cleanMean = OvercastSkyMask.compute(SyntheticScenes.landscapeClean()).average()
        val landscapeMerged = BurstMergePipeline.merge(
            SyntheticScenes.landscapeBurst(LANDSCAPE_SEED, BURST),
        ).merged
        val mergedMean = OvercastSkyMask.compute(landscapeMerged).average()
        println("[overcast] landscape overcast mask mean clean=${"%.5f".format(cleanMean)} merged=${"%.5f".format(mergedMean)}")
        assertTrue("landscape (clean) overcast mask $cleanMean must stay under $MAX_LANDSCAPE_MASK", cleanMean < MAX_LANDSCAPE_MASK)
        assertTrue("landscape (merged) overcast mask $mergedMean must stay under $MAX_LANDSCAPE_MASK", mergedMean < MAX_LANDSCAPE_MASK)
    }

    // --- (e) false-positive matrix --------------------------------------------------

    @Test
    fun overcastMaskStaysWithinItsCeilingOnEveryExistingScene() {
        for (name in SyntheticScenes.names) {
            val scene = SyntheticScenes.clean(name)
            val mean = OvercastSkyMask.compute(scene).average()
            val ceiling = requireNotNull(SCENE_CEILINGS[name]) { "missing ceiling for $name" }
            println("[overcast] false-positive $name mask mean=${"%.4f".format(mean)} (ceiling $ceiling)")
            assertTrue("$name whole-image overcast mask mean $mean must stay under $ceiling", mean < ceiling)
        }
    }

    @Test
    fun skinChartStaysQuiet() {
        val chart = SyntheticScenes.skinChartClean()
        val mask = OvercastSkyMask.compute(chart)
        val whole = mask.average()
        println("[overcast] skin chart whole-image mask mean=${"%.5f".format(whole)}")
        assertTrue("skin chart whole-image overcast mask $whole must stay under $MAX_SKINCHART_MASK", whole < MAX_SKINCHART_MASK)
        for (region in SyntheticScenes.skinChartRegions.filter { it.skin }) {
            val bounds = intArrayOf(region.x0 + 4, region.y0 + 4, region.x1 - 4, region.y1 - 4)
            val mean = regionMean(mask, bounds)
            println("[overcast] skin patch ${region.name} mask mean=${"%.5f".format(mean)}")
            assertTrue(
                "skin patch ${region.name} overcast mask $mean must stay under $MAX_SKINCHART_MASK",
                mean < MAX_SKINCHART_MASK,
            )
        }
    }

    // --- (f) benign grayscale effect ------------------------------------------------

    @Test
    fun gradientsRenditionEffectIsBoundedAndChromaOnly() {
        val gradMerged = BurstMergePipeline.merge(
            SyntheticScenes.burst("gradients", GRADIENTS_SEED, BURST),
        ).merged
        val withSemantic = FinishingPipeline.apply(gradMerged, FinishingParams.RENDITION)
        val withoutSemantic = FinishingPipeline.apply(gradMerged, FinishingParams.RENDITION.copy(semanticRendering = 0.0))
        val whole = intArrayOf(0, 0, SyntheticScenes.SIZE, SyntheticScenes.SIZE)
        val maxDiff = maxChannelDiff(withSemantic, withoutSemantic, whole)
        val (crA, cbA) = meanChromaVec(withSemantic, whole)
        val (crB, cbB) = meanChromaVec(withoutSemantic, whole)
        val lumaShift = abs(meanLuma(withSemantic, whole) - meanLuma(withoutSemantic, whole))
        println(
            "[overcast] gradients on-vs-off maxChannelDiff=$maxDiff meanShift " +
                "Cr=${"%.3f".format(abs(crA - crB))} Cb=${"%.3f".format(abs(cbA - cbB))} luma=${"%.3f".format(lumaShift)}",
        )
        assertTrue("gradients on-vs-off max channel diff $maxDiff must stay within $MAX_GRADIENTS_DIFF", maxDiff <= MAX_GRADIENTS_DIFF)
        assertTrue("gradients mean chroma shift must stay under 1 code", abs(crA - crB) < 1.0 && abs(cbA - cbB) < 1.0)
        assertTrue("gradients mean luma shift $lumaShift must stay under 1 code", lumaShift < 1.0)
    }

    // --- (g) determinism ------------------------------------------------------------

    @Test
    fun maskAndFinishAreDeterministic() {
        val maskA = OvercastSkyMask.compute(merged)
        val maskB = OvercastSkyMask.compute(merged)
        for (i in maskA.indices) {
            if (maskA[i] != maskB[i]) throw AssertionError("overcast mask must be deterministic at $i")
        }
        val again = FinishingPipeline.apply(merged, FinishingParams.RENDITION)
        assertTrue("overcast finish must be deterministic", again.argb.contentEquals(on.argb))
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

    private companion object {
        const val SEED = 0x0CA5CAFEL
        const val LANDSCAPE_SEED = 0x1A9D5CAFEL
        const val GRADIENTS_SEED = 0x1A9D5CAFEL
        const val BURST = 8

        // MEASURED 2026-07-22 (seed 0x0CA5CAFE, 8-frame merge). Actuals -> baked bound (margin):
        //   overcast sky band mask mean       : 0.784        -> floor 0.6 (detection)
        //   wall band mask mean               : 0.051        -> ceiling 0.15 (the band is
        //     equally bright and equally neutral, so the texture prior ALONE separates it)
        //   ground band mask mean             : 0.000        -> ceiling 0.02
        //   backlit background band mask mean : 0.195        -> ceiling 0.25 (honest: the
        //     cell-10 +/-15 lattice is only MILDLY textured, with locally near-planar patches
        //     keeping partial membership; the consequence is the bounded mean-preserving
        //     chroma smoothing only, never a colour or luma change)
        //   sky chroma std ratio (on/off)     : 0.50         -> ceiling 0.75 (noise reduced)
        //   sky mean Cr / Cb / luma shift     : 0.005 / 0.044 / 0.005 -> ceilings 1.0
        //     (the gray-stays-gray gate: the overcast path smooths, never tints)
        //   landscape overcast mask clean/mrg : 0.000 / 0.000 -> ceiling 0.005 (the blue sky
        //     fails the neutrality prior, the green/dark rest fails bright -- so
        //     max(blue, overcast) degenerates to the blue mask and that path is untouched)
        //   skin chart whole + per-patch mask : 0.000 (all)  -> ceiling 0.01 (skin chroma
        //     magnitude 32..67 >> the 12-code neutrality ceiling; see OvercastSkyMaskTest)
        //   gradients on-vs-off maxChannelDiff: 1            -> ceiling 2 (benign smoothing)
        //   false-positive matrix             : edges/texture/lowlight/colorchart 0.000
        //     -> 0.02; gradients 0.089 -> 0.15, highcontrast 0.136 -> 0.20. The last two
        //     fire PARTIALLY by design: their bright smooth neutral upper areas are
        //     overcast-sky-shaped, and the effect is benign per (f).
        const val MIN_SKY_MASK = 0.6
        const val MAX_WALL_MASK = 0.15
        const val MAX_GROUND_MASK = 0.02
        const val MAX_BACKLIT_BG_MASK = 0.25
        const val MAX_SKY_STD_RATIO = 0.75
        const val MAX_MEAN_CHROMA_SHIFT = 1.0
        const val MAX_MEAN_LUMA_SHIFT = 1.0
        const val MAX_LANDSCAPE_MASK = 0.005
        const val MAX_SKINCHART_MASK = 0.01
        const val MAX_GRADIENTS_DIFF = 2

        val SCENE_CEILINGS = mapOf(
            "edges" to 0.02,
            "texture" to 0.02,
            "gradients" to 0.15,
            "lowlight" to 0.02,
            "highcontrast" to 0.20,
            "colorchart" to 0.02,
        )
    }
}
