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
 *
 * [detailEnhance] is the strength of the [DetailEnhancer] pass in [0, 1] (0 disables
 * it). The strength linearly scales the sharpen gain from an identity (0) up to the
 * [DetailParams] default gain (1), so a modest value sharpens gently; radius, coring
 * and the overshoot allowance stay at their defaults so only the effect magnitude
 * scales.
 *
 * [whiteBalance] is the strength of the [WhiteBalance] pass in [0, 1] (0 disables it),
 * blending the bounded auto white-balance gains toward identity.
 *
 * [skinProtection] is the strength of the [SkinMask]-driven skin-tone protection in [0, 1]
 * (0 disables it = current behaviour). Where the mask fires it scales DOWN the effective
 * strength of the [Saturation], [LocalToneMapper] and [DetailEnhancer] passes -- bounding
 * saturation, local contrast and sharpening inside skin so the generic rendition operators
 * cannot over-cook skin. It only ever REDUCES those operators; it never lightens, darkens
 * or shifts skin hue. On a grayscale frame the mask is all-zero, so any [skinProtection]
 * value is an exact no-op there.
 */
data class FinishingParams(
    val shadowsLift: Double,
    val highlightRolloff: Double,
    val saturation: Double,
    val contrast: Double,
    val localContrast: Double = 0.0,
    val chromaDenoise: Double = 0.0,
    val detailEnhance: Double = 0.0,
    val whiteBalance: Double = 0.0,
    val skinProtection: Double = 0.0,
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
         * [detailEnhance] ships on at a conservative strength for the same reason as
         * [localContrast]: sharpening a merged step that still carries residual noise
         * deviates from the clean truth, so the "edges" scene is the sensitive one. At
         * DEFAULT its MAE rises ~1.7% (1.918 -> 1.951), inside the committed floor's 2%
         * tolerance, so every quality-harness floor stays green; a higher strength
         * sharpens more but pushes edges MAE past its ceiling. The full strength range
         * (up to 1.0, scaling to the [DetailParams] default gain) is available for real
         * captures, where perceived sharpness matters more than PSNR against a synthetic
         * clean truth.
         *
         * [whiteBalance] ships on at a strong-but-bounded strength. On a colour-cast
         * capture it recovers most of the cast (large MAE/PSNR gains vs. the uncast
         * truth); on an already-neutral scene the estimator returns near-identity gains,
         * so it barely moves the image and every neutral-scene floor stays green. Its
         * effect is bounded twice over -- the gains are clamped to [WhiteBalance]'s
         * [WhiteBalance.DEFAULT_MAX_GAIN] range and then scaled by this strength -- so it
         * cannot over-correct. See AwbGoldenRegressionTest for the cast-correction and
         * neutrality proofs behind this default.
         *
         * [skinProtection] ships on at 0.7: strong enough to visibly bound saturation,
         * local contrast and sharpening inside skin regions, while still letting the
         * operators do most of their work everywhere else. It is a strict no-op on every
         * grayscale scene (the skin mask is all-zero without skin chroma), so the fidelity
         * floors on those scenes are bit-unaffected; only the colour scenes with skin
         * chroma shift, and only within tolerance. See SkinProtectionGoldenTest for the
         * hue-shift, sharpening and cross-skin-tone fairness proofs behind this default.
         */
        val DEFAULT = FinishingParams(
            shadowsLift = 0.12,
            highlightRolloff = 0.12,
            saturation = 1.08,
            contrast = 1.05,
            localContrast = 0.03,
            chromaDenoise = 0.6,
            detailEnhance = 0.08,
            whiteBalance = 0.8,
            skinProtection = 0.7,
        )

        /**
         * Reference local-contrast strength for the rendition axis. Visibly stronger
         * than [DEFAULT]'s timid 0.03: it drives a real local shadow lift / highlight
         * taming that reads as a deliberate look rather than a no-op. It is the strength
         * the rendition TARGET (`RenditionTargets`) bakes into the ideal look and that
         * [RENDITION] renders at, so the two share a single source of truth.
         */
        const val REF_LOCAL_CONTRAST = 0.35

        /**
         * Reference detail-enhance strength for the rendition axis. Visibly stronger
         * than [DEFAULT]'s timid 0.08: it produces a clearly perceptible sharpen instead
         * of the near-no-op the clean-truth fidelity floors force onto [DEFAULT]. Paired
         * with [REF_LOCAL_CONTRAST] as the reference look for `RenditionTargets` and
         * [RENDITION].
         */
        const val REF_DETAIL_ENHANCE = 0.5

        /**
         * Rendition profile: identical to [DEFAULT] except that [localContrast] and
         * [detailEnhance] run at their reference strengths ([REF_LOCAL_CONTRAST],
         * [REF_DETAIL_ENHANCE]) instead of the timid values the clean-truth fidelity
         * floors pin [DEFAULT] to. Same global tone/saturation/contrast plus the same
         * white-balance and chroma-denoise strengths.
         *
         * This encodes the deliberate look a future "Natural+" or preset system ships
         * (issue #46). It is evaluated on a SEPARATE axis: `RenditionGoldenRegressionTest`
         * scores the full pipeline run with these params against the rendition TARGET
         * (`RenditionTargets`), not against the clean truth, so the stronger rendition is
         * measured as tracking the intended look rather than as fidelity error. The
         * existing clean-truth golden suites keep running [DEFAULT] and stay untouched.
         * No camera/UI stage selects this profile yet; wiring it into a preset is issue #46.
         */
        val RENDITION = DEFAULT.copy(
            localContrast = REF_LOCAL_CONTRAST,
            detailEnhance = REF_DETAIL_ENHANCE,
        )

        /**
         * Night finishing profile, paired with [NightMergeParams] by [NightPipeline].
         * Identical to [DEFAULT] except it retunes three stages for a strongly-noisy
         * low-light merge, each justified empirically against the "nightscene" golden
         * (see NightGoldenRegressionTest):
         *
         *  - [chromaDenoise] 0.6 -> 0.8: even after a 12-frame stack a high-gain capture
         *    keeps visible chroma speckle, so the night look leans harder on the
         *    luma-guided denoiser. The stronger pass measurably lowers MAE against the
         *    clean night truth.
         *  - [shadowsLift] 0.12 -> 0.18: night content lives in the bottom of the range,
         *    so a deeper toe lift is what makes the merged shadow detail visible. Like
         *    DEFAULT's detail/local-contrast, a lift is "error" against a dark clean truth,
         *    so it is gated: 0.18 is a visibly stronger toe than DEFAULT yet the 12-frame
         *    night merge still clears the standard 6-frame pipeline on the night golden by
         *    a wide margin (MAE ~2.48 vs ~3.50).
         *  - [detailEnhance] 0.08 -> 0.03: at night, residual noise dominates fine
         *    detail, so sharpening mostly amplifies grain. Backing the sharpen off keeps
         *    the denoised result clean rather than re-injecting noise as false texture.
         *
         * Global saturation/contrast/white-balance stay at DEFAULT.
         */
        val NIGHT = DEFAULT.copy(
            shadowsLift = 0.18,
            chromaDenoise = 0.8,
            detailEnhance = 0.03,
        )
    }
}

