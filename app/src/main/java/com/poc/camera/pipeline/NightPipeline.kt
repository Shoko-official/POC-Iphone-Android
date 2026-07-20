package com.poc.camera.pipeline

/**
 * The high-gain low-light capture path: a longer burst merged with [NightMergeParams]
 * and finished with [FinishingParams.NIGHT].
 *
 * It is the standard still pipeline ([BurstMergePipeline] + [FinishingPipeline]) run
 * with the night tuning, not a separate implementation: the merge widens ghost
 * rejection, brightness-matches the frames and applies motion-adaptive weighting for
 * strong noise, and the finish leans harder on chroma denoise and shadow lift while
 * easing off sharpening. Capturing more frames ([NIGHT_BURST_FRAME_COUNT]) gives the
 * merge more signal to average away the heavier noise.
 *
 * Deterministic and free of Android dependencies.
 */
object NightPipeline {

    /**
     * Frames captured for a night burst, regardless of the user's normal burst-size
     * preference: low light needs the extra frames to beat the noise down.
     */
    const val NIGHT_BURST_FRAME_COUNT = 12

    /** The merge profile the night path uses. */
    val MERGE_PARAMS: NightMergeParams = NightMergeParams.NIGHT

    /** The finishing profile the night path uses. */
    val FINISHING_PARAMS: FinishingParams = FinishingParams.NIGHT

    /** Merges [frames] with the night merge profile. */
    fun merge(frames: List<Frame>): BurstMergePipeline.MergeResult =
        BurstMergePipeline.merge(frames, MERGE_PARAMS)

    /** Merges [frames] and applies the night finishing profile, returning the final frame. */
    fun process(frames: List<Frame>): Frame =
        FinishingPipeline.apply(merge(frames).merged, FINISHING_PARAMS)
}
