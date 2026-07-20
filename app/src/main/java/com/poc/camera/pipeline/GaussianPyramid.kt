package com.poc.camera.pipeline

/**
 * A single scalar plane of a multi-scale pyramid: a [width] x [height] field of
 * doubles in row-major order. Kept in floating point so pyramid construction,
 * fusion and collapse accumulate without repeated 8-bit quantisation; callers
 * quantise back to codes only at the very end.
 */
class PyramidPlane(val width: Int, val height: Int, val data: DoubleArray) {
    init {
        require(data.size == width * height) { "data size must equal width*height" }
    }
}

/**
 * Burt-Adelson Gaussian pyramid over a scalar [PyramidPlane], the low-pass half of
 * the Laplacian-pyramid exposure fusion (see [LaplacianExposureFusion]).
 *
 * ## Downsample (REDUCE)
 *
 * A separable 5-tap binomial kernel [1, 4, 6, 4, 1] / 16 is convolved with the
 * plane and the result decimated by two, in two passes (horizontal then vertical).
 * For an output sample at column `ox` the five taps read input columns `2*ox + t`,
 * `t in -2..2`, so the kernel is centred on the even input grid; samples past the
 * border are CLAMPED to the nearest valid column/row (replicate edge). A dimension
 * of size `n` reduces to `(n + 1) / 2` (ceil), so odd sizes lose their dangling
 * sample gracefully.
 *
 * ## Upsample (EXPAND)
 *
 * The inverse re-grids a coarse plane onto a finer `(dstW, dstH)` target by
 * zero-insertion (placing coarse sample `k` at fine position `2k`, zeros between)
 * followed by the SAME binomial kernel, with a x2 gain per separable pass (x4
 * total). The gain compensates for the zeros: after zero-insertion only half the
 * taps in each 1-D pass are non-zero, so the kernel would otherwise halve the DC
 * level. Edge samples clamp to the nearest coarse column/row exactly as REDUCE
 * does. The explicit target size lets EXPAND undo a REDUCE of an odd dimension
 * (where `2 * ((n + 1) / 2)` overshoots `n`).
 *
 * Every pass writes each output element from clamped read-only inputs alone, so the
 * row/column loops satisfy the [PipelineParallel] determinism contract and the
 * parallel result is bit-identical to the serial one.
 *
 * Pure Kotlin, deterministic, no Android dependencies.
 */
object GaussianPyramid {

    /** Separable binomial weights; normalised by 16 in REDUCE and by 16 (x2 gain) in EXPAND. */
    private val KERNEL = doubleArrayOf(1.0, 4.0, 6.0, 4.0, 1.0)

    /** Default coarsest-level floor: stop before either dimension would drop below this. */
    const val MIN_DIMENSION = 8

    /**
     * Builds the Gaussian pyramid of [base], finest level first. Downsampling stops
     * once the NEXT level's smaller dimension would fall below [minDimension], or
     * once [maxLevels] levels exist. The finest level is [base] itself (shared, not
     * copied); every coarser level is a fresh [PyramidPlane].
     */
    fun build(
        base: PyramidPlane,
        minDimension: Int = MIN_DIMENSION,
        maxLevels: Int = Int.MAX_VALUE,
        chunkCount: Int = PipelineParallel.parallelism,
    ): List<PyramidPlane> {
        require(minDimension >= 1) { "minDimension must be >= 1" }
        require(maxLevels >= 1) { "maxLevels must be >= 1" }
        val levels = ArrayList<PyramidPlane>()
        levels.add(base)
        var cur = base
        while (levels.size < maxLevels) {
            val nextWidth = (cur.width + 1) / 2
            val nextHeight = (cur.height + 1) / 2
            if (minOf(nextWidth, nextHeight) < minDimension) break
            cur = downsample(cur, chunkCount)
            levels.add(cur)
        }
        return levels
    }

