package com.poc.camera.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.exp
import kotlin.math.pow

/**
 * Unit proofs for the pure pieces of [BacklitRescue]: the shadow gain curve (target /
 * alpha / maxGain / floor), the highlight-protection smoothstep endpoints, the soft
 * shoulder's continuity at its knee, engagement passthrough, the lift itself, the warmth
 * of lifted regions, and parallel bit-identity. All numbers are closed-form, so the tests
 * are deterministic and machine-independent.
 */
class BacklitRescueTest {

    private val params = BacklitRescueParams.DEFAULT

    // --- shadow gain math ----------------------------------------------------------

    @Test
    fun shadowGainMatchesClosedFormBelowTarget() {
        for (base in doubleArrayOf(20.0, 37.0, 45.0, 80.0, 120.0, 149.0)) {
            val expected = ((params.target + BacklitRescue.GAIN_FLOOR) / (base + BacklitRescue.GAIN_FLOOR))
                .pow(params.alpha)
                .coerceAtMost(params.maxGain)
            assertEquals("shadowGain($base)", expected, BacklitRescue.shadowGain(base, params), 1e-9)
        }
    }

    @Test
    fun shadowGainIsCappedAtMaxGainForNearBlack() {
        // A near-black base drives the raw ratio far above the cap, so the gain saturates.
        assertEquals(params.maxGain, BacklitRescue.shadowGain(1.0, params), 1e-9)
        assertEquals(params.maxGain, BacklitRescue.shadowGain(5.0, params), 1e-9)
    }

    @Test
    fun shadowGainDecreasesAsBaseRises() {
        var prev = Double.MAX_VALUE
        for (base in doubleArrayOf(10.0, 30.0, 60.0, 90.0, 130.0)) {
            val g = BacklitRescue.shadowGain(base, params)
            assertTrue("gain must be non-increasing in base", g <= prev)
            prev = g
        }
    }

    @Test
    fun floorSoftensTheGainAtTheTarget() {
        // At base == target the ratio is (target + floor) / (target + floor) == 1, so the
        // gain is exactly 1 (the floor keeps the curve continuous through the target).
        assertEquals(1.0, BacklitRescue.shadowGain(params.target, params), 1e-9)
    }

    // --- highlight protection ------------------------------------------------------

    @Test
    fun highlightProtectionIsZeroBelowStartAndOneAboveEnd() {
        assertEquals(0.0, BacklitRescue.highlightProtect(params.hiStart, params), 0.0)
        assertEquals(0.0, BacklitRescue.highlightProtect(params.hiStart - 30.0, params), 0.0)
        assertEquals(1.0, BacklitRescue.highlightProtect(params.hiEnd, params), 0.0)
        assertEquals(1.0, BacklitRescue.highlightProtect(params.hiEnd + 30.0, params), 0.0)
    }

    @Test
    fun highlightProtectionIsHalfAtTheMidpoint() {
        val mid = (params.hiStart + params.hiEnd) / 2.0
        // smoothstep(0.5) == 0.5.
        assertEquals(0.5, BacklitRescue.highlightProtect(mid, params), 1e-9)
    }

    @Test
    fun highlightProtectionIsMonotonic() {
        var prev = -1.0
        var b = params.hiStart
        while (b <= params.hiEnd) {
            val p = BacklitRescue.highlightProtect(b, params)
            assertTrue("protection must be non-decreasing", p >= prev)
            prev = p
            b += 5.0
        }
    }

    // --- soft shoulder -------------------------------------------------------------

    @Test
    fun shoulderIsIdentityBelowKneeAndContinuousAcrossIt() {
        assertEquals(100.0, BacklitRescue.shoulder(100.0), 0.0)
        assertEquals(BacklitRescue.SHOULDER_KNEE, BacklitRescue.shoulder(BacklitRescue.SHOULDER_KNEE), 0.0)
        // Continuity + unit slope at the knee: just above, the value barely departs from it.
        val justAbove = BacklitRescue.shoulder(BacklitRescue.SHOULDER_KNEE + 1e-6)
        assertEquals(BacklitRescue.SHOULDER_KNEE, justAbove, 1e-5)
        assertTrue("shoulder must keep rising just past the knee", justAbove > BacklitRescue.SHOULDER_KNEE)
    }

