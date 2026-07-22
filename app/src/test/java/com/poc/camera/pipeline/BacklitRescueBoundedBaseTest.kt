package com.poc.camera.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Proofs for the bounded-base backlit rescue (issue #108).
 *
 *  1. BIT-IDENTITY below [BacklitRescue.MAX_BASE_PIXELS]: the routed output is byte-
 *     identical to a [GuidedFilter]-direct reference replicating the pre-change
 *     implementation, so the small-capture path (and every golden fixture) is untouched.
 *  2. EQUIVALENCE above the threshold: the bounded downsampled-base path tracks the
 *     whole-frame path ([BacklitRescue.applyFullResBase]) on a 3.1 MP synthetic backlit
 *     frame -- MAE / max-difference bounds plus the rescued LOOK (subject mean lift
 *     within 2%).
 *  3. MEMORY: the pure-arithmetic peak estimate for a 50 MP capture stays under a
 *     documented budget and the double-plane transient is capped regardless of size
 *     (the pattern of TiledFinishingMemoryTest).
 *  4. DETERMINISM + parallel bit-identity of the new downsample/upsample path.
 *
 * Equivalence baselines were measured 2026-07-22 and are documented at the assertions;
 * bounds carry margin so the test is a regression gate, not a brittle snapshot.
 */
class BacklitRescueBoundedBaseTest {

    private val params = BacklitRescueParams.DEFAULT

    // --- bit-identity below the threshold -------------------------------------------

    @Test
    fun belowThresholdIsByteIdenticalToTheGuidedFilterDirectReference() {
        val frame = syntheticBacklit(320, 240)
        assertTrue(
            "fixture must sit under the threshold",
            frame.width.toLong() * frame.height <= BacklitRescue.MAX_BASE_PIXELS,
        )
        assertEquals(1, BacklitRescue.baseDownscaleFactor(frame.width, frame.height))

        val out = BacklitRescue.apply(frame, params, engagement = 1.0)
        val reference = directReference(frame, params, engagement = 1.0)
        assertTrue(
            "below the threshold the routed output must be byte-identical to the " +
                "pre-change whole-frame implementation",
            reference.contentEquals(out.argb),
        )
    }

    @Test
    fun partialEngagementBelowThresholdIsAlsoByteIdentical() {
        val frame = syntheticBacklit(300, 200)
        val out = BacklitRescue.apply(frame, params, engagement = 0.6)
        val reference = directReference(frame, params, engagement = 0.6)
        assertTrue("partial engagement must keep byte-identity", reference.contentEquals(out.argb))
    }

    // --- equivalence above the threshold --------------------------------------------

    @Test
    fun boundedPathTracksTheWholeFramePathAboveThreshold() {
        val frame = syntheticBacklit(LARGE_W, LARGE_H)
        assertTrue(
            "fixture must sit above the threshold",
            frame.width.toLong() * frame.height > BacklitRescue.MAX_BASE_PIXELS,
        )
        assertEquals(2, BacklitRescue.baseDownscaleFactor(LARGE_W, LARGE_H))

        val bounded = BacklitRescue.apply(frame, params, engagement = 1.0)
        val whole = BacklitRescue.applyFullResBase(frame, params, engagement = 1.0, PipelineParallel.parallelism)

        var sumAbs = 0.0
        var maxDiff = 0
        var maxFlatDiff = 0
        for (y in 0 until LARGE_H) {
            for (x in 0 until LARGE_W) {
                val i = y * LARGE_W + x
                val a = bounded.argb[i]
                val b = whole.argb[i]
                val flat = distToSubjectBoundary(x, y) > FLAT_MARGIN
                for (shift in intArrayOf(16, 8, 0)) {
                    val diff = abs(((a shr shift) and 0xFF) - ((b shr shift) and 0xFF))
                    sumAbs += diff
                    if (diff > maxDiff) maxDiff = diff
                    if (flat && diff > maxFlatDiff) maxFlatDiff = diff
                }
            }
        }
        val mae = sumAbs / (bounded.argb.size * 3.0)

        val interior = subjectInterior()
        val inputSubject = meanLuma(frame, interior)
        val boundedLift = meanLuma(bounded, interior) / inputSubject
        val wholeLift = meanLuma(whole, interior) / inputSubject
        val liftDelta = abs(boundedLift / wholeLift - 1.0)

        println(
            "[bounded-base] 3.1MP equivalence mae=${"%.4f".format(mae)} max=$maxDiff " +
                "flat-max=$maxFlatDiff subject lift bounded=x${"%.3f".format(boundedLift)} " +
                "whole=x${"%.3f".format(wholeLift)} (delta ${"%.4f".format(liftDelta)})",
        )
        assertTrue("bounded-vs-whole MAE must stay under $MAX_EQUIVALENCE_MAE codes (was $mae)", mae < MAX_EQUIVALENCE_MAE)
        assertTrue("bounded-vs-whole max diff must stay under $MAX_EDGE_DIFF codes (was $maxDiff)", maxDiff < MAX_EDGE_DIFF)
        assertTrue(
            "beyond $FLAT_MARGIN px of the boundary the paths must agree within $MAX_FLAT_DIFF codes (was $maxFlatDiff)",
            maxFlatDiff < MAX_FLAT_DIFF,
        )
        assertTrue("subject mean lift must match the whole-frame path within 2% (was $liftDelta)", liftDelta <= MAX_LIFT_DELTA)
    }

    // --- memory bound ----------------------------------------------------------------

    @Test
    fun downscaleFactorBoundsTheBasePlane() {
        assertEquals(1, BacklitRescue.baseDownscaleFactor(1024, 1024))
        // Exactly at the threshold: still the full-resolution path.
        assertEquals(1, BacklitRescue.baseDownscaleFactor(2000, 1000))
        assertEquals(2, BacklitRescue.baseDownscaleFactor(2048, 1536))
        // 12.5 MP native: a NON-power-of-two factor (k = 3), which is why the base uses a
        // k x k box downsampler rather than repeated 2x halving.
        assertEquals(3, BacklitRescue.baseDownscaleFactor(4080, 3072))
        assertEquals(6, BacklitRescue.baseDownscaleFactor(8192, 6144))

        for ((w, h) in listOf(
            2048 to 1536, 4080 to 3072, 8192 to 6144, 12000 to 9000, 16384 to 12288, 5000 to 5000,
        )) {
            val k = BacklitRescue.baseDownscaleFactor(w, h)
            val basePixels = ceilDiv(w, k).toLong() * ceilDiv(h, k).toLong()
            assertTrue(
                "downsampled base for ${w}x$h (k=$k) must fit ${BacklitRescue.MAX_BASE_PIXELS} px (was $basePixels)",
                basePixels <= BacklitRescue.MAX_BASE_PIXELS,
            )
        }
    }

    @Test
    fun fiftyMegapixelBasePeakStaysUnderBudget() {
        val peak = BacklitRescue.basePeakBytesEstimate(HUGE_W, HUGE_H)
        val whole = BacklitRescue.wholeFrameBasePeakBytesEstimate(HUGE_W, HUGE_H)
        println(
            "[bounded-base] 50MP peak estimate = %.1f MB (budget %d MB, whole-frame %.1f MB)".format(
                peak.toDouble() / MB, BUDGET_BYTES / MB, whole.toDouble() / MB,
            ),
        )
        assertTrue("50MP bounded peak ${peak / MB} MB exceeds ${BUDGET_BYTES / MB} MB budget", peak < BUDGET_BYTES)
        // The bound must remove the full-resolution double peak: a small fraction of the
        // whole-frame estimate (which scales its ~11 double planes with all 50M pixels).
        val fraction = peak.toDouble() / whole.toDouble()
        assertTrue("bounded peak is not a small fraction of whole-frame ($fraction)", fraction < 0.33)
    }

    @Test
    fun doublePlaneTransientIsCappedRegardlessOfCaptureSize() {
        // Peak minus the resident ARGB in/out IntArrays leaves the double-plane transient,
        // which the downscale factor caps at MAX_BASE_PIXELS * 11 planes * 8 bytes (176 MB)
        // no matter how large the capture grows; the whole-frame transient would keep
        // scaling with the pixels instead.
        val cap = BacklitRescue.MAX_BASE_PIXELS * BacklitRescue.BASE_FLOAT_PLANES_PEAK * 8L
        for ((w, h) in listOf(HUGE_W to HUGE_H, 16384 to 12288)) {
            val transient = BacklitRescue.basePeakBytesEstimate(w, h) - 2L * w * h * 4L
            println("[bounded-base] ${w}x$h transient = %.1f MB (cap %.1f MB)".format(transient.toDouble() / MB, cap.toDouble() / MB))
            assertTrue("transient for ${w}x$h must stay under the ${cap / MB} MB cap (was ${transient / MB} MB)", transient <= cap)
        }
    }

    // --- determinism + parallel bit-identity -----------------------------------------

    @Test
    fun boundedPathIsDeterministicAndParallelMatchesSerial() {
        val frame = syntheticBacklit(LARGE_W, LARGE_H)
        val a = BacklitRescue.apply(frame, params, engagement = 1.0)
        val b = BacklitRescue.apply(frame, params, engagement = 1.0)
        assertTrue("bounded path must be deterministic", a.argb.contentEquals(b.argb))

        val serial = BacklitRescue.apply(frame, params, engagement = 1.0, chunkCount = PipelineParallel.SERIAL_CHUNKS)
        val parallel = BacklitRescue.apply(frame, params, engagement = 1.0, chunkCount = PipelineParallel.parallelism)
        assertTrue("parallel bounded path must be bit-identical to serial", serial.argb.contentEquals(parallel.argb))
    }

    // --- helpers ---------------------------------------------------------------------

    /**
     * Replicates the pre-change whole-frame implementation op for op (luma extraction,
     * [GuidedFilter.selfGuided] base at full radius, per-pixel gain / engagement lerp /
     * warmth / shoulder), so byte-equality proves the routed small path is untouched.
     */
    private fun directReference(frame: Frame, params: BacklitRescueParams, engagement: Double): IntArray {
        val e = engagement.coerceAtMost(1.0)
        val n = frame.argb.size
        val luma = DoubleArray(n)
        for (i in 0 until n) {
            val px = frame.argb[i]
            luma[i] = 0.299 * ((px shr 16) and 0xFF) +
                0.587 * ((px shr 8) and 0xFF) +
                0.114 * (px and 0xFF)
        }
        val eps = (params.epsScale * 255.0).pow(2.0)
        val base = GuidedFilter.selfGuided(luma, frame.width, frame.height, params.radius, eps)
        val out = IntArray(n)
        val warmthExcess = params.warmth - 1.0
        val gainSpan = params.maxGain - 1.0
        for (i in 0 until n) {
            val b = base[i].coerceAtLeast(1.0)
            var gain = if (b < params.target) BacklitRescue.shadowGain(b, params) else 1.0
            val protect = BacklitRescue.highlightProtect(b, params)
            gain = gain * (1.0 - protect) + protect
            val effGain = 1.0 + e * (gain - 1.0)
            val liftAmount = if (gainSpan > 0.0) ((effGain - 1.0) / gainSpan).coerceIn(0.0, 1.0) else 0.0
            val wr = 1.0 + warmthExcess * liftAmount
            val wb = 1.0 - warmthExcess * 0.7 * liftAmount
            val px = frame.argb[i]
            val r = BacklitRescue.shoulder(((px shr 16) and 0xFF) * effGain * wr)
            val g = BacklitRescue.shoulder(((px shr 8) and 0xFF) * effGain)
            val bch = BacklitRescue.shoulder((px and 0xFF) * effGain * wb)
            out[i] = (0xFF shl 24) or (r.roundToInt() shl 16) or (g.roundToInt() shl 8) or bch.roundToInt()
        }
        return out
    }

    /**
     * A synthetic backlit frame at any size: a dark textured SUBJECT rectangle (luma ~37,
     * +/- 8 of deterministic per-pixel texture) over a bright textured BACKGROUND (~200,
     * +/- 15) with a hard boundary -- the canonical backlit content [BacklitRescue] lifts,
     * scaled up from the golden "backlitportrait" recipe. The per-pixel texture is the
     * adversarial detail for the bounded base (it is invisible at the downsampled base's
     * scale), and the hard boundary is where the gain-transition difference peaks.
     */
    private fun syntheticBacklit(w: Int, h: Int): Frame {
        val out = IntArray(w * h)
        val sx0 = w / 4
        val sy0 = h / 4
        val sx1 = (w * 5) / 8
        val sy1 = (h * 3) / 4
        for (y in 0 until h) {
            for (x in 0 until w) {
                val t = hashNoise(x, y)
                val inSubject = x in sx0 until sx1 && y in sy0 until sy1
                val v = if (inSubject) 37.0 + 8.0 * t else 200.0 + 15.0 * t
                val vi = v.roundToInt().coerceIn(0, 255)
                out[y * w + x] = (0xFF shl 24) or (vi shl 16) or (vi shl 8) or vi
            }
        }
        return Frame(w, h, out, timestampMillis = 7L)
    }

    /** Deterministic hash noise in [-1, 1] (integer mix, machine-independent). */
    private fun hashNoise(x: Int, y: Int): Double {
        var v = x * 374761393 + y * 668265263
        v = (v xor (v ushr 13)) * 1274126177
        v = v xor (v ushr 16)
        return ((v and 0xFFFF).toDouble() / 0xFFFF - 0.5) * 2.0
    }

    /** Subject-core bounds of the [LARGE_W]x[LARGE_H] fixture, inset well past the radius-32
     *  gain-transition band so the lift metric reads the flat interior. */
    private fun subjectInterior(): IntArray = intArrayOf(
        LARGE_W / 4 + INSET,
        LARGE_H / 4 + INSET,
        (LARGE_W * 5) / 8 - INSET,
        (LARGE_H * 3) / 4 - INSET,
    )

    private fun meanLuma(frame: Frame, bounds: IntArray): Double {
        var sum = 0.0
        var n = 0
        for (y in bounds[1] until bounds[3]) {
            for (x in bounds[0] until bounds[2]) {
                val px = frame.argb[y * frame.width + x]
                sum += 0.299 * ((px shr 16) and 0xFF) + 0.587 * ((px shr 8) and 0xFF) + 0.114 * (px and 0xFF)
                n++
            }
        }
        return sum / n
    }

    /** Chebyshev distance from (x, y) to the subject-rectangle BOUNDARY of the large
     *  fixture (0 on the boundary itself, growing inward and outward). */
    private fun distToSubjectBoundary(x: Int, y: Int): Int {
        val x0 = LARGE_W / 4
        val y0 = LARGE_H / 4
        val x1 = (LARGE_W * 5) / 8
        val y1 = (LARGE_H * 3) / 4
        val inside = x in x0 until x1 && y in y0 until y1
        return if (inside) {
            minOf(x - x0, x1 - 1 - x, y - y0, y1 - 1 - y)
        } else {
            val dx = maxOf(x0 - x, x - (x1 - 1), 0)
            val dy = maxOf(y0 - y, y - (y1 - 1), 0)
            maxOf(dx, dy)
        }
    }

    private fun ceilDiv(a: Int, b: Int): Int = (a + b - 1) / b

    private companion object {
        /** 2048x1536 = 3.1 MP, above the 2 MP threshold (downscale factor 2). */
        const val LARGE_W = 2048
        const val LARGE_H = 1536

        /** 8192x6144 = 50.3 MP, the memory-bound scenario. */
        const val HUGE_W = 8192
        const val HUGE_H = 6144

        const val INSET = 64
        const val MB = 1024L * 1024L

        /** Documented budget for the 50 MP bounded rescue peak: ~384 MB resident ARGB
         *  in/out + ~117 MB bounded double transient = ~501 MB estimated. */
        const val BUDGET_BYTES = 560L * MB

        // MEASURED 2026-07-22 on the 3.1 MP fixture (k = 2, radius 32 -> 16). Actuals ->
        // baked bound (with margin):
        //   whole-frame MAE            : 0.581 codes -> ceiling 1.0
        //   max diff, > 96 px off edge : 6 codes     -> ceiling 12
        //   max diff, anywhere         : 62 codes    -> ceiling 80
        //   subject mean lift delta    : 0.23%       -> ceiling 2%
        // The max diff decays with Chebyshev distance from the hard subject boundary
        // (62 / 25 / 26 / 21 / 17 / 6 within <=4 / <=8 / <=16 / <=32 / <=64 / > 96 px):
        // the whole-frame guided base snaps its gain at the preserved edge while the
        // bounded path interpolates the gain across the transition band, so single-pixel
        // differences at a synthetic 163-code step edge are inherent to the design and
        // confined there -- everywhere flat, the paths agree within a few codes and the
        // rescued LOOK (subject lift) is preserved to a fraction of a percent.
        const val MAX_EQUIVALENCE_MAE = 1.0
        const val FLAT_MARGIN = 96
        const val MAX_FLAT_DIFF = 12
        const val MAX_EDGE_DIFF = 80
        const val MAX_LIFT_DELTA = 0.02
    }
}
