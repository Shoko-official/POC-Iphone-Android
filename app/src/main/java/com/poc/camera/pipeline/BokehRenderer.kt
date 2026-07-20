package com.poc.camera.pipeline

import kotlin.math.roundToInt

/**
 * Mask-driven portrait background blur -- the PURE half of portrait mode (issue #79). Given
 * an image and a soft subject mask it produces a synthetic-defocus ("bokeh") rendition:
 * the subject stays sharp, the background is disc-blurred with per-pixel radius, near-clipped
 * highlights bloom into glowing discs, and the subject boundary is feathered onto real edges
 * so hair survives and no subject colour bleeds into the blur.
 *
 * Segmentation is out of scope here; the mask is supplied by the caller. Issue #80 wires a
 * real segmenter into this same [render] entry point. See [BlurMap] for the soft-mask input
 * contract (a `FloatArray` in [0, 1], 1 = subject).
 *
 * ## Pipeline
 *
 *  1. Unpack the input to float R/G/B planes and a luma plane.
 *  2. [BlurMap] feathers the mask onto image edges (guided filter + small box blur) and
 *     derives the per-pixel blur radius.
 *  3. [HighlightBloom] lifts near-clipped BACKGROUND highlights above 255 in float headroom.
 *  4. [DiscBlur] gathers the bloomed background over a Vogel-spiral disc of the local radius,
 *     weighting every tap by background-ness (1 - featheredMask) so SUBJECT pixels never
 *     bleed into the background bokeh (the classic halo artifact).
 *  5. Composite: `out = m * sharp + (1 - m) * blurred` per channel (m = featheredMask), with
 *     the ORIGINAL (un-bloomed) channels as the sharp layer, clamped to 0..255, alpha opaque.
 *
 * Every stage is row/pixel parallel under the [PipelineParallel] contract and bit-identical
 * to its serial reference (pass `chunkCount = PipelineParallel.SERIAL_CHUNKS`). Pure Kotlin,
 * deterministic, no Android dependencies; hot loops are allocation-free (the per-call working
 * planes are allocated once up front, never inside the pixel loops).
 */
object BokehRenderer {

    // Rec. 601 luma weights, matching Luminance / Saturation / HighlightBloom.
    private const val R_WEIGHT = 0.299
    private const val G_WEIGHT = 0.587
    private const val B_WEIGHT = 0.114

    /**
     * Renders [input] with the soft subject [mask] (length width*height, values in [0, 1],
     * 1 = subject) using [params]. Returns a new opaque [Frame] the same size as [input].
     *
     * At `params.strength == 0` every per-pixel radius is 0, so the disc blur copies the
     * source through and the composite reproduces the input exactly (bit-for-bit on the RGB
     * channels; alpha is forced opaque).
     */
    fun render(
        input: Frame,
        mask: FloatArray,
        params: BokehParams = BokehParams.DEFAULT,
        chunkCount: Int = PipelineParallel.parallelism,
    ): Frame {
        val width = input.width
        val height = input.height
        val size = width * height
        require(mask.size == size) { "mask size ${mask.size} must be width*height ($size)" }

        val src = input.argb
        val sharpR = FloatArray(size)
        val sharpG = FloatArray(size)
        val sharpB = FloatArray(size)
        val luma = DoubleArray(size)
        PipelineParallel.parallelRows(size, chunkCount) { start, end ->
            for (i in start until end) {
                val p = src[i]
                val r = ((p shr 16) and 0xFF)
                val g = ((p shr 8) and 0xFF)
                val b = (p and 0xFF)
                sharpR[i] = r.toFloat()
                sharpG[i] = g.toFloat()
                sharpB[i] = b.toFloat()
                luma[i] = R_WEIGHT * r + G_WEIGHT * g + B_WEIGHT * b
            }
        }

        val maps = BlurMap.compute(mask, luma, width, height, params, chunkCount)
        val featheredMask = maps.featheredMask
        val radius = maps.radius

        // Background-ness = 1 - featheredMask: the gather weight AND the bloom scale, so both
        // effects apply to the background only.
        val backgroundness = FloatArray(size)
        PipelineParallel.parallelRows(size, chunkCount) { start, end ->
            for (i in start until end) backgroundness[i] = 1f - featheredMask[i]
        }

        // Bloom near-clipped background highlights into float headroom before blurring. The
        // bloom is part of the effect, so it scales with overall strength: at strength 0 the
        // effective boost is 1 (no bloom), which -- together with the all-zero radius -- makes
        // the whole render a passthrough.
        val effectiveBoost = 1.0 + (params.highlightBoost - 1.0) * params.strength
        val bloomR = FloatArray(size)
        val bloomG = FloatArray(size)
        val bloomB = FloatArray(size)
        HighlightBloom.apply(
            sharpR, sharpG, sharpB, backgroundness, size,
            params.highlightThreshold, effectiveBoost,
            bloomR, bloomG, bloomB, chunkCount,
        )

        // Disc-blur the bloomed background, weighting taps by background-ness (no subject bleed).
        val offsets = DiscBlur.vogelSpiral(params.tapCount)
        val blurR = FloatArray(size)
        val blurG = FloatArray(size)
        val blurB = FloatArray(size)
        DiscBlur.blur(
            bloomR, bloomG, bloomB, width, height,
            radius, backgroundness, offsets,
            blurR, blurG, blurB,
            chunkCount = chunkCount,
        )

        // Composite: sharp subject over blurred background, clamped, alpha opaque.
        val out = IntArray(size)
        PipelineParallel.parallelRows(size, chunkCount) { start, end ->
            for (i in start until end) {
                val im = 1f - featheredMask[i]
                // Lerp form (sharp + im*(blur - sharp)): when the background layer equals the
                // sharp layer (strength 0 -> no bloom and radius 0 -> copy) this is EXACTLY the
                // source, so the RGB channels pass through bit-for-bit.
                val r = clamp8(sharpR[i] + im * (blurR[i] - sharpR[i]))
                val g = clamp8(sharpG[i] + im * (blurG[i] - sharpG[i]))
                val b = clamp8(sharpB[i] + im * (blurB[i] - sharpB[i]))
                out[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        return Frame(width, height, out, input.timestampMillis)
    }

    /** Rounds a float channel to the nearest integer code and clamps to 0..255. */
    private fun clamp8(v: Float): Int = v.roundToInt().coerceIn(0, 255)
}
