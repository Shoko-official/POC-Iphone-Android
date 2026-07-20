package com.poc.camera.pipeline

/**
 * Tunable parameters for [FinishingPipeline].
 *
 * [shadowsLift] and [highlightRolloff] drive the [ToneCurve] (both in [0, 1]),
 * [saturation] and [contrast] are multiplicative factors (1.0 = no change), and
 * [localContrast] is the strength of the [LocalToneMapper] pass in [0, 1] (0
 * disables it). The strength linearly scales the local base compression and detail
 * gain from an identity (0) up to [LocalToneParams] defaults (1), so a modest value
 * gives a gentle local lift without over-cooking scene-referred detail.
 *
 * [chromaDenoise] is the strength of the luma-guided [ChromaDenoiser] pass in [0, 1]
 * (0 disables it), blending filtered chroma over the original.
 */
data class FinishingParams(
    val shadowsLift: Double,
    val highlightRolloff: Double,
    val saturation: Double,
    val contrast: Double,
    val localContrast: Double = 0.0,
    val chromaDenoise: Double = 0.0,
) {
    companion object {
        /**
         * Restrained POC default: a mild S-curve with a gentle saturation and
         * contrast lift, plus a conservative local-contrast pass on. These values
         * are hand-tuned defaults for this proof-of-concept, not a colour-calibrated
         * look. [localContrast] is kept modest so every quality-harness scene
         * (including pure gradients, where any local contrast deviates from the ideal
         * ramp) stays within its committed regression floor. [chromaDenoise] ships on
         * at a modest-but-effective strength, tuned against the colorchart golden so it
         * clearly improves chroma metrics without violating any existing floor.
         */
        val DEFAULT = FinishingParams(
            shadowsLift = 0.12,
            highlightRolloff = 0.12,
            saturation = 1.08,
            contrast = 1.05,
            localContrast = 0.03,
            chromaDenoise = 0.6,
        )
    }
}

/**
 * Applies finishing to a merged burst frame: local tone mapping, then global tone
 * curve, then luma-preserving saturation, then pivot contrast. Returns a new [Frame]
 * with the source dimensions and timestamp and alpha forced opaque.
 *
 * Ordering rationale: [ChromaDenoiser] runs FIRST, on the merged data, so chroma
 * speckle is cleaned before any rendition stage can amplify it -- in particular before
 * [Saturation] boosts colour and before [LocalToneMapper]'s per-channel luma ratio
 * redistributes it. [LocalToneMapper] then runs on the denoised, still
 * scene-referred-ish data, so its guided base/detail split sees the untouched local
 * contrast and its shadow lift / highlight taming operate before the global curve
 * redistributes tones. The global [ToneCurve] then runs last of the tonal stages, shaping the
 * overall response after local adjustments are baked in; putting the global curve
 * first would feed the guided filter an already-compressed signal and blunt the local
 * pass. Saturation and contrast follow, as pure per-pixel colour finishing.
 *
 * Deterministic and free of Android dependencies (no randomness, no clock).
 */
object FinishingPipeline {

    fun apply(frame: Frame, params: FinishingParams = FinishingParams.DEFAULT): Frame {
        val denoised = if (params.chromaDenoise > 0.0) {
            ChromaDenoiser.apply(frame, params.chromaDenoise)
        } else {
            frame
        }
        val locallyMapped = if (params.localContrast > 0.0) {
            LocalToneMapper.apply(denoised, localToneParams(params.localContrast))
        } else {
            denoised
        }
        val toned = ToneCurve(params.shadowsLift, params.highlightRolloff).apply(locallyMapped)
        val saturated = Saturation.apply(toned, params.saturation)
        val contrasted = Contrast.apply(saturated, params.contrast)
        return forceOpaque(contrasted)
    }

    /**
     * Maps a [strength] in [0, 1] to [LocalToneParams], interpolating base
     * compression and detail gain from an identity pass (0) to the params defaults
     * (1). Radius and eps stay at their defaults so only the effect magnitude scales.
     */
    private fun localToneParams(strength: Double): LocalToneParams {
        val s = strength.coerceIn(0.0, 1.0)
        return LocalToneParams(
            baseCompression = s * LocalToneParams.DEFAULT_BASE_COMPRESSION,
            detailGain = 1.0 + s * (LocalToneParams.DEFAULT_DETAIL_GAIN - 1.0),
        )
    }

    private fun forceOpaque(frame: Frame): Frame {
        val src = frame.argb
        val out = IntArray(src.size) { (0xFF shl 24) or (src[it] and 0x00FFFFFF) }
        return Frame(frame.width, frame.height, out, frame.timestampMillis)
    }
}
