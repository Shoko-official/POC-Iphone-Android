package com.poc.camera.pipeline

/**
 * Tuning for the mask-driven background blur of portrait mode ([BokehRenderer]).
 *
 * All radii are in PIXELS at the working resolution. The defaults are tuned for a
 * ~2000 px-wide working frame (the resolution portrait mode is expected to run the pure
 * blur at, downstream of segmentation in issue #80): [maxBlurRadius] = 12 there gives a
 * defocus disc a little under 1% of the frame width, which reads as a moderate f/2-ish
 * portrait separation without becoming a cartoonish full-frame smear. Because the look is
 * scale-relative, [forImageWidth] rescales every spatial radius from that 2000 px
 * reference so the same visual amount of blur is produced at any resolution; callers that
 * want an exact pixel radius can also construct [BokehParams] directly.
 *
 * Pure data holder, no Android dependencies, so it can be built and asserted on in JVM
 * unit tests.
 *
 * @param maxBlurRadius the disc-blur radius applied to fully-background pixels (mask 0);
 *   the per-pixel radius ramps from 0 at the subject to this at the background
 *   ([BlurMap]).
 * @param tapCount number of Vogel-spiral taps the disc kernel samples (fixed, deterministic).
 *   More taps = smoother discs at higher cost; 24 is a good speed/quality point.
 * @param featherRadius guided-filter window half-width used to snap the soft subject mask
 *   to real image edges before it drives the blur, protecting fine structure such as hair.
 * @param featherEps guided-filter regulariser in SQUARED luma-code units (0..255 domain):
 *   larger treats bigger local luma swings as "flat" and lets the mask smooth through them,
 *   smaller makes the feathering hug edges more tightly.
 * @param featherSmoothRadius small box-blur radius applied to the feathered mask so the
 *   blur-radius field varies smoothly (no visible stair-stepping in the transition).
 * @param highlightThreshold luma (0..255) above which a background pixel is treated as a
 *   near-clipped highlight and bloomed before blurring ([HighlightBloom]).
 * @param highlightBoost multiplicative gain applied to fully-clipped background highlights;
 *   1.0 disables bloom. This is what makes the out-of-focus discs glow like a real lens.
 * @param strength overall effect strength in [0, 1]; 0 is a bit-exact passthrough of the
 *   input (every per-pixel radius collapses to 0), 1 is the full defocus.
 */
data class BokehParams(
    val maxBlurRadius: Int,
    val tapCount: Int = 24,
    val featherRadius: Int = 12,
    val featherEps: Double = 150.0,
    val featherSmoothRadius: Int = 2,
    val highlightThreshold: Double = 235.0,
    val highlightBoost: Double = 3.0,
    val strength: Double = 1.0,
) {
    init {
        require(maxBlurRadius >= 0) { "maxBlurRadius must be >= 0" }
        require(tapCount >= 1) { "tapCount must be >= 1" }
        require(featherRadius >= 0) { "featherRadius must be >= 0" }
        require(featherEps > 0.0) { "featherEps must be > 0" }
        require(featherSmoothRadius >= 0) { "featherSmoothRadius must be >= 0" }
        require(highlightThreshold in 0.0..255.0) { "highlightThreshold must be in [0, 255]" }
        require(highlightBoost >= 1.0) { "highlightBoost must be >= 1" }
        require(strength in 0.0..1.0) { "strength must be in [0, 1]" }
    }

    companion object {
        /** Reference working width the defaults are tuned for; [forImageWidth] scales from it. */
        const val REFERENCE_WIDTH = 2000

        /** Ship defaults, tuned for a ~2000 px-wide working frame (see class KDoc). */
        val DEFAULT = BokehParams(maxBlurRadius = 12)

        /**
         * [DEFAULT] with every spatial radius rescaled from [REFERENCE_WIDTH] to [width], so
         * the same fraction-of-frame blur is produced at any resolution. Non-spatial tuning
         * ([tapCount], [featherEps], highlight and strength controls) is unchanged. Each
         * scaled radius is rounded to the nearest pixel and floored at its minimum sensible
         * value (1 px for the blur/feather windows so they never degenerate to no-ops).
         */
        fun forImageWidth(width: Int): BokehParams {
            require(width > 0) { "width must be > 0" }
            val scale = width.toDouble() / REFERENCE_WIDTH
            fun scaled(base: Int, floor: Int): Int =
                Math.round(base * scale).toInt().coerceAtLeast(floor)
            return DEFAULT.copy(
                maxBlurRadius = scaled(DEFAULT.maxBlurRadius, 1),
                featherRadius = scaled(DEFAULT.featherRadius, 1),
                featherSmoothRadius = scaled(DEFAULT.featherSmoothRadius, 0),
            )
        }
    }
}
