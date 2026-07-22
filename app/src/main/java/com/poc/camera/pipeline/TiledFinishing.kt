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
 *    [Saturation], [Contrast], [ChromaRollOff] -- output at a pixel depends only on that
 *    pixel.
 *  - WINDOWED-local: [ChromaDenoiser], [LocalToneMapper], and [DetailEnhancer]'s spatial
 *    part -- output at a pixel depends only on input within a bounded radius.
 *  - GLOBAL-statistic: [WhiteBalance] gain estimation and [DetailEnhancer]'s coring knee
 *    -- fixed once in [FinishingStats] and passed in, so no tile re-estimates them.
 *
 * With the global stats fixed, the whole chain is a purely LOCAL operator: the finished
 * value at a pixel depends only on merged input within a finite radius -- the SUM of the
 * per-stage supports. Each [GuidedFilter] pass is two [BoxBlur] passes, so its support is
 * `2 * radius`, giving per-stage supports of chroma `2*4 = 8`, local-tone `2*16 = 32`,
 * detail `2*3 = 6` (its 3x3 morphology adds 1, subsumed). The pure stages add 0. The
 * chain support is therefore `8 + 32 + 6 = 46` px ([SUPPORT_RADIUS]). The [SkinMask]
 * modulation is also windowed-local but does NOT extend this: it is computed on the
 * denoised frame (support 8) blurred by radius 8 (support 16), so its dependency reaches
 * only 16 px from a core pixel -- inside the 46 px chain support -- and it feeds the
 * modulated stages as a per-pixel coefficient rather than widening their read radius. Give
 * every tile a halo of at least that many pixels of real neighbouring content and its CORE
 * (the halo cropped off) sees exactly the neighbourhood the whole-frame pass saw, so tile cores
 * agree in their interior with no feathering. [OVERLAP] is 48 (>= 46, a 2 px margin).
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

    /** Chain spatial support in pixels (chroma 8 + local-tone 32 + detail 6); see class doc. */
    const val SUPPORT_RADIUS: Int = 46

    /** Tile halo in pixels: >= [SUPPORT_RADIUS], with a small margin. */
    const val OVERLAP: Int = 48

    /** Default core tile side; the padded tile is `tileSize + 2 * OVERLAP` per axis. */
    const val DEFAULT_TILE_SIZE: Int = 512

    /**
     * Target pixel budget for the analysis (stats) frame. Above this the merged frame is
     * block-averaged down before the global stats are estimated, so the stats pass costs a
     * fixed fraction of the frame rather than a full-resolution guided-filter run. ~1 MP
     * keeps the white-balance gains and detail sigma statistically indistinguishable from
     * the full-frame estimate (a ~4x linear downsample of a 12.5 MP frame) while bounding
     * the analysis working set. See TiledFinishingStatsApproximationTest.
     */
    const val ANALYSIS_TARGET_PIXELS: Long = 1_000_000L

    /**
     * Conservative upper bound on the number of full-tile-size `DoubleArray` planes the
     * finishing chain holds live at once. The heaviest stage is [LocalToneMapper]'s
     * [GuidedFilter.selfGuided]: it holds the luma plane plus meanI, meanII, squares, a, b,
     * meanA, meanB and the output (~9), and a [BoxBlur] call adds two scratch planes, so
     * ~11 are live at peak; 12 adds margin. Used only by [peakBytesEstimate].
     */
    const val FLOAT_PLANES_PEAK: Int = 12

    /** Full-tile-size `IntArray` buffers live at once (a stage's src + out). */
    const val INT_PLANES_PEAK: Int = 2

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
     */
    fun apply(
        frame: Frame,
        params: FinishingParams = FinishingParams.DEFAULT,
        tileSize: Int = DEFAULT_TILE_SIZE,
        overlap: Int = OVERLAP,
        stats: FinishingStats? = null,
    ): Frame {
        require(tileSize > 0) { "tileSize must be positive" }
        require(overlap >= 0) { "overlap must be >= 0" }
        val width = frame.width
        val height = frame.height
        if (width == 0 || height == 0) {
            return FinishingPipeline.forceOpaque(frame)
        }

        val effectiveStats = stats ?: FinishingStats.compute(analysisFrame(frame), params)

        val out = IntArray(width * height)
        var cy0 = 0
        while (cy0 < height) {
            val cy1 = minOf(cy0 + tileSize, height)
            var cx0 = 0
            while (cx0 < width) {
                val cx1 = minOf(cx0 + tileSize, width)
                finishTile(frame, out, cx0, cy0, cx1, cy1, overlap, params, effectiveStats)
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

        val finished = finishRegion(tile, params, stats).argb
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
     */
    internal fun finishRegion(tile: Frame, params: FinishingParams, stats: FinishingStats): Frame {
        val balanced = if (params.whiteBalance > 0.0) {
            WhiteBalance.applyGains(tile, stats.wbGains, params.whiteBalance)
        } else {
            tile
        }
        val denoised = if (params.chromaDenoise > 0.0) {
            ChromaDenoiser.apply(balanced, params.chromaDenoise)
        } else {
            balanced
        }
        // Skin-protection modulation is WINDOWED-local (SkinMask blur radius 8 -> support
        // 16 px, well under OVERLAP 48), so a tile core computes the same plane the
        // whole-frame path does and the finish stays seam-free. Computed identically to
        // FinishingPipeline via the shared helper.
        val skinModulation = FinishingPipeline.skinModulation(denoised, params)
        val locallyMapped = if (params.localContrast > 0.0) {
            LocalToneMapper.apply(denoised, FinishingPipeline.localToneParams(params.localContrast), skinModulation)
        } else {
            denoised
        }
        val enhanced = if (params.detailEnhance > 0.0) {
            DetailEnhancer.apply(locallyMapped, FinishingPipeline.detailParams(params.detailEnhance), stats.detailKnee, skinModulation)
        } else {
            locallyMapped
        }
        val toned = ToneCurve(params.shadowsLift, params.highlightRolloff).apply(enhanced)
        val saturated = Saturation.apply(toned, params.saturation, skinModulation)
        val contrasted = Contrast.apply(saturated, params.contrast)
        // Chroma roll-off is a PURE per-pixel op (no spatial support, no global statistic),
        // so a tile core computes the same values the whole-frame path does -- it needs
        // nothing from FinishingStats and adds no seam. Applied identically to
        // FinishingPipeline via the shared helper.
        val rolledOff = FinishingPipeline.applyChromaRollOff(contrasted, params)
        return FinishingPipeline.forceOpaque(rolledOff)
    }

    /**
     * The frame the global stats are estimated from: [frame] itself when it is already at
     * or under [ANALYSIS_TARGET_PIXELS], otherwise a block-averaged downsample by
     * [analysisDownsampleFactor].
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
