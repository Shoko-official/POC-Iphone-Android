package com.poc.camera.pipeline.quality

import com.poc.camera.pipeline.BurstMergePipeline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Objective quality gate for the still pipeline. For each synthetic scene it runs
 * [PipelineQualityReport] with fixed seeds and asserts the metrics stay at or above
 * a committed hard floor (or, for MAE, at or below a ceiling).
 *
 * The floors below are MEASURED BASELINES of the current pipeline
 * (BurstMergePipeline + FinishingPipeline DEFAULT), captured 2026-07-20, set to the
 * measured value minus a ~2% tolerance. They are a regression tripwire: any change
 * that degrades measured quality past the tolerance fails this suite. Do NOT lower
 * them casually -- a drop means the pipeline got worse. Re-baseline only with an
 * explicit, justified reason (e.g. a deliberate look change), and record why.
 */
class GoldenPipelineRegressionTest {

    /** Committed per-scene baselines: PSNR/SSIM floors (>=) and MAE ceiling (<=). */
    private data class Floor(
        val scene: String,
        val minPsnr: Double,
        val minSsim: Double,
        val maxMae: Double,
    )

    @Test
    fun everySceneMeetsItsQualityFloor() {
        val report = PipelineQualityReport.measureAll(SEED).associateBy { it.scene }
        for (floor in FLOORS) {
            val metrics = requireNotNull(report[floor.scene]) { "missing scene ${floor.scene}" }
            assertTrue(
                "${floor.scene} PSNR ${metrics.psnr} regressed below floor ${floor.minPsnr}",
                metrics.psnr >= floor.minPsnr,
            )
            assertTrue(
                "${floor.scene} SSIM ${metrics.ssim} regressed below floor ${floor.minSsim}",
                metrics.ssim >= floor.minSsim,
            )
            assertTrue(
                "${floor.scene} MAE ${metrics.mae} regressed above ceiling ${floor.maxMae}",
                metrics.mae <= floor.maxMae,
            )
        }
    }

    @Test
    fun mergeBeatsEveryNoisyInputOnMae() {
        for (name in SyntheticScenes.names) {
            val clean = SyntheticScenes.clean(name)
            val burst = SyntheticScenes.burst(name, MERGE_SEED, PipelineQualityReport.DEFAULT_BURST_SIZE)
            val merged = BurstMergePipeline.merge(burst).merged

            val mergedMae = Mae.between(merged, clean)
            val bestInputMae = burst.minOf { Mae.between(it, clean) }

            // The core noise-reduction property: the merged frame is strictly closer
            // to the clean ground truth than the best single noisy input.
            assertTrue(
                "$name merged MAE $mergedMae must beat the best single-frame MAE $bestInputMae",
                mergedMae < bestInputMae,
            )
        }
    }

    @Test
    fun reportIsDeterministic() {
        val first = PipelineQualityReport.measureAll(SEED)
        val second = PipelineQualityReport.measureAll(SEED)
        assertEquals(first.size, second.size)
        for (i in first.indices) {
            assertEquals(first[i].scene, second[i].scene)
            assertEquals(first[i].psnr, second[i].psnr, 0.0)
            assertEquals(first[i].ssim, second[i].ssim, 0.0)
            assertEquals(first[i].mae, second[i].mae, 0.0)
        }
    }

    private companion object {
        const val SEED = 0xC0FFEEL
        const val MERGE_SEED = 0xBEEFL

        // RAISED after tile/sub-pixel alignment + ghost rejection, 2026-07-20.
        // Each floor = measured value * 0.98 (PSNR/SSIM) or measured value * 1.02
        // (MAE ceiling). Floors were raised where the new pipeline improved beyond
        // the tolerance and left unchanged where it held flat; none were lowered.
        //
        // RE-BASELINED after local tone mapping (LocalToneMapper on at the
        // conservative FinishingParams.DEFAULT.localContrast), 2026-07-20. Local
        // contrast is a deliberate rendition change measured against the CLEAN truth,
        // so DEFAULT strength is gated low enough that every floor stays green:
        //   edges        36.657->36.628  ssim 0.99209  mae 2.535->2.554  (flat: kept)
        //   texture      33.030->36.520  ssim 0.9953->0.99789 mae 3.079->2.628 (raised*)
        //   gradients    39.068->39.066  ssim 0.96386  mae 2.211->2.211  (flat: kept)
        //   lowlight     39.046->39.007  ssim 0.96423  mae 2.246->2.251  (flat: kept)
        //   highcontrast 36.219->36.646  ssim 0.8910->0.90192 mae 3.123->2.950 (raised)
        // (*) texture also carries the SyntheticScenes grayscale-invariant fix: the
        // scene was formerly stored in the blue channel only, which both depressed its
        // baseline and made luma-based ops meaningless. edges is marginally softer
        // (thin-line detail gain) but holds; highcontrast improves (local shadow lift
        // and highlight taming). gradients/lowlight are unmoved at this strength.
        val FLOORS = listOf(
            Floor("edges", minPsnr = 35.9, minSsim = 0.972, maxMae = 2.59),
            Floor("texture", minPsnr = 35.7, minSsim = 0.977, maxMae = 2.68),
            Floor("gradients", minPsnr = 38.2, minSsim = 0.944, maxMae = 2.26),
            Floor("lowlight", minPsnr = 38.2, minSsim = 0.943, maxMae = 2.30),
            Floor("highcontrast", minPsnr = 35.9, minSsim = 0.883, maxMae = 3.01),
        )
    }
}
