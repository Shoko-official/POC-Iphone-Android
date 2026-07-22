package com.poc.camera.pipeline

import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The two GLOBAL statistics the finishing chain derives from the whole frame, hoisted so
 * they can be computed ONCE and then applied uniformly across every tile of a tiled
 * finish. Estimating either per tile would give each tile a different correction and
 * leave visible seams, so [TiledFinishing] fixes both up front.
 *
 *  - [wbGains]: the bounded auto white-balance gains ([WhiteBalance.estimateGains]).
 *    White balance is otherwise a per-pixel op, so with fixed gains it is seam-free by
 *    construction.
 *  - [detailKnee]: the [DetailEnhancer] coring knee (`coringSigmaFactor * robustSigma`),
 *    the noise floor its soft-threshold is scaled against ([DetailEnhancer.computeKnee]).
 *
 * The knee is measured on the frame state ENTERING [DetailEnhancer] -- i.e. after white
 * balance, chroma denoise and local tone mapping -- exactly as the whole-frame
 * [FinishingPipeline] measures it internally, so [compute] mirrors those pre-stages.
 */
data class FinishingStats(
    val wbGains: WhiteBalanceGains,
    val detailKnee: Double,
) {
    companion object {
        /**
         * Computes the global stats for [params] from [analysisFrame]. Passing the FULL
         * merged frame yields the byte-for-byte statistics the whole-frame
         * [FinishingPipeline] would compute internally (used as the bit-identity
         * reference and for frames small enough to finish in a single tile); passing a
         * downsample yields the near-identical approximation the production auto route
         * uses to keep the analysis pass cheap (see [TiledFinishing.apply]).
         *
         * The stat-bearing pre-stages ([WhiteBalance] gains, then the WB -> chroma
         * denoise -> local tone chain feeding [DetailEnhancer.computeKnee]) are run in the
         * SAME order and with the SAME parameter mapping as [FinishingPipeline], so an
         * exact (full-frame) analysis reproduces the production knee precisely.
         */
        fun compute(analysisFrame: Frame, params: FinishingParams): FinishingStats {
            val wbGains = if (params.whiteBalance > 0.0) {
                WhiteBalance.estimateGains(analysisFrame)
            } else {
                WhiteBalanceGains.IDENTITY
            }

            val detailKnee = if (params.detailEnhance > 0.0) {
                val balanced = if (params.whiteBalance > 0.0) {
                    WhiteBalance.applyGains(analysisFrame, wbGains, params.whiteBalance)
                } else {
                    analysisFrame
                }
                val denoised = if (params.chromaDenoise > 0.0) {
                    ChromaDenoiser.apply(balanced, params.chromaDenoise)
                } else {
                    balanced
                }
                val locallyMapped = if (params.localContrast > 0.0) {
                    LocalToneMapper.apply(denoised, FinishingPipeline.localToneParams(params.localContrast))
                } else {
                    denoised
                }
                DetailEnhancer.computeKnee(locallyMapped, FinishingPipeline.detailParams(params.detailEnhance))
            } else {
                0.0
            }

            return FinishingStats(wbGains, detailKnee)
        }
    }
}

