package com.poc.camera.pipeline

import kotlin.math.roundToInt

/**
 * Estimates a global integer translation of each burst frame relative to the
 * reference (the first frame) and flags outliers.
 *
 * Alignment is coarse-to-fine over a luma pyramid. At the coarsest level the frame
 * is divided into [VOTE_TILE_SIZE]-px tiles, each tile searches +/-[coarseRadius]
 * independently, and the global estimate is the component-wise MEDIAN of the tile
 * votes (see [coarseEstimate]): a walking-subject-scale moving object can dominate
 * a whole-frame matching cost and drag the estimate with it (issue #130), but as a
 * minority of tiles it cannot outvote the static majority. Each finer level then
 * refines by +/-[refineRadius] (the estimate is doubled between levels), minimising
 * the mean absolute difference (MAD) of the reference's central region against the
 * shifted frame, so partial-overlap offsets are compared fairly.
 *
 * Offset convention: an offset (dx, dy) means reference pixel (x, y) corresponds
 * to frame pixel (x + dx, y + dy); [TileAligner] and [RobustFrameMerger] use the
 * same convention.
 *
 * Cost per frame is bounded by
 *   (2*coarseRadius+1)^2 * Wc*Hc + (levels-1) * (2*refineRadius+1)^2 * N
 * pixel comparisons, where N is the full-resolution central-region pixel count and
 * Wc*Hc the coarsest level's FULL pixel count (the vote tiles cover the whole level;
 * with the default 3 levels and central fraction 0.5 that is 4x the former
 * central-only coarse pass and roughly doubles total alignment comparisons --
 * measured ~+0.1 s per aligned frame on a 3 MP merge); i.e. O(window^2 * pixels).
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
        var best = coarseEstimate(refPyramid[coarsest], framePyramid[coarsest])
        for (level in coarsest - 1 downTo 0) {
            best = search(refPyramid[level], framePyramid[level], best.dx * 2, best.dy * 2, refineRadius)
        }
        return best
    }

    /**
     * Subject-robust coarsest-level estimate: the component-wise median of independent
     * per-tile searches. Every complete [VOTE_TILE_SIZE]x[VOTE_TILE_SIZE] tile runs its
     * own exhaustive +/-[coarseRadius] search ([tileVote]) and the tile offsets are
     * aggregated with the median, so a textured moving object -- a minority of tiles,
     * however much whole-frame matching cost it carries -- cannot drag the global
     * estimate away from the static majority's true camera motion. On a drift-only
     * burst every tile votes the same offset and the median IS that offset, preserving
     * the previous behaviour.
     *
     * Tiles without matchable structure (flat or clipped content, whose MAD surface is
     * near-constant) abstain instead of casting a noise-driven vote. Degenerate inputs
     * fall back to the previous whole-frame central-region search: a coarsest level
     * with fewer than [MIN_VOTE_TILES] complete tiles (tiny frames), or fewer than
     * [MIN_VOTE_TILES] surviving votes.
     *
     * The returned MAD is evaluated over the central region at the voted offset, so a
     * single-level pyramid still reports an honest rejection statistic; multi-level
     * pyramids recompute it during refinement.
     */
    private fun coarseEstimate(ref: LumaPlane, frame: LumaPlane): RawMatch {
        val cols = ref.width / VOTE_TILE_SIZE
        val rows = ref.height / VOTE_TILE_SIZE
        if (cols * rows < MIN_VOTE_TILES) return search(ref, frame, 0, 0, coarseRadius)

        // Row-major tile order; the median is order-independent, so the aggregation is
        // deterministic regardless of how votes were collected.
        val dxVotes = ArrayList<Double>(cols * rows)
        val dyVotes = ArrayList<Double>(cols * rows)
        for (r in 0 until rows) {
            val y0 = r * VOTE_TILE_SIZE
            for (c in 0 until cols) {
                val x0 = c * VOTE_TILE_SIZE
                val vote = tileVote(ref, frame, x0, y0, x0 + VOTE_TILE_SIZE, y0 + VOTE_TILE_SIZE)
                if (vote != null) {
                    dxVotes.add(vote.dx.toDouble())
                    dyVotes.add(vote.dy.toDouble())
                }
            }
        }
        if (dxVotes.size < MIN_VOTE_TILES) return search(ref, frame, 0, 0, coarseRadius)

        val dx = median(dxVotes.toDoubleArray()).roundToInt()
        val dy = median(dyVotes.toDoubleArray()).roundToInt()
        return search(ref, frame, dx, dy, 0)
    }

    /**
     * One tile's vote: the +/-[coarseRadius] offset minimising the tile's MAD, or null
     * when the tile abstains -- no candidate had enough overlap, or the MAD surface is
     * too flat to be trusted (spread below [MIN_VOTE_SPREAD] absolute or
     * [VOTE_SPREAD_FRACTION] of the best MAD). Flat surfaces arise on textureless or
     * clipped content, where the argmin is decided by noise (or by scan order) rather
     * than structure; letting such tiles vote would bias the median on scenes with
     * large uniform areas, e.g. the clipped highlights of a bright HDR exposure.
     */
    private fun tileVote(
        ref: LumaPlane,
        frame: LumaPlane,
        x0: Int,
        y0: Int,
        x1: Int,
        y1: Int,
    ): RawMatch? {
        val minOverlap = maxOf(1, (x1 - x0) * (y1 - y0) / 2)
        var bestDx = 0
        var bestDy = 0
        var bestMad = Double.MAX_VALUE
        var worstMad = 0.0
        for (dy in -coarseRadius..coarseRadius) {
            for (dx in -coarseRadius..coarseRadius) {
                val mad = regionMad(ref, frame, x0, y0, x1, y1, dx, dy, minOverlap)
                if (mad.isNaN()) continue
                if (mad < bestMad) {
                    bestMad = mad
                    bestDx = dx
                    bestDy = dy
                }
                if (mad > worstMad) worstMad = mad
            }
        }
        if (bestMad == Double.MAX_VALUE) return null
        val spread = worstMad - bestMad
        if (spread < maxOf(MIN_VOTE_SPREAD, VOTE_SPREAD_FRACTION * bestMad)) return null
        return RawMatch(bestDx, bestDy, bestMad)
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
                val mad = regionMad(ref, frame, x0, y0, x1, y1, dx, dy, minOverlap)
                if (mad.isNaN()) continue
                if (mad < bestMad) {
                    bestMad = mad
                    bestDx = dx
                    bestDy = dy
                }
            }
        }
        return RawMatch(bestDx, bestDy, bestMad)
    }

    /**
     * Mean absolute luma difference of the reference region [x0, x1) x [y0, y1)
     * against [frame] shifted by (dx, dy). Returns NaN when fewer than [minOverlap]
     * pixels overlap, so callers can skip candidates that compare too little area.
     */
    @Suppress("LongParameterList")
    private fun regionMad(
        ref: LumaPlane,
        frame: LumaPlane,
        x0: Int,
        y0: Int,
        x1: Int,
        y1: Int,
        dx: Int,
        dy: Int,
        minOverlap: Int,
    ): Double {
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
        return if (count < minOverlap) Double.NaN else sum.toDouble() / count
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

        /** Side of the coarsest-level vote tiles (see [coarseEstimate]). At the default
         *  3 pyramid levels one tile covers a 32x32 full-resolution region -- small
         *  enough that a walking-subject-scale object corrupts only a minority of
         *  tiles, large enough (64 coarse pixels) for a meaningful match. */
        const val VOTE_TILE_SIZE = 8

        /** Fewest votes for which a median is meaningful (one outlier is outvoted). */
        const val MIN_VOTE_TILES = 3

        /** A tile abstains when its MAD surface spread (worst - best) sits under this
         *  absolute luma floor: an essentially constant surface has no structure. */
        const val MIN_VOTE_SPREAD = 1.0

        /** ...or under this fraction of its best MAD: a spread within the estimation
         *  noise of a high, flat surface (e.g. pure sensor noise) is equally untrusted. */
        const val VOTE_SPREAD_FRACTION = 0.25
    }
}
