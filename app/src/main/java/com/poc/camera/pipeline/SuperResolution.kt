package com.poc.camera.pipeline

import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * Multi-frame super-resolution: accumulates sub-pixel-aligned burst frames onto a 2x
 * output grid by kernel-weighted splatting with coverage normalisation, ghost-gated.
 *
 * This is a simplified member of the Google handheld super-resolution family. The
 * reference frame (frame 0) defines the output grid at exactly [SCALE]x the input width
 * and height. Every accepted frame -- reference included -- is aligned to the reference
 * ([FrameAligner] for a global integer offset, [TileAligner] for a per-tile fractional
 * field, with its sub-pixel dead-zone DISABLED because genuine sub-pixel motion is the
 * super-resolution signal, not noise to suppress). Each input pixel is then scattered
 * onto the output grid at its continuous aligned position, [KernelSplat] distributing it
 * bilinearly into the four straddling output texels. Because the frames sample the scene
 * at DIFFERENT sub-pixel phases, they land between each other's samples and populate the
 * odd output texels that a single frame -- which lands only on even texels -- leaves
 * empty. That is where resolution beyond the input Nyquist comes from.
 *
 * ## Geometry
 *
 * [FrameAligner]/[TileAligner] use the convention: reference pixel (x, y) corresponds to
 * frame pixel (x + offX, y + offY). So an input pixel at frame coordinate (fx, fy) sits at
 * continuous REFERENCE coordinate (fx - offX, fy - offY), and thus at output coordinate
 * [SCALE] * (fx - offX, fy - offY). The offset field is a function of REFERENCE
 * coordinates, and the reference position a frame pixel lands on depends on the offset
 * itself -- the scatter direction inverts the gather the field was built for -- so the
 * splat resolves it with one fixed-point step (see [resolveReferencePosition]). The
 * reference itself has a zero offset field, so its pixels land exactly on the even output
 * texels. (The task states this as 2*(x + dxAt): our dxAt is -offX, the
 * frame-to-reference displacement.)
 *
 * ## Ghost gating
 *
 * A moved object would otherwise splat a second, displaced copy of itself into the grid.
 * Each non-reference sample is therefore weighted by a [GhostRejector] weight comparing it
 * to the reference at its landing position. The comparison is made on a LOW-PASS of luma
 * ([LOW_PASS_BLUR_RADIUS] box blur): genuine super-Nyquist detail -- which the reference
 * cannot represent, so it disagrees pixel-for-pixel and would be spuriously rejected --
 * blurs away and passes, while object-scale motion survives the blur and is rejected. The
 * reference always contributes weight 1.
 *
 * ## Coverage and fallback
 *
 * Output texels are normalised by their accumulated weight (coverage). A texel whose
 * coverage falls at or below [WEIGHT_EPS] -- borders, or grids with no sub-pixel diversity
 * -- falls back to a plain 2x bilinear upsample of the merged reference layer (the
 * standard [RobustFrameMerger] denoise, computed here from the SAME alignment so the burst
 * is aligned only once and the fallback is denoised rather than raw).
 *
 * ## No sharpening
 *
 * SR only recovers sampling; it deliberately does not sharpen. The finishing
 * [DetailEnhancer] downstream owns perceptual sharpening, and doubling it here would
 * double-count.
 *
 * ## Determinism, parallelism and memory
 *
 * Splatting is serial per frame and frames are folded in input order, so the float
 * accumulation is bit-identical run to run (a row-parallel scatter would race on the
 * shared texels a sample writes into). Only the coverage-normalisation / fallback pass is
 * row-parallel (each output texel is written once from read-only accumulators). The 2x
 * grid quadruples the pixel count; accumulators are [FloatArray] rather than double to
 * halve that footprint (a 3 MP input yields a 12 MP grid, ~48 MB per float accumulator,
 * four of them).
 *
 * Pure Kotlin, deterministic, no Android dependencies.
 */
object SuperResolution {

