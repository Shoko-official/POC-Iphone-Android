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

    fun merge(frames: List<Frame>): MergeResult {
        require(frames.isNotEmpty()) { "frames must not be empty" }
        if (frames.size == 1) {
            return MergeResult(
                merged = frames.first(),
                usedFrameCount = 1,
                offsets = listOf(0 to 0),
            )
        }

        val alignments = aligner.align(frames)
        val offsets = alignments.map { it.dx to it.dy }
        val accepted = alignments.map { it.accepted }

        val referenceLuma = Luminance.extract(frames.first())
        val tileOffsets = frames.mapIndexed { index, frame ->
            if (index == 0 || !accepted[index]) {
                null
            } else {
                val frameLuma = Luminance.extract(frame)
                tileAligner.refine(referenceLuma, frameLuma, alignments[index].dx, alignments[index].dy)
            }
        }

        val merged = RobustFrameMerger.merge(frames, accepted, tileOffsets, ghostRejector)

        return MergeResult(
            merged = merged,
            usedFrameCount = accepted.count { it },
            offsets = offsets,
        )
    }
}
