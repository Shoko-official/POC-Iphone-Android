package com.poc.camera.pipeline.quality

import com.poc.camera.pipeline.BurstMergePipeline
import com.poc.camera.pipeline.FinishingParams
import com.poc.camera.pipeline.FinishingPipeline
import com.poc.camera.pipeline.Frame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Objective quality gate for auto white balance ([com.poc.camera.pipeline.WhiteBalance]).
 *
 * A synthetic illuminant cast is applied to the CLEAN colorchart ground truth BEFORE the
 * sensor-noise model (so it behaves like a real illuminant, not post-capture tinting),
 * the cast burst is merged and finished with the full pipeline, and the finished frame is
 * scored against the UNCAST clean truth. The proof has two halves:
 *
 *  1. Cast correction. With AWB on (DEFAULT), the finished frame is substantially closer
 *     to the uncast truth than with `whiteBalance = 0` on the exact same cast input --
 *     both a large PSNR gain and a large MAE drop, for a warm and a cool cast. The
 *     absolute post-AWB numbers are also pinned to committed floors (raise if the
 *     estimator improves past tolerance, never lower).
 *
 *  2. Neutrality (no false correction). On the UNCAST colorchart, AWB is near-identity:
 *     its output barely differs from the AWB-off output and does not worsen the error
 *     against truth. This is what keeps AWB from desaturating an already-balanced but
 *     colourful scene -- and it is why the existing [GoldenPipelineRegressionTest]
 *     colorchart floor (which now runs with AWB on) stays green.
 *
 * Fully deterministic for the fixed seeds. Pure Kotlin, no Android dependencies.
 */
class AwbGoldenRegressionTest {

    private data class Metrics(val mae: Double, val psnr: Double)

    /** Merges a cast (or uncast) colorchart burst and finishes it with AWB on and off. */
    private fun runCast(rGain: Double, gGain: Double, bGain: Double, seed: Long): Pair<Metrics, Metrics> {
        val truth = SyntheticScenes.clean("colorchart")
        val castClean = SyntheticScenes.withColorCast(truth, rGain, gGain, bGain)
        val merged = mergedBurstOf(castClean, seed)
        val on = FinishingPipeline.apply(merged, FinishingParams.DEFAULT)
        val off = FinishingPipeline.apply(merged, FinishingParams.DEFAULT.copy(whiteBalance = 0.0))
        return Metrics(Mae.between(on, truth), Psnr.between(on, truth)) to
            Metrics(Mae.between(off, truth), Psnr.between(off, truth))
    }

    private fun mergedBurstOf(clean: Frame, seed: Long): Frame =
        BurstMergePipeline.merge(SyntheticScenes.burstOf(clean, seed, BURST_SIZE)).merged

    @Test
    fun awbCorrectsWarmCast() {
        val (on, off) = runCast(WARM_R, 1.0, WARM_B, CAST_SEED)
        val msg = "warm cast: AWB on MAE ${on.mae} PSNR ${on.psnr} vs off MAE ${off.mae} PSNR ${off.psnr}"

        // Substantially better than no correction on the same cast input.
        assertTrue("$msg -- on MAE must clearly beat off", on.mae <= off.mae * SUBSTANTIAL_MAE_RATIO)
        assertTrue("$msg -- on PSNR must clearly beat off", on.psnr - off.psnr >= SUBSTANTIAL_PSNR_GAIN)

        // Committed absolute floors on the corrected result.
        assertTrue("$msg -- MAE regressed above ceiling $WARM_MAX_MAE", on.mae <= WARM_MAX_MAE)
        assertTrue("$msg -- PSNR regressed below floor $WARM_MIN_PSNR", on.psnr >= WARM_MIN_PSNR)
    }

