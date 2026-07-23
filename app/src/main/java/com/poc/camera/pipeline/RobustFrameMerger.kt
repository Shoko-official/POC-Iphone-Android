package com.poc.camera.pipeline

import kotlin.math.floor
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
 * The ghost comparison is DUAL-BAND -- the final weight is the minimum of the same
 * [GhostRejector] band applied at two spatial scales, so either detector can veto a
 * sample:
 *  - the RAW per-pixel difference (the original band) catches pixel-scale
 *    outliers: clipped-noise tails, specular flicker, motion finer than the
 *    low-pass window;
 *  - a LOW-PASS difference ([GHOST_LOW_PASS_RADIUS] box blur of each luma plane)
 *    catches object-scale motion hiding BELOW the raw noise: under strong sensor
 *    noise a walking-subject-scale luma step can sit under 2x the raw
 *    frame-minus-reference sigma (the night-motion fixture), where no raw band can
 *    separate motion from noise without also rejecting the static signal the merge
 *    exists to average. The blur shrinks the noise sigma by the window size while
 *    object-scale motion keeps its full contrast, so the same sigma-relative band
 *    becomes a real discriminator there; [SuperResolution]'s splat gate uses the
 *    same construction.
 * Each band's noise sigma is estimated per frame from a strided sample of its own
 * difference (see [GhostRejector.estimateSigma]), keeping the band's sigma-multiple
 * semantics at both scales; on static content the low-pass difference is far inside
 * its acceptance knee, so the raw band's behaviour is preserved. The merge hot loop
 * adds one scalar bilinear fetch per pixel over the precomputed low-pass planes,
 * with no per-pixel allocation beyond the shared output and scratch arrays.
 */
object RobustFrameMerger {

    private const val WEIGHT_EPS = 1e-3
    private const val SIGMA_SAMPLE_STRIDE = 4

    // Box-blur radius of the low-pass luma planes the ghost gate's second band
    // compares on. A 7x7 window shrinks iid noise sigma ~7x, which on the night
    // profile's 4-12 sigma ramp pushes a walking-subject-scale step (~13 low-pass
    // sigmas on the night-motion fixture) past full rejection while low-passed static
    // noise stays inside the acceptance knee. The trade-off is spatial: motion smaller
    // than the window is attenuated toward acceptance in THIS band, so low-contrast
    // ghost rejection is only guaranteed at object scale (> ~2*radius px); finer
    // motion remains the raw band's job, exactly as before the second band existed.
    private const val GHOST_LOW_PASS_RADIUS = 3

