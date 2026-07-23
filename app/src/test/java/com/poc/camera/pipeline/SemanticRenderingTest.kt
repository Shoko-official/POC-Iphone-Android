package com.poc.camera.pipeline

import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * Unit proofs for [SemanticRendering]: the deliberate per-channel shift is bounded to <= 12
 * codes at full mask+strength, the chroma deepening preserves hue (scales magnitude only),
 * the foliage luma lift and green enrichment are positive-and-bounded, the sky chroma
 * smoothing reduces chroma variance, the OVERCAST mask drives the smoothing but NEVER the
 * blue deepening (gray stays gray, issue #106), the strength endpoints/blend behave, zero
 * masks are a bit-exact passthrough, and the parallel path is bit-identical to the serial
 * one.
 */
class SemanticRenderingTest {

    private val params = SemanticRenderingParams.DEFAULT

    // --- boundedness (<= 12 codes per channel) -------------------------------------

    @Test
    fun skyDeepeningIsBoundedToTwelveCodesAndPreservesHue() {
        val frame = flat(24, 24, 110, 155, 210) // clear blue sky
        val sky = ones(frame)
        val fol = zeros(frame)
        val out = SemanticRendering.apply(frame, sky, zeros(frame), fol, params)
        assertMaxChannelShift(frame, out, 12)

        // Hue (opponent chroma angle) is preserved: the deepening scales Cr and Cb by one factor.
        val angBefore = chromaAngle(frame, 12, 12)
        val angAfter = chromaAngle(out, 12, 12)
        assertTrue("sky hue must be preserved (${abs(angBefore - angAfter)} deg)", abs(angBefore - angAfter) < 1.0)

        // The blue is actually deepened (chroma magnitude grows).
        assertTrue("sky chroma must deepen", chromaMag(out, 12, 12) > chromaMag(frame, 12, 12) + 1.0)
    }

    @Test
    fun foliageAdjustmentIsBoundedAndLiftsLumaAndGreen() {
        val frame = flat(24, 24, 60, 110, 45) // foliage green
        val sky = zeros(frame)
        val fol = ones(frame)
        val out = SemanticRendering.apply(frame, sky, zeros(frame), fol, params)
        assertMaxChannelShift(frame, out, 12)

        val angBefore = chromaAngle(frame, 12, 12)
        val angAfter = chromaAngle(out, 12, 12)
        assertTrue("foliage hue must be preserved (${abs(angBefore - angAfter)} deg)", abs(angBefore - angAfter) < 1.5)

        assertTrue("foliage luma must lift", lumaAt(out, 12, 12) > lumaAt(frame, 12, 12) + 0.5)
        assertTrue("foliage green must enrich", chromaMag(out, 12, 12) > chromaMag(frame, 12, 12) + 1.0)
    }

    @Test
    fun deepeningStaysInGamutAndPreservesHueAtBoundary() {
        // A saturated blue-ish sky pixel already at the gamut edge (B = 255, zero headroom).
        // The deepening would push B past 255; the old per-channel coerceIn pinned B at 255 while
        // Cr kept scaling, rotating the hue ~2.3 deg. The gamut-aware scale clamp (issue #168) must
        // cap the scale (to 1.0 here) so the hue angle is held fixed instead.
        val frame = flat(8, 8, 150, 190, 255)
        val out = SemanticRendering.apply(frame, ones(frame), zeros(frame), zeros(frame), params)
        val angBefore = chromaAngle(frame, 4, 4)
        val angAfter = chromaAngle(out, 4, 4)
        assertTrue(
            "hue must be preserved at the gamut boundary (${abs(angBefore - angAfter)} deg)",
            abs(angBefore - angAfter) < 1.0,
        )
        // Blue was already clipped; it must stay 255, not be altered by a rotated reconstruction.
        val px = out.argb[4 * out.width + 4]
        assertTrue("blue stays clipped at 255", (px and 0xFF) == 255)
    }

    @Test
    fun deepeningStillEngagesWhenHeadroomAllows() {
        // Regression guard: a sky pixel with headroom (B = 210) must still deepen fully — the
        // gamut clamp only bites when the boost would exceed 8-bit range.
        val frame = flat(8, 8, 110, 155, 210)
        val out = SemanticRendering.apply(frame, ones(frame), zeros(frame), zeros(frame), params)
        assertTrue("sky with headroom must still deepen", chromaMag(out, 4, 4) > chromaMag(frame, 4, 4) + 1.0)
    }

    // --- sky chroma smoothing reduces variance -------------------------------------

    @Test
    fun skySmoothingReducesChromaVariance() {
        // A noisy blue patch, sky mask fully on. Isolate the smoothing (no deepening) so the
        // measurement is pure noise reduction, not variance amplified by the deepening scale.
        val frame = noisyBlue(40, 40, seed = 0x5C1L)
        val sky = ones(frame)
        val fol = zeros(frame)
        val smoothOnly = params.copy(skySatGain = 0.0, foliageSatGain = 0.0)
        val out = SemanticRendering.apply(frame, sky, zeros(frame), fol, smoothOnly)
        val before = chromaStd(frame)
        val after = chromaStd(out)
        assertTrue("sky chroma variance must drop (before=$before after=$after)", after < before * 0.8)
    }

    // --- overcast mask: smoothing only, never deepening ----------------------------

    @Test
    fun overcastMaskNeverDrivesTheBlueDeepening() {
        // A flat blue frame with ONLY the overcast mask on: were the overcast prior wired
        // into the deepening, this blue would deepen like the sky-mask case above; it must
        // stay put (up to 8-bit rounding).
        val frame = flat(24, 24, 110, 155, 210)
        val out = SemanticRendering.apply(frame, zeros(frame), ones(frame), zeros(frame), params)
        val magBefore = chromaMag(frame, 12, 12)
        val magAfter = chromaMag(out, 12, 12)
        assertTrue(
            "overcast-only mask must not deepen chroma ($magBefore -> $magAfter)",
            abs(magAfter - magBefore) <= 1.0,
        )
    }

    @Test
    fun overcastSmoothingReducesChromaVarianceWithoutColourShift() {
        // A noisy gray patch driven by the overcast mask alone: chroma variance drops (the
        // smoothing engages) while the MEAN chroma stays put -- gray stays gray.
        val frame = noisyGray(40, 40, seed = 0xACEL)
        val out = SemanticRendering.apply(frame, zeros(frame), ones(frame), zeros(frame), params)
        val before = chromaStd(frame)
        val after = chromaStd(out)
        assertTrue("overcast chroma variance must drop (before=$before after=$after)", after < before * 0.8)
        val (crB, cbB) = meanChromaVec(frame)
        val (crA, cbA) = meanChromaVec(out)
        assertTrue(
            "overcast smoothing must not shift mean chroma (dCr=${abs(crA - crB)} dCb=${abs(cbA - cbB)})",
            abs(crA - crB) < 1.0 && abs(cbA - cbB) < 1.0,
        )
    }

    @Test
    fun smoothingIsDrivenByTheStrongerOfTheTwoSkyMasks() {
        // max() consumption: with the deepening disabled, smoothing via the blue mask alone
        // and via the overcast mask alone is the same treatment -- bit-identical outputs.
        val frame = noisyGray(32, 32, seed = 0xBEEFL)
        val smoothOnly = params.copy(skySatGain = 0.0, foliageSatGain = 0.0)
        val viaBlue = SemanticRendering.apply(frame, ones(frame), zeros(frame), zeros(frame), smoothOnly)
        val viaOvercast = SemanticRendering.apply(frame, zeros(frame), ones(frame), zeros(frame), smoothOnly)
        assertTrue(
            "smoothing via blue and via overcast masks must be bit-identical when deepening is off",
            viaBlue.argb.contentEquals(viaOvercast.argb),
        )
    }

    // --- strength endpoints + blend ------------------------------------------------

    @Test
    fun strengthZeroIsBitExactPassthrough() {
        val frame = flat(8, 8, 110, 155, 210)
        val out = SemanticRendering.apply(frame, ones(frame), zeros(frame), zeros(frame), params.copy(strength = 0.0))
        assertSame("strength 0 must return the input frame untouched", frame, out)
    }

    @Test
    fun strengthBlendDeepensLessThanFull() {
        val frame = flat(16, 16, 110, 155, 210)
        val sky = ones(frame)
        val fol = zeros(frame)
        val orig = chromaMag(frame, 8, 8)
        val half = chromaMag(SemanticRendering.apply(frame, sky, zeros(frame), fol, params.copy(strength = 0.5)), 8, 8)
        val full = chromaMag(SemanticRendering.apply(frame, sky, zeros(frame), fol, params.copy(strength = 1.0)), 8, 8)
        assertTrue("half strength must deepen less than full ($half vs $full)", half < full)
        assertTrue("half strength must still deepen vs original ($half vs $orig)", half > orig)
    }

    // --- zero masks passthrough + parallel bit-identity ----------------------------

    @Test
    fun zeroMasksLeaveRgbUnchanged() {
        val frame = mixed(20, 20)
        val out = SemanticRendering.apply(frame, zeros(frame), zeros(frame), zeros(frame), params)
        for (i in frame.argb.indices) {
            assertTrue(
                "zero masks must leave RGB unchanged at $i",
                (frame.argb[i] and 0x00FFFFFF) == (out.argb[i] and 0x00FFFFFF),
            )
        }
    }

    @Test
    fun outputIsAlwaysOpaque() {
        val frame = Frame(4, 4, IntArray(16) { 0x00_6E_9B_D2.toInt() }, timestampMillis = 1L)
        val out = SemanticRendering.apply(frame, ones(frame), zeros(frame), zeros(frame), params)
        for (px in out.argb) assertTrue("alpha must be opaque", ((px ushr 24) and 0xFF) == 0xFF)
    }

    @Test
    fun parallelMatchesSerial() {
        val frame = noisyBlue(48, 40, seed = 0x1234L)
        val sky = SkyMask.compute(frame)
        val overcast = OvercastSkyMask.compute(frame)
        val fol = FoliageMask.compute(frame)
        val serial = SemanticRendering.apply(frame, sky, overcast, fol, params, PipelineParallel.SERIAL_CHUNKS)
        val parallel = SemanticRendering.apply(frame, sky, overcast, fol, params, PipelineParallel.parallelism)
        assertTrue("parallel semantic rendering must be bit-identical to serial", serial.argb.contentEquals(parallel.argb))
    }

    // --- helpers -------------------------------------------------------------------

    private fun flat(w: Int, h: Int, r: Int, g: Int, b: Int): Frame {
        val px = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        return Frame(w, h, IntArray(w * h) { px }, 1L)
    }

    private fun mixed(w: Int, h: Int): Frame {
        val out = IntArray(w * h)
        for (i in out.indices) {
            val r = (i * 7 + 30) and 0xFF
            val g = (i * 5 + 90) and 0xFF
            val b = (i * 3 + 150) and 0xFF
            out[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        return Frame(w, h, out, 1L)
    }

    /** A blue base with per-pixel channel noise (chroma speckle) for the smoothing proof. */
    private fun noisyBlue(w: Int, h: Int, seed: Long): Frame {
        var state = seed and 0xFFFFFFFFL
        fun next(): Int {
            state = (state * 1664525L + 1013904223L) and 0xFFFFFFFFL
            return (((state ushr 24) and 0xFFL).toInt()) - 128 // ~[-128, 127]
        }
        val out = IntArray(w * h)
        for (i in out.indices) {
            val r = (110 + next() / 8).coerceIn(0, 255)
            val g = (155 + next() / 8).coerceIn(0, 255)
            val b = (210 + next() / 8).coerceIn(0, 255)
            out[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        return Frame(w, h, out, 1L)
    }

    /** A bright gray base with per-pixel channel noise (chroma speckle on neutral content). */
    private fun noisyGray(w: Int, h: Int, seed: Long): Frame {
        var state = seed and 0xFFFFFFFFL
        fun next(): Int {
            state = (state * 1664525L + 1013904223L) and 0xFFFFFFFFL
            return (((state ushr 24) and 0xFFL).toInt()) - 128 // ~[-128, 127]
        }
        val out = IntArray(w * h)
        for (i in out.indices) {
            val r = (200 + next() / 10).coerceIn(0, 255)
            val g = (200 + next() / 10).coerceIn(0, 255)
            val b = (200 + next() / 10).coerceIn(0, 255)
            out[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        return Frame(w, h, out, 1L)
    }

    private fun ones(frame: Frame) = DoubleArray(frame.argb.size) { 1.0 }
    private fun zeros(frame: Frame) = DoubleArray(frame.argb.size) { 0.0 }

    /** Mean (Cr, Cb) opponent chroma vector over the whole frame. */
    private fun meanChromaVec(frame: Frame): Pair<Double, Double> {
        var sumCr = 0.0
        var sumCb = 0.0
        for (px in frame.argb) {
            val r = ((px shr 16) and 0xFF).toDouble()
            val g = ((px shr 8) and 0xFF).toDouble()
            val b = (px and 0xFF).toDouble()
            val yl = 0.299 * r + 0.587 * g + 0.114 * b
            sumCr += r - yl
            sumCb += b - yl
        }
        return (sumCr / frame.argb.size) to (sumCb / frame.argb.size)
    }

    private fun assertMaxChannelShift(a: Frame, b: Frame, maxShift: Int) {
        var worst = 0
        for (i in a.argb.indices) {
            val pa = a.argb[i]
            val pb = b.argb[i]
            val dr = abs(((pa shr 16) and 0xFF) - ((pb shr 16) and 0xFF))
            val dg = abs(((pa shr 8) and 0xFF) - ((pb shr 8) and 0xFF))
            val db = abs((pa and 0xFF) - (pb and 0xFF))
            worst = maxOf(worst, dr, dg, db)
        }
        assertTrue("max per-channel shift $worst must stay within $maxShift codes", worst <= maxShift)
    }

    private fun lumaAt(frame: Frame, x: Int, y: Int): Double {
        val px = frame.argb[y * frame.width + x]
        return 0.299 * ((px shr 16) and 0xFF) + 0.587 * ((px shr 8) and 0xFF) + 0.114 * (px and 0xFF)
    }

    private fun chromaMag(frame: Frame, x: Int, y: Int): Double {
        val px = frame.argb[y * frame.width + x]
        val r = ((px shr 16) and 0xFF).toDouble()
        val g = ((px shr 8) and 0xFF).toDouble()
        val b = (px and 0xFF).toDouble()
        val yl = 0.299 * r + 0.587 * g + 0.114 * b
        return sqrt((r - yl) * (r - yl) + (b - yl) * (b - yl))
    }

    private fun chromaAngle(frame: Frame, x: Int, y: Int): Double {
        val px = frame.argb[y * frame.width + x]
        val r = ((px shr 16) and 0xFF).toDouble()
        val g = ((px shr 8) and 0xFF).toDouble()
        val b = (px and 0xFF).toDouble()
        val yl = 0.299 * r + 0.587 * g + 0.114 * b
        return Math.toDegrees(atan2(r - yl, b - yl))
    }

    private fun chromaStd(frame: Frame): Double {
        val crs = ArrayList<Double>()
        val cbs = ArrayList<Double>()
        for (px in frame.argb) {
            val r = ((px shr 16) and 0xFF).toDouble()
            val g = ((px shr 8) and 0xFF).toDouble()
            val b = (px and 0xFF).toDouble()
            val yl = 0.299 * r + 0.587 * g + 0.114 * b
            crs.add(r - yl); cbs.add(b - yl)
        }
        return sqrt(variance(crs) + variance(cbs))
    }

    private fun variance(v: List<Double>): Double {
        val m = v.average()
        return v.sumOf { (it - m) * (it - m) } / v.size
    }
}
