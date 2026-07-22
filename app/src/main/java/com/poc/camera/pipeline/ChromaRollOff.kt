package com.poc.camera.pipeline

import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Parameters for [ChromaRollOff], productised from the validated local pair-tuning
 * (issue #97): a real backlit Pixel RAW whose lips glowed red after a shadow lift +
 * saturation, escaping the skin cluster because their chroma was too saturated for the
 * skin ellipse. The roll-off is a soft shoulder on the chroma MAGNITUDE that compresses
 * only ISOLATED extreme chroma back toward the rest of the image -- isolation is judged
 * against the pixel's own neighbourhood (the spatial gate, issue #107).
 *
 *  - [knee] the GLOBAL floor of the per-pixel effective knee: a pixel whose
 *    `sqrt(cr^2 + cb^2)` is at or under [knee] is passed through untouched regardless of
 *    its neighbourhood, so normal saturation is never compressed.
 *  - [soft] the asymptotic headroom of the shoulder above the effective knee: chroma
 *    magnitude is mapped to `kneeEff + (mag - kneeEff) / (1 + (mag - kneeEff) / soft)`, so
 *    it approaches `kneeEff + soft` as the input magnitude grows -- a smaller [soft]
 *    compresses harder.
 *  - [strength] the blend in [0, 1] between the ORIGINAL chroma (0, a bit-exact
 *    passthrough) and the fully compressed chroma (1). It lets [FinishingParams.chromaRollOff]
 *    dial the effect between off and full without changing the tuned knee/soft curve.
 *  - [isolationFactor] the spatial gate (issue #107): the per-pixel effective knee is
 *    `kneeEff = max(knee, isolationFactor * localMean)` where `localMean` is the box mean
 *    of the chroma-magnitude plane over the [neighborhoodRadius] window. A pixel therefore
 *    compresses only when its magnitude exceeds BOTH the global [knee] AND
 *    [isolationFactor] times its neighbourhood's typical chroma. In a uniformly saturated
 *    region `localMean ~= mag`, so `kneeEff ~= isolationFactor * mag > mag` and nothing
 *    compresses; an isolated spot on a low-chroma surround reads a low `localMean`, so
 *    `kneeEff` collapses to the global [knee] and the spot compresses exactly as the
 *    original whole-frame shoulder did. Must be >= 1 (below 1 the uniform-region
 *    passthrough guarantee would not hold).
 *  - [neighborhoodRadius] the box-mean window radius (in pixels) the local reference is
 *    measured over. 24 (a 49x49 window) is the validated neighbourhood scale: large
 *    enough that a lips-sized runaway spot cannot dominate its own reference (so it still
 *    reads as isolated), small enough that a genuinely saturated REGION -- a colour-chart
 *    patch, a flower bed -- fills its own window and passes through. This radius is the
 *    operator's spatial support and is accounted for in [TiledFinishing.SUPPORT_RADIUS].
 *
 * The winning shoulder params on the real pair were [knee] 30 and [soft] 18 at full
 * [strength]; the gate defaults ([isolationFactor] 1.5, [neighborhoodRadius] 24) were
 * validated against the colorchart fidelity/rendition goldens (uniform saturation passes
 * through) while preserving the lips-case compression (see ChromaRollOffGoldenTest).
 * Deterministic, no Android dependencies.
 */
data class ChromaRollOffParams(
    val knee: Double = 30.0,
    val soft: Double = 18.0,
    val strength: Double = 1.0,
    val isolationFactor: Double = 1.5,
    val neighborhoodRadius: Int = 24,
) {
    init {
        require(knee >= 0.0) { "knee must be >= 0" }
        require(soft > 0.0) { "soft must be > 0" }
        require(strength in 0.0..1.0) { "strength must be in [0, 1]" }
        require(isolationFactor >= 1.0) { "isolationFactor must be >= 1" }
        require(neighborhoodRadius >= 1) { "neighborhoodRadius must be >= 1" }
    }

    companion object {
        /** The parameters that won the local pair-tuning against the iPhone reference. */
        val DEFAULT = ChromaRollOffParams()
    }
}

/**
 * Spatially gated chroma-magnitude roll-off for the finishing tail (issues #97, #107).
 *
 * Each pixel is split into a Rec. 601 luma and opponent chroma (`cr = R - Y`, `cb = B - Y`,
 * `cg = G - Y`); the chroma MAGNITUDE `sqrt(cr^2 + cb^2)` is passed through a soft shoulder
 * ([shoulder]) that is the identity at/below the pixel's EFFECTIVE knee and compresses
 * everything above it toward the `kneeEff + soft` asymptote. All three chroma components are
 * scaled by the resulting ratio and recombined with the (unchanged) luma, so the operator
 * moves colour magnitude only -- hue and luma are preserved. The compressed scale is blended
 * toward identity by [ChromaRollOffParams.strength].
 *
 * ## The spatial gate (issue #107)
 *
 * The original operator was a whole-frame shoulder: any pixel above the global knee was
 * compressed, so a uniformly saturated scene (a colour chart, a flower field) was
 * desaturated broadly -- measured on the colorchart golden, enabling the whole-frame
 * shoulder in RENDITION cost 31.07 -> 23.80 dB of clean-truth PSNR, and the shipped
 * reference profile removed more colour than the measured iPhone signature keeps. The
 * operator exists to catch ISOLATED runaway chroma (the validated real case: lifted lips
 * glowing red), not to desaturate rich scenes, so the gate makes "isolated" explicit: the
 * chroma-magnitude plane is box-blurred ([BoxBlur], [ChromaRollOffParams.neighborhoodRadius])
 * into a local reference, and the per-pixel effective knee is
 * `max(knee, isolationFactor * localMean)`. A uniformly saturated region is its own
 * neighbourhood, so its effective knee sits above its own magnitude and it passes through;
 * an isolated spot on a low-chroma surround reads a low local mean, so its effective knee
 * collapses to the global knee and it compresses exactly as before. The real-pair lips case
 * remains the anchor (see ChromaRollOffGoldenTest).
 *
 * It exists to catch post-saturation runaway chroma, so [FinishingPipeline] runs it AFTER
 * [Saturation] and [Contrast], as the last colour operator before [SemanticRendering]: a
 * region the earlier stages pushed to an isolated extreme chroma (e.g. lips that escaped the
 * skin-protection ellipse and took the full saturation boost) is settled back toward the
 * rest of the frame, while every pixel whose magnitude the boost left at or under its
 * effective knee is bit-exactly untouched.
 *
 * ## Determinism / parallelism
 *
 * The operator is three passes: an element-wise magnitude-plane pass, one [BoxBlur] (both
 * row/column-parallel and bit-identical across chunk counts by [BoxBlur]'s contract), and an
 * element-wise compression pass -- so the whole operator runs under the [PipelineParallel]
 * row-parallel contract and is BYTE-identical across chunk counts. It derives no global
 * statistic, but the box mean gives it a spatial support of
 * [ChromaRollOffParams.neighborhoodRadius] pixels, which [TiledFinishing] accounts for in
 * its halo ([TiledFinishing.SUPPORT_RADIUS]). At [ChromaRollOffParams.strength] <= 0 the
 * input frame is returned unchanged (same reference, bit-exact passthrough). A pixel with
 * chroma magnitude at or under its effective knee reconstructs to its exact input (the
 * scale is exactly 1 and `Y + (channel - Y)` rounds back to the channel), so below-knee and
 * uniformly-saturated content is bit-exact even at full strength.
 *
 * Memory: one extra `DoubleArray` plane (the magnitude plane) plus [BoxBlur]'s two
 * transient planes -- bounded, and far under the finishing chain's guided-filter peaks.
 *
 * Pure Kotlin, deterministic, no Android dependencies. Mirrors the validated prototype.
 */
object ChromaRollOff {

    // Rec. 601 luma weights, matching Luminance / Saturation / BacklitRescue.
    private const val R_WEIGHT = 0.299
    private const val G_WEIGHT = 0.587
    private const val B_WEIGHT = 0.114

    /**
     * Returns [frame] with the spatially gated chroma roll-off applied. At
     * [ChromaRollOffParams.strength] <= 0 the frame is returned unchanged (bit-exact
     * passthrough); at 1 the full tuned shoulder runs wherever the isolation gate opens.
     * Alpha is forced opaque on the result.
     *
     * @param chunkCount row-parallel chunk count for the element-wise passes and the
     *   [BoxBlur]; [PipelineParallel.SERIAL_CHUNKS] forces the serial reference path.
     */
    fun apply(
        frame: Frame,
        params: ChromaRollOffParams = ChromaRollOffParams.DEFAULT,
        chunkCount: Int = PipelineParallel.parallelism,
    ): Frame {
        if (params.strength <= 0.0) return frame
        val src = frame.argb
        val knee = params.knee
        val soft = params.soft
        val strength = params.strength
        val isolationFactor = params.isolationFactor
        // Pass 1: the chroma-magnitude plane. Element-wise, so chunk-count independent.
        val mag = DoubleArray(src.size)
        PipelineParallel.parallelRows(src.size, chunkCount) { start, end ->
            for (i in start until end) {
                val px = src[i]
                val r = ((px shr 16) and 0xFF).toDouble()
                val g = ((px shr 8) and 0xFF).toDouble()
                val b = (px and 0xFF).toDouble()
                val y = R_WEIGHT * r + G_WEIGHT * g + B_WEIGHT * b
                val cr = r - y
                val cb = b - y
                mag[i] = sqrt(cr * cr + cb * cb)
            }
        }
        // Pass 2: the local chroma reference -- the box mean of the magnitude plane over
        // the neighbourhood window. Bit-identical across chunk counts (BoxBlur contract).
        val localMean = BoxBlur.blur(mag, frame.width, frame.height, params.neighborhoodRadius, chunkCount)
        // Pass 3: per-pixel compression against the effective knee. Element-wise over the
        // precomputed planes, so row-parallel and bit-identical across chunk counts.
        val out = IntArray(src.size)
        PipelineParallel.parallelRows(src.size, chunkCount) { start, end ->
            for (i in start until end) {
                val px = src[i]
                val r = ((px shr 16) and 0xFF).toDouble()
                val g = ((px shr 8) and 0xFF).toDouble()
                val b = (px and 0xFF).toDouble()
                val y = R_WEIGHT * r + G_WEIGHT * g + B_WEIGHT * b
                val cr = r - y
                val cg = g - y
                val cb = b - y
                val m = mag[i]
                // The spatial gate: a pixel compresses only above BOTH the global knee and
                // isolationFactor times its neighbourhood's mean chroma. Below the effective
                // knee the scale is exactly 1 (identity); above it the compressed magnitude
                // drives a < 1 scale, blended toward identity by strength.
                val kneeEff = max(knee, isolationFactor * localMean[i])
                val scale = if (m > kneeEff) {
                    val full = shoulder(m, kneeEff, soft) / m
                    1.0 + strength * (full - 1.0)
                } else {
                    1.0
                }
                val nr = (y + cr * scale).roundToInt().coerceIn(0, 255)
                val ng = (y + cg * scale).roundToInt().coerceIn(0, 255)
                val nb = (y + cb * scale).roundToInt().coerceIn(0, 255)
                out[i] = (0xFF shl 24) or (nr shl 16) or (ng shl 8) or nb
            }
        }
        return Frame(frame.width, frame.height, out, frame.timestampMillis)
    }

    /**
     * The soft chroma-magnitude shoulder at the GLOBAL knee: [shoulder] with
     * [ChromaRollOffParams.knee]/[ChromaRollOffParams.soft] -- the ungated reference curve
     * an isolated spot compresses along. Exposed for direct testing of the shoulder math.
     */
    internal fun shoulder(mag: Double, params: ChromaRollOffParams): Double =
        shoulder(mag, params.knee, params.soft)

    /**
     * The soft chroma-magnitude shoulder: the identity for [mag] at/below [knee], then
     * `knee + (mag - knee) / (1 + (mag - knee) / soft)` above it -- continuous with unit
     * slope at the knee and asymptotic to `knee + soft`. Monotonically increasing. In the
     * gated apply loop [knee] is the per-pixel EFFECTIVE knee.
     */
    internal fun shoulder(mag: Double, knee: Double, soft: Double): Double {
        if (mag <= knee) return mag
        val over = mag - knee
        return knee + over / (1.0 + over / soft)
    }
}