    /** Fixed output magnification per axis. Constant by design. */
    const val SCALE = 2

    /** Coverage at or below this is treated as "no sample here" and falls back. */
    private const val WEIGHT_EPS = 1e-3

    // Box-blur radius of the low-pass luma planes shared by the ghost gate and the TILE
    // aligner. The aligner matches on a low-pass because the sub-pixel offset is a
    // property of the smooth, band-limited scene structure; aliased super-Nyquist detail
    // (which shifts unpredictably frame to frame) would otherwise dominate the per-tile
    // MAD and corrupt the very sub-pixel estimate SR depends on. Low-pass matching locks
    // onto the base; the full-resolution samples are still splatted, so no detail is
    // lost. The ghost gate compares on the SAME band: alignment minimised the residual
    // exactly in the band it matched, so gating there sees true motion rather than
    // alignment mismatch. A single constant keeps the two in agreement by construction.
    private const val LOW_PASS_BLUR_RADIUS = 2

    /** Stride of the residual sample used to estimate each frame's ghost sigma. */
    private const val SIGMA_SAMPLE_STRIDE = 4

    private val aligner = FrameAligner()

    // Dead-zone 0.0: sub-pixel offsets are the signal SR reconstructs, so none is snapped away.
    private val tileAligner = TileAligner(subPixelDeadZone = 0.0)
    private val ghostRejector = GhostRejector()

    /**
     * @property superResolved the 2x-resolution output frame.
     * @property merged the standard 1x robust merge used as the fallback layer, exposed so
     *   callers (and the golden proof) can compare SR against a bilinear upsample of it.
     * @property usedFrameCount how many frames survived global outlier rejection.
     */
    data class Result(
        val superResolved: Frame,
        val merged: Frame,
        val usedFrameCount: Int,
    )