/**
 * Runs the [FinishingPipeline] over a large frame in overlapping tiles so a native-
 * resolution (12 MP+) merge is finished within bounded memory. The whole-frame path
 * allocates its guided-filter intermediates over the FULL pixel count -- at 12.5 MP the
 * [LocalToneMapper] pass alone peaks near a gigabyte of transient `DoubleArray`s -- which
 * is the finishing memory hot spot [BurstImageGeometry] was capped to avoid. Tiling caps
 * that working set at a per-tile size independent of the frame.
 *
 * ## Why fixed global stats + overlap need no blending
 *
 * Every finishing stage is one of three kinds:
 *  - PURE per-pixel: [WhiteBalance] (with fixed [FinishingStats.wbGains]), [ToneCurve],
 *    [Saturation], [Contrast] -- output at a pixel depends only on that pixel.
 *  - WINDOWED-local: [ChromaDenoiser], [LocalToneMapper], [DetailEnhancer]'s spatial part,
 *    [ChromaRollOff]'s isolation gate, and [SemanticRendering]'s sky chroma smoothing --
 *    output at a pixel depends only on input within a bounded radius.
 *  - GLOBAL-statistic: [WhiteBalance] gain estimation and [DetailEnhancer]'s coring knee
 *    -- fixed once in [FinishingStats] and passed in, so no tile re-estimates them.
 *
 * With the global stats fixed, the whole chain is a purely LOCAL operator: the finished
 * value at a pixel depends only on merged input within a finite radius -- the SUM of the
 * per-stage supports. Each [GuidedFilter] pass is two [BoxBlur] passes, so its support is
 * `2 * radius`, giving per-stage supports of chroma `2*4 = 8`, local-tone `2*16 = 32`,
 * detail `2*3 = 6` (its 3x3 morphology adds 1, subsumed); [ChromaRollOff]'s isolation gate
 * is a SINGLE [BoxBlur] of the chroma-magnitude plane, so its support is its neighbourhood
 * radius -- resolution-adaptive since issue #114 ([ChromaRollOffParams.forImageWidth]) and
 * bounded by [ChromaRollOffParams.MAX_NEIGHBORHOOD_RADIUS] `= 96`, which is what the
 * compile-time chain must be sized for; and [SemanticRendering]'s sky chroma smoothing adds
 * `2*8 = 16` at the tail. The pure stages add 0. The worst-case chain support is therefore
 * `8 + 32 + 6 + 96 + 16 = 158` px ([SUPPORT_RADIUS]). The windowed-local mask pieces do NOT
 * extend this, because they feed a stage as per-pixel coefficients rather than widening its
 * read radius and their own reach is inside the chain support: the [SkinMask] modulation
 * (denoised support 8, blur radius 8 -> reach 16), the [SemanticRendering] sky/foliage
 * masks (denoised support 8, single-pass blur radius 12 -> reach 20) and the [OvercastSkyMask] (denoised
 * support 8, texture detail energy two sequential box means 2*12 = 24, mask blur 12 ->
 * reach 44), all under 158. The two tail
 * stages DO widen the read radius in sequence: the roll-off's local reference reads a
 * radius-<=96 neighbourhood of the post-[Contrast] frame (reach 8+32+6 = 46 from the input,
 * so <=142 total), and the sky chroma smoothing reads a `2*8 = 16` neighbourhood of the
 * post-roll-off frame, closing the chain at 158. Give every tile a halo of at least that
 * many pixels of real neighbouring content and its CORE (the halo cropped off) sees exactly
 * the neighbourhood the whole-frame pass saw, so tile cores agree in their interior with no
 * feathering. [OVERLAP] is 160 (>= 158, a 2 px margin). Sizing the constants for the radius
 * CEILING costs halo area on every tiled finish (padded tile `512 + 2*160 = 832` px vs 688
 * before, ~1.46x the per-tile pixels, ~106 MB of float working set per tile -- still under
 * the ~120 MB analysis-pass transient that dominates [peakBytesEstimate], so the documented
 * 12.5 MP peak is unchanged); the actual radius at 12 MP is ~63, so part of that halo is
 * margin, the price of keeping [OVERLAP] a compile-time constant. The sky position prior is
 * geometric, not content-derived, so each tile reproduces it exactly from its absolute row
 * offset (see [finishRegion]) -- it adds no spatial support. The roll-off radius is derived
 * from the FULL image width, threaded into [finishRegion], never from the padded tile
 * width, so every tile gates with the whole-frame radius.
 *
 * ## The one caveat: box-blur running sums are accumulation-order sensitive
 *
 * [BoxBlur] slides an incremental running sum along each row/column, so the floating-
 * point value at a pixel depends on where the sum was seeded. A tile seeds its sums at its
 * own edge, the whole frame at the image edge, so the two accumulate the same window
 * contents along different paths and can differ by ~1 ULP. That difference almost never
 * survives the final round-to-8-bit, so tiled output is metric-identical (and usually
 * byte-identical on small frames) to the whole-frame path with the same stats, but it is
 * not GUARANTEED bit-identical for arbitrary content -- see TiledFinishingBitIdentityTest,
 * which measures the actual deviation. This is inherent to the shared incremental
 * [BoxBlur] and is not removed by a larger halo.
 *
 * Deterministic and free of Android dependencies.
 */
object TiledFinishing {

    /** Worst-case chain spatial support in pixels (chroma 8 + local-tone 32 + detail 6 +
     *  roll-off gate ceiling [ChromaRollOffParams.MAX_NEIGHBORHOOD_RADIUS] 96 + sky-smooth
     *  16); see class doc. */
    const val SUPPORT_RADIUS: Int = 158

