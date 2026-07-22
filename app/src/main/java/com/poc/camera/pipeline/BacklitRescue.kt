package com.poc.camera.pipeline

import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Winning parameters for [BacklitRescue], productised from the validated local tuning
 * harness (issue #96): a real backlit Pixel RAW whose subject sat 2-3 stops under a
 * sunlit background, tuned against an iPhone reference rendition.
 *
 * The lift is driven by a guided-filter BASE layer (large-scale luma), so the per-pixel
 * gain follows the local scene brightness rather than the noisy per-pixel luma -- that is
 * what stops the lift from ringing at edges. The remaining fields shape the gain curve,
 * protect the highlights and add the subtle warmth a lifted shadow needs.
 *
 *  - [target] the luma the shadow gain aims a fully-lifted base toward (~150).
 *  - [alpha] softens the gain curve (< 1 lifts less aggressively as the base rises).
 *  - [maxGain] hard ceiling on the per-pixel gain, so a near-black base cannot explode.
 *  - [radius] guided-filter window half-width for the base layer.
 *  - [epsScale] guided-filter edge-preservation regulariser, as a fraction of full scale
 *    (eps = (epsScale * 255)^2, squared intensity units).
 *  - [hiStart]/[hiEnd] the base-luma band over which the gain is faded back to 1 so
 *    highlights are protected from the lift (smoothstep).
 *  - [warmth] gentle warm tint applied ONLY in proportion to how much a pixel was lifted
 *    (shadow skin reads cool after a large lift, so the lifted regions are nudged warm).
 *    1.0 disables the tint.
 *
 * Deterministic, no Android dependencies.
 */
data class BacklitRescueParams(
    val target: Double = 150.0,
    val alpha: Double = 0.9,
    val maxGain: Double = 7.5,
    val radius: Int = 32,
    val epsScale: Double = 0.035,
    val hiStart: Double = 150.0,
    val hiEnd: Double = 220.0,
    val warmth: Double = 1.05,
) {
    init {
        require(target > 0.0) { "target must be > 0" }
        require(alpha > 0.0) { "alpha must be > 0" }
        require(maxGain >= 1.0) { "maxGain must be >= 1" }
        require(radius >= 0) { "radius must be >= 0" }
        require(epsScale > 0.0) { "epsScale must be > 0" }
        require(hiEnd > hiStart) { "hiEnd must be > hiStart" }
        require(warmth >= 1.0) { "warmth must be >= 1" }
    }

    companion object {
        /** The parameters that won the local pair-tuning against the iPhone reference. */
        val DEFAULT = BacklitRescueParams()
    }
}

/**
 * Adaptive local exposure lift for backlit scenes (issue #96).
 *
 * A guided-filter self-guided smoothing of the luma plane produces a large-scale BASE
 * layer; each pixel is then multiplied by a gain that pushes its base toward
 * [BacklitRescueParams.target], so a dark subject sitting under a bright background is
 * lifted while the background (whose base already exceeds the target) is left alone. The
 * gain is:
 *
 *  - capped at [BacklitRescueParams.maxGain] so a near-black base cannot explode;
 *  - faded back to 1 across the [BacklitRescueParams.hiStart]..[BacklitRescueParams.hiEnd]
 *    base band (a smoothstep) so highlights are protected from the lift;
 *  - scaled by [engagement] (a lerp of the gain toward 1), which lets the adaptive
 *    [BacklitDetector] and the [FinishingParams.backlitRescue] master strength dial the
 *    effect between off and full without changing the tuned curve.
 *
 * Lifted pixels are nudged warm in proportion to how much they were lifted (shadow skin
 * reads cool after a large lift), and every channel is passed through a soft [shoulder]
 * that compresses overshoot above [SHOULDER_KNEE] instead of hard-clipping.
 *
 * ## Determinism / parallelism
 *
 * The luma extraction and the per-pixel gain/apply loop are element-wise (each output
 * pixel depends only on its own input and its own base sample), so they run under the
 * [PipelineParallel] row-parallel contract and are BYTE-identical across chunk counts.
 * The guided base ([GuidedFilter.selfGuided]) is already parallel under the same contract,
 * as are the bounded-base box downsample and gain interpolation (each output element reads
 * only read-only input and writes only its own slot).
 * At [engagement] <= 0 the input frame is returned unchanged (bit-exact passthrough), so a
 * scene the detector reports as non-backlit is left completely untouched.
 *
 * ## Bounded base memory (issue #108)
 *
 * The guided BASE layer is the rescue's only whole-frame double-plane computation. At or
 * below [MAX_BASE_PIXELS] input pixels it is computed at full resolution -- the original
 * path, byte-identical for small captures and every golden fixture. Above the threshold
 * the base is computed on a k x k box-downsampled luma plane (k = [baseDownscaleFactor],
 * chosen so the downsampled plane fits the budget) with the guided radius scaled to
 * `max(1, radius / k)` -- a radius-r window in the downsampled space spans ~r*k source
 * pixels, so the base keeps its full-resolution spatial support. The per-pixel GAIN is
 * then evaluated on the downsampled base (the gain formula, highlight-protection
 * smoothstep included, depends solely on the base luma) and bilinearly interpolated back
 * to full resolution inside the apply loop. Interpolating the GAIN field rather than the
 * base avoids re-deriving gains from an interpolated base: the gain is what must vary
 * smoothly across the frame, and it is smooth by construction (a monotone function of a
 * radius-32-guided base). The interpolation is fused into the apply loop -- no full-
 * resolution gain plane is materialised -- so the bounded path allocates NO full-
 * resolution double plane at all; its transient working set is capped by the downsampled
 * plane count regardless of capture size (see [basePeakBytesEstimate]). Because the base
 * is low-frequency by construction the output is near-identical to the whole-frame path
 * everywhere flat; only at a hard subject/background edge -- where the whole-frame guided
 * base snaps its preserved edge while the bounded gain interpolates across the transition
 * band -- do per-pixel deviations concentrate. BacklitRescueBoundedBaseTest measures the
 * deviation profile and the memory bound.
 *
 * Pure Kotlin, deterministic, no Android dependencies. Mirrors the validated prototype.
 */
object BacklitRescue {

    /**
     * Floor added to both sides of the gain ratio `(target + floor) / (base + floor)`.
     * It bounds the ratio for a near-black base (so the curve is well-behaved before the
     * [BacklitRescueParams.maxGain] cap) and softens the knee near the target.
     */
    const val GAIN_FLOOR = 8.0

    /** Channel value at/below which [shoulder] is the identity; above it the soft roll-off starts. */
    const val SHOULDER_KNEE = 210.0

    /** Softness (asymptotic headroom) of the [shoulder] roll-off above [SHOULDER_KNEE]. */
    const val SHOULDER_SOFTNESS = 45.0

    /**
     * Pixel count at or below which the guided base runs at full resolution (the original
     * whole-frame path, byte-identical for every existing golden fixture); above it the
     * base is computed on a downsampled luma plane bounded to at most this many pixels.
     *
     * Memory math at the 2 MP cap: the downsampled luma plane is 16 MB of doubles, and the
     * [GuidedFilter.selfGuided] estimator holds up to ~[BASE_FLOAT_PLANES_PEAK] (11) such
     * planes live at once (input + meanI + squares + meanII + a + b + meanA/meanB + output
     * + two [BoxBlur] scratch), so the bounded base peaks at ~176 MB of transient doubles
     * -- a fixed ceiling regardless of capture size, where the old whole-frame base scaled
     * those same planes with the full pixel count (~4.4 GB at 50 MP).
     */
    const val MAX_BASE_PIXELS = 2_000_000L

    /**
     * Conservative count of downsampled-size `DoubleArray` planes live at once during the
     * base computation (see [MAX_BASE_PIXELS]); used by [basePeakBytesEstimate]. Matches
     * the ~11-plane [GuidedFilter] accounting in [TiledFinishing.FLOAT_PLANES_PEAK].
     */
    const val BASE_FLOAT_PLANES_PEAK = 11

    private const val BYTES_PER_DOUBLE = 8L
    private const val BYTES_PER_INT = 4L

    // Rec. 601 luma weights, matching Luminance / WhiteBalance / ChromaDenoiser.
    private const val R_WEIGHT = 0.299
    private const val G_WEIGHT = 0.587
    private const val B_WEIGHT = 0.114

    /**
     * Returns [frame] with the backlit lift applied at [engagement] in [0, 1]. At
     * [engagement] <= 0 the frame is returned unchanged (bit-exact passthrough); at 1 the
     * full tuned operator runs. [engagement] is intended to be
     * `FinishingParams.backlitRescue * BacklitDetector.detect(frame).strength`, so the
     * effect is gated both by the master strength and by how strongly the scene reads as
     * backlit.
     *
     * Frames at or under [MAX_BASE_PIXELS] take the original whole-frame base path
     * unchanged; larger frames take the bounded downsampled-base path (see the class doc).
     *
     * @param chunkCount row-parallel chunk count for the element-wise passes and the guided
     *   base; [PipelineParallel.SERIAL_CHUNKS] forces the serial reference path.
     */
    fun apply(
        frame: Frame,
        params: BacklitRescueParams = BacklitRescueParams.DEFAULT,
        engagement: Double,
        chunkCount: Int = PipelineParallel.parallelism,
    ): Frame {
        if (engagement <= 0.0) return frame
        val factor = baseDownscaleFactor(frame.width, frame.height)
        return if (factor <= 1) {
            applyFullResBase(frame, params, engagement, chunkCount)
        } else {
            applyDownsampledBase(frame, params, engagement, chunkCount, factor)
        }
    }

    /**
     * The original whole-frame path: guided base at full resolution, gain/apply per pixel.
     * The routed path for every frame at or under [MAX_BASE_PIXELS]; exposed internal as
     * the whole-frame REFERENCE the bounded path is measured against in
     * BacklitRescueBoundedBaseTest.
     */
    internal fun applyFullResBase(
        frame: Frame,
        params: BacklitRescueParams,
        engagement: Double,
        chunkCount: Int,
    ): Frame {
        val e = engagement.coerceAtMost(1.0)

        val width = frame.width
        val height = frame.height
        val argb = frame.argb
        val n = argb.size

        // Luma plane: element-wise, so it is row-parallel under the determinism contract.
        val luma = DoubleArray(n)
        PipelineParallel.parallelRows(n, chunkCount) { start, end ->
            for (i in start until end) {
                val px = argb[i]
                luma[i] = R_WEIGHT * ((px shr 16) and 0xFF) +
                    G_WEIGHT * ((px shr 8) and 0xFF) +
                    B_WEIGHT * (px and 0xFF)
            }
        }

        val eps = (params.epsScale * 255.0).pow(2.0)
        val base = GuidedFilter.selfGuided(luma, width, height, params.radius, eps, chunkCount)

        val out = IntArray(n)
        val warmthExcess = params.warmth - 1.0
        val gainSpan = params.maxGain - 1.0
        // Per-pixel gain/apply: each output depends only on its own base + own pixel, so it
        // is row-parallel and bit-identical across chunk counts.
        PipelineParallel.parallelRows(n, chunkCount) { start, end ->
            for (i in start until end) {
                val b = base[i].coerceAtLeast(1.0)
                // Shadow gain toward the target, protected as the base enters highlights.
                var gain = if (b < params.target) shadowGain(b, params) else 1.0
                val protect = highlightProtect(b, params)
                gain = gain * (1.0 - protect) + protect // fade toward 1 across the highlight band
                // Engagement scales the effective gain by lerping it toward 1.
                val effGain = 1.0 + e * (gain - 1.0)
                // Warmth is proportional to the ACTUAL applied lift, so an untouched pixel
                // (effGain == 1) gets no tint; at engagement 1 this equals the prototype.
                val liftAmount = if (gainSpan > 0.0) ((effGain - 1.0) / gainSpan).coerceIn(0.0, 1.0) else 0.0
                val wr = 1.0 + warmthExcess * liftAmount
                val wb = 1.0 - warmthExcess * 0.7 * liftAmount

                val px = argb[i]
                val r = shoulder(((px shr 16) and 0xFF) * effGain * wr)
                val g = shoulder(((px shr 8) and 0xFF) * effGain)
                val bch = shoulder((px and 0xFF) * effGain * wb)
                out[i] = (0xFF shl 24) or (r.roundToInt() shl 16) or (g.roundToInt() shl 8) or bch.roundToInt()
            }
        }
        return Frame(width, height, out, frame.timestampMillis)
    }

    /**
     * The bounded-base path for frames above [MAX_BASE_PIXELS] (see the class doc): the
     * luma plane is box-downsampled by [factor] straight from the ARGB data (no full-
     * resolution double plane is ever allocated), the guided base runs in the downsampled
     * space at radius `max(1, radius / factor)` with [BacklitRescueParams.epsScale]
     * unchanged (eps is an intensity-domain threshold, not a spatial one), the gain
     * formula is evaluated on that base, and the apply loop bilinearly samples the smooth
     * downsampled GAIN field per full-resolution pixel. Warmth, engagement lerp and the
     * soft shoulder are exactly the full-resolution loop's arithmetic.
     */
    private fun applyDownsampledBase(
        frame: Frame,
        params: BacklitRescueParams,
        engagement: Double,
        chunkCount: Int,
        factor: Int,
    ): Frame {
        val e = engagement.coerceAtMost(1.0)

        val width = frame.width
        val height = frame.height
        val argb = frame.argb
        val downWidth = ceilDiv(width, factor)
        val downHeight = ceilDiv(height, factor)

        val lumaDown = downsampleLuma(argb, width, height, factor, downWidth, downHeight, chunkCount)

        val downRadius = (params.radius / factor).coerceAtLeast(1)
        val eps = (params.epsScale * 255.0).pow(2.0)
        val baseDown = GuidedFilter.selfGuided(lumaDown, downWidth, downHeight, downRadius, eps, chunkCount)

        // The gain depends ONLY on the base luma, so it is fully determined in the
        // downsampled space -- highlight-protection smoothstep included. Element-wise, so
        // row-parallel under the determinism contract.
        val gainDown = DoubleArray(baseDown.size)
        PipelineParallel.parallelRows(baseDown.size, chunkCount) { start, end ->
            for (i in start until end) {
                val b = baseDown[i].coerceAtLeast(1.0)
                val gain = if (b < params.target) shadowGain(b, params) else 1.0
                val protect = highlightProtect(b, params)
                gainDown[i] = gain * (1.0 - protect) + protect
            }
        }

        // Bilinear sample coordinates: down sample ox is the mean of source columns
        // [ox*factor, ox*factor + factor), whose center is ox*factor + (factor-1)/2, so a
        // full-resolution x maps to (x - (factor-1)/2) / factor in down space -- block
        // centers land exactly on integer down coordinates -- clamped at the borders.
        // Column indices/fractions are shared by every row, so they are precomputed once.
        val halfBlock = (factor - 1) * 0.5
        val x0Idx = IntArray(width)
        val x1Idx = IntArray(width)
        val xFrac = DoubleArray(width)
        val maxSx = (downWidth - 1).toDouble()
        for (x in 0 until width) {
            val sx = ((x - halfBlock) / factor).coerceIn(0.0, maxSx)
            val xi = floor(sx).toInt()
            x0Idx[x] = xi
            x1Idx[x] = if (xi < downWidth - 1) xi + 1 else xi
            xFrac[x] = sx - xi
        }

        val out = IntArray(argb.size)
        val warmthExcess = params.warmth - 1.0
        val gainSpan = params.maxGain - 1.0
        val maxSy = (downHeight - 1).toDouble()
        // Row-parallel: each output row reads only read-only input (argb, gainDown, the
        // coordinate tables) and writes only its own slots, so it is bit-identical across
        // chunk counts.
        PipelineParallel.parallelRows(height, chunkCount) { yStart, yEnd ->
            for (y in yStart until yEnd) {
                val sy = ((y - halfBlock) / factor).coerceIn(0.0, maxSy)
                val yi = floor(sy).toInt()
                val fy = sy - yi
                val row0 = yi * downWidth
                val row1 = (if (yi < downHeight - 1) yi + 1 else yi) * downWidth
                val outRow = y * width
                for (x in 0 until width) {
                    val x0 = x0Idx[x]
                    val x1 = x1Idx[x]
                    val fx = xFrac[x]
                    val g00 = gainDown[row0 + x0]
                    val g01 = gainDown[row1 + x0]
                    val top = g00 + (gainDown[row0 + x1] - g00) * fx
                    val bottom = g01 + (gainDown[row1 + x1] - g01) * fx
                    val gain = top + (bottom - top) * fy

                    val effGain = 1.0 + e * (gain - 1.0)
                    val liftAmount = if (gainSpan > 0.0) ((effGain - 1.0) / gainSpan).coerceIn(0.0, 1.0) else 0.0
                    val wr = 1.0 + warmthExcess * liftAmount
                    val wb = 1.0 - warmthExcess * 0.7 * liftAmount

                    val px = argb[outRow + x]
                    val r = shoulder(((px shr 16) and 0xFF) * effGain * wr)
                    val g = shoulder(((px shr 8) and 0xFF) * effGain)
                    val bch = shoulder((px and 0xFF) * effGain * wb)
                    out[outRow + x] = (0xFF shl 24) or (r.roundToInt() shl 16) or (g.roundToInt() shl 8) or bch.roundToInt()
                }
            }
        }
        return Frame(width, height, out, frame.timestampMillis)
    }

    /**
     * The k x k box-downsampled Rec. 601 luma plane of [argb], computed directly from the
     * packed pixels so no full-resolution double plane is allocated. Output sample (ox, oy)
     * is the mean luma over the `factor x factor` source block at (ox*factor, oy*factor);
     * partial edge blocks average only the pixels that exist (dimensions are ceil(size /
     * factor), so every source pixel contributes to exactly one sample). Each output
     * element accumulates its own block in a fixed order from read-only input, so the pass
     * is row-parallel and bit-identical across chunk counts.
     */
    private fun downsampleLuma(
        argb: IntArray,
        width: Int,
        height: Int,
        factor: Int,
        downWidth: Int,
        downHeight: Int,
        chunkCount: Int,
    ): DoubleArray {
        val out = DoubleArray(downWidth * downHeight)
        PipelineParallel.parallelRows(downHeight, chunkCount) { oyStart, oyEnd ->
            for (oy in oyStart until oyEnd) {
                val sy0 = oy * factor
                val sy1 = minOf(sy0 + factor, height)
                val outRow = oy * downWidth
                for (ox in 0 until downWidth) {
                    val sx0 = ox * factor
                    val sx1 = minOf(sx0 + factor, width)
                    var sum = 0.0
                    for (sy in sy0 until sy1) {
                        val row = sy * width
                        for (sx in sx0 until sx1) {
                            val px = argb[row + sx]
                            sum += R_WEIGHT * ((px shr 16) and 0xFF) +
                                G_WEIGHT * ((px shr 8) and 0xFF) +
                                B_WEIGHT * (px and 0xFF)
                        }
                    }
                    out[outRow + ox] = sum / ((sy1 - sy0) * (sx1 - sx0))
                }
            }
        }
        return out
    }

    /**
     * The integer box-downscale factor bounding the base plane: 1 (full resolution) when
     * [width]x[height] is at or under [MAX_BASE_PIXELS], otherwise the smallest k >= 2 --
     * seeded at `ceil(sqrt(pixels / MAX_BASE_PIXELS))` -- whose ceil-divided downsampled
     * plane `ceil(w/k) * ceil(h/k)` fits the budget (the seed can undershoot by one when
     * the partial edge blocks push the ceil dimensions just past it).
     */
    internal fun baseDownscaleFactor(width: Int, height: Int): Int {
        val pixels = width.toLong() * height.toLong()
        if (pixels <= MAX_BASE_PIXELS) return 1
        var factor = ceil(sqrt(pixels.toDouble() / MAX_BASE_PIXELS)).toInt().coerceAtLeast(2)
        while (ceilDiv(width, factor).toLong() * ceilDiv(height, factor).toLong() > MAX_BASE_PIXELS) {
            factor++
        }
        return factor
    }

    /**
     * Theoretical peak heap (bytes) of a rescue of a [width]x[height] frame: the resident
     * input and output ARGB `IntArray`s plus [BASE_FLOAT_PLANES_PEAK] double planes over
     * the BASE pixel count -- the full pixel count at or under [MAX_BASE_PIXELS], the
     * bounded downsampled count above it. Pure arithmetic (mirroring
     * [TiledFinishing.peakBytesEstimate]), so the memory-ceiling test is deterministic
     * rather than a flaky live-heap probe.
     */
    fun basePeakBytesEstimate(width: Int, height: Int): Long {
        val pixels = width.toLong() * height.toLong()
        val residentInt = 2L * pixels * BYTES_PER_INT
        val factor = baseDownscaleFactor(width, height)
        val basePixels = if (factor <= 1) {
            pixels
        } else {
            ceilDiv(width, factor).toLong() * ceilDiv(height, factor).toLong()
        }
        return residentInt + basePixels * BASE_FLOAT_PLANES_PEAK * BYTES_PER_DOUBLE
    }

    /**
     * Theoretical peak heap (bytes) the OLD whole-frame base would need for a
     * [width]x[height] frame (its double planes scale with the full pixel count). Provided
     * so the memory test can assert the bounded peak is a small fraction of it.
     */
    fun wholeFrameBasePeakBytesEstimate(width: Int, height: Int): Long {
        val pixels = width.toLong() * height.toLong()
        return 2L * pixels * BYTES_PER_INT + pixels * BASE_FLOAT_PLANES_PEAK * BYTES_PER_DOUBLE
    }

    private fun ceilDiv(a: Int, b: Int): Int = (a + b - 1) / b

    /**
     * The pre-protection shadow gain for a base luma [base] below [BacklitRescueParams.target]:
     * `((target + GAIN_FLOOR) / (base + GAIN_FLOOR))^alpha`, capped at
     * [BacklitRescueParams.maxGain]. Exposed for direct testing of the gain math.
     */
    internal fun shadowGain(base: Double, params: BacklitRescueParams): Double {
        val g = ((params.target + GAIN_FLOOR) / (base + GAIN_FLOOR)).pow(params.alpha)
        return g.coerceAtMost(params.maxGain)
    }

    /**
     * The highlight-protection blend in [0, 1] for a base luma [base]: a smoothstep that is
     * 0 at/below [BacklitRescueParams.hiStart] (full gain) and 1 at/above
     * [BacklitRescueParams.hiEnd] (gain forced to 1). Exposed for direct testing.
     */
    internal fun highlightProtect(base: Double, params: BacklitRescueParams): Double {
        val t = ((base - params.hiStart) / (params.hiEnd - params.hiStart)).coerceIn(0.0, 1.0)
        return t * t * (3.0 - 2.0 * t)
    }

    /**
     * Soft highlight shoulder: the identity for [v] at/below [SHOULDER_KNEE] (clamped to a
     * non-negative floor), then an exponential roll-off toward 255 so lifted overshoot is
     * compressed rather than hard-clipped. Continuous with unit slope at the knee.
     */
    internal fun shoulder(v: Double): Double {
        if (v <= SHOULDER_KNEE) return v.coerceAtLeast(0.0)
        val over = v - SHOULDER_KNEE
        return (SHOULDER_KNEE + SHOULDER_SOFTNESS * (1.0 - exp(-over / SHOULDER_SOFTNESS))).coerceAtMost(255.0)
    }
}
