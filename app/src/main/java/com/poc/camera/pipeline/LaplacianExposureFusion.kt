package com.poc.camera.pipeline

import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Mertens exposure fusion done properly, with multi-scale Laplacian-pyramid blending
 * instead of the single box-blurred weight ramp of [ExposureFusion].
 *
 * For a set of ALIGNED frames tagged with relative EV, each frame gets a per-pixel
 * weight from [ExposureFusionWeights], reshaped by the [DEFAULT_SELECTIVITY] exponent
 * and then NORMALISED across frames per pixel (partition of unity) BEFORE any pyramid
 * work; a pixel that is clipped or crushed in every exposure (all weights 0) falls
 * back to an equal split so the normalisation never divides by zero. Then, per RGB
 * channel:
 *
 *  - a Gaussian pyramid is built of every frame's normalised weight map;
 *  - a Laplacian pyramid is built of every frame's channel;
 *  - each fused band is the weight-blended sum `sum_i G^w_i(level) * L_i(level)`;
 *  - the fused Laplacian pyramid is collapsed and quantised to [0, 255].
 *
 * Blending band-by-band is what removes the halos the box-blur approximation leaves
 * behind: fine detail is blended with fine (small-support) weights and coarse
 * illumination with coarse weights, so a hard exposure-preference boundary no longer
 * leaks one frame's crushed/blown values into the neighbouring region. Because the
 * per-frame weight maps sum to one and REDUCE is linear, the Gaussian weight bands
 * also sum to one at every level, so the fusion preserves overall brightness.
 *
 * ## Memory
 *
 * Channels are fused one at a time; at peak the working set is N frames x one
 * channel's Gaussian+Laplacian pyramids (~2 x 4/3 x pixels doubles) plus the N shared
 * weight pyramids. Fusing the three per-EV MERGED results that [HdrMergePipeline]
 * produces (not the raw burst) keeps N at the exposure count.
 *
 * All per-level loops are per-pixel from read-only inputs and accumulate the frame
 * sum in frame order, so they meet the [PipelineParallel] determinism contract and
 * are bit-identical serial vs parallel. Pure Kotlin, no Android dependencies.
 */
object LaplacianExposureFusion {

    /** Floor below which a pixel's summed weight is treated as "clipped everywhere". */
    private const val WEIGHT_EPS = 1e-6

    /**
     * Selectivity exponent applied to each raw weight before cross-frame normalisation
     * (one reproduces plain Mertens weighting). The clip-avoidance term in
     * [ExposureFusionWeights] already hard-zeroes a frame that is blown or crushed at a
     * pixel, so this exponent only reshapes the split among the exposures that are ALL
     * validly exposed there. Below one it flattens that split, averaging the valid
     * exposures more evenly instead of sharply favouring the single most mid-grey one;
     * that averaging suppresses residual per-frame noise and softens tonal
     * discontinuities across the frame, which measurably improves shadow/highlight
     * fidelity on smooth high-dynamic-range content. The default is deliberately mild
     * so a clearly-better exposure still leads. It has essentially no effect where
     * weights are already near winner-take-all (e.g. a hard bright edge), so it does
     * not trade away halo suppression.
     */
    const val DEFAULT_SELECTIVITY = 0.7