    /** Tile halo in pixels: >= [SUPPORT_RADIUS], with a small margin. */
    const val OVERLAP: Int = 160

    /** Default core tile side; the padded tile is `tileSize + 2 * OVERLAP` per axis. */
    const val DEFAULT_TILE_SIZE: Int = 512

    /**
     * Target pixel budget for the analysis (stats) frame. Above this the merged frame is
     * block-averaged down before the global stats are estimated, so the stats pass costs a
     * fixed fraction of the frame rather than a full-resolution guided-filter run. ~1 MP
     * keeps the white-balance gains and detail sigma statistically indistinguishable from
     * the full-frame estimate (a ~4x linear downsample of a 12.5 MP frame) while bounding
     * the analysis working set. See TiledFinishingStatsApproximationTest. The whole-frame
     * [FinishingPipeline] path shares this budget (via [analysisFrame]) for its
     * white-balance gain estimation (issue #117).
     */
    const val ANALYSIS_TARGET_PIXELS: Long = 1_000_000L

    /**
     * Conservative upper bound on the number of full-tile-size `DoubleArray` planes the
     * finishing chain holds live at once. Two stages contend for the peak: [LocalToneMapper]'s
     * [GuidedFilter.selfGuided] holds the luma plane plus meanI, meanII, squares, a, b, meanA,
     * meanB and the output (~9), and a [BoxBlur] call adds two scratch planes (~11); and
     * [SemanticRendering]'s sky chroma smoothing, which holds luma, cb, cr and the first
     * filtered chroma plane (4) plus the three semantic mask planes (sky, overcast, foliage)
     * while a [GuidedFilter.guided] pass over the second chroma plane holds its ~9 estimator
     * planes plus two [BoxBlur] scratch (4 + 3 + 11 = 18, the peak). One more is budgeted
     * for the shared denoised-state luma plane (issue #113), which goes dead after the
     * local-tone stage but is conservatively counted as retained through the tail
     * (4 + 3 + 11 + 1 = 19). Used only by [peakBytesEstimate].
     */
    const val FLOAT_PLANES_PEAK: Int = 19

    /** Full-tile-size `IntArray` buffers live at once (a stage's src + out). */
    const val INT_PLANES_PEAK: Int = 2

    /**
     * Timing-hook label (issue #115) for the one-off global-stats analysis pass (the
     * block-averaged downsample plus [FinishingStats.compute]), reported once per tiled
     * finish when no precomputed stats are supplied. The per-stage labels inside
     * [finishRegion] are [FinishingPipeline]'s STAGE_* constants, fired once per tile.
     */
    const val STAGE_STATS_ANALYSIS = "stats-analysis"

    private const val BYTES_PER_DOUBLE = 8L
    private const val BYTES_PER_INT = 4L

    /**
     * Finishes [frame] in tiles. [stats] may be supplied (the bit-identity reference path
     * passes stats computed on the whole frame); when null they are estimated from a
     * block-averaged analysis frame of at most [ANALYSIS_TARGET_PIXELS] pixels -- the
     * production auto route, whose stats are a near-identical approximation of the
     * whole-frame statistics rather than exact.
     *
     * @param tileSize core tile side (padded by [overlap] on every side that has room).
     * @param overlap halo width; must be >= [SUPPORT_RADIUS] for seam-free cores.
     * @param timingHook optional per-stage wall-clock reporter (issue #115), invoked on the
     *   calling thread with [STAGE_STATS_ANALYSIS] once and each [FinishingPipeline] STAGE_*
     *   label once per tile; see [FinishingPipeline.apply]. Null (the default) takes the
     *   bare untimed path.
     */
    fun apply(
        frame: Frame,
        params: FinishingParams = FinishingParams.DEFAULT,
        tileSize: Int = DEFAULT_TILE_SIZE,
        overlap: Int = OVERLAP,
        stats: FinishingStats? = null,
        timingHook: ((stage: String, nanos: Long) -> Unit)? = null,
    ): Frame {
        require(tileSize > 0) { "tileSize must be positive" }
        require(overlap >= 0) { "overlap must be >= 0" }
        val width = frame.width
        val height = frame.height
        if (width == 0 || height == 0) {
            return FinishingPipeline.forceOpaque(frame)
        }

        val effectiveStats = stats ?: timedStage(timingHook, STAGE_STATS_ANALYSIS) {
            FinishingStats.compute(analysisFrame(frame), params)
        }

        val out = IntArray(width * height)
        var cy0 = 0
        while (cy0 < height) {
            val cy1 = minOf(cy0 + tileSize, height)
            var cx0 = 0
            while (cx0 < width) {
                val cx1 = minOf(cx0 + tileSize, width)
                finishTile(frame, out, cx0, cy0, cx1, cy1, overlap, params, effectiveStats, timingHook)
                cx0 = cx1
            }
            cy0 = cy1
        }
        return Frame(width, height, out, frame.timestampMillis)
    }

