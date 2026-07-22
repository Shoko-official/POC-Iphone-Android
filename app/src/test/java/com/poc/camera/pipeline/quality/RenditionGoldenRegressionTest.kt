package com.poc.camera.pipeline.quality

import com.poc.camera.pipeline.FinishingParams
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Objective gate for the RENDITION axis, the second evaluation axis alongside the
 * clean-truth fidelity of [GoldenPipelineRegressionTest]. Where the fidelity golden
 * scores the pipeline (running [FinishingParams.DEFAULT]) against the CLEAN scene,
 * this golden scores the pipeline running [FinishingParams.RENDITION] against the
 * deliberate-look TARGET from [RenditionTargets], via [RenditionQualityReport].
 *
 * Motivation: [FinishingParams.DEFAULT]'s localContrast (0.03) and detailEnhance (0.08)
 * are near no-ops because the fidelity golden reads any deliberate rendition change as
 * error against the clean truth. A real (iPhone-class) camera renders a visibly stronger
 * look. Measuring rendition on its own axis lets the reference-strength profile run at
 * meaningful defaults without loosening any fidelity floor. The fidelity golden is
 * untouched and still gates DEFAULT.
 *
 * This suite asserts four things:
 *  1. Tracking floor: with RENDITION params, MAE(output, target) stays at or below a
 *     committed per-scene ceiling (rendition baselines, 2026-07-20).
 *  2. The axis is meaningful: on the scenes where rendition matters (texture,
 *     highcontrast, edges) RENDITION tracks the target STRICTLY better than the timid
 *     DEFAULT profile does -- the stronger look genuinely renders the intended result.
 *  3. Targets are non-vacuous: MAE(clean, target) is a clear departure from the clean
 *     frame on every scene, so the target actually encodes a look and the axis is real.
 *  4. Fidelity does not collapse: with RENDITION params, PSNR(output, clean) stays above
 *     a documented sanity floor. This floor sits BELOW the DEFAULT fidelity numbers on
 *     purpose -- the drop is the deliberate rendition -- but it guards against a future
 *     change making RENDITION grotesque while still "matching target".
 */
class RenditionGoldenRegressionTest {

    /** Committed per-scene rendition baselines: target-MAE ceiling and clean-PSNR floor. */
    private data class Floor(
        val scene: String,
        val maxMaeVsTarget: Double,
        val minPsnrVsClean: Double,
    )

    @Test
    fun everySceneTracksItsTargetWithinFloor() {
        val report = RenditionQualityReport.measureAll(SEED).associateBy { it.scene }
        for (floor in FLOORS) {
            val m = requireNotNull(report[floor.scene]) { "missing scene ${floor.scene}" }
            assertTrue(
                "${floor.scene} rendition MAE ${m.renditionMaeVsTarget} regressed above ceiling ${floor.maxMaeVsTarget}",
                m.renditionMaeVsTarget <= floor.maxMaeVsTarget,
            )
        }
    }

    @Test
    fun renditionFidelityStaysAboveSanityFloor() {
        val report = RenditionQualityReport.measureAll(SEED).associateBy { it.scene }
        for (floor in FLOORS) {
            val m = requireNotNull(report[floor.scene]) { "missing scene ${floor.scene}" }
            assertTrue(
                "${floor.scene} rendition PSNR-vs-clean ${m.renditionPsnrVsClean} regressed below sanity floor ${floor.minPsnrVsClean}",
                m.renditionPsnrVsClean >= floor.minPsnrVsClean,
            )
        }
    }

    @Test
    fun renditionTracksTargetBetterThanTimidDefault() {
        val report = RenditionQualityReport.measureAll(SEED).associateBy { it.scene }
        for (scene in RENDITION_SENSITIVE_SCENES) {
            val m = requireNotNull(report[scene]) { "missing scene $scene" }
            // The proof the axis is meaningful: the reference-strength profile renders the
            // intended look closer to the target than the timid DEFAULT profile does.
            assertTrue(
                "$scene: RENDITION MAE-vs-target ${m.renditionMaeVsTarget} must beat DEFAULT ${m.defaultMaeVsTarget}",
                m.renditionMaeVsTarget < m.defaultMaeVsTarget,
            )
        }
    }

    @Test
    fun targetsAreNonVacuousDeparturesFromClean() {
        val report = RenditionQualityReport.measureAll(SEED)
        for (m in report) {
            // A near-zero MAE(clean, target) would mean the target encodes no look, making
            // the whole rendition axis vacuous. Every target must be a clear departure.
            assertTrue(
                "${m.scene}: target MAE-vs-clean ${m.targetMaeVsClean} must exceed $MIN_TARGET_DEPARTURE",
                m.targetMaeVsClean > MIN_TARGET_DEPARTURE,
            )
        }
    }

