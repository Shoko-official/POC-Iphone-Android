package com.poc.camera.pipeline.quality

import com.poc.camera.pipeline.BurstMergePipeline
import com.poc.camera.pipeline.ChromaRollOff
import com.poc.camera.pipeline.ChromaRollOffParams
import com.poc.camera.pipeline.FinishingParams
import com.poc.camera.pipeline.FinishingPipeline
import com.poc.camera.pipeline.Frame
import com.poc.camera.pipeline.PipelineParallel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * Objective golden gate for the spatially gated chroma roll-off (issues #97, #107),
 * productised from the validated local pair-tuning (knee 30, soft 18) plus the isolation
 * gate (isolationFactor 1.5, neighbourhood radius 24). Every claim is measured on
 * deterministic synthetic scenes, so the numbers are reproducible across machines. The
 * anchor scene is "chromaRollOff" -- an isolated saturated-red "lips" band inside a
 * low-chroma skin-toned "face", with three below-knee normal-saturation patches.
 *
 * The suite proves:
 *  1. COMPRESSION: the extreme-chroma lips are compressed by at least [MIN_LIPS_COMPRESSION]x
 *     in chroma magnitude.
 *  2. NORMAL SATURATION UNTOUCHED: the face and every normal patch (all at/under the knee)
 *     shift less than [MAX_NORMAL_SHIFT] code of mean chroma -- in fact bit-exactly, proven
 *     separately.
 *  3. UNIFORM SATURATION PASSES THROUGH (issue #107, the gate's whole point): a uniformly
 *     saturated frame far above the knee is BIT-EXACT, and the colorchart's uniformly
 *     saturated patches shift under [MAX_UNIFORM_SHIFT] codes of mean chroma -- where the
 *     ungated whole-frame shoulder desaturated them broadly.
 *  4. ISOLATION IS THE GATE: an isolated extreme-chroma spot on a low-chroma surround
 *     compresses along the UNGATED shoulder (the local mean is too low to lift the
 *     effective knee), matching the original operator on the validated real case.
 *  5. HUE PRESERVED: the lips chroma angle shifts under [MAX_HUE_SHIFT_DEG] degrees (the
 *     roll-off scales cr and cb by the same factor, so hue is preserved up to 8-bit rounding).
 *  6. STRENGTH BLEND + DETERMINISM + parallel bit-identity.
 *  7. INTEGRATION: the full pipeline with the roll-off on compresses the lips vs the roll-off
 *     off, while leaving the below-knee normal patches essentially unchanged.
 *  8. DEFAULT DECISION EVIDENCE: the ungated shoulder forced on at DEFAULT pushed the
 *     colorchart clean-truth MAE far past its committed fidelity floor (measured ~10.7 vs
 *     floor 4.21, the original reason DEFAULT ships it off). With the gate the same forced-on
 *     run stays WITHIN the floor -- the fidelity threat is gone; DEFAULT still ships it off
 *     because the clean-truth axis has no matching target for the residual isolated-spot
 *     compression (a conservative choice, no longer a forced one).
 *
 * Baselines re-measured 2026-07-22 with the spatial gate and documented at each assertion;
 * bounds carry margin so the test is a regression gate, not a brittle snapshot.
 */
class ChromaRollOffGoldenTest {

    private val scene = SyntheticScenes.chromaRollOffClean()
    private val lips = SyntheticScenes.chromaLipsBounds()
    private val face = SyntheticScenes.chromaFaceBounds()

    // --- compression ---------------------------------------------------------------

    @Test
    fun extremeLipsChromaIsCompressedByAMeasuredFactor() {
        val rolled = ChromaRollOff.apply(scene)
        val before = meanChromaMag(scene, lips)
        val after = meanChromaMag(rolled, lips)
        val factor = before / after
        println("[chroma] lips chroma magnitude ${"%.2f".format(before)} -> ${"%.2f".format(after)} (x${"%.2f".format(factor)})")
        assertTrue("lips must compress by at least ${MIN_LIPS_COMPRESSION}x (was x$factor)", factor >= MIN_LIPS_COMPRESSION)
    }

    // --- normal saturation untouched -----------------------------------------------

    @Test
    fun normalSaturationIsLeftUntouched() {
        val rolled = ChromaRollOff.apply(scene)
        val faceShift = abs(meanChromaMag(rolled, face) - meanChromaMag(scene, face))
        println("[chroma] face mean chroma shift ${"%.4f".format(faceShift)}")
        assertTrue("face must shift < $MAX_NORMAL_SHIFT code (was $faceShift)", faceShift < MAX_NORMAL_SHIFT)
        for (patch in SyntheticScenes.chromaNormalPatches) {
            val bounds = intArrayOf(patch.x0, patch.y0, patch.x1, patch.y1)
            val shift = abs(meanChromaMag(rolled, bounds) - meanChromaMag(scene, bounds))
            println("[chroma] ${patch.name} mean chroma shift ${"%.4f".format(shift)}")
            assertTrue("${patch.name} must shift < $MAX_NORMAL_SHIFT code (was $shift)", shift < MAX_NORMAL_SHIFT)
        }
    }

    @Test
    fun belowKneeRegionsAreBitExact() {
        // The face and every normal patch sit at/under the knee, so the shoulder is the exact
        // identity there: those pixels must round-trip byte-for-byte even at full strength.
        val rolled = ChromaRollOff.apply(scene)
        assertRegionByteIdentical("face", scene, rolled, face)
        for (patch in SyntheticScenes.chromaNormalPatches) {
            assertRegionByteIdentical(patch.name, scene, rolled, intArrayOf(patch.x0, patch.y0, patch.x1, patch.y1))
        }
    }

    // --- uniform saturation passes through (the issue #107 gate) --------------------

    @Test
    fun uniformlySaturatedFrameIsBitExact() {
        // Edge-to-edge extreme chroma (the lips colour, magnitude ~85 >> knee 30): every
        // pixel IS its own neighbourhood, so the effective knee (1.5x local mean ~127) sits
        // far above the magnitude and the whole frame passes through bit-exactly -- the
        // ungated shoulder compressed this frame by ~2x.
        val uniform = Frame(
            SyntheticScenes.SIZE,
            SyntheticScenes.SIZE,
            IntArray(SyntheticScenes.SIZE * SyntheticScenes.SIZE) { UNIFORM_SATURATED_ARGB },
            timestampMillis = 1L,
        )
        val rolled = ChromaRollOff.apply(uniform)
        assertTrue("a uniformly saturated frame must pass through bit-exactly", uniform.argb.contentEquals(rolled.argb))
    }

    @Test
    fun colorChartUniformPatchesPassThroughNearlyUntouched() {
        // The colorchart's patches carry chroma magnitudes up to ~116, far past the knee --
        // exactly the uniformly saturated content the ungated whole-frame shoulder
        // desaturated broadly. With the gate each patch reads as its own neighbourhood, so
        // the whole-frame MEAN chroma shift collapses to well under a code (residual
        // compression survives only at patch borders, where a saturated patch abuts a
        // neutral one and genuinely reads as partially isolated).
        val chart = SyntheticScenes.clean("colorchart")
        val rolled = ChromaRollOff.apply(chart)
        var shiftSum = 0.0
        for (i in chart.argb.indices) {
            shiftSum += abs(chromaMagOf(chart.argb[i]) - chromaMagOf(rolled.argb[i]))
        }
        val meanShift = shiftSum / chart.argb.size
        println("[chroma] colorchart mean chroma shift ${"%.4f".format(meanShift)} (gate on, full strength)")
        assertTrue(
            "uniformly saturated colorchart must shift < $MAX_UNIFORM_SHIFT codes of mean chroma (was $meanShift)",
            meanShift < MAX_UNIFORM_SHIFT,
        )
    }

    // --- isolation is the gate -------------------------------------------------------

    @Test
    fun isolatedSpotOnLowChromaSurroundCompressesAlongTheUngatedShoulder() {
        // A 4x4 spot of the lips colour on a near-gray surround (magnitude ~3): the spot's
        // local mean (~3.4) is far under knee / isolationFactor, so its effective knee IS
        // the global knee and it compresses along the original ungated shoulder -- the
        // validated real case (isolated runaway lips) is preserved exactly.
        val size = SyntheticScenes.SIZE
        val surround = (0xFF shl 24) or (120 shl 16) or (118 shl 8) or 116
        val px = IntArray(size * size) { surround }
        for (y in size / 2 - 2 until size / 2 + 2) {
            for (x in size / 2 - 2 until size / 2 + 2) {
                px[y * size + x] = UNIFORM_SATURATED_ARGB
            }
        }
        val spotFrame = Frame(size, size, px, timestampMillis = 1L)
        val rolled = ChromaRollOff.apply(spotFrame)

        val before = chromaMagOf(UNIFORM_SATURATED_ARGB)
        val after = chromaMagOf(rolled.argb[(size / 2) * size + size / 2])
        val ungated = ChromaRollOff.shoulder(before, ChromaRollOffParams.DEFAULT)
        val factor = before / after
        println(
            "[chroma] isolated spot ${"%.2f".format(before)} -> ${"%.2f".format(after)} " +
                "(ungated shoulder ${"%.2f".format(ungated)}, x${"%.2f".format(factor)})",
        )
        assertTrue("isolated spot must compress by at least ${MIN_SPOT_COMPRESSION}x (was x$factor)", factor >= MIN_SPOT_COMPRESSION)
        assertTrue(
            "isolated spot must land on the ungated shoulder within $MAX_SHOULDER_DEVIATION codes (was ${abs(after - ungated)})",
            abs(after - ungated) <= MAX_SHOULDER_DEVIATION,
        )
    }

    // --- hue preserved -------------------------------------------------------------

    @Test
    fun hueIsPreservedOnCompressedLips() {
        val rolled = ChromaRollOff.apply(scene)
        val angBefore = meanChromaAngleDeg(scene, lips)
        val angAfter = meanChromaAngleDeg(rolled, lips)
        val shift = abs(angBefore - angAfter)
        println("[chroma] lips hue angle ${"%.2f".format(angBefore)} -> ${"%.2f".format(angAfter)} deg (shift ${"%.2f".format(shift)})")
        assertTrue("lips hue must shift < $MAX_HUE_SHIFT_DEG deg (was $shift)", shift < MAX_HUE_SHIFT_DEG)
    }

    // --- strength blend + determinism + parallel bit-identity ----------------------

    @Test
    fun strengthZeroIsBitExactPassthrough() {
        val out = ChromaRollOff.apply(scene, ChromaRollOffParams.DEFAULT.copy(strength = 0.0))
        assertSame("strength 0 must return the input frame untouched", scene, out)
    }

    @Test
    fun rollOffIsDeterministicAndParallelMatchesSerial() {
        val a = ChromaRollOff.apply(scene)
        val b = ChromaRollOff.apply(scene)
        assertTrue("roll-off must be deterministic", a.argb.contentEquals(b.argb))

        val serial = ChromaRollOff.apply(scene, ChromaRollOffParams.DEFAULT, PipelineParallel.SERIAL_CHUNKS)
        val parallel = ChromaRollOff.apply(scene, ChromaRollOffParams.DEFAULT, PipelineParallel.parallelism)
        assertTrue("parallel roll-off must be bit-identical to serial", serial.argb.contentEquals(parallel.argb))
    }

    // --- integration through the finishing pipeline --------------------------------

    @Test
    fun fullPipelineEngagesTheRollOff() {
        val off = FinishingPipeline.apply(scene, FinishingParams.DEFAULT.copy(chromaRollOff = 0.0))
        val on = FinishingPipeline.apply(scene, FinishingParams.DEFAULT.copy(chromaRollOff = 1.0))
        val lipsOff = meanChromaMag(off, lips)
        val lipsOn = meanChromaMag(on, lips)
        val faceOff = meanChromaMag(off, face)
        val faceOn = meanChromaMag(on, face)
        println(
            "[chroma] pipeline lips roll-off off=${"%.1f".format(lipsOff)} on=${"%.1f".format(lipsOn)} " +
                "face off=${"%.1f".format(faceOff)} on=${"%.1f".format(faceOn)}",
        )
        assertTrue(
            "pipeline must compress the lips with the roll-off on (off=$lipsOff on=$lipsOn)",
            lipsOn <= lipsOff - MIN_PIPELINE_LIPS_DROP,
        )
        assertTrue(
            "pipeline must leave the below-knee face essentially unchanged (off=$faceOff on=$faceOn)",
            abs(faceOn - faceOff) <= MAX_PIPELINE_FACE_SHIFT,
        )
    }

    // --- DEFAULT-decision evidence -------------------------------------------------

    @Test
    fun spatialGateKeepsColorChartFidelityWithinFloorEvenForcedOn() {
        // The measured DEFAULT-decision evidence, updated for the spatial gate (issue #107).
        // The UNGATED whole-frame shoulder forced on at DEFAULT compressed the intentionally-
        // saturated colorchart patches and pushed the scene's clean-truth MAE to ~10.7, far
        // past the committed 4.21 floor -- the original reason chromaRollOff ships OFF in
        // DEFAULT. The gate removes that threat: the patches read as their own neighbourhood
        // and pass through, so the SAME forced-on run now stays WITHIN the committed floor.
        // DEFAULT still ships the roll-off off -- the clean-truth axis has no matching target
        // for the residual isolated-spot compression -- but the decision is now conservative
        // rather than forced by a broken floor.
        val clean = SyntheticScenes.clean("colorchart")
        val seed = FIDELITY_SEED + 5 * FIDELITY_STRIDE // colorchart is scene index 5
        val merged = BurstMergePipeline.merge(
            SyntheticScenes.burst("colorchart", seed, PipelineQualityReport.DEFAULT_BURST_SIZE),
        ).merged
        val maeOff = Mae.between(FinishingPipeline.apply(merged, FinishingParams.DEFAULT.copy(chromaRollOff = 0.0)), clean)
        val maeOn = Mae.between(FinishingPipeline.apply(merged, FinishingParams.DEFAULT.copy(chromaRollOff = 1.0)), clean)
        println("[chroma] colorchart fidelity MAE roll-off off=${"%.2f".format(maeOff)} on=${"%.2f".format(maeOn)} (committed floor $COLORCHART_MAE_FLOOR)")
        assertTrue("roll-off off must clear the committed colorchart floor (was $maeOff)", maeOff <= COLORCHART_MAE_FLOOR)
        assertTrue(
            "the gated roll-off forced on must STAY WITHIN the committed colorchart floor (was $maeOn)",
            maeOn <= COLORCHART_MAE_FLOOR,
        )
    }

    // --- helpers -------------------------------------------------------------------

    private fun meanChromaMag(frame: Frame, bounds: IntArray): Double {
        var sum = 0.0
        var n = 0
        for (y in bounds[1] until bounds[3]) {
            for (x in bounds[0] until bounds[2]) {
                sum += chromaMagOf(frame.argb[y * frame.width + x])
                n++
            }
        }
        return sum / n
    }

    private fun chromaMagOf(px: Int): Double {
        val r = ((px shr 16) and 0xFF).toDouble()
        val g = ((px shr 8) and 0xFF).toDouble()
        val b = (px and 0xFF).toDouble()
        val yl = 0.299 * r + 0.587 * g + 0.114 * b
        val cr = r - yl
        val cb = b - yl
        return sqrt(cr * cr + cb * cb)
    }

    /** Angle (degrees) of the mean (cr, cb) chroma vector over [bounds]. */
    private fun meanChromaAngleDeg(frame: Frame, bounds: IntArray): Double {
        var sumCr = 0.0
        var sumCb = 0.0
        var n = 0
        for (y in bounds[1] until bounds[3]) {
            for (x in bounds[0] until bounds[2]) {
                val px = frame.argb[y * frame.width + x]
                val r = ((px shr 16) and 0xFF).toDouble()
                val g = ((px shr 8) and 0xFF).toDouble()
                val b = (px and 0xFF).toDouble()
                val yl = 0.299 * r + 0.587 * g + 0.114 * b
                sumCr += r - yl
                sumCb += b - yl
                n++
            }
        }
        return Math.toDegrees(atan2(sumCr / n, sumCb / n))
    }

    private fun assertRegionByteIdentical(name: String, a: Frame, b: Frame, bounds: IntArray) {
        for (y in bounds[1] until bounds[3]) {
            for (x in bounds[0] until bounds[2]) {
                val i = y * a.width + x
                assertEquals("$name pixel ($x,$y) must be bit-exact below the knee", a.argb[i], b.argb[i])
            }
        }
    }

    private companion object {
        /** The lips colour rgb(190, 75, 82): chroma magnitude ~84.6, far above the knee. */
        const val UNIFORM_SATURATED_ARGB = (0xFF shl 24) or (190 shl 16) or (75 shl 8) or 82

        // MEASURED 2026-07-22 on the deterministic scenes with the SPATIAL GATE (knee 30,
        // soft 18, isolationFactor 1.5, neighbourhood radius 24). Actuals -> baked bound
        // (with margin):
        //   lips chroma compression factor : x1.21      -> floor 1.15 (was x1.96 ungated: the
        //     40x16 lips band partially fills its own radius-24 neighbourhood, lifting its
        //     effective knee to ~63 -- by design, a larger saturated region reads as less
        //     isolated; a genuinely small spot still compresses fully, proven separately)
        //   face / normal patch shift      : 0.00 codes -> ceiling 1.0 (bit-exact below knee)
        //   uniform frame                  : bit-exact  -> asserted exactly
        //   colorchart mean chroma shift   : 0.87 codes -> ceiling 2.0 (ungated compressed it
        //     broadly; residual is patch-border-only)
        //   isolated 4x4 spot factor       : x1.96      -> floor 1.85, and lands on the
        //     ungated shoulder (43.26 vs 43.54) -> deviation ceiling 1.0 code
        //   lips hue angle shift           : ~0.05 deg  -> ceiling 1.5
        //   pipeline lips drop (roll on)   : 16.0 codes -> floor 5.0 (off=81.4 on=65.4)
        //   pipeline face shift            : 0.0 codes  -> ceiling 1.0
        //   colorchart fidelity MAE on     : 3.97 (off 3.89) -> committed floor 4.21 HELD
        //     (the ungated shoulder measured ~10.7, breaking it -- see the evidence test)
        const val MIN_LIPS_COMPRESSION = 1.15
        const val MAX_NORMAL_SHIFT = 1.0
        const val MAX_UNIFORM_SHIFT = 2.0
        const val MIN_SPOT_COMPRESSION = 1.85
        const val MAX_SHOULDER_DEVIATION = 1.0
        const val MAX_HUE_SHIFT_DEG = 1.5
        const val MIN_PIPELINE_LIPS_DROP = 5.0
        const val MAX_PIPELINE_FACE_SHIFT = 1.0

        // The committed colorchart clean-truth MAE ceiling from GoldenPipelineRegressionTest.
        const val COLORCHART_MAE_FLOOR = 4.21

        // Matches PipelineQualityReport / GoldenPipelineRegressionTest seed derivation.
        const val FIDELITY_SEED = 0xC0FFEEL
        const val FIDELITY_STRIDE = 0x1000193L
    }
}
