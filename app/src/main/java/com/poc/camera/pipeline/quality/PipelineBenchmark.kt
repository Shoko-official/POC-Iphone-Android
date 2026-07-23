package com.poc.camera.pipeline.quality

import com.poc.camera.pipeline.BokehParams
import com.poc.camera.pipeline.BokehRenderer
import com.poc.camera.pipeline.BurstMergePipeline
import com.poc.camera.pipeline.FinishingParams
import com.poc.camera.pipeline.FinishingPipeline
import com.poc.camera.pipeline.Frame
import com.poc.camera.pipeline.GuidedFilter
import com.poc.camera.pipeline.PipelineParallel
import com.poc.camera.pipeline.TiledFinishing
import kotlin.math.sqrt

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
     * Per-stage wall-clock breakdown of ONE [FinishingPipeline.apply] finish (issue #115),
     * captured through the pipeline's timing hook so the measured chain is EXACTLY the
     * production chain, not a re-enumeration of it. [stages] holds (label, millis) in chain
     * order; under the tiled [path] each label accumulates its per-tile times (plus the
     * one-off [TiledFinishing.STAGE_STATS_ANALYSIS] pass), so a label's value is that
     * stage's total across the whole finish either way. [totalMillis] wraps the entire
     * apply; the remainder over [stageSumMillis] is untimed glue (tile extraction/copyback,
     * the final opaque pass, hook overhead).
     */
    data class FinishingBreakdown(
        val label: String,
        val width: Int,
        val height: Int,
        val path: String,
        val totalMillis: Double,
        val stages: List<Pair<String, Double>>,
    ) {
        val megapixels: Double get() = width.toDouble() * height.toDouble() / 1_000_000.0

        val stageSumMillis: Double get() = stages.sumOf { it.second }

        fun report(): String = buildString {
            append(
                "--- %s finishing %dx%d (%.1f MP, %s path): %.1f ms total ---".format(
                    label, width, height, megapixels, path, totalMillis,
                ),
            )
            for ((stage, millis) in stages) {
                append("\n  %-18s %8.1f ms  %5.1f%%".format(stage, millis, 100.0 * millis / totalMillis))
            }
            append("\n  %-18s %8.1f ms  %5.1f%%".format("(untimed glue)", totalMillis - stageSumMillis, 100.0 * (totalMillis - stageSumMillis) / totalMillis))
        }
    }

    /**
     * Runs one [FinishingPipeline.apply] finish of a synthetic noisy capture at
     * [width]x[height] under [params], accumulating each stage's wall time through the
     * timing hook (see [FinishingBreakdown]). Frames at or over
     * [FinishingPipeline.TILED_THRESHOLD_PIXELS] route through [TiledFinishing] exactly as
     * production does, so a 12 MP breakdown measures the tiled path (labels accumulated
     * across tiles) while 3 MP measures the whole-frame path. Input generation is excluded
     * from the timing.
     */
    fun finishingBreakdown(
        width: Int,
        height: Int,
        params: FinishingParams,
        label: String,
        seed: Long = DEFAULT_SEED,
    ): FinishingBreakdown {
        val input = SyntheticScenes.noisy(scene(width, height, seed), seed)
        // Tiles are finished sequentially on the calling thread (parallelism lives inside
        // the stages), so plain accumulation is safe.
        val stageNanos = LinkedHashMap<String, Long>()
        val start = System.nanoTime()
        FinishingPipeline.apply(input, params) { stage, nanos -> stageNanos.merge(stage, nanos, Long::plus) }
        val totalMillis = (System.nanoTime() - start) / 1_000_000.0
        val tiled = width.toLong() * height.toLong() >= FinishingPipeline.TILED_THRESHOLD_PIXELS
        return FinishingBreakdown(
            label = label,
            width = width,
            height = height,
            path = if (tiled) "tiled" else "whole-frame",
            totalMillis = totalMillis,
            stages = stageNanos.map { (stage, nanos) -> stage to nanos / 1_000_000.0 },
        )
    }

    /**
     * Times ONE full-resolution Rec. 601 luma-plane extraction at [width]x[height] -- the
     * RGB -> Y pass most finishing stages repeat internally -- sizing the issue #113
     * shared-luma opportunity: multiply by the chain's counted extraction passes for the
     * redundant cost per finished frame.
     */
    fun lumaExtraction(width: Int, height: Int, seed: Long = DEFAULT_SEED): Timing {
        val src = scene(width, height, seed).argb
        val start = System.nanoTime()
        val luma = DoubleArray(src.size) { i ->
            val pixel = src[i]
            0.299 * ((pixel shr 16) and 0xFF) + 0.587 * ((pixel shr 8) and 0xFF) + 0.114 * (pixel and 0xFF)
        }
        val millis = (System.nanoTime() - start) / 1_000_000.0
        // Fold one sample into a volatile sink so the extraction cannot be dead-code
        // eliminated out of the measurement.
        blackhole += luma[luma.size / 2]
        return Timing("luma-extract", width, height, 1, 1, millis)
    }

    @Volatile
    private var blackhole = 0.0

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
     * Times a single tiled [TiledFinishing.apply] finish at [width]x[height] over a
     * synthetic noisy capture, the native-resolution finishing path issue #54 adds. Input
     * generation is excluded. Confirms a 12 MP+ finish completes (no OOM) under the test
     * heap while its whole-frame equivalent would peak near a gigabyte of float
     * intermediates; the returned time is reported, not asserted against an SLA.
     */
    fun tiledFinishing(width: Int, height: Int, seed: Long = DEFAULT_SEED): Timing {
        val input = SyntheticScenes.noisy(scene(width, height, seed), seed)
        val start = System.nanoTime()
        TiledFinishing.apply(input, FinishingParams.DEFAULT)
        val millis = (System.nanoTime() - start) / 1_000_000.0
        return Timing("tiled-finishing", width, height, 1, 1, millis)
    }

    /**
     * Times a single [BokehRenderer.render] pass at [width]x[height] using width-scaled
     * [BokehParams.forImageWidth] params, on a deterministic synthetic portrait scene
     * ([bokehScene]) -- the O(pixels * tapCount) Vogel-spiral gather ([com.poc.camera.pipeline.DiscBlur])
     * that the portrait capture path runs at native decode resolution, unmeasured before
     * issue #128. Segmentation and merge are out of scope; only the render call is timed.
     * Input generation is excluded from the timing.
     */
    fun bokehRender(width: Int, height: Int, seed: Long = DEFAULT_SEED): Timing {
        val scene = bokehScene(width, height, seed)
        val params = BokehParams.forImageWidth(width)
        val start = System.nanoTime()
        BokehRenderer.render(scene.frame, scene.mask, params)
        val millis = (System.nanoTime() - start) / 1_000_000.0
        return Timing("bokeh-render", width, height, 1, 1, millis)
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

    /** A synthetic portrait frame paired with its (hard, segmentation-style) subject mask. */
    data class BokehScene(val frame: Frame, val mask: FloatArray)

    /**
     * A deterministic synthetic portrait scene at any [width]x[height]: a circular subject
     * disc with interior LCG texture over an LCG-textured background, plus a few
     * near-clipped background highlight points -- the same failure-mode shape as
     * [com.poc.camera.pipeline.quality.BokehGoldenTest]'s fixture (subject-colour bleed at
     * the boundary, highlight bloom, texture to measure blur against), adapted to run at any
     * resolution for [bokehRender] and the width-scaling proofs (issue #128). The mask is a
     * hard 0/1 disc (segmentation-style; [com.poc.camera.pipeline.BlurMap] does the
     * feathering), matching production.
     */
    fun bokehScene(width: Int, height: Int, seed: Long = DEFAULT_SEED): BokehScene {
        require(width > 0 && height > 0) { "dimensions must be positive" }
        val cx = width / 2.0
        val cy = height / 2.0
        val subjectR = width.coerceAtMost(height) * 0.22
        val cell = (width / 80).coerceAtLeast(4)
        val bgTex = textureLattice(seed xor 0x1111_1111L, cell, amp = 55, width, height)
        val subTex = textureLattice(seed xor 0x2222_2222L, cell + 2, amp = 40, width, height)
        val argb = IntArray(width * height)
        val mask = FloatArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val i = y * width + x
                val dx = x - cx
                val dy = y - cy
                if (sqrt(dx * dx + dy * dy) <= subjectR) {
                    val r = (165 + subTex[i].toInt()).coerceIn(0, 255)
                    argb[i] = (0xFF shl 24) or (r shl 16) or (35 shl 8) or 40
                    mask[i] = 1f
                } else {
                    val g = (150 + bgTex[i].toInt()).coerceIn(0, 255)
                    argb[i] = (0xFF shl 24) or (30 shl 16) or (g shl 8) or 45
                    mask[i] = 0f
                }
            }
        }
        // A few near-clipped background highlight points (proportional placement), so the
        // bloom stage has real near-clipped signal to lift at any resolution.
        val hlR = (width / 200).coerceAtLeast(1)
        for ((fx, fy) in HIGHLIGHT_FRACTIONS) {
            val hx = (fx * width).toInt()
            val hy = (fy * height).toInt()
            for (yy in (hy - hlR)..(hy + hlR)) {
                if (yy !in 0 until height) continue
                for (xx in (hx - hlR)..(hx + hlR)) {
                    if (xx !in 0 until width) continue
                    argb[yy * width + xx] = (0xFF shl 24) or (252 shl 16) or (252 shl 8) or 252
                }
            }
        }
        return BokehScene(Frame(width, height, argb, timestampMillis = 0L), mask)
    }

    /** Proportional (x, y) placement of [bokehScene]'s highlight points, scale-independent. */
    private val HIGHLIGHT_FRACTIONS = listOf(0.14 to 0.16, 0.83 to 0.14, 0.5 to 0.06)

    /**
     * Coarse [cell]-spaced LCG lattice bilinearly interpolated to [width]x[height], values in
     * [0, amp] -- adapted from [com.poc.camera.pipeline.quality.BokehGoldenTest]'s fixed-size
     * texture lattice to run at any resolution (issue #128).
     */
    private fun textureLattice(seed: Long, cell: Int, amp: Int, width: Int, height: Int): DoubleArray {
        var state = seed and 0xFFFFFFFFL
        fun next(): Double {
            state = (state * 1664525L + 1013904223L) and 0xFFFFFFFFL
            return ((state ushr 24) and 0xFFL).toDouble() / 255.0 * amp
        }
        val gw = width / cell + 2
        val gh = height / cell + 2
        val grid = DoubleArray(gw * gh) { next() }
        val out = DoubleArray(width * height)
        for (y in 0 until height) {
            val gy = y / cell
            val fy = (y % cell).toDouble() / cell
            for (x in 0 until width) {
                val gx = x / cell
                val fx = (x % cell).toDouble() / cell
                val v00 = grid[gy * gw + gx]
                val v10 = grid[gy * gw + gx + 1]
                val v01 = grid[(gy + 1) * gw + gx]
                val v11 = grid[(gy + 1) * gw + gx + 1]
                val top = v00 + (v10 - v00) * fx
                val bot = v01 + (v11 - v01) * fx
                out[y * width + x] = top + (bot - top) * fy
            }
        }
        return out
    }
}
