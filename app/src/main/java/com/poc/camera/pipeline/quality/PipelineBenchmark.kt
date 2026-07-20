package com.poc.camera.pipeline.quality

import com.poc.camera.pipeline.BurstMergePipeline
import com.poc.camera.pipeline.FinishingPipeline
import com.poc.camera.pipeline.Frame
import com.poc.camera.pipeline.GuidedFilter
import com.poc.camera.pipeline.PipelineParallel

/**
 * Wall-clock timing harness for the pure still pipeline at native-capture scales,
 * built to characterise the row-parallel work introduced for issue #42 and to guard
 * against catastrophic (e.g. accidental O(n^2)) regressions as the burst resolution
 * bound rises.
 *
 * It is deterministic: every scene and burst is generated from a fixed-seed LCG, so a
 * given size always processes the same pixels and the only thing that varies run to run
 * is the measured time. It is NOT a golden gate on absolute time -- CI machines differ
 * wildly -- so [PipelineBenchmarkTest] asserts only a generous ceiling (a tripwire) and
 * a near-linear scaling ratio, never a hard millisecond SLA.
 *
 * Pure Kotlin, no Android dependencies, so it runs as an ordinary JVM unit test.
 */
object PipelineBenchmark {

    /** Frames merged in a full-pipeline timing, mirroring a real burst capture. */
    const val DEFAULT_FRAMES = 6

    /** Base seed for the deterministic synthetic content. */
    const val DEFAULT_SEED = 0xB0A710L

    /** One measured timing. [millis] is wall time for a single [iterations]-run pass. */
    data class Timing(
        val label: String,
        val width: Int,
        val height: Int,
        val frames: Int,
        val iterations: Int,
        val millis: Double,
    ) {
        val megapixels: Double get() = width.toDouble() * height.toDouble() / 1_000_000.0

        override fun toString(): String =
            "%-22s %5dx%-5d (%.1f MP) frames=%d iters=%d  %.1f ms".format(
                label, width, height, megapixels, frames, iterations, millis,
            )
    }

    /**
     * Times the full still pipeline -- [frames]-frame [BurstMergePipeline] merge then a
     * [FinishingPipeline] DEFAULT finish -- at [width]x[height]. Input generation is
     * excluded from the timing; only the merge + finish is measured.
     */
    fun fullStill(
        width: Int,
        height: Int,
        seed: Long = DEFAULT_SEED,
        frames: Int = DEFAULT_FRAMES,
    ): Timing {
        val burst = scene(width, height, seed).let { clean ->
            SyntheticScenes.burstOf(clean, seed, frames)
        }
        val start = System.nanoTime()
        val merged = BurstMergePipeline.merge(burst).merged
        FinishingPipeline.apply(merged)
        val millis = (System.nanoTime() - start) / 1_000_000.0
        return Timing("full-still", width, height, frames, 1, millis)
    }

    /**
     * Times a single [FinishingPipeline] DEFAULT finish at [width]x[height] over a
     * synthetic noisy capture (so chroma denoise and white balance have real work).
     * Input generation is excluded from the timing.
     */
    fun finishingOnly(width: Int, height: Int, seed: Long = DEFAULT_SEED): Timing {
        val input = SyntheticScenes.noisy(scene(width, height, seed), seed)
        val start = System.nanoTime()
        FinishingPipeline.apply(input)
        val millis = (System.nanoTime() - start) / 1_000_000.0
        return Timing("finishing-only", width, height, 1, 1, millis)
    }

