package com.poc.camera.pipeline.quality

import com.poc.camera.pipeline.BurstMergePipeline
import com.poc.camera.pipeline.FinishingPipeline

/**
 * Runs the full still pipeline against synthetic ground truth and reports image
 * quality metrics per scene.
 *
 * For each scene it merges a noisy burst with [BurstMergePipeline], finishes the
 * result with [FinishingPipeline] DEFAULT params, and scores the finished frame
 * against the scene's clean ground truth with [Psnr], [Ssim] and [Mae]. Fully
 * deterministic for a fixed seed, so the numbers form a stable regression
 * baseline. Pure Kotlin, no Android dependencies.
 */
object PipelineQualityReport {

    /** Number of noisy frames merged per scene, mirroring a real burst capture. */
    const val DEFAULT_BURST_SIZE = 8

    /** Quality metrics for one scene's finished pipeline output vs. clean truth. */
    data class SceneMetrics(
        val scene: String,
        val psnr: Double,
        val ssim: Double,
        val mae: Double,
    )

    /** Measures the pipeline on a single scene. */
    fun measure(
        name: String,
        seed: Long,
        burstSize: Int = DEFAULT_BURST_SIZE,
    ): SceneMetrics {
        val clean = SyntheticScenes.clean(name)
        val burst = SyntheticScenes.burst(name, seed, burstSize)
        val merged = BurstMergePipeline.merge(burst).merged
        val finished = FinishingPipeline.apply(merged)
        return SceneMetrics(
            scene = name,
            psnr = Psnr.between(finished, clean),
            ssim = Ssim.between(finished, clean),
            mae = Mae.between(finished, clean),
        )
    }

    /**
     * Measures every scene in [SyntheticScenes.names] order. Each scene draws its
     * own noise from a distinct seed derived from [seed] so results stay both
     * varied across scenes and reproducible across runs.
     */
    fun measureAll(
        seed: Long,
        burstSize: Int = DEFAULT_BURST_SIZE,
    ): List<SceneMetrics> =
        SyntheticScenes.names.mapIndexed { index, name ->
            measure(name, seed + index * SCENE_SEED_STRIDE, burstSize)
        }

    private const val SCENE_SEED_STRIDE = 0x1000193L
}
