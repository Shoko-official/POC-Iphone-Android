package com.poc.camera.pipeline

/**
 * Pre-blur highlight shaping for the background layer of portrait mode ([BokehRenderer]).
 *
 * A real lens concentrates the energy of a bright, (near-)CLIPPED highlight into its
 * out-of-focus disc, so those discs read as bright glowing balls rather than dim grey
 * blobs -- the single most recognisable feature of good bokeh. A blur alone cannot recover
 * that: once a highlight is clipped at 255 the extra energy is gone, so spreading it just
 * dims it. This stage restores the missing headroom BEFORE the disc blur by lifting
 * near-clipped background pixels above the display range in FLOAT, so [DiscBlur] then
 * spreads that surplus into brighter discs. Values are left un-clamped here and only
 * clamped back to 0..255 at the final composite ([BokehRenderer]), so no overflow occurs.
 *
 * The lift is a smooth multiplicative gain ramped in by luma: a pixel below
 * [BokehParams.highlightThreshold] is untouched (gain 1), a fully white pixel gets the full
 * [BokehParams.highlightBoost], and the ramp between is a smoothstep so no hard edge is
 * introduced. Working directly on the gamma-encoded channels with float headroom is a
 * pragmatic "linear-ish" proxy for the real linear-light energy a lens integrates: it is
 * cheap, monotonic, and hue-preserving (all three channels scale by the same gain), and it
 * captures the essential behaviour (clipped highlights bloom, mid-tones do not) without a
 * full delinearise/relinearise round-trip. The gain is additionally scaled by a per-pixel
 * [backgroundness] plane so ONLY the background blooms -- a bright pixel on the subject is
 * left alone, keeping the in-focus subject free of halos.
 *
 * Pure Kotlin, deterministic, element-wise (row-parallel), no Android dependencies.
 */
object HighlightBloom {

    // Rec. 601 luma weights, matching Luminance / Saturation / SkinMask.
    private const val R_WEIGHT = 0.299
    private const val G_WEIGHT = 0.587
    private const val B_WEIGHT = 0.114

    /**
     * Writes the bloomed background channels into [outR]/[outG]/[outB] from the source
     * [srcR]/[srcG]/[srcB] planes (all length [size], row-major float). [backgroundness] in
     * [0, 1] scales the effect per pixel (typically 1 - featheredMask). [threshold] and
     * [boost] come from [BokehParams]. When [boost] <= 1 the output equals the input exactly.
     *
     * Output may exceed 255 by design (headroom for the disc blur to spread); it is clamped
     * only at the final composite. Out planes may alias the source planes (each element is
     * read once before it is written).
     */
    @Suppress("LongParameterList")
    fun apply(
        srcR: FloatArray,
        srcG: FloatArray,
        srcB: FloatArray,
        backgroundness: FloatArray,
        size: Int,
        threshold: Double,
        boost: Double,
        outR: FloatArray,
        outG: FloatArray,
        outB: FloatArray,
        chunkCount: Int = PipelineParallel.parallelism,
    ) {
        require(srcR.size == size && srcG.size == size && srcB.size == size) { "src planes must be size" }
        require(outR.size == size && outG.size == size && outB.size == size) { "out planes must be size" }
        require(backgroundness.size == size) { "backgroundness must be size" }
        val extra = boost - 1.0
        PipelineParallel.parallelRows(size, chunkCount) { start, end ->
            for (i in start until end) {
                val r = srcR[i]
                val g = srcG[i]
                val b = srcB[i]
                if (extra <= 0.0) {
                    outR[i] = r
                    outG[i] = g
                    outB[i] = b
                    continue
                }
                val luma = R_WEIGHT * r + G_WEIGHT * g + B_WEIGHT * b
                // Smooth ramp from threshold (0) to fully clipped 255 (1), scaled to background.
                val ramp = smoothstep(threshold, 255.0, luma) * backgroundness[i]
                val gain = (1.0 + extra * ramp).toFloat()
                outR[i] = r * gain
                outG[i] = g * gain
                outB[i] = b * gain
            }
        }
    }

    /** Classic smoothstep: 0 for x <= e0, 1 for x >= e1, C1-smooth in between. */
    private fun smoothstep(e0: Double, e1: Double, x: Double): Double {
        val t = ((x - e0) / (e1 - e0)).coerceIn(0.0, 1.0)
        return t * t * (3.0 - 2.0 * t)
    }
}