/**
 * Applies finishing to a merged burst frame: local tone mapping, then global tone
 * curve, then luma-preserving saturation, then pivot contrast. Returns a new [Frame]
 * with the source dimensions and timestamp and alpha forced opaque.
 *
 * Ordering rationale: [WhiteBalance] runs FIRST of all, on the raw merged data, so the
 * colour cast is neutralised before any later stage computes statistics from the colour.
 * In particular it precedes [ChromaDenoiser]: chroma denoise reasons in an opponent
 * (luma/chroma) space, and a cast biases the chroma planes, so correcting the cast first
 * lets the denoiser see the true, cast-free chroma. [ChromaDenoiser] then runs, on the
 * white-balanced data, so chroma speckle is cleaned before any rendition stage can
 * amplify it -- in particular before
 * [Saturation] boosts colour and before [LocalToneMapper]'s per-channel luma ratio
 * redistributes it. [LocalToneMapper] then runs on the denoised, still
 * scene-referred-ish data, so its guided base/detail split sees the untouched local
 * contrast and its shadow lift / highlight taming operate before the global curve
 * redistributes tones. [DetailEnhancer] then sharpens AFTER local tone mapping but
 * BEFORE the global [ToneCurve]: the detail is still scene-referred, so it is
 * sharpened before the curve compresses it (sharpening after the curve would boost
 * detail unevenly, exaggerated in the toe/shoulder the curve steepens). The global
 * [ToneCurve] then runs last of the tonal stages, shaping the overall response after
 * local adjustments are baked in; putting the global curve first would feed the guided
 * filter an already-compressed signal and blunt the local pass. Saturation and
 * contrast follow, as pure per-pixel colour finishing.
 *
 * Deterministic and free of Android dependencies (no randomness, no clock).
 */