    /**
     * @param frames burst frames; the first is the reference.
     * @param accepted per-frame acceptance from the global aligner.
     * @param tileOffsets per-frame refined offsets; null for the reference and any
     *   rejected frame (both are skipped as supporting frames).
     * @param nightParams when non-null, enables motion-adaptive per-frame global
     *   weighting (see [NightMergeParams.globalMotionWeight]): each supporting frame's
     *   contribution is scaled by a weight derived from how far its aligned residual
     *   sigma exceeds the burst's baseline noise. When null the merge is unchanged and
     *   every frame carries a global weight of exactly 1.0.
     */
    fun merge(
        frames: List<Frame>,
        accepted: List<Boolean>,
        tileOffsets: List<TileAligner.TileOffsets?>,
        ghostRejector: GhostRejector = GhostRejector(),
        nightParams: NightMergeParams? = null,
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

        // Low-pass planes for the ghost comparison (see [GHOST_LOW_PASS_RADIUS]). Each
        // frame's plane is blurred in its OWN coordinates and sampled bilinearly at the
        // aligned position -- blur and translation commute over the smooth per-tile
        // field, so this equals low-passing the warped frame without a second fetch pass.
        val refLumaLp = BoxBlur.blur(
            DoubleArray(pixelCount) { refLuma[it].toDouble() }, width, height, GHOST_LOW_PASS_RADIUS,
        )

        // Per-frame aligned residual sigmas (one per band), computed up front so the
        // motion-adaptive baseline (below) can see every supporting frame's sigma.
        // These are the SAME values the merge would compute inline, just hoisted, so
        // the non-night path stays bit-identical.
        val sigmasRaw = DoubleArray(frames.size)
        val sigmasLp = DoubleArray(frames.size)
        for (index in frames.indices) {
            if (index == 0 || !accepted[index]) continue
            val offsets = tileOffsets[index] ?: continue
            val frameLumaLp = lowPassLuma(frames[index])
            val (raw, lp) = estimateSigmas(
                frames[index], frameLumaLp, refLuma, refLumaLp, offsets, width, height, ghostRejector,
            )
            sigmasRaw[index] = raw
            sigmasLp[index] = lp
        }

        // Motion-adaptive per-frame global weights (night profile only). The baseline
        // noise is the median RAW residual sigma of the supporting frames (the raw
        // band is the whole-frame noise statistic this weighting has always keyed
        // on); a frame whose residual exceeds that baseline carries un-cancellable
        // motion and is down-weighted everywhere. Without a profile every weight is
        // exactly 1.0, so the accumulation is identical to the standard merge.
        val globalWeights = DoubleArray(frames.size) { 1.0 }
        if (nightParams != null) {
            val supporting = frames.indices.filter { it != 0 && accepted[it] && tileOffsets[it] != null }
            if (supporting.isNotEmpty()) {
                val referenceSigma = medianOf(DoubleArray(supporting.size) { sigmasRaw[supporting[it]] })
                for (index in supporting) {
                    val excess = (sigmasRaw[index] - referenceSigma).coerceAtLeast(0.0)
                    globalWeights[index] = nightParams.globalMotionWeight(excess, referenceSigma)
                }
            }
        }

        for (index in frames.indices) {
            if (index == 0 || !accepted[index]) continue
            val offsets = tileOffsets[index] ?: continue
            val frame = frames[index]
            val sigmaRaw = sigmasRaw[index]
            val sigmaLp = sigmasLp[index]
            val globalWeight = globalWeights[index]
            val frameLumaLp = lowPassLuma(frame)

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
                        val lumaLp = samplePlane(frameLumaLp, width, height, x + offsetX, y + offsetY)
                        val rawWeight = ghostRejector.weight(luma - refLuma[i], sigmaRaw)
                        val lpWeight = ghostRejector.weight(lumaLp - refLumaLp[i], sigmaLp)
                        val weight = globalWeight * if (rawWeight < lpWeight) rawWeight else lpWeight
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

    /** Box-blurred Rec. 601 luma plane of [frame] for the ghost comparison. */
    private fun lowPassLuma(frame: Frame): DoubleArray {
        val luma = Luminance.extract(frame)
        val src = DoubleArray(luma.values.size) { luma.values[it].toDouble() }
        return BoxBlur.blur(src, frame.width, frame.height, GHOST_LOW_PASS_RADIUS)
    }

    /** Bilinear sample of a scalar [plane] at fractional (x, y), edges clamped. */
    private fun samplePlane(plane: DoubleArray, width: Int, height: Int, x: Double, y: Double): Double {
        val maxX = width - 1
        val maxY = height - 1
        val cx = x.coerceIn(0.0, maxX.toDouble())
        val cy = y.coerceIn(0.0, maxY.toDouble())
        val x0 = floor(cx).toInt()
        val y0 = floor(cy).toInt()
        val x1 = if (x0 < maxX) x0 + 1 else x0
        val y1 = if (y0 < maxY) y0 + 1 else y0
        val fx = cx - x0
        val fy = cy - y0
        val v00 = plane[y0 * width + x0]
        val v10 = plane[y0 * width + x1]
        val v01 = plane[y1 * width + x0]
        val v11 = plane[y1 * width + x1]
        val top = v00 + (v10 - v00) * fx
        val bottom = v01 + (v11 - v01) * fx
        return top + (bottom - top) * fy
    }

    /**
     * Robust per-frame noise sigmas -- (raw, low-pass) -- from one strided sample of
     * the aligned luma differences at both scales. The raw sigma is computed exactly
     * as the single-band merge always did, so the raw band's behaviour is preserved.
     */
    @Suppress("LongParameterList")
    private fun estimateSigmas(
        frame: Frame,
        frameLumaLp: DoubleArray,
        refLuma: IntArray,
        refLumaLp: DoubleArray,
        offsets: TileAligner.TileOffsets,
        width: Int,
        height: Int,
        ghostRejector: GhostRejector,
    ): Pair<Double, Double> {
        val cols = (width + SIGMA_SAMPLE_STRIDE - 1) / SIGMA_SAMPLE_STRIDE
        val rows = (height + SIGMA_SAMPLE_STRIDE - 1) / SIGMA_SAMPLE_STRIDE
        val diffsRaw = DoubleArray(cols * rows)
        val diffsLp = DoubleArray(cols * rows)
        val scratch = DoubleArray(3)
        var k = 0
        var y = 0
        while (y < height) {
            val row = y * width
            var x = 0
            while (x < width) {
                val fx = x + offsets.offsetXAt(x, y)
                val fy = y + offsets.offsetYAt(x, y)
                SubPixelSampler.sampleRgb(frame, fx, fy, scratch)
                val luma = 0.299 * scratch[0] + 0.587 * scratch[1] + 0.114 * scratch[2]
                diffsRaw[k] = luma - refLuma[row + x]
                diffsLp[k] = samplePlane(frameLumaLp, width, height, fx, fy) - refLumaLp[row + x]
                k++
                x += SIGMA_SAMPLE_STRIDE
            }
            y += SIGMA_SAMPLE_STRIDE
        }
        return ghostRejector.estimateSigma(diffsRaw) to ghostRejector.estimateSigma(diffsLp)
    }

    /** Median of a copy of [values] (input left untouched); 0.0 for an empty array. */
    private fun medianOf(values: DoubleArray): Double {
        if (values.isEmpty()) return 0.0
        val sorted = values.copyOf()
        sorted.sort()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) {
            sorted[mid]
        } else {
            (sorted[mid - 1] + sorted[mid]) / 2.0
        }
    }
}
