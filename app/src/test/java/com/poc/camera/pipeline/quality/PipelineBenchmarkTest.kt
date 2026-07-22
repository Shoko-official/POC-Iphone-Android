package com.poc.camera.pipeline.quality

import com.poc.camera.pipeline.FinishingParams
import com.poc.camera.pipeline.FinishingPipeline
import com.poc.camera.pipeline.TiledFinishing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Performance tripwires (NOT a perf SLA) for the full-resolution pipeline.
 *
 * These do not assert an absolute millisecond budget -- CI machines vary far too much
 * for that -- but two robust properties that any healthy build satisfies with room to
 * spare:
 *  - a GENEROUS ceiling on the 3 MP full pipeline (catches a catastrophic regression
 *    such as an accidental O(n^2), which would blow past seconds into minutes), and
 *  - near-linear SCALING of finishing from 3 MP to 12 MP (a 4x pixel jump must stay
 *    well under a 8x time jump), which would break if a super-linear cost crept in.
 *
 * Since issue #115 the class also reports a PER-STAGE breakdown of the current finishing
 * chain ([finishingStageBreakdownReportsCurrentChain]), captured through the pipeline's
 * timing hook so the measured chain is exactly the shipping one: DEFAULT vs RENDITION at
 * 3 MP (whole-frame path) and 12 MP (tiled path), plus the shared-luma sizing for issue
 * #113. The breakdown asserts STRUCTURE only (paths, stage sets, off-stage near-zeroes);
 * every absolute time is printed for the record, never gated.
 *
 * Measured times are printed to stdout for the record and reported with the change.
 * Every timing is a single iteration (the 12 MP full pipeline is expensive); a short
 * warm-up first lets the JIT compile the hot paths so the printed numbers and the
 * scaling ratio reflect steady-state, not first-call interpreter cost.
 */
class PipelineBenchmarkTest {

    private data class Sizes(val w: Int, val h: Int)

    @Test
    fun fullResolutionPipelineStaysWithinTripwires() {
        // Warm up the JIT on a small frame so the measured passes are steady-state.
        PipelineBenchmark.fullStill(256, 256)
        PipelineBenchmark.finishingOnly(256, 256)

        val small = Sizes(1280, 960) // ~1.2 MP
        val burstSize = Sizes(2016, 1512) // ~3 MP (a 12 MP sensor decoded at sample 2)
        val native = Sizes(4032, 3024) // 12 MP

        val fullSmall = PipelineBenchmark.fullStill(small.w, small.h)
        val fullBurst = PipelineBenchmark.fullStill(burstSize.w, burstSize.h)
        val finish3mp = PipelineBenchmark.finishingOnly(burstSize.w, burstSize.h)
        val finish12mp = PipelineBenchmark.finishingOnly(native.w, native.h)
        val fullNative = PipelineBenchmark.fullStill(native.w, native.h)

        println("=== PipelineBenchmark (parallelism=${com.poc.camera.pipeline.PipelineParallel.parallelism}) ===")
        for (t in listOf(fullSmall, fullBurst, fullNative, finish3mp, finish12mp)) {
            println(t)
        }
        val scaling = finish12mp.millis / finish3mp.millis
        println("finishing 12MP/3MP scaling ratio = %.2f (pixel ratio = 4.0)".format(scaling))

        // Native 12.5 MP tiled finish (issue #54): confirm it runs to completion (no OOM)
        // under the unit-test heap and record its wall time. The whole-frame equivalent at
        // this size would peak near a gigabyte of transient float planes.
        val tiled12mp = PipelineBenchmark.tiledFinishing(4080, 3072)
        println(tiled12mp)

        // Serial vs. parallel on the dominant finishing kernel, for the speedup record.
        val (guidedSerial, guidedParallel) = PipelineBenchmark.guidedFilterSerialVsParallel(burstSize.w, burstSize.h)
        println(guidedSerial)
        println(guidedParallel)
        println("guided-filter parallel speedup = %.2fx".format(guidedSerial.millis / guidedParallel.millis))

        // Tripwire: 3 MP full pipeline must complete far inside 30 s on any plausible
        // CI machine. A catastrophic regression (e.g. O(n^2)) would take minutes.
        // Re-measured 2026-07-22 after the bounded white-balance estimation (issue #117):
        // ~2.4 s actual on a 24-thread dev machine (down from ~2.9 s with the full-res
        // estimation), so the 30 s bound keeps >12x CI headroom.
        assertTrue(
            "3MP full pipeline ${fullBurst.millis} ms exceeded the 30s regression tripwire",
            fullBurst.millis < 30_000.0,
        )

        // Scaling: 4x the pixels must cost well under 8x the time (near-linear). K=8 is
        // deliberately generous to absorb cache effects and CI noise on a single run. Note
        // the two sides take DIFFERENT paths: 3 MP finishes whole-frame while 12 MP routes
        // through TiledFinishing (>= TILED_THRESHOLD_PIXELS), whose halo overlap re-finishes
        // ~2.4x the pixel count (up from ~1.7x when the halo was 88: issue #114 sized the
        // overlap to 160 for the roll-off radius ceiling) -- the ratio absorbs that
        // amplification and stays honest as the production 12 MP route. Re-measured
        // 2026-07-22 with the bounded white-balance estimation on BOTH sides (issue #117;
        // previously the 3 MP side alone carried a full-resolution estimation, dragging the
        // ratio down to ~1.7): ratio ~2.7 actual with the 88 halo, expected ~3.5-4 with the
        // 160 halo, still comfortably under the 8x bound.
        assertTrue(
            "finishing 12MP (${finish12mp.millis} ms) exceeded 8x 3MP (${finish3mp.millis} ms): ratio $scaling",
            finish12mp.millis < 8.0 * finish3mp.millis,
        )
    }