    /**
     * Fuses [frames] using their matching relative exposures [evs].
     *
     * @param frames aligned input frames, all sharing dimensions; must be non-empty.
     * @param evs relative EV per frame, parallel to [frames].
     * @param minDimension coarsest-level floor forwarded to [GaussianPyramid.build].
     * @param maxLevels pyramid depth cap forwarded to [GaussianPyramid.build].
     */
    fun fuse(
        frames: List<Frame>,
        evs: List<Double>,
        selectivity: Double = DEFAULT_SELECTIVITY,
        minDimension: Int = GaussianPyramid.MIN_DIMENSION,
        maxLevels: Int = Int.MAX_VALUE,
        chunkCount: Int = PipelineParallel.parallelism,
    ): Frame {
        require(frames.isNotEmpty()) { "frames must not be empty" }
        require(frames.size == evs.size) { "frames and evs must have equal size" }
        require(selectivity > 0.0) { "selectivity must be > 0" }
        val width = frames.first().width
        val height = frames.first().height
        require(frames.all { it.width == width && it.height == height }) {
            "all frames must share dimensions"
        }

        val pixelCount = width * height
        val frameCount = frames.size

        // Per-frame weight maps, reshaped by the selectivity exponent then normalised
        // to a per-pixel partition of unity.
        val rawWeights = frames.mapIndexed { index, frame ->
            val map = ExposureFusionWeights.weightMap(frame, evs[index])
            if (selectivity != 1.0) {
                for (p in map.indices) map[p] = map[p].pow(selectivity)
            }
            map
        }
        val normalisedWeights = Array(frameCount) { DoubleArray(pixelCount) }
        for (p in 0 until pixelCount) {
            var sum = 0.0
            for (index in 0 until frameCount) sum += rawWeights[index][p]
            if (sum <= WEIGHT_EPS) {
                val equal = 1.0 / frameCount
                for (index in 0 until frameCount) normalisedWeights[index][p] = equal
            } else {
                for (index in 0 until frameCount) normalisedWeights[index][p] = rawWeights[index][p] / sum
            }
        }

        // Gaussian pyramids of the normalised weights, shared across all three channels.
        val weightPyramids = Array(frameCount) { index ->
            GaussianPyramid.build(PyramidPlane(width, height, normalisedWeights[index]), minDimension, maxLevels, chunkCount)
        }

        val frameArgb = Array(frameCount) { frames[it].argb }
        val red = fuseChannel(frameArgb, weightPyramids, width, height, 16, minDimension, maxLevels, chunkCount)
        val green = fuseChannel(frameArgb, weightPyramids, width, height, 8, minDimension, maxLevels, chunkCount)
        val blue = fuseChannel(frameArgb, weightPyramids, width, height, 0, minDimension, maxLevels, chunkCount)

        val out = IntArray(pixelCount)
        PipelineParallel.parallelRows(pixelCount, chunkCount) { start, end ->
            for (p in start until end) {
                val r = red[p].roundToInt().coerceIn(0, 255)
                val g = green[p].roundToInt().coerceIn(0, 255)
                val b = blue[p].roundToInt().coerceIn(0, 255)
                out[p] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        return Frame(width, height, out, frames.first().timestampMillis)
    }

    /**
     * Fuses one channel (selected by [shift]: 16=R, 8=G, 0=B) across all frames and
     * returns the collapsed full-resolution plane. Builds a Laplacian pyramid of each
     * frame's channel, blends every band against the matching Gaussian weight band,
     * and collapses.
     */
    private fun fuseChannel(
        frameArgb: Array<IntArray>,
        weightPyramids: Array<List<PyramidPlane>>,
        width: Int,
        height: Int,
        shift: Int,
        minDimension: Int,
        maxLevels: Int,
        chunkCount: Int,
    ): DoubleArray {
        val frameCount = frameArgb.size
        val laplacianPyramids = Array(frameCount) { index ->
            val argb = frameArgb[index]
            val channel = DoubleArray(argb.size) { ((argb[it] shr shift) and 0xFF).toDouble() }
            val gaussian = GaussianPyramid.build(PyramidPlane(width, height, channel), minDimension, maxLevels, chunkCount)
            LaplacianPyramid.build(gaussian, chunkCount)
        }

        val levelCount = laplacianPyramids[0].size
        val fusedBands = ArrayList<PyramidPlane>(levelCount)
        for (level in 0 until levelCount) {
            val template = laplacianPyramids[0][level]
            val levelWidth = template.width
            val fused = DoubleArray(template.data.size)
            PipelineParallel.parallelRows(template.height, chunkCount) { yStart, yEnd ->
                for (p in yStart * levelWidth until yEnd * levelWidth) {
                    var acc = 0.0
                    for (index in 0 until frameCount) {
                        acc += weightPyramids[index][level].data[p] * laplacianPyramids[index][level].data[p]
                    }
                    fused[p] = acc
                }
            }
            fusedBands.add(PyramidPlane(levelWidth, template.height, fused))
        }
        return LaplacianPyramid.collapse(fusedBands, chunkCount).data
    }
}
