package com.poc.camera.pipeline

/**
 * Separable box blur of a scalar field, shared by the merge stack (weight-map
 * smoothing in [ExposureFusion]) and the finishing stack (base-layer estimation in
 * [GuidedFilter]).
 *
 * A horizontal pass into scratch then a vertical pass into the output both use
 * running sums, so cost is O(pixels) independent of [radius]. Edges are clamped to
 * the nearest valid sample and the window is normalised, so [blur] returns the box
 * MEAN over each (2*[radius]+1) neighbourhood. Pure Kotlin, deterministic, no
 * Android dependencies.
 *
 * Both passes are row/column parallel via [PipelineParallel]: each row of the
 * horizontal pass re-seeds its own running sum at x = 0 and each column of the
 * vertical pass re-seeds its own running sum at y = 0, so rows and columns are fully
 * independent and the output is bit-identical to the serial path (see [chunkCount]).
 */
object BoxBlur {

    /**
     * Box mean of a [width]x[height] scalar field with a (2*[radius]+1) window per
     * axis, edges clamped to the nearest valid sample.
     *
     * @param chunkCount how many parallel chunks each pass is split into; the default
     *   is one per worker. Passing [PipelineParallel.SERIAL_CHUNKS] forces the serial
     *   path, which is the deterministic reference the parallel path is proven against.
     */
    fun blur(
        src: DoubleArray,
        width: Int,
        height: Int,
        radius: Int,
        chunkCount: Int = PipelineParallel.parallelism,
    ): DoubleArray {
        require(radius >= 0) { "radius must be >= 0" }
        val horizontal = DoubleArray(src.size)
        val window = 2 * radius + 1
        // Horizontal pass -- rows are independent (each re-seeds its running sum at x = 0).
        PipelineParallel.parallelRows(height, chunkCount) { yStart, yEnd ->
            for (y in yStart until yEnd) {
                val row = y * width
                var sum = 0.0
                // Seed the window at x = 0 with clamped left edge.
                for (k in -radius..radius) {
                    sum += src[row + k.coerceIn(0, width - 1)]
                }
                horizontal[row] = sum / window
                for (x in 1 until width) {
                    val leaving = (x - radius - 1).coerceIn(0, width - 1)
                    val entering = (x + radius).coerceIn(0, width - 1)
                    sum += src[row + entering] - src[row + leaving]
                    horizontal[row + x] = sum / window
                }
            }
        }
        // Vertical pass -- columns are independent (each re-seeds its running sum at y = 0).
        val out = DoubleArray(src.size)
        PipelineParallel.parallelRows(width, chunkCount) { xStart, xEnd ->
            for (x in xStart until xEnd) {
                var sum = 0.0
                for (k in -radius..radius) {
                    sum += horizontal[k.coerceIn(0, height - 1) * width + x]
                }
                out[x] = sum / window
                for (y in 1 until height) {
                    val leaving = (y - radius - 1).coerceIn(0, height - 1)
                    val entering = (y + radius).coerceIn(0, height - 1)
                    sum += horizontal[entering * width + x] - horizontal[leaving * width + x]
                    out[y * width + x] = sum / window
                }
            }
        }
        return out
    }
}
