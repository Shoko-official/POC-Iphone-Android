package com.poc.camera.pipeline

/**
 * Pure, histogram-only decision on whether a scene is BACKLIT and, if so, how strongly.
 * It reads nothing but the luma distribution of a single frame and returns a continuous
 * [BacklitDecision.strength] in [0, 1], so [BacklitRescue] can be engaged proportionally
 * (a smooth ramp) rather than switched on by a brittle boolean.
 *
 * ## The backlit signature (three criteria)
 *
 * A backlit scene -- a subject a couple of stops under a much brighter background -- leaves
 * a distinctive BIMODAL luma histogram: a populated shadow mode (the subject), a populated
 * highlight mode (the background), and a comparatively empty middle (the "valley"). The
 * three criteria test exactly that:
 *
 *  1. **Dark mass** -- fraction of pixels in the RECOVERABLE-shadow band
 *     [[SHADOW_FLOOR], [LUMA_LOW]) is at least [DARK_FRACTION]. The band is FLOORED at
 *     [SHADOW_FLOOR] on purpose (see below).
 *  2. **Bright mass** -- fraction of pixels above [LUMA_HIGH] is at least [BRIGHT_FRACTION].
 *  3. **Valley** -- fraction of pixels in the midtone band [[MID_LOW], [MID_HIGH]] is at
 *     most [MID_FRACTION_MAX] (the middle is comparatively empty).
 *
 * Each criterion contributes a smoothstepped margin (how comfortably it holds past its
 * threshold, ramped over [DARK_RAMP] / [BRIGHT_RAMP] / [VALLEY_RAMP]); the strength is the
 * MIN of the three, so ALL must hold for the scene to read as backlit and the weakest one
 * caps the strength. When any criterion sits at or below its threshold its margin is 0 and
 * the strength is exactly 0.0 -- a genuinely non-backlit scene is bit-exactly untouched.
 *
 * ## False-positive protection and the shadow floor
 *
 * The bright-mass requirement is what protects a legitimately dark, low-key scene: a night
 * or deep-shadow frame has the dark mass and the empty middle but NO bright mass, so
 * criterion 2 fails and the strength is 0 -- the detector does not lift a scene that is
 * meant to be dark.
 *
 * The shadow FLOOR ([SHADOW_FLOOR]) is what separates a real backlit subject from a
 * high-contrast scene whose shadows are crushed to near-black (deep-shadow-beside-clipped-
 * highlight test charts share the raw bimodal shape). A recoverable backlit subject sits a
 * couple of stops down (luma ~25-55), NOT crushed to black; pixels below [SHADOW_FLOOR]
 * carry no recoverable detail, so counting them as "dark mass" would misfire on crushed
 * high-contrast content. Restricting the dark band to the recoverable range keeps the
 * detector quiet on those scenes while still firing on a true backlit subject. This is a
 * deliberate tightening of the plain "below [LUMA_LOW]" criterion, not a re-baseline.
 *
 * ## Known limitation (measured on a real reference, issue #145)
 *
 * Because the detector is HISTOGRAM-ONLY, it cannot distinguish a backlit *subject* from a
 * high-contrast *scene* by shape alone -- they share the bimodal signature, and the sole
 * thing separating them is [SHADOW_FLOOR]. On a real backlit Pixel frame (a face under a
 * sunlit background) whose subject shadows the RAW development crushed BELOW [SHADOW_FLOOR]
 * (measured: only 13.6% of pixels landed in the recoverable [24,60) band, under the 0.25
 * [DARK_FRACTION] threshold), the detector reads strength 0 and [BacklitRescue] never
 * engages -- so the shipped Natural profile leaves that face darker than the tuned target.
 * Lowering [SHADOW_FLOOR] to catch it would re-admit crushed high-contrast blacks (the exact
 * false positive the floor exists to block), so a histogram-only detector cannot fix this
 * without regressing that gate. The real resolution is a SPATIAL / subject-aware prior (is
 * the dark mass a coherent region, not scattered crushed blacks?), which needs real backlit
 * captures to validate rather than the single synthetic golden and one RAW development
 * available now. Tracked in issue #145; do not naively re-tune the thresholds against one
 * frame.
 *
 * Deterministic, pure Kotlin, no Android dependencies. The histogram is a whole-frame
 * reduction, so it runs serially (it is a single O(pixels) pass and never a hot spot).
 */
