package com.poc.camera.pipeline.quality

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Objective quality gate for the exposure-bracketed HDR pipeline. It runs
 * [HdrPipelineQualityReport] with a fixed seed against the tone-mapped HDR truth and
 * asserts two things:
 *
 *  1. Full-frame PSNR/SSIM stay at or above a committed floor and MAE at or below a
 *     ceiling. The floors are MEASURED BASELINES of the current pipeline
 *     (HdrMergePipeline + FinishingPipeline DEFAULT), captured 2026-07-20, set to the
 *     measured value minus a ~2% tolerance. They are a regression tripwire; do NOT
 *     lower them casually, and raise them if the pipeline improves past tolerance.
 *
 *  2. The genuine dynamic-range property: the fused result's MAE in BOTH the truth's
 *     darkest decile (shadow recovery) and its brightest decile (highlight recovery)
 *     strictly beats the best single exposure over that same region. This is what
 *     separates real HDR fusion from a single well-chosen exposure -- each single
 *     exposure clips or crushes one end of the range, while fusion recovers both.
 *
 * The single-EV golden path ([GoldenPipelineRegressionTest]) is untouched: the HDR
 * scene is deliberately kept out of [SyntheticScenes.names].
 */
class HdrGoldenPipelineRegressionTest {

    @Test
    fun fusedResultMeetsQualityFloor() {
        val m = HdrPipelineQualityReport.measure(SEED)
        assertTrue("HDR PSNR ${m.psnr} regressed below floor $MIN_PSNR", m.psnr >= MIN_PSNR)
        assertTrue("HDR SSIM ${m.ssim} regressed below floor $MIN_SSIM", m.ssim >= MIN_SSIM)
        assertTrue("HDR MAE ${m.mae} regressed above ceiling $MAX_MAE", m.mae <= MAX_MAE)
    }

    @Test
    fun fusionRecoversShadowsBeyondBestSingleExposure() {
        val m = HdrPipelineQualityReport.measure(SEED)
        assertTrue(
            "fused shadow MAE ${m.fusedShadowMae} must beat best single ${m.bestSingleShadowMae}",
            m.fusedShadowMae < m.bestSingleShadowMae,
        )
    }

    @Test
    fun fusionRecoversHighlightsBeyondBestSingleExposure() {
        val m = HdrPipelineQualityReport.measure(SEED)
        assertTrue(
            "fused highlight MAE ${m.fusedHighlightMae} must beat best single ${m.bestSingleHighlightMae}",
            m.fusedHighlightMae < m.bestSingleHighlightMae,
        )
    }

    @Test
    fun reportIsDeterministic() {
        val first = HdrPipelineQualityReport.measure(SEED)
        val second = HdrPipelineQualityReport.measure(SEED)
        assertEquals(first, second)
    }

    private companion object {
        const val SEED = 0xDEC1L

        // MEASURED BASELINES, 2026-07-20 (seed 0xDEC1, HdrMergePipeline + Finishing).
        // Floors = actual * 0.98 (PSNR/SSIM); ceiling = actual * 1.02 (MAE).
        //
        // RAISED after Laplacian fusion, 2026-07-20. HdrMergePipeline now fuses with
        // LaplacianExposureFusion (true multi-scale Laplacian-pyramid blending) instead
        // of the box-blurred weight ramp of ExposureFusion. On this smooth radiance
        // scene the pyramid blend improves every full-frame metric:
        //   psnr 20.930 -> 22.108   ssim 0.6662 -> 0.6923   mae 18.027 -> 15.499
        // Dynamic-range decile actuals over the tone-mapped truth (fusion still beats
        // the best single exposure at BOTH ends, the genuine HDR property):
        //   shadow:    fused 18.627 vs best single 19.687  (was box-blur fused 14.877)
        //   highlight: fused 3.953  vs best single 5.340   (was box-blur fused 4.064)
        // The shadow decile is looser than the box-blur baseline because full-depth
        // pyramid blending smooths the deep-shadow low frequencies; the mild
        // sub-unity LaplacianExposureFusion.DEFAULT_SELECTIVITY (0.7) keeps it below the
        // best single exposure while pulling the full-frame errors down. Floors raised
        // and the MAE ceiling tightened to the new actuals.
        const val MIN_PSNR = 21.66
        const val MIN_SSIM = 0.678
        const val MAX_MAE = 15.81
    }
}