    @Test
    fun finishingStageBreakdownReportsCurrentChain() {
        // Warm up both profiles on a small frame so the breakdowns are steady-state.
        PipelineBenchmark.finishingBreakdown(256, 256, FinishingParams.DEFAULT, "warmup")
        PipelineBenchmark.finishingBreakdown(256, 256, FinishingParams.RENDITION, "warmup")

        val burstSize = Sizes(2016, 1512) // ~3 MP: whole-frame finishing path
        val native = Sizes(4032, 3024) // 12 MP: tiled finishing path (>= TILED_THRESHOLD_PIXELS)

        val default3mp = PipelineBenchmark.finishingBreakdown(burstSize.w, burstSize.h, FinishingParams.DEFAULT, "DEFAULT")
        val rendition3mp = PipelineBenchmark.finishingBreakdown(burstSize.w, burstSize.h, FinishingParams.RENDITION, "RENDITION")
        val default12mp = PipelineBenchmark.finishingBreakdown(native.w, native.h, FinishingParams.DEFAULT, "DEFAULT")
        val rendition12mp = PipelineBenchmark.finishingBreakdown(native.w, native.h, FinishingParams.RENDITION, "RENDITION")
        val breakdowns = listOf(default3mp, rendition3mp, default12mp, rendition12mp)

        println("=== Finishing stage breakdown (issue #115, parallelism=${com.poc.camera.pipeline.PipelineParallel.parallelism}) ===")
        for (b in breakdowns) println(b.report())
        println(
            "DEFAULT -> RENDITION total: 3 MP %.1f -> %.1f ms (%.2fx), 12 MP %.1f -> %.1f ms (%.2fx)".format(
                default3mp.totalMillis, rendition3mp.totalMillis, rendition3mp.totalMillis / default3mp.totalMillis,
                default12mp.totalMillis, rendition12mp.totalMillis, rendition12mp.totalMillis / default12mp.totalMillis,
            ),
        )

        // Shared-luma census (issue #113): counted from the stage sources, the RENDITION
        // chain runs 8 separate full-res RGB->Y passes per finished frame (detector,
        // chroma denoise, the SHARED denoised-state plane -- one extraction replacing the
        // former skin mask, sky mask, 2x overcast mask, foliage mask and local tone
        // passes -- detail enhance, saturation, 2x chroma roll-off, semantic render; 9
        // with the backlit lift engaged) vs 4 in DEFAULT. Before #113 those counts were
        // 13 (14 engaged) and 5. The two WB cue passes run at the bounded <= 1 MP
        // analysis resolution since issue #117, so they no longer count as full-res
        // passes on either path. The remaining passes each read a DIFFERENT frame state
        // (or Saturation's integer convention), so no further plane can be shared -- see
        // FinishingPipeline.sharedLumaPlane. One measured pass sizes the remaining cost
        // in ms; the tiled path additionally re-extracts over the ~2.4x halo area
        // (overlap 160 since issue #114).
        val luma12mp = PipelineBenchmark.lumaExtraction(native.w, native.h)
        println(luma12mp)
        println(
            "shared-luma census: 8 RENDITION passes x %.1f ms = ~%.0f ms of RGB->Y at 12 MP (whole-frame equivalent; 13 passes before #113)".format(
                luma12mp.millis, 8 * luma12mp.millis,
            ),
        )

        // Measured 2026-07-22 after the shared denoised-state luma plane (issue #113;
        // 24-thread dev machine), for the record -- report-only, never gated: 3 MP
        // whole-frame DEFAULT ~0.78 s / RENDITION ~0.95 s (1.22x); 12 MP tiled DEFAULT
        // ~2.2 s / RENDITION ~3.4 s (1.54x) -- within single-iteration run-to-run noise
        // of the issue #117 numbers (previously 3 MP ~0.72 / ~0.94 s, 12 MP ~2.0 /
        // ~3.2 s). The one shared extraction reports as its own near-free stage
        // (shared-luma ~2-5 ms at 3 MP, ~16 ms summed across the 12 MP tiles) and the
        // mask-side stages now run extraction-free: skin-mask ~16 ms at 3 MP / ~100 ms
        // tiled 12 MP, semantic-masks ~76 ms at 3 MP / ~338 ms tiled 12 MP (RENDITION).
        // Dominant stages are unchanged: the 3 MP whole-frame path is led by
        // detail-enhance (~54-69%) and chroma-denoise (~12-17%), the tiled 12 MP path by
        // detail-enhance (~22-34%), chroma-denoise (~22-33%) and, in RENDITION,
        // semantic-render (~22%). The off stages read 0.0 ms in DEFAULT.

        // Structural sanity only -- absolute stage times are machine-dependent and stay
        // report-only. The paths and stage sets are deterministic.
        assertEquals("whole-frame", default3mp.path)
        assertEquals("whole-frame", rendition3mp.path)
        assertEquals("tiled", default12mp.path)
        assertEquals("tiled", rendition12mp.path)
        for (b in breakdowns) {
            assertTrue("${b.label} breakdown must report stages", b.stages.isNotEmpty())
            // Stage windows nest inside the total window, so their sum can never exceed it.
            assertTrue(
                "${b.label} stage sum ${b.stageSumMillis} ms exceeded total ${b.totalMillis} ms",
                b.stageSumMillis <= b.totalMillis,
            )
            val labels = b.stages.map { it.first }
            for (stage in EXPECTED_STAGES) {
                assertTrue("${b.label} ${b.path} breakdown missing stage $stage", stage in labels)
            }
        }
        // The tiled path additionally reports its one-off stats analysis pass.
        for (b in listOf(default12mp, rendition12mp)) {
            assertTrue(
                "${b.label} tiled breakdown missing ${TiledFinishing.STAGE_STATS_ANALYSIS}",
                b.stages.any { it.first == TiledFinishing.STAGE_STATS_ANALYSIS },
            )
        }
        // DEFAULT ships backlit rescue, chroma roll-off and semantic rendering OFF; their
        // guards report only a near-zero passthrough. 10% of total is a deliberately
        // generous structural bound (measured: well under 0.1%), asserting "off means off"
        // without gating absolute time.
        val offStages = listOf(
            FinishingPipeline.STAGE_BACKLIT,
            FinishingPipeline.STAGE_SEMANTIC_MASKS,
            FinishingPipeline.STAGE_CHROMA_ROLL_OFF,
            FinishingPipeline.STAGE_SEMANTIC_RENDER,
        )
        for (b in listOf(default3mp, default12mp)) {
            for ((stage, millis) in b.stages) {
                if (stage in offStages) {
                    assertTrue(
                        "${b.label} off stage $stage took $millis ms (> 10% of ${b.totalMillis} ms total)",
                        millis < 0.10 * b.totalMillis,
                    )
                }
            }
        }
    }

    private companion object {
        /** The coarse stage labels every breakdown must report, in chain order. */
        val EXPECTED_STAGES = listOf(
            FinishingPipeline.STAGE_BACKLIT,
            FinishingPipeline.STAGE_WHITE_BALANCE,
            FinishingPipeline.STAGE_CHROMA_DENOISE,
            FinishingPipeline.STAGE_SHARED_LUMA,
            FinishingPipeline.STAGE_SKIN_MASK,
            FinishingPipeline.STAGE_SEMANTIC_MASKS,
            FinishingPipeline.STAGE_LOCAL_TONE,
            FinishingPipeline.STAGE_DETAIL_ENHANCE,
            FinishingPipeline.STAGE_TONE_SAT_CONTRAST,
            FinishingPipeline.STAGE_CHROMA_ROLL_OFF,
            FinishingPipeline.STAGE_SEMANTIC_RENDER,
        )
    }
}