    @Test
    fun shoulderMatchesClosedFormAndStaysUnder255() {
        for (v in doubleArrayOf(215.0, 230.0, 260.0, 400.0)) {
            val over = v - BacklitRescue.SHOULDER_KNEE
            val expected = (BacklitRescue.SHOULDER_KNEE + BacklitRescue.SHOULDER_SOFTNESS * (1.0 - exp(-over / BacklitRescue.SHOULDER_SOFTNESS)))
                .coerceAtMost(255.0)
            assertEquals("shoulder($v)", expected, BacklitRescue.shoulder(v), 1e-9)
            assertTrue("shoulder must never reach 255", BacklitRescue.shoulder(v) < 255.0)
        }
    }

    // --- engagement passthrough ----------------------------------------------------

    @Test
    fun engagementZeroIsBitExactPassthrough() {
        val frame = uniform(8, 8, 30)
        val out = BacklitRescue.apply(frame, params, engagement = 0.0)
        // Same reference: a non-engaged rescue must not allocate or alter anything.
        assertSame("engagement 0 must return the input frame untouched", frame, out)
    }

    @Test
    fun negativeEngagementIsPassthrough() {
        val frame = uniform(8, 8, 30)
        assertSame(frame, BacklitRescue.apply(frame, params, engagement = -0.5))
    }

    // --- the lift + warmth ---------------------------------------------------------

    @Test
    fun darkFrameIsLiftedTowardTarget() {
        val frame = uniform(64, 64, 30)
        val out = BacklitRescue.apply(frame, params, engagement = 1.0)
        val before = lumaAt(frame, 32, 32)
        val after = lumaAt(out, 32, 32)
        assertTrue("dark pixel must be lifted well above its input", after > before * 2.0)
        assertTrue("lift must not exceed the target-driven ceiling", after < params.target)
    }

    @Test
    fun liftedRegionPicksUpWarmth() {
        // A neutral dark pixel (R == G == B) must come out warm: R > G > B, because warmth is
        // applied in proportion to the lift.
        val frame = uniform(64, 64, 30)
        val out = BacklitRescue.apply(frame, params, engagement = 1.0)
        val px = out.argb[32 * 64 + 32]
        val r = (px shr 16) and 0xFF
        val g = (px shr 8) and 0xFF
        val b = px and 0xFF
        assertTrue("lifted neutral pixel must warm up (R > G)", r > g)
        assertTrue("lifted neutral pixel must warm up (G > B)", g > b)
    }

    @Test
    fun brightBaseIsLeftEssentiallyUntouched() {
        // A bright uniform frame (base above the target) gets gain 1; only the >210 soft
        // shoulder may nudge it by a code or two.
        val frame = uniform(64, 64, 200)
        val out = BacklitRescue.apply(frame, params, engagement = 1.0)
        assertEquals(200.0, lumaAt(out, 32, 32), 1.0)
    }

    // --- determinism / parallelism -------------------------------------------------

    @Test
    fun applyIsDeterministicAndParallelMatchesSerial() {
        val frame = ramp(96, 96)
        val a = BacklitRescue.apply(frame, params, engagement = 1.0)
        val b = BacklitRescue.apply(frame, params, engagement = 1.0)
        assertTrue("apply must be deterministic", a.argb.contentEquals(b.argb))

        val serial = BacklitRescue.apply(frame, params, engagement = 1.0, chunkCount = PipelineParallel.SERIAL_CHUNKS)
        val parallel = BacklitRescue.apply(frame, params, engagement = 1.0, chunkCount = PipelineParallel.parallelism)
        assertTrue("parallel apply must be bit-identical to serial", serial.argb.contentEquals(parallel.argb))
    }

    // --- helpers -------------------------------------------------------------------

    private fun uniform(w: Int, h: Int, v: Int): Frame {
        val g = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        return Frame(w, h, IntArray(w * h) { g }, timestampMillis = 3L)
    }

    /** A diagonal luma ramp with a dark subject block, so the guided base has real structure. */
    private fun ramp(w: Int, h: Int): Frame {
        val out = IntArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                var v = ((x + y).toDouble() / (w + h - 2) * 255.0).toInt().coerceIn(0, 255)
                if (x in 20 until 50 && y in 20 until 50) v = 35
                out[y * w + x] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
            }
        }
        return Frame(w, h, out, timestampMillis = 3L)
    }

    private fun lumaAt(frame: Frame, x: Int, y: Int): Double {
        val px = frame.argb[y * frame.width + x]
        return 0.299 * ((px shr 16) and 0xFF) + 0.587 * ((px shr 8) and 0xFF) + 0.114 * (px and 0xFF)
    }
}
