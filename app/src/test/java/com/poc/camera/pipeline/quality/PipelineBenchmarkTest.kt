package com.poc.camera.pipeline.quality

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
        assertTrue(
            "3MP full pipeline ${fullBurst.millis} ms exceeded the 30s regression tripwire",
            fullBurst.millis < 30_000.0,
        )

        // Scaling: 4x the pixels must cost well under 8x the time (near-linear). K=8 is
        // deliberately generous to absorb cache effects and CI noise on a single run.
        assertTrue(
            "finishing 12MP (${finish12mp.millis} ms) exceeded 8x 3MP (${finish3mp.millis} ms): ratio $scaling",
            finish12mp.millis < 8.0 * finish3mp.millis,
        )
    }
}