    /**
     * REDUCE: binomial-blur [src] and decimate by two into a `((w+1)/2) x ((h+1)/2)`
     * plane, edges clamped. Separable: horizontal blur+decimate then vertical.
     */
    fun downsample(src: PyramidPlane, chunkCount: Int = PipelineParallel.parallelism): PyramidPlane {
        val w = src.width
        val h = src.height
        val ow = (w + 1) / 2
        val oh = (h + 1) / 2

        // Horizontal: (w, h) -> (ow, h). Each output row is independent.
        val mid = DoubleArray(ow * h)
        PipelineParallel.parallelRows(h, chunkCount) { yStart, yEnd ->
            for (y in yStart until yEnd) {
                val srcRow = y * w
                val dstRow = y * ow
                for (ox in 0 until ow) {
                    val cx = 2 * ox
                    var sum = 0.0
                    for (t in -2..2) {
                        val sx = (cx + t).coerceIn(0, w - 1)
                        sum += KERNEL[t + 2] * src.data[srcRow + sx]
                    }
                    mid[dstRow + ox] = sum / 16.0
                }
            }
        }

        // Vertical: (ow, h) -> (ow, oh). Each output row is independent.
        val out = DoubleArray(ow * oh)
        PipelineParallel.parallelRows(oh, chunkCount) { yStart, yEnd ->
            for (oy in yStart until yEnd) {
                val cy = 2 * oy
                val dstRow = oy * ow
                for (ox in 0 until ow) {
                    var sum = 0.0
                    for (t in -2..2) {
                        val sy = (cy + t).coerceIn(0, h - 1)
                        sum += KERNEL[t + 2] * mid[sy * ow + ox]
                    }
                    out[dstRow + ox] = sum / 16.0
                }
            }
        }
        return PyramidPlane(ow, oh, out)
    }

    /**
     * EXPAND: re-grid [src] onto a `[dstWidth] x [dstHeight]` plane by zero-insertion
     * plus the binomial kernel at x4 total gain, edges clamped. [dstWidth]/[dstHeight]
     * must be the dimensions [src] was reduced FROM (i.e. `(dst+1)/2 == src`).
     */
    fun upsample(
        src: PyramidPlane,
        dstWidth: Int,
        dstHeight: Int,
        chunkCount: Int = PipelineParallel.parallelism,
    ): PyramidPlane {
        val sw = src.width
        val sh = src.height
        require((dstWidth + 1) / 2 == sw && (dstHeight + 1) / 2 == sh) {
            "dst ${dstWidth}x$dstHeight is not an upsample of src ${sw}x$sh"
        }

        // Horizontal expand: (sw, sh) -> (dstWidth, sh). Each output row is independent.
        val mid = DoubleArray(dstWidth * sh)
        PipelineParallel.parallelRows(sh, chunkCount) { yStart, yEnd ->
            for (y in yStart until yEnd) {
                val srcRow = y * sw
                val dstRow = y * dstWidth
                for (dx in 0 until dstWidth) {
                    var sum = 0.0
                    for (t in -2..2) {
                        val q = dx - t
                        // Only even fine positions carry a (non-zero) coarse sample.
                        if ((q and 1) == 0) {
                            val sx = (q / 2).coerceIn(0, sw - 1)
                            sum += KERNEL[t + 2] * src.data[srcRow + sx]
                        }
                    }
                    mid[dstRow + dx] = 2.0 * sum / 16.0
                }
            }
        }

        // Vertical expand: (dstWidth, sh) -> (dstWidth, dstHeight). Rows independent.
        val out = DoubleArray(dstWidth * dstHeight)
        PipelineParallel.parallelRows(dstHeight, chunkCount) { yStart, yEnd ->
            for (dy in yStart until yEnd) {
                val dstRow = dy * dstWidth
                for (dx in 0 until dstWidth) {
                    var sum = 0.0
                    for (t in -2..2) {
                        val q = dy - t
                        if ((q and 1) == 0) {
                            val sy = (q / 2).coerceIn(0, sh - 1)
                            sum += KERNEL[t + 2] * mid[sy * dstWidth + dx]
                        }
                    }
                    out[dstRow + dx] = 2.0 * sum / 16.0
                }
            }
        }
        return PyramidPlane(dstWidth, dstHeight, out)
    }
}
