package com.poc.camera.pipeline.quality

import com.poc.camera.pipeline.BurstMergePipeline
import com.poc.camera.pipeline.FinishingParams
import com.poc.camera.pipeline.FinishingPipeline

/**
 * Scores the still pipeline on the RENDITION axis: how well the finished output tracks
 * the deliberate look encoded by [RenditionTargets], as opposed to the clean-truth
 * fidelity measured by [PipelineQualityReport].
 *
 * For each scene it merges a noisy burst with [BurstMergePipeline] (identical merge to
 * the fidelity report), then finishes that same merged frame TWICE:
 *  - with [FinishingParams.RENDITION] (the reference-strength look), and
 *  - with [FinishingParams.DEFAULT] (the timid look the fidelity floors pin).
 * Both finished frames are scored (MAE) against the scene's rendition target. The
 * RENDITION run is expected to track the target markedly better than the DEFAULT run --
 * that gap is the whole point of the axis: the stronger profile genuinely renders the
 * intended look, which the clean-truth floors would otherwise read as error.
 *
 * It also reports, per scene:
 *  - MAE(clean, target): proof the target is a non-vacuous departure from the clean
 *    frame (a near-zero value would make the target -- and the axis -- meaningless).
 *  - PSNR(RENDITION output, clean) and PSNR(DEFAULT output, clean): the fidelity cost of
 *    the deliberate look. The RENDITION run is expected to score a bit LOWER than DEFAULT
 *    against the clean truth; that drop IS the rendition, and the rendition golden bakes
 *    a sanity floor under it so a future change cannot make RENDITION grotesque while
 *    still "matching target".
 *
 * Fully deterministic for a fixed seed. Pure Kotlin, no Android dependencies.
 */
object RenditionQualityReport {

    /** Rendition metrics for one scene. */
    data class SceneRendition(
        val scene: String,
        /** MAE of the RENDITION-params output vs. the rendition target (lower = better tracking). */
        val renditionMaeVsTarget: Double,
        /** MAE of the DEFAULT-params (timid) output vs. the same target. */
        val defaultMaeVsTarget: Double,
        /** MAE(clean, target): the deliberate-look departure from the clean frame. */
        val targetMaeVsClean: Double,
        /** PSNR of the RENDITION-params output vs. the clean truth (fidelity under the look). */
        val renditionPsnrVsClean: Double,
        /** PSNR of the DEFAULT-params output vs. the clean truth (for the fidelity-drop delta). */
        val defaultPsnrVsClean: Double,
    )

    /** Measures the rendition axis on a single scene. */
    fun measure(
        name: String,
        seed: Long,
        burstSize: Int = PipelineQualityReport.DEFAULT_BURST_SIZE,
    ): SceneRendition {
        val clean = SyntheticScenes.clean(name)
        val target = RenditionTargets.target(name)
        val burst = SyntheticScenes.burst(name, seed, burstSize)
        val merged = BurstMergePipeline.merge(burst).merged

        val renditionOut = FinishingPipeline.apply(merged, FinishingParams.RENDITION)
        val defaultOut = FinishingPipeline.apply(merged, FinishingParams.DEFAULT)

        return SceneRendition(
            scene = name,
            renditionMaeVsTarget = Mae.between(renditionOut, target),
            defaultMaeVsTarget = Mae.between(defaultOut, target),
            targetMaeVsClean = Mae.between(target, clean),
            renditionPsnrVsClean = Psnr.between(renditionOut, clean),
            defaultPsnrVsClean = Psnr.between(defaultOut, clean),
        )
    }

    /**
     * Measures every scene in [SyntheticScenes.names] order, using the same per-scene
     * seed derivation as [PipelineQualityReport.measureAll] so the merged frames match
     * the fidelity report exactly.
     */
    fun measureAll(
        seed: Long,
        burstSize: Int = PipelineQualityReport.DEFAULT_BURST_SIZE,
    ): List<SceneRendition> =
        SyntheticScenes.names.mapIndexed { index, name ->
            measure(name, seed + index * SCENE_SEED_STRIDE, burstSize)
        }

    // Matches PipelineQualityReport.SCENE_SEED_STRIDE so both reports merge identical bursts.
    private const val SCENE_SEED_STRIDE = 0x1000193L
}