object FinishingPipeline {

    /**
     * Pixel count at or above which [apply] routes through [TiledFinishing] instead of
     * running the whole frame through the float-heavy stages at once. ~9 MP sits above
     * every quality-harness fixture (the largest is 256x256) and above the old 8 MP
     * decode bound, so the golden suites and any sub-9 MP capture keep taking the
     * untouched whole-frame path, while native 12 MP+ captures -- whose full-resolution
     * guided-filter intermediates would otherwise peak near a gigabyte of transient
     * `DoubleArray`s -- are finished in bounded-memory tiles. See [TiledFinishing].
     */
    const val TILED_THRESHOLD_PIXELS: Long = 9_000_000L

    fun apply(frame: Frame, params: FinishingParams = FinishingParams.DEFAULT): Frame {
        if (frame.width.toLong() * frame.height.toLong() >= TILED_THRESHOLD_PIXELS) {
            return TiledFinishing.apply(frame, params)
        }
        val balanced = if (params.whiteBalance > 0.0) {
            WhiteBalance.apply(frame, params.whiteBalance)
        } else {
            frame
        }
        val denoised = if (params.chromaDenoise > 0.0) {
            ChromaDenoiser.apply(balanced, params.chromaDenoise)
        } else {
            balanced
        }
        // Skin-protection modulation, computed ONCE on the cleaned (post-denoise) chroma and
        // shared by the three operators it bounds. Null when protection is off, so those
        // stages take their original path unchanged.
        val skinModulation = skinModulation(denoised, params)
        val locallyMapped = if (params.localContrast > 0.0) {
            LocalToneMapper.apply(denoised, localToneParams(params.localContrast), skinModulation)
        } else {
            denoised
        }
        val enhanced = if (params.detailEnhance > 0.0) {
            DetailEnhancer.apply(locallyMapped, detailParams(params.detailEnhance), skinModulation)
        } else {
            locallyMapped
        }
        val toned = ToneCurve(params.shadowsLift, params.highlightRolloff).apply(enhanced)
        val saturated = Saturation.apply(toned, params.saturation, skinModulation)
        val contrasted = Contrast.apply(saturated, params.contrast)
        return forceOpaque(contrasted)
    }

    /**
     * The per-pixel skin-protection modulation plane for [denoisedFrame] under [params], or
     * null when [FinishingParams.skinProtection] is 0 (protection disabled -> the modulated
     * stages take their original path). Each entry is `1 - skinProtection * mask`, so it is
     * 1 (full operator effect) off skin and drops to `1 - skinProtection` (bounded effect)
     * where the [SkinMask] fires. The mask is computed on the DENOISED frame -- cleaner
     * chroma -- exactly as [FinishingPipeline.apply] and [TiledFinishing.finishRegion] both
     * do, so the whole-frame and tiled paths build the same plane.
     */
    internal fun skinModulation(denoisedFrame: Frame, params: FinishingParams): FloatArray? {
        if (params.skinProtection <= 0.0) return null
        val mask = SkinMask.compute(denoisedFrame)
        val strength = params.skinProtection
        return FloatArray(mask.size) { (1.0 - strength * mask[it]).toFloat() }
    }

    /**
     * Maps a [strength] in [0, 1] to [LocalToneParams], interpolating base
     * compression and detail gain from an identity pass (0) to the params defaults
     * (1). Radius and eps stay at their defaults so only the effect magnitude scales.
     */
    internal fun localToneParams(strength: Double): LocalToneParams {
        val s = strength.coerceIn(0.0, 1.0)
        return LocalToneParams(
            baseCompression = s * LocalToneParams.DEFAULT_BASE_COMPRESSION,
            detailGain = 1.0 + s * (LocalToneParams.DEFAULT_DETAIL_GAIN - 1.0),
        )
    }

    /**
     * Maps a [strength] in [0, 1] to [DetailParams], scaling the sharpen gain from an
     * identity pass (0) to the params default gain (1). Radius, eps, coring factor and
     * overshoot allowance stay at their defaults so only the effect magnitude scales.
     */
    internal fun detailParams(strength: Double): DetailParams {
        val s = strength.coerceIn(0.0, 1.0)
        return DetailParams(gain = s * DetailParams.DEFAULT_GAIN)
    }

    internal fun forceOpaque(frame: Frame): Frame {
        val src = frame.argb
        val out = IntArray(src.size) { (0xFF shl 24) or (src[it] and 0x00FFFFFF) }
        return Frame(frame.width, frame.height, out, frame.timestampMillis)
    }
}