    /**
     * Extracts the padded region around core [cx0,cx1) x [cy0,cy1) (clamped to the frame),
     * finishes it with the fixed [stats], and copies the core rows back into [out].
     */
    private fun finishTile(
        frame: Frame,
        out: IntArray,
        cx0: Int,
        cy0: Int,
        cx1: Int,
        cy1: Int,
        overlap: Int,
        params: FinishingParams,
        stats: FinishingStats,
        timingHook: ((stage: String, nanos: Long) -> Unit)? = null,
    ) {
        val width = frame.width
        val height = frame.height
        val px0 = max(0, cx0 - overlap)
        val py0 = max(0, cy0 - overlap)
        val px1 = minOf(width, cx1 + overlap)
        val py1 = minOf(height, cy1 + overlap)
        val tileW = px1 - px0
        val tileH = py1 - py0

        val src = frame.argb
        val tileArgb = IntArray(tileW * tileH)
        for (y in 0 until tileH) {
            val srcRow = (py0 + y) * width + px0
            val dstRow = y * tileW
            System.arraycopy(src, srcRow, tileArgb, dstRow, tileW)
        }
        val tile = Frame(tileW, tileH, tileArgb, frame.timestampMillis)

        // The tile's first row within the full image, so SemanticRendering's sky POSITION
        // prior matches the whole-frame weight at each absolute row; the full-image width,
        // so ChromaRollOff's resolution-adaptive gate radius matches the whole-frame one.
        val finished = finishRegion(tile, params, stats, py0, height, width, timingHook).argb
        for (y in cy0 until cy1) {
            val tileRow = (y - py0) * tileW + (cx0 - px0)
            val outRow = y * width + cx0
            System.arraycopy(finished, tileRow, out, outRow, cx1 - cx0)
        }
    }

