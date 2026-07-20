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

        // Measured 2026-07-20 (see class KDoc), each floor = measured value * 0.98
        // (PSNR/SSIM) or measured value * 1.02 (MAE ceiling).
        // Reference actuals at capture time:
        //   edges        psnr 36.66  ssim 0.9921  mae 2.534
        //   texture      psnr 32.98  ssim 0.9951  mae 3.099
        //   gradients    psnr 36.78  ssim 0.9395  mae 2.813
        //   lowlight     psnr 37.60  ssim 0.9476  mae 2.601
        //   highcontrast psnr 34.95  ssim 0.8763  mae 3.529
        val FLOORS = listOf(
            Floor("edges", minPsnr = 35.9, minSsim = 0.972, maxMae = 2.59),
            Floor("texture", minPsnr = 32.3, minSsim = 0.975, maxMae = 3.17),
            Floor("gradients", minPsnr = 36.0, minSsim = 0.920, maxMae = 2.87),
            Floor("lowlight", minPsnr = 36.8, minSsim = 0.928, maxMae = 2.66),
            Floor("highcontrast", minPsnr = 34.2, minSsim = 0.858, maxMae = 3.60),
        )
    }
}
