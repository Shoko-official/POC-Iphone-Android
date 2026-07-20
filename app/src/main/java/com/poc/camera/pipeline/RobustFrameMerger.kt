package com.poc.camera.pipeline

import kotlin.math.roundToInt

/**
 * Merges aligned burst frames with sub-pixel fetches and per-pixel ghost rejection.
 *
 * Each accepted non-reference frame is sampled at its per-tile fractional offset
 * ([TileAligner.TileOffsets] via [SubPixelSampler]) and accumulated with a
 * [GhostRejector] weight against the reference luma, so pixels that disagree with
 * the reference (local motion) are down-weighted or dropped instead of ghosting.
 * The reference itself always contributes weight 1, so a pixel with no supporting
 * frame -- total weight at or below 1 + [WEIGHT_EPS] -- falls back to the exact
 * reference value. Output inherits the reference frame's dimensions and timestamp.
 *
 * Noise sigma is estimated once per frame from a strided sample of the aligned
 * luma difference (see [GhostRejector.estimateSigma]); the merge hot loop then does
 * a fixed number of bilinear fetches per pixel with no per-pixel allocation beyond
 * the shared output and scratch arrays.
 */
object RobustFrameMerger {

    private const val WEIGHT_EPS = 1e-3
    private const val SIGMA_SAMPLE_STRIDE = 4

    /**
     * @param frames burst frames; the first is the reference.
     * @param accepted per-frame acceptance from the global aligner.
     * @param tileOffsets per-frame refined offsets; null for the reference and any
     *   rejected frame (both are skipped as supporting frames).
     */
    fun merge(
        frames: List<Frame>,
        accepted: List<Boolean>,
        tileOffsets: List<TileAligner.TileOffsets?>,
        ghostRejector: GhostRejector = GhostRejector(),
    ): Frame {
        require(frames.isNotEmpty()) { "frames must not be empty" }
        require(frames.size == accepted.size && frames.size == tileOffsets.size) {
            "frames, accepted and tileOffsets must have equal size"
        }

        val reference = frames.first()
        val width = reference.width
        val height = reference.height
        val pixelCount = width * height
        val refArgb = reference.argb

        // Reference luma per pixel, matching Luminance.extract's rounded Rec. 601.
        // Element-wise seeding: each pixel is written once, so it satisfies the
        // [PipelineParallel] contract and is chunked by row.
        val refLuma = IntArray(pixelCount)
        val sumR = DoubleArray(pixelCount)
        val sumG = DoubleArray(pixelCount)
        val sumB = DoubleArray(pixelCount)
        val sumW = DoubleArray(pixelCount)
        PipelineParallel.parallelRows(pixelCount) { start, end ->
            for (i in start until end) {
                val pixel = refArgb[i]
                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr 8) and 0xFF
                val b = pixel and 0xFF
                refLuma[i] = (299 * r + 587 * g + 114 * b + 500) / 1000
                // Reference seeds every accumulator with weight 1.
                sumR[i] = r.toDouble()
                sumG[i] = g.toDouble()
                sumB[i] = b.toDouble()
                sumW[i] = 1.0
            }
        }

        for (index in frames.indices) {
            if (index == 0 || !accepted[index]) continue
            val offsets = tileOffsets[index] ?: continue
            val frame = frames[index]
            val sigma = estimateSigma(frame, offsets, refLuma, width, height, ghostRejector)

            // Row-parallel per frame: each output row reads arbitrary INPUT rows (via
            // the sub-pixel fetch) but writes only its own accumulators, so rows are
            // independent within a frame; frames are still folded in sequentially, so
            // the per-pixel accumulation order across frames is unchanged and the merge
            // is bit-identical to the serial path. Scratch is per-chunk (thread-local).
            PipelineParallel.parallelRows(height) { yStart, yEnd ->
                val scratch = DoubleArray(3)
                for (y in yStart until yEnd) {
                    val row = y * width
                    for (x in 0 until width) {
                        val offsetX = offsets.offsetXAt(x, y)
                        val offsetY = offsets.offsetYAt(x, y)
                        SubPixelSampler.sampleRgb(frame, x + offsetX, y + offsetY, scratch)
                        val luma = 0.299 * scratch[0] + 0.587 * scratch[1] + 0.114 * scratch[2]
                        val i = row + x
                        val weight = ghostRejector.weight(luma - refLuma[i], sigma)
                        if (weight > 0.0) {
                            sumR[i] += weight * scratch[0]
                            sumG[i] += weight * scratch[1]
                            sumB[i] += weight * scratch[2]
                            sumW[i] += weight
                        }
                    }
                }
            }
        }

        val merged = IntArray(pixelCount)
        PipelineParallel.parallelRows(pixelCount) { start, end ->
            for (i in start until end) {
                val totalWeight = sumW[i]
                if (totalWeight <= 1.0 + WEIGHT_EPS) {
                    // Only the reference supports this pixel: keep it exactly.
                    merged[i] = refArgb[i]
                    continue
                }
                val r = (sumR[i] / totalWeight).roundToInt().coerceIn(0, 255)
                val g = (sumG[i] / totalWeight).roundToInt().coerceIn(0, 255)
                val b = (sumB[i] / totalWeight).roundToInt().coerceIn(0, 255)
                merged[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }

        return Frame(width, height, merged, reference.timestampMillis)
    }

    /** Robust per-frame noise sigma from a strided sample of aligned luma differences. */
    private fun estimateSigma(
        frame: Frame,
        offsets: TileAligner.TileOffsets,
        refLuma: IntArray,
        width: Int,
        height: Int,
        ghostRejector: GhostRejector,
    ): Double {
        val cols = (width + SIGMA_SAMPLE_STRIDE - 1) / SIGMA_SAMPLE_STRIDE
        val rows = (height + SIGMA_SAMPLE_STRIDE - 1) / SIGMA_SAMPLE_STRIDE
        val diffs = DoubleArray(cols * rows)
        val scratch = DoubleArray(3)
        var k = 0
        var y = 0
        while (y < height) {
            val row = y * width
            var x = 0
            while (x < width) {
                SubPixelSampler.sampleRgb(frame, x + offsets.offsetXAt(x, y), y + offsets.offsetYAt(x, y), scratch)
                val luma = 0.299 * scratch[0] + 0.587 * scratch[1] + 0.114 * scratch[2]
                diffs[k++] = luma - refLuma[row + x]
                x += SIGMA_SAMPLE_STRIDE
            }
            y += SIGMA_SAMPLE_STRIDE
        }
        return ghostRejector.estimateSigma(diffs)
    }
}
