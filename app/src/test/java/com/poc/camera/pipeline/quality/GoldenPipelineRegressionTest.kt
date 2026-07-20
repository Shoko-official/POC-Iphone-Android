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
        //
        // RE-BASELINED after luma-guided chroma denoise (ChromaDenoiser on at
        // FinishingParams.DEFAULT.chromaDenoise = 0.6, radius 4), 2026-07-20, and the
        // new COLOUR "colorchart" scene added. The sensor model draws noise per
        // channel, so every merged burst carries residual chroma speckle that the
        // denoiser removes; PSNR/MAE improve on every scene (off -> on):
        //   edges        36.628->39.514  mae 2.554->1.918
        //   texture      36.520->39.370  mae 2.628->2.005
        //   gradients    39.066->41.333  mae 2.211->1.694
        //   lowlight     39.007->40.403  mae 2.251->1.918
        //   highcontrast 36.646->37.989  mae 2.950->2.564
        //   colorchart   32.483->33.032  mae 4.533->4.125  (NEW colour scene)
        // PSNR floors raised to actual*0.98 and MAE ceilings tightened to actual*1.02.
        // SSIM dips a hair on the gray scenes (chroma reconstruction rounds RGB, so
        // integer luma shifts by <=1 code value); it stays far above each committed
        // SSIM floor, and per the never-lower rule those SSIM floors are kept.
        //
        // UNCHANGED after detail enhancement (DetailEnhancer on at
        // FinishingParams.DEFAULT.detailEnhance = 0.08), 2026-07-20. Sharpening a merged
        // step that still carries residual noise deviates from the CLEAN truth, so the
        // DEFAULT strength is gated to keep every scene inside its committed tolerance
        // rather than re-baselining. Actuals (off -> on at 0.08):
        //   edges        39.514->39.346  mae 1.918->1.951  (sensitive: +1.7% MAE, within tol)
        //   texture      39.370->39.338  mae 2.005->2.012
        //   gradients    41.333->41.327  mae 1.694->1.695
        //   lowlight     40.403->40.397  mae 1.918->1.919
        //   highcontrast 37.989->37.989  mae 2.564->2.564  (flat)
        //   colorchart   33.032->33.036  mae 4.125->4.123  (a hair better: crisper luma edges)
        // No floor moved: the sensitive "edges" scene stays within its 2% ceiling
        // (1.951 < 1.96) and nothing improved beyond tolerance, so no floor is raised.
        val FLOORS = listOf(
            Floor("edges", minPsnr = 38.7, minSsim = 0.972, maxMae = 1.96),
            Floor("texture", minPsnr = 38.5, minSsim = 0.977, maxMae = 2.05),
            Floor("gradients", minPsnr = 40.5, minSsim = 0.944, maxMae = 1.73),
            Floor("lowlight", minPsnr = 39.5, minSsim = 0.943, maxMae = 1.96),
            Floor("highcontrast", minPsnr = 37.2, minSsim = 0.883, maxMae = 2.62),
            Floor("colorchart", minPsnr = 32.3, minSsim = 0.938, maxMae = 4.21),
        )
    }
}
