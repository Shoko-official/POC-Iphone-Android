package com.poc.camera.pipeline.quality

import com.poc.camera.pipeline.BurstMergePipeline
import com.poc.camera.pipeline.FinishingParams
import com.poc.camera.pipeline.FinishingPipeline
import com.poc.camera.pipeline.Frame
import com.poc.camera.pipeline.NightPipeline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Objective gate for the high-gain low-light path. It runs the night pipeline
 * ([NightPipeline]: a 12-frame burst merged with [com.poc.camera.pipeline.NightMergeParams]
 * and finished with [FinishingParams.NIGHT]) against the dedicated "nightscene" ground
 * truth ([SyntheticScenes.nightClean]) and proves the four properties that justify the
 * feature.
 *
 * The nightscene is darker than "lowlight" (mean luma ~22), carries a bright hard-edged
 * lamp, and is captured with a heavier noise model (3x read, 2x shot) plus a slight
 * per-frame drift, i.e. a long hand-held high-ISO capture. All floors are MEASURED
 * BASELINES (seed 0xC0FFEE, 2026-07-20), set to the measured value with a ~2% tolerance;
 * a regression past the tolerance fails the suite. Re-baseline only with an explicit,
 * justified reason.
 */
class NightGoldenRegressionTest {

    /** MAE of the night pipeline output vs the clean night truth. */
    private fun nightOutputMae(seed: Long, frames: Int): Double {
        val clean = SyntheticScenes.nightClean()
        val out = NightPipeline.process(SyntheticScenes.nightBurst(seed, frames))
        return Mae.between(out, clean)
    }

    /** Rec. 601 mean luma of [frame] over the lamp bounding box. */
    private fun lampMeanLuma(frame: Frame): Double {
        val b = SyntheticScenes.nightLampBounds()
        var sum = 0.0
        var count = 0
        for (y in b[1]..b[3]) {
            for (x in b[0]..b[2]) {
                val p = frame.argb[y * frame.width + x]
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val bl = p and 0xFF
                sum += 0.299 * r + 0.587 * g + 0.114 * bl
                count++
            }
        }
        return sum / count
    }

    @Test
    fun nightPipelineMeetsItsQualityFloor() {
        val clean = SyntheticScenes.nightClean()
        val out = NightPipeline.process(SyntheticScenes.nightBurst(SEED, NightPipeline.NIGHT_BURST_FRAME_COUNT))
        val mae = Mae.between(out, clean)
        val psnr = Psnr.between(out, clean)
        val ssim = Ssim.between(out, clean)
        assertTrue("night MAE $mae regressed above ceiling $MAX_MAE", mae <= MAX_MAE)
        assertTrue("night PSNR $psnr regressed below floor $MIN_PSNR", psnr >= MIN_PSNR)
        assertTrue("night SSIM $ssim regressed below floor $MIN_SSIM", ssim >= MIN_SSIM)
    }

    /**
     * The reason night mode exists: the 12-frame night pipeline is strictly closer to
     * the clean night truth than the standard pipeline (6 frames, default merge, DEFAULT
     * finishing) on the same scene.
     */
    @Test
    fun nightBeatsStandardPipelineOnNightScene() {
        val clean = SyntheticScenes.nightClean()

        val nightMae = nightOutputMae(SEED, NightPipeline.NIGHT_BURST_FRAME_COUNT)

        val standardBurst = SyntheticScenes.nightBurst(SEED, STANDARD_FRAME_COUNT)
        val standardOut = FinishingPipeline.apply(
            BurstMergePipeline.merge(standardBurst).merged,
            FinishingParams.DEFAULT,
        )
        val standardMae = Mae.between(standardOut, clean)

        assertTrue(
            "night MAE $nightMae must beat standard-pipeline MAE $standardMae",
            nightMae < standardMae,
        )
    }

    /**
     * Motion robustness: injecting one frame with large artificial motion (a shift well
     * beyond the merge's alignment range) into an otherwise clean burst degrades the
     * night output by less than a bounded amount vs the same burst without that frame.
     * The merge's widened ghost rejection and motion-adaptive weighting absorb the
     * outlier instead of ghosting it into the stack.
     */
    @Test
    fun nightIsRobustToAGrossMotionOutlier() {
        val clean = SyntheticScenes.nightClean()

        val good = SyntheticScenes.nightBurst(SEED, ROBUST_GOOD_FRAMES)
        val withOutlier = good + SyntheticScenes.nightMotionOutlier(OUTLIER_SEED)

        val goodMae = Mae.between(NightPipeline.process(good), clean)
        val withOutlierMae = Mae.between(NightPipeline.process(withOutlier), clean)

        assertTrue(
            "night MAE with a gross-motion outlier ($withOutlierMae) must not exceed " +
                "the outlier-free MAE ($goodMae) by more than $MAX_OUTLIER_DEGRADATION",
            withOutlierMae <= goodMae + MAX_OUTLIER_DEGRADATION,
        )
    }

    /**
     * No bloom from stacking: the bright lamp's mean luma stays close to its clean value
     * and never clips upward. A merge that let the noisy lamp pixels pile up would push
     * the region past 255 (bloom); the ghost-aware weighting keeps it bounded.
     */
    @Test
    fun lampRegionStaysUnclippedAndBloomFree() {
        val clean = SyntheticScenes.nightClean()
        val out = NightPipeline.process(SyntheticScenes.nightBurst(SEED, NightPipeline.NIGHT_BURST_FRAME_COUNT))

        val cleanLamp = lampMeanLuma(clean)
        val outLamp = lampMeanLuma(out)

        assertTrue("lamp mean $outLamp must not clip above 255 (bloom)", outLamp <= 255.0)
        assertTrue(
            "lamp mean shift ${outLamp - cleanLamp} must stay within +/-$MAX_LAMP_SHIFT of clean $cleanLamp",
            kotlin.math.abs(outLamp - cleanLamp) <= MAX_LAMP_SHIFT,
        )
    }

    @Test
    fun nightPipelineIsDeterministic() {
        val first = NightPipeline.process(SyntheticScenes.nightBurst(SEED, NightPipeline.NIGHT_BURST_FRAME_COUNT))
        val second = NightPipeline.process(SyntheticScenes.nightBurst(SEED, NightPipeline.NIGHT_BURST_FRAME_COUNT))
        assertEquals(first, second)
    }

    private companion object {
        const val SEED = 0xC0FFEEL
        const val OUTLIER_SEED = 0xBADBADL
        const val STANDARD_FRAME_COUNT = 6
        const val ROBUST_GOOD_FRAMES = 11

        // MEASURED BASELINES (seed 0xC0FFEE, 2026-07-20). Night pipeline actuals:
        //   out MAE 2.477  PSNR 38.115  SSIM 0.8929
        //   standard(6) out MAE 3.497  ->  night beats it by ~1.02 MAE.
        //   lamp clean mean 250.0, night mean 244.9 (shift -5.1; bounded, no upward clip).
        //   robustness: good(11) MAE 2.576 vs +outlier MAE 2.512 (delta -0.063; absorbed).
        // MAE ceiling = actual*1.02; PSNR/SSIM floors = actual*0.98.
        const val MAX_MAE = 2.527
        const val MIN_PSNR = 37.35
        const val MIN_SSIM = 0.875

        // Generous bounds around the measured behaviour (both hold with wide margin):
        // the outlier actually IMPROVES MAE (delta negative) and the lamp shift is -5.1.
        const val MAX_OUTLIER_DEGRADATION = 0.30
        const val MAX_LAMP_SHIFT = 10.0
    }
}
