package com.poc.camera.pipeline

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Exposure-bracketed HDR merge: denoises each exposure with the existing burst
 * pipeline, aligns the per-exposure results to a common reference, then fuses them
 * for real dynamic-range gain.
 *
 * Pipeline:
 *  1. Group the input frames by their tagged relative EV.
 *  2. Merge each group with [BurstMergePipeline] (global + tile alignment, ghost-aware
 *     robust merge) so every exposure is noise-reduced before fusion.
 *  3. Pick the EV closest to 0 as the global reference and align every other merged
 *     exposure to it with the same [FrameAligner] + [TileAligner] stack, warping it
 *     into the reference's coordinates with [SubPixelSampler]. Cross-exposure MAD
 *     alignment still locks onto structure because a brightness offset shifts the
 *     matching cost uniformly, so the minimum stays at the true translation; for a
 *     static scene this resolves to identity.
 *  4. Fuse the aligned per-exposure frames with [ExposureFusion].
 *
 * Deterministic and free of Android dependencies. A single-EV input degenerates to
 * the (aligned) single merged exposure.
 */
object HdrMergePipeline {

    /** One exposure's merged, reference-aligned result. */
    data class PerEvMerge(
        val ev: Double,
        val merged: Frame,
    )

    /**
     * @property fused the exposure-fused output frame.
     * @property perEvMerged each exposure's merged frame after alignment to the
     *   reference, in ascending EV order; exposed so callers (e.g. the quality
     *   harness) can compare fusion against the best single exposure.
     * @property usedFrameCount total frames that survived global outlier rejection
     *   across all exposure groups.
     */
    data class HdrMergeResult(
        val fused: Frame,
        val perEvMerged: List<PerEvMerge>,
        val usedFrameCount: Int,
    )

    private val aligner = FrameAligner()
    private val tileAligner = TileAligner()

    /**
     * Merges the bracketed burst [frames], each tagged with its relative EV in the
     * parallel list [evs], into an exposure-fused frame.
     */
    fun merge(frames: List<Frame>, evs: List<Double>): HdrMergeResult {
        require(frames.isNotEmpty()) { "frames must not be empty" }
        require(frames.size == evs.size) { "frames and evs must have equal size" }
        val width = frames.first().width
        val height = frames.first().height
        require(frames.all { it.width == width && it.height == height }) {
            "all frames must share dimensions"
        }

        // Group by EV, preserving a deterministic ascending-EV order.
        val evOrder = evs.distinct().sorted()
        val perEvRaw = evOrder.map { ev ->
            val group = frames.filterIndexed { index, _ -> evs[index] == ev }
            val result = BurstMergePipeline.merge(group)
            ev to result
        }
        val usedFrameCount = perEvRaw.sumOf { it.second.usedFrameCount }

        // Reference exposure: the one nearest EV 0.
        val referenceEv = evOrder.minByOrNull { abs(it) } ?: evOrder.first()
        val referenceFrame = perEvRaw.first { it.first == referenceEv }.second.merged
        val referenceLuma = Luminance.extract(referenceFrame)

        val aligned = perEvRaw.map { (ev, result) ->
            val merged = result.merged
            val alignedFrame = if (ev == referenceEv) {
                merged
            } else {
                alignToReference(referenceFrame, referenceLuma, merged)
            }
            PerEvMerge(ev, alignedFrame)
        }

        val fused = ExposureFusion.fuse(
            frames = aligned.map { it.merged },
            evs = aligned.map { it.ev },
        )

        return HdrMergeResult(
            fused = fused,
            perEvMerged = aligned,
            usedFrameCount = usedFrameCount,
        )
    }

    /**
     * Warps [frame] into [reference]'s coordinate system: recovers a global offset
     * with [FrameAligner], refines it into a per-tile fractional field with
     * [TileAligner], then resamples with [SubPixelSampler]. Returns a new frame with
     * the reference's dimensions and the source frame's timestamp.
     */
    private fun alignToReference(
        reference: Frame,
        referenceLuma: LumaPlane,
        frame: Frame,
    ): Frame {
        val alignment = aligner.align(listOf(reference, frame))[1]
        val frameLuma = Luminance.extract(frame)
        val offsets = tileAligner.refine(referenceLuma, frameLuma, alignment.dx, alignment.dy)

        val width = reference.width
        val height = reference.height
        val out = IntArray(width * height)
        val scratch = DoubleArray(3)
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                val sx = x + offsets.offsetXAt(x, y)
                val sy = y + offsets.offsetYAt(x, y)
                SubPixelSampler.sampleRgb(frame, sx, sy, scratch)
                val r = scratch[0].roundToInt().coerceIn(0, 255)
                val g = scratch[1].roundToInt().coerceIn(0, 255)
                val b = scratch[2].roundToInt().coerceIn(0, 255)
                out[row + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        return Frame(width, height, out, frame.timestampMillis)
    }
}
