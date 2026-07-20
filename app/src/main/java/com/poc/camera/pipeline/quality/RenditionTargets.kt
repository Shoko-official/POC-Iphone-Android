package com.poc.camera.pipeline.quality

import com.poc.camera.pipeline.FinishingParams
import com.poc.camera.pipeline.FinishingPipeline
import com.poc.camera.pipeline.Frame

/**
 * The rendition evaluation axis's ground truth. Where [PipelineQualityReport] scores
 * the pipeline against the CLEAN, noise-free scene (fidelity: "did we reproduce the
 * scene?"), a rendition target encodes what the pipeline SHOULD render including the
 * deliberate look (rendition: "did we apply the intended look?").
 *
 * For each named scene the target is the output of a REFERENCE operator chain applied
 * to the CLEAN frame (no noise, no colour cast):
 *
 *   target(clean) = LocalToneMapper(REF_LOCAL_CONTRAST)
 *                 -> DetailEnhancer(REF_DETAIL_ENHANCE)
 *                 -> ToneCurve / Saturation / Contrast at DEFAULT global params
 *
 * This is exactly [FinishingPipeline.apply] with the rendition strengths but white
 * balance and chroma denoise switched off: on a clean, cast-free frame those two
 * stages have nothing to do (no cast to neutralise, no noise to clean), so the target
 * is the pure deliberate LOOK -- an achievable ideal the full noisy pipeline is then
 * scored against. Reusing [FinishingPipeline.apply] keeps the exact production
 * strength->params mapping as the single source of truth rather than re-deriving it.
 *
 * The strengths ([FinishingParams.REF_LOCAL_CONTRAST], [FinishingParams.REF_DETAIL_ENHANCE])
 * are set high enough that the target is a MEASURABLE departure from the clean frame --
 * see [RenditionQualityReport]'s per-scene MAE(clean, target), which is clearly nonzero
 * on every scene; a vacuous (near-zero) target would make the whole axis meaningless.
 *
 * Deterministic and free of Android dependencies.
 */
object RenditionTargets {

    /**
     * The reference finishing params: [FinishingParams.RENDITION]'s deliberate look with
     * white balance and chroma denoise disabled, so the target is the pure look applied
     * to the clean frame (those two stages are no-ops on a clean, cast-free input).
     */
    private val REFERENCE_PARAMS: FinishingParams = FinishingParams.RENDITION.copy(
        whiteBalance = 0.0,
        chromaDenoise = 0.0,
    )

    /** The rendition target for a named [SyntheticScenes] scene. */
    fun target(name: String): Frame = render(SyntheticScenes.clean(name))

    /** Applies the reference rendition chain to an arbitrary [clean] frame. */
    fun render(clean: Frame): Frame = FinishingPipeline.apply(clean, REFERENCE_PARAMS)
}
