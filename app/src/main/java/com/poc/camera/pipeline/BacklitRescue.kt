package com.poc.camera.pipeline

import kotlin.math.exp
import kotlin.math.pow

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
 * The guided base ([GuidedFilter.selfGuided]) is already parallel under the same contract.
 * At [engagement] <= 0 the input frame is returned unchanged (bit-exact passthrough), so a
 * scene the detector reports as non-backlit is left completely untouched.
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
                out[i] = (0xFF shl 24) or (r.toInt() shl 16) or (g.toInt() shl 8) or bch.toInt()
            }
        }
        return Frame(width, height, out, frame.timestampMillis)
    }

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
