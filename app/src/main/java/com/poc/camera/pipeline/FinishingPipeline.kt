package com.poc.camera.pipeline

/**
 * Tunable parameters for [FinishingPipeline].
 *
 * [shadowsLift] and [highlightRolloff] drive the [ToneCurve] (both in [0, 1]),
 * [saturation] and [contrast] are multiplicative factors (1.0 = no change).
 */
data class FinishingParams(
    val shadowsLift: Double,
    val highlightRolloff: Double,
    val saturation: Double,
    val contrast: Double,
) {
    companion object {
        /**
         * Restrained POC default: a mild S-curve with a gentle saturation and
         * contrast lift. These values are hand-tuned defaults for this
         * proof-of-concept, not a colour-calibrated look.
         */
        val DEFAULT = FinishingParams(
            shadowsLift = 0.12,
            highlightRolloff = 0.12,
            saturation = 1.08,
            contrast = 1.05,
        )
    }
}

/**
 * Applies finishing to a merged burst frame: tone curve, then luma-preserving
 * saturation, then pivot contrast. Returns a new [Frame] with the source
 * dimensions and timestamp and alpha forced opaque.
 *
 * Deterministic and free of Android dependencies (no randomness, no clock).
 */
object FinishingPipeline {

    fun apply(frame: Frame, params: FinishingParams = FinishingParams.DEFAULT): Frame {
        val toned = ToneCurve(params.shadowsLift, params.highlightRolloff).apply(frame)
        val saturated = Saturation.apply(toned, params.saturation)
        val contrasted = Contrast.apply(saturated, params.contrast)
        return forceOpaque(contrasted)
    }

    private fun forceOpaque(frame: Frame): Frame {
        val src = frame.argb
        val out = IntArray(src.size) { (0xFF shl 24) or (src[it] and 0x00FFFFFF) }
        return Frame(frame.width, frame.height, out, frame.timestampMillis)
    }
}
