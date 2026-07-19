package com.poc.camera.pipeline

/**
 * Estimates a global integer translation of each burst frame relative to the
 * reference (the first frame) and flags outliers.
 *
 * Alignment is coarse-to-fine over a luma pyramid: an exhaustive +/-[coarseRadius]
 * search at the coarsest level, then a +/-[refineRadius] refinement at each finer
 * level (the estimate is doubled between levels). Matching minimises the mean
 * absolute difference (MAD) of the reference's central region against the shifted
 * frame, so partial-overlap offsets are compared fairly.
 *
 * Offset convention: an offset (dx, dy) means reference pixel (x, y) corresponds
 * to frame pixel (x + dx, y + dy); [FrameMerger] uses the same convention.
 *
 * Cost per frame is bounded by
 *   (2*coarseRadius+1)^2 * Nc + (levels-1) * (2*refineRadius+1)^2 * N
 * pixel comparisons, where N is the full-resolution central-region pixel count and
 * Nc the coarsest level's (N shrinks by 4x per level); i.e. O(window^2 * pixels).
 */
class FrameAligner(
    private val coarseRadius: Int = 8,
    private val refineRadius: Int = 2,
    private val pyramidLevels: Int = 3,
    private val centralRegionFraction: Double = 0.5,
    private val rejectFactor: Double = 4.0,
) {
    init {
        require(coarseRadius > 0) { "coarseRadius must be positive" }
        require(refineRadius > 0) { "refineRadius must be positive" }
        require(pyramidLevels >= 1) { "pyramidLevels must be >= 1" }
        require(centralRegionFraction > 0.0 && centralRegionFraction <= 1.0) {
            "centralRegionFraction must be in (0, 1]"
        }
        require(rejectFactor > 0.0) { "rejectFactor must be positive" }
    }

    /** Per-frame result: the recovered offset, its MAD, and whether it survived rejection. */
    data class Alignment(
        val dx: Int,
        val dy: Int,
        val meanAbsDiff: Double,
        val accepted: Boolean,
    )

    fun align(frames: List<Frame>): List<Alignment> {
        require(frames.isNotEmpty()) { "frames must not be empty" }
        val refPyramid = buildPyramid(Luminance.extract(frames.first()))

        // The first frame is the reference: identity offset, always accepted.
        val matches = ArrayList<RawMatch>(frames.size)
        matches.add(RawMatch(0, 0, 0.0))
        for (index in 1 until frames.size) {
            val framePyramid = buildPyramid(Luminance.extract(frames[index]))
            matches.add(estimate(refPyramid, framePyramid))
        }

        val accepted = BooleanArray(frames.size) { true }
        if (frames.size > 1) {
            // Outlier rejection: reject a non-reference frame whose MAD exceeds
            // rejectFactor * baseline, where baseline is the median MAD of the
            // non-reference frames floored at MIN_BASELINE_MAD. The floor stops a
            // near-identical burst (median MAD ~0) from rejecting its own members.
            val mads = DoubleArray(frames.size - 1) { matches[it + 1].mad }
            val threshold = rejectFactor * maxOf(median(mads), MIN_BASELINE_MAD)
            for (index in 1 until frames.size) {
                if (matches[index].mad > threshold) accepted[index] = false
            }
        }

        return matches.mapIndexed { index, match ->
            Alignment(match.dx, match.dy, match.mad, accepted[index])
        }
    }

    /** Builds [full, half, quarter, ...] up to [pyramidLevels], stopping if a level would be < 2px. */
    private fun buildPyramid(full: LumaPlane): List<LumaPlane> {
        val pyramid = ArrayList<LumaPlane>(pyramidLevels)
        pyramid.add(full)
        var current = full
        while (pyramid.size < pyramidLevels && current.width >= 2 && current.height >= 2) {
            current = Luminance.downsample2x(current)
            pyramid.add(current)
        }
        return pyramid
    }

    private fun estimate(refPyramid: List<LumaPlane>, framePyramid: List<LumaPlane>): RawMatch {
        val coarsest = refPyramid.lastIndex
        var best = search(refPyramid[coarsest], framePyramid[coarsest], 0, 0, coarseRadius)
        for (level in coarsest - 1 downTo 0) {
            best = search(refPyramid[level], framePyramid[level], best.dx * 2, best.dy * 2, refineRadius)
        }
        return best
    }

    private fun search(
        ref: LumaPlane,
        frame: LumaPlane,
        centerDx: Int,
        centerDy: Int,
        radius: Int,
    ): RawMatch {
        val marginX = ((ref.width * (1.0 - centralRegionFraction)) / 2.0).toInt()
        val marginY = ((ref.height * (1.0 - centralRegionFraction)) / 2.0).toInt()
        val x0 = marginX
        val x1 = ref.width - marginX
        val y0 = marginY
        val y1 = ref.height - marginY
        val minOverlap = maxOf(1, (x1 - x0) * (y1 - y0) / 2)

        var bestDx = centerDx
        var bestDy = centerDy
        var bestMad = Double.MAX_VALUE
        for (dy in centerDy - radius..centerDy + radius) {
            for (dx in centerDx - radius..centerDx + radius) {
                var sum = 0L
                var count = 0
                for (y in y0 until y1) {
                    val fy = y + dy
                    if (fy < 0 || fy >= frame.height) continue
                    val refRow = y * ref.width
                    val frameRow = fy * frame.width
                    for (x in x0 until x1) {
                        val fx = x + dx
                        if (fx < 0 || fx >= frame.width) continue
                        val diff = ref.values[refRow + x] - frame.values[frameRow + fx]
                        sum += if (diff < 0) -diff else diff
                        count++
                    }
                }
                if (count < minOverlap) continue
                val mad = sum.toDouble() / count
                if (mad < bestMad) {
                    bestMad = mad
                    bestDx = dx
                    bestDy = dy
                }
            }
        }
        return RawMatch(bestDx, bestDy, bestMad)
    }

    private fun median(values: DoubleArray): Double {
        val sorted = values.sortedArray()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) {
            sorted[mid]
        } else {
            (sorted[mid - 1] + sorted[mid]) / 2.0
        }
    }

    private data class RawMatch(val dx: Int, val dy: Int, val mad: Double)

    private companion object {
        const val MIN_BASELINE_MAD = 1.0
    }
}
