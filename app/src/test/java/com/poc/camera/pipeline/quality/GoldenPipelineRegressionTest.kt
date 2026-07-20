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
        // Old actuals (global integer merge)  ->  New actuals (tile/sub-pixel merge):
        //   edges        psnr 36.66->36.66  ssim 0.9921->0.9921  mae 2.534->2.535
        //   texture      psnr 32.98->33.03  ssim 0.9951->0.9953  mae 3.099->3.079
        //   gradients    psnr 36.78->39.07  ssim 0.9395->0.9638  mae 2.813->2.211
        //   lowlight     psnr 37.60->39.05  ssim 0.9476->0.9630  mae 2.601->2.246
        //   highcontrast psnr 34.95->36.22  ssim 0.8763->0.8910  mae 3.529->3.123
        val FLOORS = listOf(
            Floor("edges", minPsnr = 35.9, minSsim = 0.972, maxMae = 2.59),
            Floor("texture", minPsnr = 32.3, minSsim = 0.975, maxMae = 3.15),
            Floor("gradients", minPsnr = 38.2, minSsim = 0.944, maxMae = 2.26),
            Floor("lowlight", minPsnr = 38.2, minSsim = 0.943, maxMae = 2.30),
            Floor("highcontrast", minPsnr = 35.4, minSsim = 0.873, maxMae = 3.19),
        )
    }
}
