package com.poc.camera.pipeline

/**
 * Orchestrates the multi-frame merge for noise reduction: luma extraction,
 * alignment, outlier rejection and per-channel averaging.
 *
 * Deterministic and free of Android dependencies (timestamps come from the
 * reference frame, no randomness). A single-frame input is returned unchanged.
 */
object BurstMergePipeline {

    data class MergeResult(
        val merged: Frame,
        val usedFrameCount: Int,
        val offsets: List<Pair<Int, Int>>,
    )

    private val aligner = FrameAligner()

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
        val merged = FrameMerger.merge(frames, offsets, accepted)

        return MergeResult(
            merged = merged,
            usedFrameCount = accepted.count { it },
            offsets = offsets,
        )
    }
}