    /** Super-resolves [frames] (first is the reference) into a [SCALE]x output. */
    fun superResolve(frames: List<Frame>): Result {
        require(frames.isNotEmpty()) { "frames must not be empty" }
        val reference = frames.first()
        val width = reference.width
        val height = reference.height
        require(frames.all { it.width == width && it.height == height }) {
            "all frames must share dimensions"
        }

        if (frames.size == 1) {
            // No sub-pixel diversity: SR degenerates to a plain bilinear upsample.
            return Result(BilinearUpsampler.upsample2x(reference), reference, 1)
        }

        // 1. Align the whole burst once, sharing the result between the fallback merge and
        //    the splat. Dead-zone-free tile refinement keeps genuine sub-pixel offsets.
        val alignments = aligner.align(frames)
        val accepted = alignments.map { it.accepted }
        val referenceLuma = Luminance.extract(reference)
        // Tile matching runs on a low-pass of luma so aliased detail cannot corrupt the
        // sub-pixel estimate (see [LOW_PASS_BLUR_RADIUS]).
        val alignRefLuma = blurLumaPlane(reference, referenceLuma)
        val tileOffsets = frames.mapIndexed { index, frame ->
            if (index == 0 || !accepted[index]) {
                null
            } else {
                val frameLuma = Luminance.extract(frame)
                tileAligner.refine(alignRefLuma, blurLumaPlane(frame, frameLuma), alignments[index].dx, alignments[index].dy)
            }
        }

        // 2. Fallback layer source: the standard robust denoise merge from the same alignment.
        val merged = RobustFrameMerger.merge(frames, accepted, tileOffsets, ghostRejector)

        // 3. Reference low-pass luma for the ghost gate.
        val refBlur = blurredLuma(reference, referenceLuma)

        val outWidth = width * SCALE
        val outHeight = height * SCALE
        val texels = outWidth * outHeight
        val sumR = FloatArray(texels)
        val sumG = FloatArray(texels)
        val sumB = FloatArray(texels)
        val sumW = FloatArray(texels)

        // 4. Splat each accepted frame serially (write contention forbids a parallel scatter).
        for (index in frames.indices) {
            if (!accepted[index]) continue
            val frame = frames[index]
            if (index == 0) {
                splatReference(reference, sumR, sumG, sumB, sumW, outWidth, outHeight)
            } else {
                val offsets = tileOffsets[index] ?: continue
                val frameBlur = blurredLuma(frame, null)
                val sigma = estimateSigma(frameBlur, refBlur, offsets, width, height)
                splatFrame(frame, offsets, refBlur, frameBlur, sigma, sumR, sumG, sumB, sumW, outWidth, outHeight)
            }
        }

        // 5. Coverage-normalise; low-coverage texels take the bilinear-upsampled merge.
        val out = IntArray(texels)
        PipelineParallel.parallelRows(outHeight) { yStart, yEnd ->
            val scratch = DoubleArray(3)
            for (y in yStart until yEnd) {
                val rowBase = y * outWidth
                for (x in 0 until outWidth) {
                    val i = rowBase + x
                    val totalWeight = sumW[i]
                    if (totalWeight > WEIGHT_EPS) {
                        val r = (sumR[i] / totalWeight).roundToInt().coerceIn(0, 255)
                        val g = (sumG[i] / totalWeight).roundToInt().coerceIn(0, 255)
                        val b = (sumB[i] / totalWeight).roundToInt().coerceIn(0, 255)
                        out[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                    } else {
                        SubPixelSampler.sampleRgb(merged, x / 2.0, y / 2.0, scratch)
                        val r = scratch[0].roundToInt().coerceIn(0, 255)
                        val g = scratch[1].roundToInt().coerceIn(0, 255)
                        val b = scratch[2].roundToInt().coerceIn(0, 255)
                        out[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                    }
                }
            }
        }

        return Result(
            superResolved = Frame(outWidth, outHeight, out, reference.timestampMillis),
            merged = merged,
            usedFrameCount = accepted.count { it },
        )
    }

    /** Reference pixels land on the even output texels with weight 1 (no ghost gate). */
    private fun splatReference(
        reference: Frame,
        sumR: FloatArray,
        sumG: FloatArray,
        sumB: FloatArray,
        sumW: FloatArray,
        outWidth: Int,
        outHeight: Int,
    ) {
        val width = reference.width
        val height = reference.height
        val argb = reference.argb
        for (fy in 0 until height) {
            val rowBase = fy * width
            for (fx in 0 until width) {
                val pixel = argb[rowBase + fx]
                val r = ((pixel shr 16) and 0xFF).toFloat()
                val g = ((pixel shr 8) and 0xFF).toFloat()
                val b = (pixel and 0xFF).toFloat()
                KernelSplat.splat(
                    sumR, sumG, sumB, sumW, outWidth, outHeight,
                    (SCALE * fx).toDouble(), (SCALE * fy).toDouble(), r, g, b, 1f,
                )
            }
        }
    }

    /** Splats one aligned non-reference frame, ghost-gated on the low-pass luma difference. */
    @Suppress("LongParameterList")
    private fun splatFrame(
        frame: Frame,
        offsets: TileAligner.TileOffsets,
        refBlur: DoubleArray,
        frameBlur: DoubleArray,
        sigma: Double,
        sumR: FloatArray,
        sumG: FloatArray,
        sumB: FloatArray,
        sumW: FloatArray,
        outWidth: Int,
        outHeight: Int,
    ) {
        val width = frame.width
        val height = frame.height
        val argb = frame.argb
        val ref = DoubleArray(2)
        for (fy in 0 until height) {
            val rowBase = fy * width
            for (fx in 0 until width) {
                resolveReferencePosition(offsets, fx, fy, ref)
                val refX = ref[0]
                val refY = ref[1]
                val refBlurAt = samplePlane(refBlur, width, height, refX, refY)
                val weight = ghostRejector.weight(frameBlur[rowBase + fx] - refBlurAt, sigma)
                if (weight <= 0.0) continue
                val pixel = argb[rowBase + fx]
                val r = ((pixel shr 16) and 0xFF).toFloat()
                val g = ((pixel shr 8) and 0xFF).toFloat()
                val b = (pixel and 0xFF).toFloat()
                KernelSplat.splat(
                    sumR, sumG, sumB, sumW, outWidth, outHeight,
                    SCALE * refX, SCALE * refY, r, g, b, weight.toFloat(),
                )
            }
        }
    }

    /** Robust ghost sigma from a strided sample of the low-pass aligned residual. */
    private fun estimateSigma(
        frameBlur: DoubleArray,
        refBlur: DoubleArray,
        offsets: TileAligner.TileOffsets,
        width: Int,
        height: Int,
    ): Double {
        val cols = (width + SIGMA_SAMPLE_STRIDE - 1) / SIGMA_SAMPLE_STRIDE
        val rows = (height + SIGMA_SAMPLE_STRIDE - 1) / SIGMA_SAMPLE_STRIDE
        val diffs = DoubleArray(cols * rows)
        val ref = DoubleArray(2)
        var k = 0
        var y = 0
        while (y < height) {
            val rowBase = y * width
            var x = 0
            while (x < width) {
                resolveReferencePosition(offsets, x, y, ref)
                diffs[k++] = frameBlur[rowBase + x] - samplePlane(refBlur, width, height, ref[0], ref[1])
                x += SIGMA_SAMPLE_STRIDE
            }
            y += SIGMA_SAMPLE_STRIDE
        }
        return ghostRejector.estimateSigma(diffs)
    }

    /**
     * The continuous REFERENCE position frame pixel ([fx], [fy]) lands on, written as
     * (refX, refY) into [outRef].
     *
     * [TileAligner.TileOffsets] is a field over reference coordinates, but the reference
     * position ref = f - offset(ref) depends on the offset itself: the splat scatters in
     * the direction the field was built to gather. One fixed-point step resolves the
     * inversion: seed with the field at the frame coordinate, then re-evaluate at the
     * guessed reference position. Convergence bound: the field is bilinear over
     * tile-centre samples confined to +/-(searchRadius + 0.5) px around the global
     * offset, so its per-pixel slope L is at most (2 * searchRadius + 1) / tileSize
     * (~0.16 with the defaults, far smaller for the smooth fields real bursts produce).
     * The seed is within L * |offset| of the true position and the step contracts that to
     * L^2 * |offset| -- sub-0.1 px for any offset up to ~4 px even at the worst-case
     * slope, and for realistic slopes (L <= 0.05) up to ~40 px -- so one step suffices.
     */
    private fun resolveReferencePosition(
        offsets: TileAligner.TileOffsets,
        fx: Int,
        fy: Int,
        outRef: DoubleArray,
    ) {
        val guessX = fx - offsets.offsetXAt(fx, fy)
        val guessY = fy - offsets.offsetYAt(fx, fy)
        outRef[0] = fx - offsets.offsetXAt(guessX, guessY)
        outRef[1] = fy - offsets.offsetYAt(guessX, guessY)
    }

    /** Box-blurred luma plane of [frame] (Rec. 601), used only by the ghost gate. */
    private fun blurredLuma(frame: Frame, precomputed: LumaPlane?): DoubleArray {
        val luma = precomputed ?: Luminance.extract(frame)
        val src = DoubleArray(luma.values.size) { luma.values[it].toDouble() }
        return BoxBlur.blur(src, frame.width, frame.height, LOW_PASS_BLUR_RADIUS)
    }

    /** Box-blurred luma plane of [frame] used for tile alignment (see [LOW_PASS_BLUR_RADIUS]). */
    private fun blurLumaPlane(frame: Frame, luma: LumaPlane): LumaPlane {
        val src = DoubleArray(luma.values.size) { luma.values[it].toDouble() }
        val blurred = BoxBlur.blur(src, frame.width, frame.height, LOW_PASS_BLUR_RADIUS)
        return LumaPlane(frame.width, frame.height, IntArray(blurred.size) { blurred[it].roundToInt() })
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
}