    /**
     * Runs the [FinishingPipeline] stage chain on one [tile] with the GLOBAL [stats]
     * pinned: white balance applies the fixed gains ([WhiteBalance.applyGains]) instead of
     * re-estimating, and detail enhancement uses the fixed knee. Stage order, guards and
     * parameter mapping mirror [FinishingPipeline.apply] exactly, so a tile core computes
     * the same values the whole-frame path does (subject to the box-blur caveat in the
     * class doc).
     *
     * [rowOffset] is the tile's first row within the full image and [imageHeight] the
     * full-image height; both are threaded ONLY into [SemanticRendering]'s [SkyMask] and
     * [OvercastSkyMask] position priors so a tile reproduces the whole-frame vertical weight
     * at each absolute row (the chroma/luma/texture priors and the sky chroma smoothing are
     * local to the tile). [imageWidth] is the full-image width, threaded ONLY into
     * [ChromaRollOff]'s resolution-adaptive gate radius
     * ([FinishingPipeline.applyChromaRollOff], issue #114) so a tile gates with the
     * whole-frame radius rather than one derived from its own padded width. [timingHook]
     * reports each stage's wall-clock nanos for THIS tile (issue #115, see
     * [FinishingPipeline.apply]); null takes the bare untimed path.
     */
    internal fun finishRegion(
        tile: Frame,
        params: FinishingParams,
        stats: FinishingStats,
        rowOffset: Int = 0,
        imageHeight: Int = tile.height,
        imageWidth: Int = tile.width,
        timingHook: ((stage: String, nanos: Long) -> Unit)? = null,
    ): Frame {
        val balanced = timedStage(timingHook, FinishingPipeline.STAGE_WHITE_BALANCE) {
            if (params.whiteBalance > 0.0) {
                WhiteBalance.applyGains(tile, stats.wbGains, params.whiteBalance)
            } else {
                tile
            }
        }
        val denoised = timedStage(timingHook, FinishingPipeline.STAGE_CHROMA_DENOISE) {
            if (params.chromaDenoise > 0.0) {
                ChromaDenoiser.apply(balanced, params.chromaDenoise)
            } else {
                balanced
            }
        }
        // Shared luma plane of the denoised TILE (issue #113): per-tile scope, no cross-tile
        // state -- extracted once here and read by the skin/sky/overcast/foliage priors and
        // the local tone mapper below, exactly as FinishingPipeline.apply shares it on the
        // whole frame. The values are bit-identical to each stage's own extraction, so tile
        // cores are unaffected.
        val sharedLuma = timedStage(timingHook, FinishingPipeline.STAGE_SHARED_LUMA) {
            FinishingPipeline.sharedLumaPlane(denoised, params)
        }
        // Skin-protection modulation is WINDOWED-local (SkinMask blur radius 8 -> support
        // 16 px, well under OVERLAP), so a tile core computes the same plane the whole-frame
        // path does and the finish stays seam-free. Computed identically to FinishingPipeline
        // via the shared helper.
        val skinModulation = timedStage(timingHook, FinishingPipeline.STAGE_SKIN_MASK) {
            FinishingPipeline.skinModulation(denoised, params, sharedLuma)
        }
        // Semantic sky/overcast/foliage masks, computed on the denoised tile exactly as the
        // whole-frame path does. Their reach (32 px for sky/foliage, 44 px for the overcast
        // texture prior -- see the class doc) is inside OVERLAP and both sky position priors
        // use the absolute row offset, so the boost is seam-consistent tile-vs-whole-frame.
        val semanticMasks = timedStage(timingHook, FinishingPipeline.STAGE_SEMANTIC_MASKS) {
            FinishingPipeline.semanticMasks(denoised, params, rowOffset, imageHeight, sharedLuma)
        }
        val locallyMapped = timedStage(timingHook, FinishingPipeline.STAGE_LOCAL_TONE) {
            if (params.localContrast > 0.0) {
                LocalToneMapper.apply(denoised, FinishingPipeline.localToneParams(params.localContrast), skinModulation, sharedLuma)
            } else {
                denoised
            }
        }
        val enhanced = timedStage(timingHook, FinishingPipeline.STAGE_DETAIL_ENHANCE) {
            if (params.detailEnhance > 0.0) {
                DetailEnhancer.apply(locallyMapped, FinishingPipeline.detailParams(params.detailEnhance), stats.detailKnee, skinModulation)
            } else {
                locallyMapped
            }
        }
        val contrasted = timedStage(timingHook, FinishingPipeline.STAGE_TONE_SAT_CONTRAST) {
            val toned = ToneCurve(params.shadowsLift, params.highlightRolloff).apply(enhanced)
            val saturated = Saturation.apply(toned, params.saturation, skinModulation)
            Contrast.apply(saturated, params.contrast)
        }
        // Chroma roll-off is WINDOWED-local (its isolation gate box-means the chroma-
        // magnitude plane over the width-scaled radius, whose ceiling SUPPORT_RADIUS is
        // sized for) with no global statistic, so a tile core computes the same values the
        // whole-frame path does -- it needs nothing from FinishingStats. The radius derives
        // from the FULL image width (issue #114), so every tile gates exactly as the
        // whole-frame path. Applied identically to FinishingPipeline via the shared helper
        // (subject to the same box-blur caveat as the other windowed stages).
        val rolledOff = timedStage(timingHook, FinishingPipeline.STAGE_CHROMA_ROLL_OFF) {
            FinishingPipeline.applyChromaRollOff(contrasted, params, imageWidth)
        }
        // Semantic rendering runs AFTER the roll-off (see FinishingPipeline.apply): its sky
        // chroma smoothing is WINDOWED-local (radius 8 -> support 16, inside OVERLAP) and its
        // per-pixel boosts are element-wise, so a tile core matches the whole-frame path
        // (subject to the same box-blur caveat as the other windowed stages).
        val semanticRendered = timedStage(timingHook, FinishingPipeline.STAGE_SEMANTIC_RENDER) {
            FinishingPipeline.applySemanticRendering(rolledOff, semanticMasks, params)
        }
        return FinishingPipeline.forceOpaque(semanticRendered)
    }

    /**
     * The frame the global stats are estimated from: [frame] itself when it is already at
     * or under [ANALYSIS_TARGET_PIXELS], otherwise a block-averaged downsample by
     * [analysisDownsampleFactor]. Shared with the whole-frame [FinishingPipeline] path,
     * which estimates its white-balance gains on the same bounded frame (issue #117).
     */
    internal fun analysisFrame(frame: Frame): Frame {
        val factor = analysisDownsampleFactor(frame.width, frame.height)
        return if (factor <= 1) frame else downsample(frame, factor)
    }

