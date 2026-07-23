package com.poc.camera.pipeline

/**
 * Separable windowed maximum (grayscale morphological dilation) of a scalar field, the
 * boundary-robust companion to [BoxBlur]'s windowed mean.
 *
 * A horizontal pass into scratch then a vertical pass into the output each compute, per
 * sample, the maximum of the `(2*radius+1)` neighbourhood along that axis, edges clamped to
 * the nearest valid sample (identical edge convention to [BoxBlur]). Each line runs a
 * monotonic deque of candidate indices in strictly-decreasing value order, so the running
 * maximum is available in O(1) amortised and the whole operator is O(pixels) independent of
 * [radius] -- the same cost class as [BoxBlur], not the O(radius*pixels) of a naive window.
 *
 * Both passes are row/column parallel via [PipelineParallel]: each line owns its own deque and
 * re-seeds at index 0, so lines are fully independent and the result is bit-identical across
 * chunk counts (the maximum is order-independent, so there is no float-summation reordering to
 * worry about). Pure Kotlin, deterministic, no Android dependencies.
 */
object WindowedMax {

    /**
     * Windowed maximum of a [width]x[height] scalar field with a `(2*radius+1)` window per
     * axis, edges clamped to the nearest valid sample. [radius] 0 is the identity (a copy).
     *
     * @param chunkCount how many parallel chunks each pass is split into; the default is one
     *   per worker. [PipelineParallel.SERIAL_CHUNKS] forces the serial reference path.
     */
    fun dilate(
        src: DoubleArray,
        width: Int,
        height: Int,
        radius: Int,
        chunkCount: Int = PipelineParallel.parallelism,
    ): DoubleArray {
        require(radius >= 0) { "radius must be >= 0" }
        if (radius == 0) return src.copyOf()
        val horizontal = DoubleArray(src.size)
        // Horizontal pass -- rows are independent; one deque buffer reused across the chunk.
        PipelineParallel.parallelRows(height, chunkCount) { yStart, yEnd ->
            val deque = IntArray(width)
            for (y in yStart until yEnd) {
                lineMax(src, y * width, 1, width, radius, horizontal, y * width, 1, deque)
            }
        }
        // Vertical pass -- columns are independent.
        val out = DoubleArray(src.size)
        PipelineParallel.parallelRows(width, chunkCount) { xStart, xEnd ->
            val deque = IntArray(height)
            for (x in xStart until xEnd) {
                lineMax(horizontal, x, width, height, radius, out, x, width, deque)
            }
        }
        return out
    }

    /**
     * Windowed max along a single line of [len] samples read from [src] at [srcOffset] with
     * [srcStride], written to [dst] at [dstOffset]/[dstStride]. Window at index i is
     * `[i-radius, i+radius]` clamped to `[0, len)`; because clamping repeats the boundary
     * sample, that clamped max equals the max over the intersection `[max(0,i-radius),
     * min(len-1,i+radius)]`. [deque] is a caller-provided scratch of at least [len] ints
     * holding line-local indices in strictly-decreasing sample order (front = current max).
     */
    @Suppress("LongParameterList")
    private fun lineMax(
        src: DoubleArray,
        srcOffset: Int,
        srcStride: Int,
        len: Int,
        radius: Int,
        dst: DoubleArray,
        dstOffset: Int,
        dstStride: Int,
        deque: IntArray,
    ) {
        var head = 0
        var tail = 0
        var nextAdd = 0
        for (i in 0 until len) {
            val right = minOf(len - 1, i + radius)
            while (nextAdd <= right) {
                val v = src[srcOffset + nextAdd * srcStride]
                while (tail > head && src[srcOffset + deque[tail - 1] * srcStride] <= v) tail--
                deque[tail++] = nextAdd
                nextAdd++
            }
            val left = maxOf(0, i - radius)
            while (deque[head] < left) head++
            dst[dstOffset + i * dstStride] = src[srcOffset + deque[head] * srcStride]
        }
    }
}
