package com.poc.camera.pipeline

/**
 * Turns a SOFT subject mask into the per-pixel blur-radius field that drives the portrait
 * defocus ([BokehRenderer]), feathering the mask to real image edges on the way.
 *
 * ## Input mask contract (shared with segmentation, issue #80)
 *
 * The mask is a `FloatArray` in [0, 1], one value per pixel (row-major), where 1 = subject
 * (keep sharp) and 0 = background (blur fully). It is deliberately a plain soft plane so the
 * segmentation stage of issue #80 can drop its probability map straight in without any
 * shared type.
 *
 * ## Why feather before deriving the radius
 *
 * A raw segmentation mask has a soft, spatially-lazy boundary that rarely coincides with the
 * real subject silhouette -- it cuts across hair strands and misses fine structure. Driving
 * the blur straight off it produces the tell-tale "cut-out" halo: sharp background clinging
 * to the subject, or blurred hair. So the mask is first run through [GuidedFilter.guided]
 * with the image LUMA as the guide. The guided filter makes its output a locally-linear
 * function of the guide, so the mask edge SNAPS onto the luma edges of the actual image:
 * where a bright hair strand crosses a dark background the mask transition is pulled onto
 * that strand, keeping the strand on the "subject" side and thus sharp. A small [BoxBlur]
 * then smooths the feathered mask so the radius field has no stair-stepping. The result is
 * clamped back to [0, 1] (box mean of feathered values can drift a hair outside).
 *
 * ## Radius field
 *
 *   radius(pixel) = maxBlurRadius * (1 - featheredMask(pixel)) * strength
 *
 * so subject pixels (mask 1) get radius 0 (untouched), background pixels (mask 0) get the
 * full [BokehParams.maxBlurRadius], and the feathered transition gives a smooth ramp -- a
 * crude but effective depth proxy in the absence of a real depth map.
 *
 * All heavy lifting is the shared [GuidedFilter] / [BoxBlur] (both O(pixels), row/column
 * parallel and bit-identical to serial). Pure Kotlin, deterministic, no Android deps.
 */
object BlurMap {

    /**
     * The feathered mask and the derived per-pixel blur radius, both [width]x[height]
     * row-major floats. [featheredMask] is reused by [BokehRenderer] both to weight the
     * background gather (as 1 - mask) and to composite, so it is returned rather than
     * recomputed.
     */
    class Maps(val featheredMask: FloatArray, val radius: FloatArray)

    /**
     * Computes the [Maps] for [mask] (length [width]*[height], values in [0, 1]) given the
     * image [luma] plane (same length, 0..255 domain) and [params].
     */
    fun compute(
        mask: FloatArray,
        luma: DoubleArray,
        width: Int,
        height: Int,
        params: BokehParams,
        chunkCount: Int = PipelineParallel.parallelism,
    ): Maps {
        val size = width * height
        require(mask.size == size) { "mask must be width*height" }
        require(luma.size == size) { "luma must be width*height" }

        // Guided-filter the mask with image luma as guide: snaps the mask onto real edges.
        val maskD = DoubleArray(size) { mask[it].toDouble() }
        val guided = GuidedFilter.guided(
            input = maskD,
            guide = luma,
            width = width,
            height = height,
            radius = params.featherRadius,
            eps = params.featherEps,
            chunkCount = chunkCount,
        )
        // Small box blur for a smooth radius field, then clamp back to [0, 1].
        val smoothed = BoxBlur.blur(guided, width, height, params.featherSmoothRadius, chunkCount)

        val featheredMask = FloatArray(size)
        val radius = FloatArray(size)
        val maxR = params.maxBlurRadius.toDouble()
        val strength = params.strength
        PipelineParallel.parallelRows(size, chunkCount) { start, end ->
            for (i in start until end) {
                val m = smoothed[i].coerceIn(0.0, 1.0)
                featheredMask[i] = m.toFloat()
                radius[i] = (maxR * (1.0 - m) * strength).toFloat()
            }
        }
        return Maps(featheredMask, radius)
    }
}
