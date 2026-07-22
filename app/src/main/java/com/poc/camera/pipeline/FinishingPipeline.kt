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
 * value is an exact no-op there. Note it does NOT bound [backlitRescue]: lifting a backlit
 * face is the whole point of the rescue, and skin protection bounds STYLISTIC operators,
 * not exposure recovery (see [backlitRescue]).
 *
 * [backlitRescue] is the MASTER strength of the adaptive [BacklitRescue] exposure lift in
 * [0, 1] (0 = off). It is gated twice: this master strength is multiplied by the
 * [BacklitDetector] scene strength, so the lift only engages on a scene whose luma
 * histogram reads as backlit (dark subject under a bright background). DEFAULT keeps it OFF
 * (0.0): the clean-truth fidelity floors compare against a clean truth where any lift reads
 * as error, so the fidelity axis must not run it. [RENDITION] ships it at 1.0 with the
 * detector gating engagement, so a genuinely backlit capture is rescued while every
 * non-backlit scene is left bit-exactly untouched. Unlike the other stylistic strengths it
 * is deliberately NOT reduced by [skinProtection].
 *
 * [chromaRollOff] is the strength of the [ChromaRollOff] chroma-magnitude shoulder in
 * [0, 1] (0 = off). It runs LAST, after [Saturation] and [Contrast], to compress isolated
 * extreme chroma the earlier stages pushed past the roll-off knee (e.g. lips that escaped
 * the skin-protection ellipse and took the full saturation boost) back toward the rest of
 * the frame, while leaving normal saturation bit-exactly untouched. Like [backlitRescue] it
 * ships OFF in DEFAULT (0.0) and ON at 1.0 in [RENDITION]: the roll-off is a WHOLE-FRAME
 * shoulder with no spatial isolation, so on the clean-truth fidelity axis it would compress
 * the intentionally-saturated colorchart patches and read as error, pushing that scene's
 * chroma MAE past its committed floor (measured: colorchart MAE 4.12 -> ~6.4 at DEFAULT on).
 * The rendition axis stays consistent because [com.poc.camera.pipeline.quality.RenditionTargets]
 * derives its target from [RENDITION], so the target compresses the same chroma the output
 * does. See ChromaRollOffGoldenTest for the compression, hue-preservation and
 * normal-saturation-untouched proofs and the measured DEFAULT-decision evidence.
 *
 * [semanticRendering] is the strength of the [SemanticRendering] sky/foliage rendering in
 * [0, 1] (0 = off). It runs LAST of all colour stages, AFTER [ChromaRollOff]: the [SkyMask] /
 * [FoliageMask] priors drive region-targeted BOOSTS -- a bounded blue deepening + chroma-noise
 * smoothing inside sky, a bounded green enrichment + shadow lift inside foliage. It follows
 * the roll-off deliberately: the roll-off is a whole-frame chroma-magnitude SHOULDER that
 * compresses everything past its knee, and a blue sky sits above that knee, so running the
 * deepening before the roll-off would let the shoulder compress the deliberate boost straight
 * back out (measured: net sky Cb shift collapses to < 1 code). Placing the region boost after
 * the roll-off makes it the final, deliberate colour word on sky/foliage while the roll-off
 * still tames any isolated extreme chroma the earlier global stages produced. This is the
 * "semantic rendering" that separates an iPhone-class look from a global-only pipeline (skin
 * is already handled by [skinProtection]). Like
 * [backlitRescue] / [chromaRollOff] it ships OFF in DEFAULT (0.0) and ON at 1.0 in [RENDITION].
 * The masks read EXACTLY 0 on any grayscale content (no sky/foliage chroma), so it is a strict
 * no-op on every grayscale scene and the clean-truth fidelity floors on those scenes are
 * bit-unaffected. Its region boosts are ZERO outside the masks, so ground/skin/neutral content
 * is left bit-exactly untouched. On the colour scenes the masks fire and the boost applies,
 * bounded to <= 12 codes per channel; the rendition axis stays consistent because
 * [com.poc.camera.pipeline.quality.RenditionTargets] derives its target from [RENDITION], so
 * the target renders the same sky/foliage the output does. It is kept OFF in DEFAULT because
 * the clean-truth fidelity axis has no matching target (a deliberate deepening reads as error
 * against the clean truth). See SemanticRenderingGoldenTest for the chroma-noise-reduction,
 * bounded-deepening, hue-stability and mask false-positive proofs.
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
    val backlitRescue: Double = 0.0,
    val chromaRollOff: Double = 0.0,
    val semanticRendering: Double = 0.0,
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
         *
         * It additionally ships the adaptive [BacklitRescue] on at full master strength
         * ([backlitRescue] = 1.0). The [BacklitDetector] gates engagement, so a genuinely
         * backlit capture is rescued while every non-backlit scene (including every existing
         * rendition/fidelity golden scene) reads a detector strength of 0 and is left
         * bit-exactly untouched -- which is why enabling it here does not move any existing
         * floor. See BacklitRescueGoldenTest for the lift, halo and detector-quiet proofs.
         *
         * It also ships the [ChromaRollOff] shoulder on at full strength ([chromaRollOff] =
         * 1.0), catching the post-saturation runaway chroma the rendition look can produce
         * on an isolated extreme-chroma region. Unlike the backlit rescue this has no scene
         * gate, so it DOES act on the rendition golden scenes -- but the rendition axis is
         * self-consistent: [com.poc.camera.pipeline.quality.RenditionTargets] derives its
         * target from these same RENDITION params, so the target compresses exactly the
         * chroma the output does and the tracking floors hold. It is kept OFF in [DEFAULT]
         * because the clean-truth fidelity axis has no such matching target (see
         * [chromaRollOff]). See ChromaRollOffGoldenTest for the proofs.
         *
         * Finally it ships the [SemanticRendering] sky/foliage stage on at full strength
         * ([semanticRendering] = 1.0). Like the roll-off it has no scene gate, but the
         * [SkyMask] / [FoliageMask] priors are EXACTLY zero on grayscale content, so it is a
         * bit-exact no-op on the five grayscale rendition scenes and moves only "colorchart"
         * (whose blue/green patches fire the priors); [RenditionTargets] derives from these
         * same params so the target renders the identical sky/foliage boost and the tracking
         * floor holds. It is kept OFF in [DEFAULT] for the same reason as the roll-off -- the
         * clean-truth axis has no matching target (see [semanticRendering]). See
         * SemanticRenderingGoldenTest for the proofs.
         */
        val RENDITION = DEFAULT.copy(
            localContrast = REF_LOCAL_CONTRAST,
            detailEnhance = REF_DETAIL_ENHANCE,
            backlitRescue = 1.0,
            chromaRollOff = 1.0,
            semanticRendering = 1.0,
        )

        /**
         * Reference-matched rendition profile: the constants FITTED against a real
         * reference pair rather than hand-picked. It is [RENDITION] retuned on four global
         * stage strengths so its output tracks the measured look of a real iPhone HEIC
         * rendition of the same scene:
         *
         *  - [saturation] 1.08 -> 1.15: the reference rendition runs a visibly richer global
         *    colour than the restrained RENDITION baseline.
         *  - [chromaDenoise] 0.6 -> 1.0: the reference is chroma-clean even in noisy shadow /
         *    sky regions, so this profile leans fully on the luma-guided denoiser.
         *  - [shadowsLift] 0.12 -> 0.18: the reference opens the shadows more (matching NIGHT's
         *    toe), lifting the shadow luma percentiles toward the measured reference tone.
         *  - [skinProtection] 0.7 -> 0.85: with the stronger global saturation and shadow lift,
         *    skin is bounded harder so the richer look never over-cooks faces; the
         *    hue-preserving guarantees still hold at 0.85 (see ReferenceProfileGoldenTest).
         *
         * Everything else is inherited from [RENDITION] unchanged, including [detailEnhance]
         * ([REF_DETAIL_ENHANCE] = 0.5, already the fitted value), the adaptive [backlitRescue]
         * (1.0, detector-gated), the [chromaRollOff] shoulder (1.0) and [semanticRendering]
         * (1.0) -- the last two productised from the SAME fit (see [ChromaRollOff]).
         *
         * FITTED 2026-07-22 against a real Pixel-RAW / iPhone-HEIC pair on a severe backlit
         * portrait, the fitted config judged against the iPhone rendition. This is a
         * SINGLE-PAIR fit and is expected to be refined as more reference pairs arrive. The
         * measured iPhone style signature, documented for posterity: luma percentiles
         * p25=62 / p50=100 / p75=139, mean saturation 15.3, skin luma 134, skin R-B 52.
         *
         * It is a SHIPPING preset (wired to [com.poc.camera.settings.FinishingPreset.Natural]),
         * NOT a replacement for [RENDITION] on the rendition evaluation axis:
         * [com.poc.camera.pipeline.quality.RenditionTargets] stays anchored on [RENDITION], so
         * this profile does not perturb any rendition/fidelity floor. Its own behaviour is
         * gated by ReferenceProfileGoldenTest (saturation/chroma-noise/shadow direction vs
         * RENDITION, skin fairness at 0.85, determinism).
         */
        val REFERENCE = RENDITION.copy(
            saturation = 1.15,
            chromaDenoise = 1.0,
            shadowsLift = 0.18,
            skinProtection = 0.85,
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
 * Ordering rationale: [BacklitRescue] runs FIRST of all, before even [WhiteBalance], and
 * on the whole frame ahead of the tiled/whole-frame split. It deliberately RESHAPES the
 * luma distribution (lifting a dark subject toward the midtones), and every downstream
 * stage that derives a global statistic from the frame -- the [WhiteBalance] gray-world /
 * highlight cues, the [DetailEnhancer] coring knee -- must see the post-lift distribution,
 * not the pre-lift one, or those stats would be estimated from an image the user never
 * sees. Running it before the split also means the tiled path finishes the already-rescued
 * frame, so its per-tile stats stay consistent with the whole-frame path. It is gated by
 * the [BacklitDetector], so on a non-backlit scene it is a bit-exact passthrough and the
 * rest of the chain is byte-for-byte unchanged.
 *
 * Then [WhiteBalance] runs, on the (rescued) merged data, so the colour cast is neutralised
 * before any later stage computes statistics from the colour.
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
 * [ChromaRollOff] runs after [Saturation] and [Contrast]: it exists to catch the runaway
 * chroma those stages can leave on an isolated extreme-chroma region, so it must see the
 * post-saturation colour it is there to tame, not an earlier state. It is a pure per-pixel op
 * with no global statistic, so its position does not perturb any upstream stats.
 *
 * [SemanticRendering] runs LAST of all, after [ChromaRollOff], as the final colour word before
 * the opaque pass. It applies bounded, region-targeted sky/foliage boosts driven by the
 * [SkyMask] / [FoliageMask] priors. It follows the roll-off deliberately: the roll-off shoulder
 * would otherwise compress the deliberate sky deepening (a blue sky sits above the roll-off
 * knee) straight back out, so the region boost is applied after the whole-frame shoulder has
 * done its taming. Its masks are computed once on the denoised frame (shared with the tiled
 * path); the stage itself is windowed-local (a small sky chroma smooth) plus per-pixel.
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
        // Backlit rescue runs FIRST, on the whole frame, ahead of the tiled/whole-frame
        // split so both paths finish the already-rescued frame with consistent global stats
        // (see the class-level ordering rationale). On a non-backlit scene this is a bit-exact
        // passthrough (rescued === frame), so the untouched-scene contract holds.
        val rescued = applyBacklitRescue(frame, params)
        if (rescued.width.toLong() * rescued.height.toLong() >= TILED_THRESHOLD_PIXELS) {
            return TiledFinishing.apply(rescued, params)
        }
        val balanced = if (params.whiteBalance > 0.0) {
            WhiteBalance.apply(rescued, params.whiteBalance)
        } else {
            rescued
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
        // Semantic sky/foliage masks, also computed ONCE on the denoised chroma and applied at
        // the tail. Null when the stage is off. The whole-frame path spans the whole image, so
        // the sky position prior uses rowOffset 0 over the full height.
        val semanticMasks = semanticMasks(denoised, params, rowOffset = 0, imageHeight = denoised.height)
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
        val rolledOff = applyChromaRollOff(contrasted, params)
        val semanticRendered = applySemanticRendering(rolledOff, semanticMasks, params)
        return forceOpaque(semanticRendered)
    }

    /**
     * Applies the [ChromaRollOff] chroma-magnitude shoulder to [frame] when
     * [FinishingParams.chromaRollOff] is on, at that master strength. Returns [frame]
     * UNCHANGED (same reference, bit-exact) when the strength is 0. It carries no global
     * statistic and has no spatial support, so it needs nothing from [FinishingStats] and
     * is seam-free under [TiledFinishing] by construction.
     */
    internal fun applyChromaRollOff(frame: Frame, params: FinishingParams): Frame {
        if (params.chromaRollOff <= 0.0) return frame
        return ChromaRollOff.apply(frame, ChromaRollOffParams.DEFAULT.copy(strength = params.chromaRollOff))
    }

    /** The precomputed sky/foliage masks feeding [SemanticRendering], shared by the whole-frame
     *  and tiled paths so both build the same region boosts. */
    internal class SemanticMasks(val sky: DoubleArray, val foliage: DoubleArray)

    /**
     * The sky/foliage likelihood masks for [denoisedFrame] under [params], or null when
     * [FinishingParams.semanticRendering] is 0 (the stage is disabled -> the tail takes its
     * original path unchanged). Both are computed on the DENOISED frame (cleaner chroma),
     * exactly as [skinModulation] is, so the whole-frame and tiled paths build the same masks.
     * [rowOffset] and [imageHeight] locate the frame within the full image for the [SkyMask]
     * position prior: the whole-frame path passes (0, full height); a [TiledFinishing] tile
     * passes its top row and the full-image height so its sky position prior matches.
     */
    internal fun semanticMasks(
        denoisedFrame: Frame,
        params: FinishingParams,
        rowOffset: Int,
        imageHeight: Int,
    ): SemanticMasks? {
        if (params.semanticRendering <= 0.0) return null
        val sky = SkyMask.compute(denoisedFrame, rowOffset = rowOffset, imageHeight = imageHeight)
        val foliage = FoliageMask.compute(denoisedFrame)
        return SemanticMasks(sky, foliage)
    }

    /**
     * Applies the [SemanticRendering] sky/foliage stage to [frame] with the precomputed
     * [masks] at [FinishingParams.semanticRendering] strength. Returns [frame] UNCHANGED (same
     * reference, bit-exact) when the stage is off (null masks or zero strength). Applied
     * identically by [FinishingPipeline.apply] and [TiledFinishing.finishRegion].
     */
    internal fun applySemanticRendering(frame: Frame, masks: SemanticMasks?, params: FinishingParams): Frame {
        if (masks == null || params.semanticRendering <= 0.0) return frame
        return SemanticRendering.apply(
            frame,
            masks.sky,
            masks.foliage,
            SemanticRenderingParams.DEFAULT.copy(strength = params.semanticRendering),
        )
    }

    /**
     * Applies the adaptive [BacklitRescue] to [frame] when [FinishingParams.backlitRescue] is
     * on, gating engagement with the [BacklitDetector] scene strength. Returns [frame]
     * UNCHANGED (same reference, bit-exact) when the master strength is 0 or the detector
     * reports the scene is not backlit, so a non-backlit capture is left completely
     * untouched. The effective engagement is `backlitRescue * detector.strength`, so the
     * lift is dialled by both the master strength and how strongly the scene reads as
     * backlit.
     *
     * NOTE ON LARGE FRAMES: the rescue runs whole-frame (the detector is a global histogram
     * and the guided base is a global edge-preserving smooth), so on a native-resolution
     * capture that the detector DOES engage it allocates the guided filter's luma-plane
     * intermediates over the full pixel count -- outside the bounded-memory tiling that
     * finishes the rest of the chain. This only happens on a genuinely backlit capture under
     * a rescue-enabled profile (DEFAULT keeps it off), so it is not on the common path;
     * tiling the rescue's guided base for very large backlit captures is left as future work.
     */
    internal fun applyBacklitRescue(frame: Frame, params: FinishingParams): Frame {
        if (params.backlitRescue <= 0.0) return frame
        val strength = BacklitDetector.detect(frame).strength
        val engagement = params.backlitRescue * strength
        if (engagement <= 0.0) return frame
        return BacklitRescue.apply(frame, BacklitRescueParams.DEFAULT, engagement)
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