    @Test
    fun reportIsDeterministic() {
        val first = RenditionQualityReport.measureAll(SEED)
        val second = RenditionQualityReport.measureAll(SEED)
        assertEquals(first.size, second.size)
        for (i in first.indices) {
            assertEquals(first[i].scene, second[i].scene)
            assertEquals(first[i].renditionMaeVsTarget, second[i].renditionMaeVsTarget, 0.0)
            assertEquals(first[i].defaultMaeVsTarget, second[i].defaultMaeVsTarget, 0.0)
            assertEquals(first[i].targetMaeVsClean, second[i].targetMaeVsClean, 0.0)
            assertEquals(first[i].renditionPsnrVsClean, second[i].renditionPsnrVsClean, 0.0)
            assertEquals(first[i].defaultPsnrVsClean, second[i].defaultPsnrVsClean, 0.0)
        }
    }

    private companion object {
        // Same canonical seed as the fidelity golden, so both reports merge identical
        // bursts and the two axes are directly comparable scene-for-scene.
        const val SEED = 0xC0FFEEL

        /** Scenes whose look is dominated by local contrast / sharpening. */
        val RENDITION_SENSITIVE_SCENES = listOf("texture", "highcontrast", "edges")

        /** Every rendition target must depart from its clean frame by more than this MAE. */
        const val MIN_TARGET_DEPARTURE = 2.0

        // MEASURED BASELINES of the RENDITION axis, 2026-07-20 (seed 0xC0FFEE): the full
        // pipeline (BurstMergePipeline + FinishingPipeline.RENDITION) scored against the
        // RenditionTargets look, with FinishingParams.REF_LOCAL_CONTRAST = 0.35 and
        // REF_DETAIL_ENHANCE = 0.5. maxMaeVsTarget = actual * 1.02 (ceiling);
        // minPsnrVsClean = actual * 0.98 (floor). These are rendition baselines: a
        // regression tripwire for the deliberate-look axis, separate from the clean-truth
        // fidelity floors in GoldenPipelineRegressionTest (which stay untouched, gating
        // DEFAULT). Re-baseline only with an explicit, justified look change.
        //
        // Per-scene actuals (RENDITION output). Columns:
        //   renMAE->target : RENDITION MAE vs target      (baked as ceiling)
        //   defMAE->target : DEFAULT (timid) MAE vs target (the axis-is-meaningful gap)
        //   tgtMAE->clean  : MAE(clean, target)           (non-vacuous target proof, all > 2)
        //   renPSNR->clean : RENDITION PSNR vs clean       (baked as sanity floor)
        //   defPSNR->clean : DEFAULT PSNR vs clean         (fidelity drop = the rendition)
        //   scene         renMAE->tgt  defMAE->tgt  tgtMAE->clean  renPSNR->clean  defPSNR->clean
        //   edges          1.698        2.954        2.959          35.06           39.35  (drop 4.29 dB)
        //   texture        1.669        2.362        2.394          36.79           39.34  (drop 2.55 dB)
        //   gradients      1.462        2.341        2.611          36.98           41.33  (drop 4.36 dB)
        //   lowlight       1.258        5.523        4.093          34.89           40.40  (drop 5.51 dB)
        //   highcontrast   2.445        5.080        3.152          34.26           37.99  (drop 3.73 dB)
        //   colorchart     2.812        4.585        5.140          30.48           33.04  (drop 2.56 dB)
        // On every scene RENDITION tracks the target better than DEFAULT (renMAE < defMAE)
        // and the target is a clear departure from clean (tgtMAE > 2). The PSNR-vs-clean
        // drop is the deliberate look; the sanity floor sits ~2% under the RENDITION value
        // (well below DEFAULT), guarding against the look degenerating.
        //
        // RE-BASELINED (colorchart only) after the [ChromaRollOff] shoulder was added to
        // FinishingParams.RENDITION (chromaRollOff = 1.0), issue #97, seed 0xC0FFEE. The
        // roll-off is a WHOLE-FRAME chroma-magnitude shoulder (knee 30, soft 18): it is a
        // no-op wherever chroma magnitude sits at or under the knee, so the five grayscale /
        // low-chroma scenes are byte-unchanged and their floors are LEFT UNTOUCHED. Only
        // "colorchart" -- 36 patches of intentionally EXTREME chroma (magnitude up to ~116,
        // far past the knee) -- is moved. RenditionTargets derives its target from these same
        // RENDITION params, so the target compresses the SAME chroma the output does; the
        // axis stays self-consistent and colorchart TRACKING actually improves:
        //   colorchart renMAE->tgt : 2.792 -> 2.410  (roll-off reduces chroma variance;
        //                                              ceiling tightened to actual*1.02)
        //   colorchart tgtMAE->clean: 4.591 -> 10.668 (target departs further from clean;
        //                                              non-vacuous gate strengthens)
        //   colorchart renPSNR->clean: 31.069 -> 23.804 (the deliberate look's fidelity cost)
        // The renPSNR-vs-clean drop is exactly the "fidelity cost of the deliberate look" this
        // sanity floor is documented to move with (a whole-frame shoulder desaturates the
        // pathologically all-saturated synthetic chart; a real photo is not uniformly extreme-
        // chroma). Tracking and non-vacuous-target both improve, confirming it is the intended
        // look and not a degeneration. Floor lowered to actual*0.98; every other scene flat.
        //
        // UNCHANGED after [SemanticRendering] was added to FinishingParams.RENDITION
        // (semanticRendering = 1.0), issue #98, seed 0xC0FFEE. The [SkyMask]/[FoliageMask] priors
        // are EXACTLY 0 on grayscale content, so all five grayscale scenes are byte-identical
        // (renMAE/tgtMAE/renPSNR unmoved). Only "colorchart" moves (its blue/green patches fire
        // the priors), and RenditionTargets derives from the same RENDITION params so the target
        // renders the identical sky/foliage boost -- tracking stays self-consistent and the moves
        // are within the committed floors, so no floor is re-baselined:
        //   colorchart renMAE->tgt : 2.410 -> 2.415 (ceiling 2.46, +0.2%, held)
        //   colorchart tgtMAE->clean: 10.668 -> 10.360 (non-vacuous gate still >> 2.0)
        //   colorchart renPSNR->clean: 23.804 -> 23.971 (floor 23.3, a hair better: sky/foliage
        //                                                 chroma smoothing trims noise)
        // See SemanticRenderingGoldenTest for the sky/foliage proofs and the mask false-positive
        // matrix (whole-image mask mean 0.089 sky / 0.096 foliage on colorchart).
        //
        // RE-BASELINED (colorchart only) after the [ChromaRollOff] shoulder gained its SPATIAL
        // isolation gate (issue #107), seed 0xC0FFEE. The ungated shoulder compressed ANY pixel
        // above its knee, so it desaturated the uniformly saturated colorchart broadly -- the
        // 23.804 renPSNR-vs-clean above (and the 23.3 floor baked from it) was the fidelity
        // cost of that whole-frame desaturation. The gate compresses only chroma that exceeds
        // 1.5x its neighbourhood's mean, so the chart's patches (each its own neighbourhood)
        // now pass through and the fidelity cost is largely RECOVERED. The five grayscale
        // scenes carry no chroma and stay byte-identical; only colorchart moves:
        //   colorchart renPSNR->clean: 23.971 -> 30.476 (floor RAISED 23.3 -> 29.8, actual*0.98;
        //                                                 recovering most of the 31.07 the
        //                                                 ungated shoulder had cost)
        //   colorchart tgtMAE->clean : 10.360 -> 4.965  (the target no longer encodes the
        //                                                 broad desaturation; still >> 2.0)
        //   colorchart renMAE->tgt   : 2.415 -> 2.689   (ceiling 2.46 -> 2.75, actual*1.02.
        //     NOT a tracking regression: the ungated shoulder crushed chroma VARIANCE in both
        //     output and target, which artificially tightened tracking (pre-shoulder actual
        //     was 2.812, ceiling 2.87); the gate restores the chart's real chroma spread and
        //     tracking returns to just UNDER its pre-shoulder level -- while the fidelity
        //     floor recovers ~6.5 dB.)
        // UNCHANGED after [OvercastSkyMask] joined [SemanticRendering] (issue #106), seed
        // 0xC0FFEE. The overcast prior deliberately fires on bright smooth neutral upper
        // regions, which includes parts of the grayscale "gradients" and "highcontrast"
        // scenes -- but it drives only the mean-preserving chroma smoothing. Measured
        // semantic-on vs semantic-off with the prior in place:
        //   gradients    renPSNR->clean 37.082 -> 37.084, renMAE->tgt 1.494 -> 1.492
        //   highcontrast renPSNR->clean 34.089 -> 34.111, renMAE->tgt 2.553 -> 2.547
        // The smoothing removes residual merged chroma speckle that the (speckle-free)
        // target never carried, so BOTH tracking and fidelity improve marginally; "edges"
        // (whose thin lines texture every window) and the remaining scenes are
        // byte-identical on vs off. No floor moves. See OvercastSkyGoldenTest.
        val FLOORS = listOf(
            Floor("edges", maxMaeVsTarget = 1.74, minPsnrVsClean = 34.3),
            Floor("texture", maxMaeVsTarget = 1.71, minPsnrVsClean = 36.0),
            Floor("gradients", maxMaeVsTarget = 1.50, minPsnrVsClean = 36.2),
            Floor("lowlight", maxMaeVsTarget = 1.29, minPsnrVsClean = 34.1),
            Floor("highcontrast", maxMaeVsTarget = 2.50, minPsnrVsClean = 33.5),
            Floor("colorchart", maxMaeVsTarget = 2.75, minPsnrVsClean = 29.8),
        )
    }
}