    @Test
    fun awbCorrectsCoolCast() {
        val (on, off) = runCast(COOL_R, 1.0, COOL_B, CAST_SEED)
        val msg = "cool cast: AWB on MAE ${on.mae} PSNR ${on.psnr} vs off MAE ${off.mae} PSNR ${off.psnr}"

        assertTrue("$msg -- on MAE must clearly beat off", on.mae <= off.mae * SUBSTANTIAL_MAE_RATIO)
        assertTrue("$msg -- on PSNR must clearly beat off", on.psnr - off.psnr >= SUBSTANTIAL_PSNR_GAIN)

        assertTrue("$msg -- MAE regressed above ceiling $COOL_MAX_MAE", on.mae <= COOL_MAX_MAE)
        assertTrue("$msg -- PSNR regressed below floor $COOL_MIN_PSNR", on.psnr >= COOL_MIN_PSNR)
    }

    @Test
    fun awbIsNearIdentityOnUncastColorchart() {
        val truth = SyntheticScenes.clean("colorchart")
        val merged = mergedBurstOf(truth, CAST_SEED)
        val on = FinishingPipeline.apply(merged, FinishingParams.DEFAULT)
        val off = FinishingPipeline.apply(merged, FinishingParams.DEFAULT.copy(whiteBalance = 0.0))

        val onOffDelta = Mae.between(on, off)
        val onVsTruth = Mae.between(on, truth)
        val offVsTruth = Mae.between(off, truth)
        val msg = "uncast colorchart: on-vs-off MAE $onOffDelta, on-vs-truth $onVsTruth, off-vs-truth $offVsTruth"

        // AWB must not move an already-neutral scene: its output stays essentially the
        // AWB-off output, and it does not worsen the error against the clean truth.
        assertTrue("$msg -- AWB falsely corrected a neutral scene", onOffDelta <= NEUTRALITY_MAX_DELTA)
        assertTrue("$msg -- AWB worsened a neutral scene vs truth", onVsTruth <= offVsTruth + NEUTRALITY_MAX_DELTA)
    }

    @Test
    fun castCorrectionIsDeterministic() {
        val first = runCast(WARM_R, 1.0, WARM_B, CAST_SEED)
        val second = runCast(WARM_R, 1.0, WARM_B, CAST_SEED)
        assertEquals(first.first.mae, second.first.mae, 0.0)
        assertEquals(first.first.psnr, second.first.psnr, 0.0)
    }

    private companion object {
        const val CAST_SEED = 0xA3B1L
        const val BURST_SIZE = PipelineQualityReport.DEFAULT_BURST_SIZE

        // Warm (tungsten-like) and cool (shade-like) illuminant casts.
        const val WARM_R = 1.25
        const val WARM_B = 0.8
        const val COOL_R = 0.85
        const val COOL_B = 1.2

        // "Substantially better" thresholds (both must hold): at least a 10% MAE drop and
        // a >= 1 dB PSNR gain vs. the uncorrected result on the same cast input.
        const val SUBSTANTIAL_MAE_RATIO = 0.90
        const val SUBSTANTIAL_PSNR_GAIN = 1.0

        // MEASURED BASELINES, 2026-07-20 (seed 0xA3B1, BurstMergePipeline + Finishing
        // DEFAULT with WhiteBalance on). Cast applied to the CLEAN colorchart before
        // noise; scored against the UNCAST truth. Actuals (AWB off -> on):
        //   warm: MAE 19.950 -> 11.252   PSNR 19.577 -> 23.068
        //   cool: MAE 17.235 -> 13.336   PSNR 20.988 -> 22.794
        // Floors = actual * 0.98 (PSNR) and ceilings = actual * 1.02 (MAE). The cool cast
        // is the harder case: the colorchart's own content is warm-biased, so a cool
        // cast's blue signal partly cancels that bias and AWB corrects it mainly via the
        // red channel (still a clear win). Raise these if the estimator improves past
        // tolerance; never lower them.
        const val WARM_MAX_MAE = 11.48
        const val WARM_MIN_PSNR = 22.60
        const val COOL_MAX_MAE = 13.60
        const val COOL_MIN_PSNR = 22.33

        // On the UNCAST colorchart the neutral-tolerance gate snaps the (weak, content
        // driven) estimate to identity, so AWB is a true no-op: measured on-vs-off delta
        // is 0.0. The bound is a tight tripwire against any future false correction.
        const val NEUTRALITY_MAX_DELTA = 0.05
    }
}
