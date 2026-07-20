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
        // Full-frame actuals: psnr 20.815  ssim 0.6692  mae 18.333.
        // Floors = actual * 0.98 (PSNR/SSIM); ceiling = actual * 1.02 (MAE).
        // Dynamic-range actuals over the tone-mapped truth's deciles:
        //   shadow:    fused 15.045  vs best single 20.032  (fusion wins by 4.99)
        //   highlight: fused 4.691   vs best single 5.486   (fusion wins by 0.80)
        const val MIN_PSNR = 20.39
        const val MIN_SSIM = 0.655
        const val MAX_MAE = 18.70
    }
}