    /**
     * Smallest integer factor for which [width]x[height] block-averages to at most
     * [ANALYSIS_TARGET_PIXELS] pixels (1 when already within budget).
     */
    internal fun analysisDownsampleFactor(width: Int, height: Int): Int {
        if (width.toLong() * height.toLong() <= ANALYSIS_TARGET_PIXELS) return 1
        var factor = 2
        while ((width / factor).toLong() * (height / factor).toLong() > ANALYSIS_TARGET_PIXELS) {
            factor++
        }
        return factor
    }

    /**
     * Block-average downsample of [frame] by an integer [factor]: output pixel (x, y) is
     * the mean colour over the `factor x factor` source block at (x*factor, y*factor),
     * with partial edge blocks averaging only the pixels that exist. Deterministic; alpha
     * forced opaque.
     */
    internal fun downsample(frame: Frame, factor: Int): Frame {
        require(factor >= 1) { "factor must be >= 1" }
        val width = frame.width
        val height = frame.height
        val outW = max(1, width / factor)
        val outH = max(1, height / factor)
        val src = frame.argb
        val out = IntArray(outW * outH)
        for (oy in 0 until outH) {
            val sy0 = oy * factor
            val sy1 = minOf(sy0 + factor, height)
            for (ox in 0 until outW) {
                val sx0 = ox * factor
                val sx1 = minOf(sx0 + factor, width)
                var sumR = 0L
                var sumG = 0L
                var sumB = 0L
                var count = 0
                for (sy in sy0 until sy1) {
                    val row = sy * width
                    for (sx in sx0 until sx1) {
                        val pixel = src[row + sx]
                        sumR += (pixel shr 16) and 0xFF
                        sumG += (pixel shr 8) and 0xFF
                        sumB += pixel and 0xFF
                        count++
                    }
                }
                val r = (sumR.toDouble() / count).roundToInt().coerceIn(0, 255)
                val g = (sumG.toDouble() / count).roundToInt().coerceIn(0, 255)
                val b = (sumB.toDouble() / count).roundToInt().coerceIn(0, 255)
                out[oy * outW + ox] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        return Frame(outW, outH, out, frame.timestampMillis)
    }

    /**
     * Theoretical peak heap (bytes) of a tiled finish of a [width]x[height] frame with the
     * given [tileSize]/[overlap]: the resident input and output `IntArray`s (`4 * pixels`
     * each) plus the larger of the two transient working sets that never overlap in time --
     * the analysis stats pass over the downsampled frame, and one tile's finishing chain.
     * Each transient is `FLOAT_PLANES_PEAK` doubles + `INT_PLANES_PEAK` ints per pixel over
     * its pixel count. Pure arithmetic, so the memory-ceiling test is deterministic rather
     * than a flaky live-heap probe.
     */
    fun peakBytesEstimate(
        width: Int,
        height: Int,
        tileSize: Int = DEFAULT_TILE_SIZE,
        overlap: Int = OVERLAP,
    ): Long {
        val pixels = width.toLong() * height.toLong()
        val residentInt = 2L * pixels * BYTES_PER_INT

        val paddedSide = (tileSize + 2L * overlap)
        val tilePixels = paddedSide * paddedSide
        val tileTransient = workingSetBytes(tilePixels)

        val factor = analysisDownsampleFactor(width, height)
        val analysisPixels = (width / factor).toLong() * (height / factor).toLong()
        val analysisTransient = workingSetBytes(analysisPixels)

        return residentInt + max(tileTransient, analysisTransient)
    }

    /**
     * Theoretical peak heap (bytes) of the WHOLE-FRAME finish of a [width]x[height] frame:
     * the resident input `IntArray` plus the finishing chain's float+int working set over
     * the ENTIRE pixel count. Provided so the memory test can assert the tiled peak is a
     * small fraction of it.
     */
    fun wholeFramePeakBytesEstimate(width: Int, height: Int): Long {
        val pixels = width.toLong() * height.toLong()
        return pixels * BYTES_PER_INT + workingSetBytes(pixels)
    }

    private fun workingSetBytes(pixels: Long): Long =
        pixels * (FLOAT_PLANES_PEAK * BYTES_PER_DOUBLE + INT_PLANES_PEAK * BYTES_PER_INT)
}