    /**
     * Times [GuidedFilter.selfGuided] -- the dominant per-pixel finishing kernel (it
     * chains six [com.poc.camera.pipeline.BoxBlur] passes) -- both forced serial and
     * fully parallel at [width]x[height], for the row-parallel speedup record. The two
     * runs are bit-identical (see the determinism tests); only their wall time differs.
     * Returns (serial, parallel).
     */
    fun guidedFilterSerialVsParallel(width: Int, height: Int, seed: Long = DEFAULT_SEED): Pair<Timing, Timing> {
        val plane = channelLattice(width, height, seed, cell = 24).let { c -> DoubleArray(c.size) { c[it].toDouble() } }
        val radius = 16
        val eps = LocalToneEps
        // Warm up the JIT so both timings are steady-state.
        GuidedFilter.selfGuided(plane, width, height, radius, eps, PipelineParallel.parallelism)

        val s0 = System.nanoTime()
        GuidedFilter.selfGuided(plane, width, height, radius, eps, PipelineParallel.SERIAL_CHUNKS)
        val serial = Timing("guided-serial", width, height, 1, 1, (System.nanoTime() - s0) / 1_000_000.0)

        val p0 = System.nanoTime()
        GuidedFilter.selfGuided(plane, width, height, radius, eps, PipelineParallel.parallelism)
        val parallel = Timing("guided-parallel", width, height, 1, 1, (System.nanoTime() - p0) / 1_000_000.0)
        return serial to parallel
    }

    // Matches LocalToneParams.DEFAULT_EPS, the guided-filter regulariser the finishing
    // luma passes use, so the speedup measurement reflects a realistic kernel setting.
    private val LocalToneEps = (0.03 * 255.0) * (0.03 * 255.0)

    /**
     * A deterministic colourful synthetic scene at any [width]x[height]. Each channel
     * is an independent coarse LCG lattice bilinearly interpolated to full resolution,
     * giving smooth large-scale colour structure (flat-ish patches where chroma noise
     * lives, plus gradients and edges), representative of the content the pipeline runs
     * on without the fixed 128px cap of [SyntheticScenes].
     */
    fun scene(width: Int, height: Int, seed: Long): Frame {
        require(width > 0 && height > 0) { "dimensions must be positive" }
        val r = channelLattice(width, height, seed xor 0x11_11_11L, cell = 24)
        val g = channelLattice(width, height, seed xor 0x22_22_22L, cell = 32)
        val b = channelLattice(width, height, seed xor 0x33_33_33L, cell = 20)
        val out = IntArray(width * height)
        for (i in out.indices) {
            out[i] = (0xFF shl 24) or (r[i] shl 16) or (g[i] shl 8) or b[i]
        }
        return Frame(width, height, out, timestampMillis = 0L)
    }

    /**
     * One 8-bit channel plane: a coarse [cell]-spaced random lattice bilinearly
     * interpolated up to [width]x[height]. Mirrors the smoothness of natural content
     * (unlike per-pixel white noise) so the pipeline's edge-aware stages do real work.
     */
    private fun channelLattice(width: Int, height: Int, seed: Long, cell: Int): IntArray {
        val lcg = Lcg(seed)
        val gridWidth = width / cell + 2
        val gridHeight = height / cell + 2
        val grid = IntArray(gridWidth * gridHeight) { lcg.nextByte() }

        val out = IntArray(width * height)
        for (y in 0 until height) {
            val gy = y / cell
            val fy = (y % cell).toDouble() / cell
            for (x in 0 until width) {
                val gx = x / cell
                val fx = (x % cell).toDouble() / cell
                val v00 = grid[gy * gridWidth + gx]
                val v10 = grid[gy * gridWidth + gx + 1]
                val v01 = grid[(gy + 1) * gridWidth + gx]
                val v11 = grid[(gy + 1) * gridWidth + gx + 1]
                val top = v00 + (v10 - v00) * fx
                val bottom = v01 + (v11 - v01) * fx
                out[y * width + x] = (top + (bottom - top) * fy).toInt().coerceIn(0, 255)
            }
        }
        return out
    }

    /** Numerical-Recipes LCG, matching the byte generator used by [SyntheticScenes]. */
    private class Lcg(seed: Long) {
        private var state = seed and 0xFFFFFFFFL

        fun nextByte(): Int {
            state = (state * 1664525L + 1013904223L) and 0xFFFFFFFFL
            return ((state ushr 24) and 0xFFL).toInt()
        }
    }
}