object BacklitDetector {

    /**
     * Lower edge of the recoverable-shadow band. Pixels darker than this are crushed
     * shadow with no recoverable detail and are NOT counted as backlit dark mass, which is
     * what keeps crushed high-contrast scenes out of the detector.
     */
    const val SHADOW_FLOOR = 24.0

    /** Upper edge of the dark (recoverable-shadow) band. */
    const val LUMA_LOW = 60.0

    /** Threshold above which a pixel counts toward the bright (background) mass. */
    const val LUMA_HIGH = 160.0

    /** Lower edge of the midtone valley band. */
    const val MID_LOW = 70.0

    /** Upper edge of the midtone valley band. */
    const val MID_HIGH = 150.0

    /** Minimum dark-band fraction for the dark-mass criterion to begin holding. */
    const val DARK_FRACTION = 0.25

    /** Minimum bright fraction for the bright-mass criterion to begin holding. */
    const val BRIGHT_FRACTION = 0.15

    /** Maximum midtone fraction for the valley criterion to begin holding. */
    const val MID_FRACTION_MAX = 0.35

    /** Ramp width (in fraction units) over which the dark-mass margin rises 0 -> 1. */
    const val DARK_RAMP = 0.10

    /** Ramp width over which the bright-mass margin rises 0 -> 1. */
    const val BRIGHT_RAMP = 0.10

    /** Ramp width over which the valley margin rises 0 -> 1. */
    const val VALLEY_RAMP = 0.10

    // Rec. 601 luma weights, matching the rest of the pipeline.
    private const val R_WEIGHT = 0.299
    private const val G_WEIGHT = 0.587
    private const val B_WEIGHT = 0.114

    /**
     * The detector's verdict: the engagement [strength] plus the three raw histogram
     * fractions it was derived from (exposed so tests and reports can see WHY it fired or
     * stayed quiet).
     */
    data class BacklitDecision(
        /** Backlit engagement in [0, 1]; 0 means "not backlit", 1 means "strongly backlit". */
        val strength: Double,
        /** Fraction of pixels in the recoverable-shadow band [[SHADOW_FLOOR], [LUMA_LOW]). */
        val darkFraction: Double,
        /** Fraction of pixels above [LUMA_HIGH]. */
        val brightFraction: Double,
        /** Fraction of pixels in the midtone valley band [[MID_LOW], [MID_HIGH]]. */
        val midFraction: Double,
    )

    /** Computes the backlit decision for [frame] from its luma histogram. */
    fun detect(frame: Frame): BacklitDecision {
        val src = frame.argb
        val n = src.size
        if (n == 0) return BacklitDecision(0.0, 0.0, 0.0, 0.0)

        var darkN = 0
        var brightN = 0
        var midN = 0
        for (px in src) {
            val luma = R_WEIGHT * ((px shr 16) and 0xFF) +
                G_WEIGHT * ((px shr 8) and 0xFF) +
                B_WEIGHT * (px and 0xFF)
            if (luma >= SHADOW_FLOOR && luma < LUMA_LOW) darkN++
            if (luma > LUMA_HIGH) brightN++
            if (luma in MID_LOW..MID_HIGH) midN++
        }

        val darkFraction = darkN.toDouble() / n
        val brightFraction = brightN.toDouble() / n
        val midFraction = midN.toDouble() / n

        val darkMargin = smoothstep((darkFraction - DARK_FRACTION) / DARK_RAMP)
        val brightMargin = smoothstep((brightFraction - BRIGHT_FRACTION) / BRIGHT_RAMP)
        val valleyMargin = smoothstep((MID_FRACTION_MAX - midFraction) / VALLEY_RAMP)
        val strength = minOf(darkMargin, brightMargin, valleyMargin)

        return BacklitDecision(strength, darkFraction, brightFraction, midFraction)
    }

    /** Clamped cubic smoothstep: 0 for [x] <= 0, 1 for [x] >= 1, smooth in between. */
    private fun smoothstep(x: Double): Double {
        val t = x.coerceIn(0.0, 1.0)
        return t * t * (3.0 - 2.0 * t)
    }
}
