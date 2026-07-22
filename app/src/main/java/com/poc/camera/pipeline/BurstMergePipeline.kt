package com.poc.camera.pipeline

/**
 * Orchestrates the multi-frame merge for noise reduction: luma extraction, global
 * alignment with outlier rejection, per-tile sub-pixel refinement and ghost-aware
 * weighted merging.
 *
 * The stages are: [FrameAligner] recovers a global integer offset per frame and
 * rejects gross outliers; [TileAligner] refines each surviving frame's offset into
 * a smooth sub-pixel field per tile; [RobustFrameMerger] merges with bilinear
 * fetches and [GhostRejector] weights so local motion is dropped instead of
 * ghosting.
 *
 * Deterministic and free of Android dependencies (timestamps come from the
 * reference frame, no randomness). A single-frame input is returned unchanged.
 */
object BurstMergePipeline {

    /**
     * @property merged the merged output frame.
     * @property usedFrameCount how many frames survived global outlier rejection.
     * @property offsets the global integer offsets (the sub-pixel refinement is
     *   applied internally and not surfaced here, so existing callers are stable).
     */
    data class MergeResult(
        val merged: Frame,
        val usedFrameCount: Int,
        val offsets: List<Pair<Int, Int>>,
    )

    private val aligner = FrameAligner()
    private val tileAligner = TileAligner()
    private val ghostRejector = GhostRejector()

    /**
     * Merges [frames] into a single noise-reduced frame.
     *
     * @param nightParams optional high-gain low-light profile. When supplied the merge
     *   brightness-matches each frame to the reference before aligning
     *   ([BrightnessNormalizer]), rejects ghosts with the profile's widened thresholds,
     *   and applies motion-adaptive per-frame global weighting in the accumulation. When
     *   null the standard 3-8 sigma merge runs unchanged.
     */
    fun merge(frames: List<Frame>, nightParams: NightMergeParams? = null): MergeResult {
        require(frames.isNotEmpty()) { "frames must not be empty" }
        val width = frames.first().width
        val height = frames.first().height
        require(frames.all { it.width == width && it.height == height }) {
            "all frames must share dimensions"
        }
        if (frames.size == 1) {
            return MergeResult(
                merged = frames.first(),
                usedFrameCount = 1,
                offsets = listOf(0 to 0),
            )
        }

        // Night: normalise exposure to the reference before anything reads luma, so both
        // alignment and merge see a brightness-consistent burst. The reference (frame 0)
        // is the match target and is left untouched.
        val working = if (nightParams != null && nightParams.gainMatchEnabled) {
            val reference = frames.first()
            frames.mapIndexed { index, frame ->
                if (index == 0) frame else BrightnessNormalizer.normalize(frame, reference, nightParams)
            }
        } else {
            frames
        }

        val rejector = if (nightParams != null) nightParams.ghostRejector() else ghostRejector

        val alignments = aligner.align(working)
        val offsets = alignments.map { it.dx to it.dy }
        val accepted = alignments.map { it.accepted }

        val referenceLuma = Luminance.extract(working.first())
        val tileOffsets = working.mapIndexed { index, frame ->
            if (index == 0 || !accepted[index]) {
                null
            } else {
                val frameLuma = Luminance.extract(frame)
                tileAligner.refine(referenceLuma, frameLuma, alignments[index].dx, alignments[index].dy)
            }
        }

        val merged = RobustFrameMerger.merge(working, accepted, tileOffsets, rejector, nightParams)

        return MergeResult(
            merged = merged,
            usedFrameCount = accepted.count { it },
            offsets = offsets,
        )
    }
}
