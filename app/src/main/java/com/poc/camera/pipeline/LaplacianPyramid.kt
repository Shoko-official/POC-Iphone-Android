package com.poc.camera.pipeline

/**
 * Laplacian pyramid built on top of a [GaussianPyramid], the band-pass decomposition
 * that drives seamless multi-scale exposure fusion (see [LaplacianExposureFusion]).
 *
 * Level `i` is the detail lost by one REDUCE/EXPAND round trip,
 * `L_i = G_i - EXPAND(G_{i+1})`, for every level but the coarsest; the coarsest level
 * is the residual low-pass `G_last` verbatim. [collapse] inverts this exactly:
 * starting from the residual and folding in each finer band,
 * `C_i = L_i + EXPAND(C_{i+1})`, reconstructs `G_0` up to floating-point rounding,
 * because [build] and [collapse] use the identical [GaussianPyramid.upsample] so the
 * EXPAND terms telescope. Working in doubles keeps that round-trip error at the
 * rounding floor rather than accumulating quantisation.
 *
 * Every level operation is per-pixel from read-only inputs, so the row loops meet the
 * [PipelineParallel] determinism contract. Pure Kotlin, no Android dependencies.
 */
object LaplacianPyramid {

    /**
     * Builds the Laplacian pyramid from a pre-computed [gaussian] pyramid (finest
     * first). Returns one band per Gaussian level: bands `0..n-2` are
     * `G_i - EXPAND(G_{i+1})` and band `n-1` is the coarsest Gaussian level itself.
     */
    fun build(
        gaussian: List<PyramidPlane>,
        chunkCount: Int = PipelineParallel.parallelism,
    ): List<PyramidPlane> {
        require(gaussian.isNotEmpty()) { "gaussian pyramid must not be empty" }
        val n = gaussian.size
        val bands = ArrayList<PyramidPlane>(n)
        for (i in 0 until n - 1) {
            val fine = gaussian[i]
            val expanded = GaussianPyramid.upsample(gaussian[i + 1], fine.width, fine.height, chunkCount)
            val band = DoubleArray(fine.data.size)
            val w = fine.width
            PipelineParallel.parallelRows(fine.height, chunkCount) { yStart, yEnd ->
                for (p in yStart * w until yEnd * w) {
                    band[p] = fine.data[p] - expanded.data[p]
                }
            }
            bands.add(PyramidPlane(fine.width, fine.height, band))
        }
        bands.add(gaussian[n - 1])
        return bands
    }

    /**
     * Collapses a Laplacian pyramid [bands] back to a single finest-level plane by
     * folding each band into the running reconstruction from coarsest to finest.
     */
    fun collapse(
        bands: List<PyramidPlane>,
        chunkCount: Int = PipelineParallel.parallelism,
    ): PyramidPlane {
        require(bands.isNotEmpty()) { "bands must not be empty" }
        var cur = bands.last()
        for (i in bands.size - 2 downTo 0) {
            val fine = bands[i]
            val expanded = GaussianPyramid.upsample(cur, fine.width, fine.height, chunkCount)
            val out = DoubleArray(fine.data.size)
            val w = fine.width
            PipelineParallel.parallelRows(fine.height, chunkCount) { yStart, yEnd ->
                for (p in yStart * w until yEnd * w) {
                    out[p] = fine.data[p] + expanded.data[p]
                }
            }
            cur = PyramidPlane(fine.width, fine.height, out)
        }
        return cur
    }
}
