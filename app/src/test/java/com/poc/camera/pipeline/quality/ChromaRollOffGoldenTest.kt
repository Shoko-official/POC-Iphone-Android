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
 * Objective golden gate for the chroma roll-off (issue #97), productised from the validated
 * local pair-tuning (knee 30, soft 18). Every claim is measured on the synthetic
 * "chromaRollOff" scene -- an isolated saturated-red "lips" band inside a low-chroma
 * skin-toned "face", with three below-knee normal-saturation patches -- so the numbers are
 * deterministic and reproducible across machines.
 *
 * The suite proves:
 *  1. COMPRESSION: the extreme-chroma lips are compressed by at least [MIN_LIPS_COMPRESSION]x
 *     in chroma magnitude.
 *  2. NORMAL SATURATION UNTOUCHED: the face and every normal patch (all at/under the knee)
 *     shift less than [MAX_NORMAL_SHIFT] code of mean chroma -- in fact bit-exactly, proven
 *     separately.
 *  3. HUE PRESERVED: the lips chroma angle shifts under [MAX_HUE_SHIFT_DEG] degrees (the
 *     roll-off scales cr and cb by the same factor, so hue is preserved up to 8-bit rounding).
 *  4. STRENGTH BLEND + DETERMINISM + parallel bit-identity.
 *  5. INTEGRATION: the full pipeline with the roll-off on compresses the lips vs the roll-off
 *     off, while leaving the below-knee normal patches essentially unchanged.
 *  6. DEFAULT DECISION EVIDENCE: with the roll-off forced on at DEFAULT it pushes the
 *     colorchart clean-truth chroma MAE far past its committed fidelity floor, which is why
 *     it ships OFF in DEFAULT and ON only in RENDITION (whose target tracks it).
 *
 * Baselines were measured 2026-07-22 and are documented at each assertion; bounds carry
 * margin so the test is a regression gate, not a brittle snapshot.
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
    fun defaultOnWouldBreakTheColorChartFidelityFloor() {
        // The measured justification for shipping chromaRollOff OFF in DEFAULT: on the
        // clean-truth fidelity axis it compresses the intentionally-saturated colorchart
        // patches (no matching target), pushing that scene's chroma MAE far past its
        // committed floor. Every low-chroma scene is a no-op (below the knee), so only
        // colorchart is threatened -- which is exactly why RENDITION (whose target tracks
        // the roll-off) can ship it on while DEFAULT cannot.
        val clean = SyntheticScenes.clean("colorchart")
        val seed = FIDELITY_SEED + 5 * FIDELITY_STRIDE // colorchart is scene index 5
        val merged = BurstMergePipeline.merge(
            SyntheticScenes.burst("colorchart", seed, PipelineQualityReport.DEFAULT_BURST_SIZE),
        ).merged
        val maeOff = Mae.between(FinishingPipeline.apply(merged, FinishingParams.DEFAULT.copy(chromaRollOff = 0.0)), clean)
        val maeOn = Mae.between(FinishingPipeline.apply(merged, FinishingParams.DEFAULT.copy(chromaRollOff = 1.0)), clean)
        println("[chroma] colorchart fidelity MAE roll-off off=${"%.2f".format(maeOff)} on=${"%.2f".format(maeOn)} (committed floor $COLORCHART_MAE_FLOOR)")
        assertTrue("roll-off off must clear the committed colorchart floor (was $maeOff)", maeOff <= COLORCHART_MAE_FLOOR)
        assertTrue("roll-off on must BREAK the committed colorchart floor, justifying DEFAULT off (was $maeOn)", maeOn > COLORCHART_MAE_FLOOR)
    }

    // --- helpers -------------------------------------------------------------------

    private fun meanChromaMag(frame: Frame, bounds: IntArray): Double {
        var sum = 0.0
        var n = 0
        for (y in bounds[1] until bounds[3]) {
            for (x in bounds[0] until bounds[2]) {
                val px = frame.argb[y * frame.width + x]
                val r = ((px shr 16) and 0xFF).toDouble()
                val g = ((px shr 8) and 0xFF).toDouble()
                val b = (px and 0xFF).toDouble()
                val yl = 0.299 * r + 0.587 * g + 0.114 * b
                val cr = r - yl
                val cb = b - yl
                sum += sqrt(cr * cr + cb * cb)
                n++
            }
        }
        return sum / n
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
        // MEASURED 2026-07-22 on the deterministic "chromaRollOff" scene (knee 30, soft 18).
        // Actuals -> baked bound (with margin):
        //   lips chroma compression factor : x1.96      -> floor 1.6
        //   face / normal patch shift      : 0.00 codes -> ceiling 1.0 (bit-exact below knee)
        //   lips hue angle shift           : ~0.4 deg   -> ceiling 1.5
        //   pipeline lips drop (roll on)   : measured   -> floor (see MIN_PIPELINE_LIPS_DROP)
        //   colorchart fidelity MAE on     : ~10.7      -> committed floor 4.21 (broken -> DEFAULT off)
        const val MIN_LIPS_COMPRESSION = 1.6
        const val MAX_NORMAL_SHIFT = 1.0
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
