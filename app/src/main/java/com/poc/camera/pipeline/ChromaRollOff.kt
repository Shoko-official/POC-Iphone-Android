package com.poc.camera.pipeline

import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Parameters for [ChromaRollOff], productised from the validated local pair-tuning
 * (issue #97): a real backlit Pixel RAW whose lips glowed red after a shadow lift +
 * saturation, escaping the skin cluster because their chroma was too saturated for the
 * skin ellipse. The roll-off is a soft shoulder on the chroma MAGNITUDE that compresses
 * only that isolated extreme chroma back toward the rest of the image.
 *
 *  - [knee] the chroma magnitude at/below which the roll-off is the exact identity; a
 *    pixel whose `sqrt(cr^2 + cb^2)` is at or under the knee is passed through untouched,
 *    so normal saturation is never compressed.
 *  - [soft] the asymptotic headroom of the shoulder above the knee: chroma magnitude is
 *    mapped to `knee + (mag - knee) / (1 + (mag - knee) / soft)`, so it approaches
 *    `knee + soft` as the input magnitude grows -- a smaller [soft] compresses harder.
 *  - [strength] the blend in [0, 1] between the ORIGINAL chroma (0, a bit-exact
 *    passthrough) and the fully compressed chroma (1). It lets [FinishingParams.chromaRollOff]
 *    dial the effect between off and full without changing the tuned knee/soft curve.
 *
 * The winning params on the real pair were [knee] 30 and [soft] 18 at full [strength].
 * Deterministic, no Android dependencies.
 */
data class ChromaRollOffParams(
    val knee: Double = 30.0,
    val soft: Double = 18.0,
    val strength: Double = 1.0,
) {
    init {
        require(knee >= 0.0) { "knee must be >= 0" }
        require(soft > 0.0) { "soft must be > 0" }
        require(strength in 0.0..1.0) { "strength must be in [0, 1]" }
    }

    companion object {
        /** The parameters that won the local pair-tuning against the iPhone reference. */
        val DEFAULT = ChromaRollOffParams()
    }
}

/**
 * Soft chroma-magnitude roll-off for the finishing tail (issue #97).
 *
 * Each pixel is split into a Rec. 601 luma and opponent chroma (`cr = R - Y`, `cb = B - Y`,
 * `cg = G - Y`); the chroma MAGNITUDE `sqrt(cr^2 + cb^2)` is passed through a soft shoulder
 * ([shoulder]) that is the identity at/below [ChromaRollOffParams.knee] and compresses
 * everything above it toward the `knee + soft` asymptote. All three chroma components are
 * scaled by the resulting ratio and recombined with the (unchanged) luma, so the operator
 * moves colour magnitude only -- hue and luma are preserved. The compressed scale is blended
 * toward identity by [ChromaRollOffParams.strength].
 *
 * It exists to catch post-saturation runaway chroma, so [FinishingPipeline] runs it AFTER
 * [Saturation] and [Contrast], as the last colour operator before the final opaque pass: a
 * region the earlier stages pushed to an isolated extreme chroma (e.g. lips that escaped the
 * skin-protection ellipse and took the full saturation boost) is settled back toward the
 * rest of the frame, while every pixel whose magnitude the boost left at or under the knee
 * is bit-exactly untouched.
 *
 * ## Determinism / parallelism
 *
 * The apply loop is element-wise -- each output pixel depends only on its own input -- so it
 * runs under the [PipelineParallel] row-parallel contract and is BYTE-identical across chunk
 * counts. It has no spatial support and derives no global statistic, so the tiled finish
 * ([TiledFinishing]) is trivially seam-free (a tile core sees exactly what the whole frame
 * would). At [ChromaRollOffParams.strength] <= 0 the input frame is returned unchanged
 * (same reference, bit-exact passthrough). A pixel with chroma magnitude at or under the
 * knee reconstructs to its exact input (the scale is exactly 1 and `Y + (channel - Y)`
 * rounds back to the channel), so below-knee content is also bit-exact even at full strength.
 *
 * Pure Kotlin, deterministic, no Android dependencies. Mirrors the validated prototype.
 */
object ChromaRollOff {

    // Rec. 601 luma weights, matching Luminance / Saturation / BacklitRescue.
    private const val R_WEIGHT = 0.299
    private const val G_WEIGHT = 0.587
    private const val B_WEIGHT = 0.114

    /**
     * Returns [frame] with the chroma roll-off applied. At [ChromaRollOffParams.strength]
     * <= 0 the frame is returned unchanged (bit-exact passthrough); at 1 the full tuned
     * shoulder runs. Alpha is forced opaque on the result.
     *
     * @param chunkCount row-parallel chunk count for the element-wise pass;
     *   [PipelineParallel.SERIAL_CHUNKS] forces the serial reference path.
     */
    fun apply(
        frame: Frame,
        params: ChromaRollOffParams = ChromaRollOffParams.DEFAULT,
        chunkCount: Int = PipelineParallel.parallelism,
    ): Frame {
        if (params.strength <= 0.0) return frame
        val src = frame.argb
        val out = IntArray(src.size)
        val knee = params.knee
        val soft = params.soft
        val strength = params.strength
        // Per-pixel, element-wise: row-parallel and bit-identical across chunk counts.
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
                val mag = sqrt(cr * cr + cb * cb)
                // Below the knee the scale is exactly 1 (identity); above it the compressed
                // magnitude drives a < 1 scale, blended toward identity by strength.
                val scale = if (mag > knee) {
                    val full = shoulder(mag, params) / mag
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
     * The soft chroma-magnitude shoulder: the identity for [mag] at/below
     * [ChromaRollOffParams.knee], then `knee + (mag - knee) / (1 + (mag - knee) / soft)`
     * above it -- continuous with unit slope at the knee and asymptotic to `knee + soft`.
     * Monotonically increasing. Exposed for direct testing of the shoulder math.
     */
    internal fun shoulder(mag: Double, params: ChromaRollOffParams): Double {
        if (mag <= params.knee) return mag
        val over = mag - params.knee
        return params.knee + over / (1.0 + over / params.soft)
    }
}
