package com.poc.camera.pipeline.quality

import com.poc.camera.pipeline.BurstMergePipeline
import com.poc.camera.pipeline.FinishingParams
import com.poc.camera.pipeline.FinishingPipeline
import com.poc.camera.pipeline.Frame
import com.poc.camera.pipeline.NightMergeParams
import com.poc.camera.pipeline.NightPipeline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.roundToInt

/**
 * Objective gate for the night pipeline's LOCAL-motion machinery, the piece the
 * drift-only nightscene golden ([NightGoldenRegressionTest]) admits it never
 * exercises: a walking-subject-scale object ([SyntheticScenes.nightMotionBurst])
 * translating across an otherwise static night burst. The reference frame's object
 * position defines the expected output; everything the object swept afterwards must
 * come out as background, not as a ghost trail.
 *
 * The bounds below lock in the issue #130 fix (re-measured 2026-07-23, seed
 * 0xC0FFEE); the two documented gaps of the first baking are closed:
 *
 *  - ALIGNMENT: the global [com.poc.camera.pipeline.FrameAligner] used to FOLLOW THE
 *    SUBJECT once its displacement exceeded the drift scale (offsets up to (38, 33)
 *    against a true drift of at most (3, 2)), because the moving textured object
 *    dominated the whole-frame matching cost. The per-tile median vote makes the
 *    static majority outvote the subject: every frame now recovers its offset within
 *    1-2 px of the true drift, and the static region averages every honest frame --
 *    static MAE ~2.52 finished, EQUAL to the ~2.51 the same mask measures on the
 *    drift-only burst (it was ~5.4 under the mis-lock).
 *
 *  - TRAIL: the dual-band [com.poc.camera.pipeline.GhostRejector] comparison in
 *    [com.poc.camera.pipeline.RobustFrameMerger] is the working defense. At night
 *    noise the subject step (~48 luma) sits under 2x the RAW aligned-difference
 *    sigma, invisible to any raw per-pixel band; the low-pass band shrinks the noise
 *    ~7x while the object keeps its contrast (~13 low-pass sigmas) and rejects it.
 *    A naive unweighted average leaves a sweep-region MAE of ~17.6 vs the clean
 *    background; the robust merge holds it at ~4.2 (finished ~3.6). The sweep
 *    region's residual mean sits ~1.4 above the static region's finishing shift --
 *    not subject luminance but the noise cost of averaging fewer honest frames where
 *    the subject was rejected, which the finish lifts slightly more.
 *
 *  - OBJECT INTEGRITY: rejection symmetry now works FOR the subject. The frames that
 *    show BACKGROUND at the reference object position are rejected by the same
 *    low-pass band, so the reference's subject survives at ~66.7 finished vs ~68.7
 *    clean (~97% retained; it was ~39.7, ~58%, under the mis-lock).
 *
 *  - MOTION WEIGHTING: near-neutral here, as on the drift-only scene. k=1 vs k=0
 *    moves static-region merged MAE by only ~0.002 (3.3809 vs 3.3789). The global
 *    frame weight targets whole-frame residual motion (handshake blur), which this
 *    fixture does not model; the per-pixel ghost rejection above is the primary
 *    defense against a moving subject. The gate proves the knob is wired and does
 *    not hurt.
 *
 * All floors are MEASURED BASELINES with ~2% tolerance (generous bounds where the
 * quantity is a small difference); a regression past the tolerance fails the suite.
 * Re-baseline only with an explicit, justified reason.
 */
class NightMotionGoldenTest {

    /** Sweep mask: every pixel the object covers in frames 1..count-1, EXCLUDING its
     *  reference-frame footprint (where the object legitimately remains). */
    private fun sweepMask(count: Int): BooleanArray {
        val size = SyntheticScenes.SIZE
        val mask = BooleanArray(size * size)
        for (i in 1 until count) {
            val b = SyntheticScenes.nightMotionObjectBounds(i)
            for (y in b[1] until b[3]) for (x in b[0] until b[2]) mask[y * size + x] = true
        }
        val ref = SyntheticScenes.nightMotionObjectBounds(0)
        for (y in ref[1] until ref[3]) for (x in ref[0] until ref[2]) mask[y * size + x] = false
        return mask
    }

    /** Static mask: everything the object never touches in any frame. */
    private fun staticMask(count: Int): BooleanArray {
        val size = SyntheticScenes.SIZE
        val mask = BooleanArray(size * size) { true }
        for (i in 0 until count) {
            val b = SyntheticScenes.nightMotionObjectBounds(i)
            for (y in b[1] until b[3]) for (x in b[0] until b[2]) mask[y * size + x] = false
        }
        return mask
    }

    /** Unweighted per-pixel mean of [frames]: no alignment, no rejection -- what the
     *  merge would do with the motion machinery absent. */
    private fun naiveAverage(frames: List<Frame>): Frame {
        val n = frames.size
        val out = IntArray(frames[0].argb.size)
        for (i in out.indices) {
            var r = 0.0
            var g = 0.0
            var b = 0.0
            for (f in frames) {
                val p = f.argb[i]
                r += (p shr 16) and 0xFF
                g += (p shr 8) and 0xFF
                b += p and 0xFF
            }
            out[i] = (0xFF shl 24) or
                ((r / n).roundToInt().coerceIn(0, 255) shl 16) or
                ((g / n).roundToInt().coerceIn(0, 255) shl 8) or
                (b / n).roundToInt().coerceIn(0, 255)
        }
        return Frame(frames[0].width, frames[0].height, out, frames[0].timestampMillis)
    }

    private fun maskMae(a: Frame, b: Frame, mask: BooleanArray): Double {
        var sum = 0L
        var count = 0
        for (i in mask.indices) {
            if (!mask[i]) continue
            val x = a.argb[i]
            val y = b.argb[i]
            sum += abs(((x shr 16) and 0xFF) - ((y shr 16) and 0xFF)).toLong()
            sum += abs(((x shr 8) and 0xFF) - ((y shr 8) and 0xFF)).toLong()
            sum += abs((x and 0xFF) - (y and 0xFF)).toLong()
            count += 3
        }
        return sum.toDouble() / count
    }

    private fun maskPsnr(a: Frame, b: Frame, mask: BooleanArray): Double {
        var squaredError = 0L
        var count = 0
        for (i in mask.indices) {
            if (!mask[i]) continue
            val x = a.argb[i]
            val y = b.argb[i]
            val dr = ((x shr 16) and 0xFF) - ((y shr 16) and 0xFF)
            val dg = ((x shr 8) and 0xFF) - ((y shr 8) and 0xFF)
            val db = (x and 0xFF) - (y and 0xFF)
            squaredError += (dr * dr + dg * dg + db * db).toLong()
            count += 3
        }
        if (squaredError == 0L) return Double.POSITIVE_INFINITY
        return 10.0 * log10(255.0 * 255.0 / (squaredError.toDouble() / count))
    }

    private fun maskMeanLuma(frame: Frame, mask: BooleanArray): Double {
        var sum = 0.0
        var count = 0
        for (i in mask.indices) {
            if (!mask[i]) continue
            val p = frame.argb[i]
            sum += 0.299 * ((p shr 16) and 0xFF) + 0.587 * ((p shr 8) and 0xFF) + 0.114 * (p and 0xFF)
            count++
        }
        return sum / count
    }

    private fun regionMeanLuma(frame: Frame, bounds: IntArray): Double {
        var sum = 0.0
        var count = 0
        for (y in bounds[1] until bounds[3]) {
            for (x in bounds[0] until bounds[2]) {
                val p = frame.argb[y * frame.width + x]
                sum += 0.299 * ((p shr 16) and 0xFF) + 0.587 * ((p shr 8) and 0xFF) + 0.114 * (p and 0xFF)
                count++
            }
        }
        return sum / count
    }

    /** Fixture sanity: the object is clearly distinct from the background yet far from clip. */
    @Test
    fun fixtureObjectContrastIsWalkingSubjectScale() {
        val clean = SyntheticScenes.nightMotionClean()
        val objMean = regionMeanLuma(clean, SyntheticScenes.nightMotionObjectBounds(0))
        assertTrue("clean object mean $objMean should sit near 70", objMean in 60.0..80.0)
        val bgMean = maskMeanLuma(SyntheticScenes.nightClean(), sweepMask(FRAMES))
        assertTrue("clean swept background mean $bgMean should stay deep-shadow", bgMean < 30.0)
    }

    /**
     * No ghost trail: the swept region (minus the reference footprint) must come out as
     * background. The in-test naive average shows what the trail would look like with the
     * motion machinery absent; the robust merge must land far below it, and the finished
     * output's sweep error must hold its measured ceiling. The residual-mean check
     * subtracts the static region's own finishing shift, so it isolates trail luminance
     * from the night finish's global brightening.
     */
    @Test
    fun ghostRejectionSuppressesTheSweepTrail() {
        val burst = SyntheticScenes.nightMotionBurst(SEED, FRAMES)
        val cleanBg = SyntheticScenes.nightClean()
        val sweep = sweepMask(FRAMES)

        val merged = NightPipeline.merge(burst).merged
        val finished = FinishingPipeline.apply(merged, FinishingParams.NIGHT)
        val naive = naiveAverage(burst)

        val naiveSweepMae = maskMae(naive, cleanBg, sweep)
        val robustSweepMae = maskMae(merged, cleanBg, sweep)
        val finishedSweepMae = maskMae(finished, cleanBg, sweep)

        assertTrue(
            "robust sweep MAE $robustSweepMae must stay materially below the naive-average " +
                "trail $naiveSweepMae (ratio <= $MAX_TRAIL_VS_NAIVE_RATIO)",
            robustSweepMae <= naiveSweepMae * MAX_TRAIL_VS_NAIVE_RATIO,
        )
        assertTrue(
            "finished sweep MAE $finishedSweepMae regressed above ceiling $MAX_SWEEP_MAE",
            finishedSweepMae <= MAX_SWEEP_MAE,
        )

        val static = staticMask(FRAMES)
        val cleanMotion = SyntheticScenes.nightMotionClean()
        val sweepShift = maskMeanLuma(finished, sweep) - maskMeanLuma(cleanBg, sweep)
        val staticShift = maskMeanLuma(finished, static) - maskMeanLuma(cleanMotion, static)
        assertTrue(
            "residual trail mean ${sweepShift - staticShift} (sweep shift $sweepShift minus " +
                "static shift $staticShift) must stay under $MAX_RESIDUAL_TRAIL_MEAN",
            sweepShift - staticShift <= MAX_RESIDUAL_TRAIL_MEAN,
        )
    }

    /**
     * Object integrity at the reference position: the frames showing background there
     * are rejected by the same ghost gate that suppresses the trail, so the reference's
     * subject survives nearly intact (measured finished mean ~66.7 vs ~68.7 clean).
     */
    @Test
    fun objectSurvivesAtItsReferencePosition() {
        val burst = SyntheticScenes.nightMotionBurst(SEED, FRAMES)
        val finished = NightPipeline.process(burst)
        val refBounds = SyntheticScenes.nightMotionObjectBounds(0)

        val objMean = regionMeanLuma(finished, refBounds)
        val naiveObjMean = regionMeanLuma(naiveAverage(burst), refBounds)

        assertTrue(
            "finished object mean $objMean regressed below floor $MIN_OBJECT_MEAN",
            objMean >= MIN_OBJECT_MEAN,
        )
        assertTrue(
            "finished object mean $objMean must clear the naive-average level $naiveObjMean " +
                "by at least $MIN_OBJECT_VS_NAIVE_MARGIN",
            objMean >= naiveObjMean + MIN_OBJECT_VS_NAIVE_MARGIN,
        )
    }

    /**
     * Motion weighting is wired and does not hurt: merging with k=0 must differ (the knob
     * does something) and the enabled path's static-region MAE must not exceed the
     * disabled path's beyond a small tolerance. Measured delta: +0.002 (3.3809 vs
     * 3.3789) -- near-neutral, as the class doc explains: the global frame weight
     * targets whole-frame motion; the per-pixel rejection gated above is the primary
     * defense on this fixture.
     */
    @Test
    fun motionWeightingHelpsOrAtWorstDoesNotHurt() {
        val burst = SyntheticScenes.nightMotionBurst(SEED, FRAMES)
        val cleanMotion = SyntheticScenes.nightMotionClean()
        val static = staticMask(FRAMES)

        val enabled = NightPipeline.merge(burst).merged
        val disabled = BurstMergePipeline.merge(
            burst,
            NightMergeParams.NIGHT.copy(motionWeightK = 0.0),
        ).merged

        assertTrue(
            "k=0 merge must not be bit-identical to k=1 (the weighting must be wired)",
            !enabled.argb.contentEquals(disabled.argb),
        )

        val enabledMae = maskMae(enabled, cleanMotion, static)
        val disabledMae = maskMae(disabled, cleanMotion, static)
        assertTrue(
            "static MAE with motion weighting ($enabledMae) must not exceed the k=0 MAE " +
                "($disabledMae) by more than $MOTION_WEIGHT_TOLERANCE (measured delta " +
                "${enabledMae - disabledMae})",
            enabledMae <= disabledMae + MOTION_WEIGHT_TOLERANCE,
        )
    }

    /**
     * Static-region floors, at the drift-only band since issue #130: the same mask on
     * a drift-only burst measures ~2.51 MAE and the subject-robust aligner recovers
     * essentially the same offsets here, so the walking subject no longer costs the
     * background anything. Gated at the measured baseline so the reclaimed band
     * cannot silently erode.
     */
    @Test
    fun staticRegionHoldsItsMeasuredQualityFloor() {
        val finished = NightPipeline.process(SyntheticScenes.nightMotionBurst(SEED, FRAMES))
        val cleanMotion = SyntheticScenes.nightMotionClean()
        val static = staticMask(FRAMES)

        val mae = maskMae(finished, cleanMotion, static)
        val psnr = maskPsnr(finished, cleanMotion, static)
        assertTrue("static MAE $mae regressed above ceiling $MAX_STATIC_MAE", mae <= MAX_STATIC_MAE)
        assertTrue("static PSNR $psnr regressed below floor $MIN_STATIC_PSNR", psnr >= MIN_STATIC_PSNR)
    }

    @Test
    fun nightMotionPipelineIsDeterministic() {
        val first = NightPipeline.process(SyntheticScenes.nightMotionBurst(SEED, FRAMES))
        val second = NightPipeline.process(SyntheticScenes.nightMotionBurst(SEED, FRAMES))
        assertEquals(first, second)
    }

    private companion object {
        const val SEED = 0xC0FFEEL
        const val FRAMES = NightPipeline.NIGHT_BURST_FRAME_COUNT

        // MEASURED BASELINES (seed 0xC0FFEE, re-baked 2026-07-23 after issue #130's
        // subject-robust alignment + dual-band ghost gate; every bound tightened or
        // kept, none loosened). Actuals:
        //   naive sweep MAE 17.620  robust merged sweep MAE 4.214 (ratio 0.239)
        //   finished sweep MAE 3.569  residual trail mean +1.45 (sweep +2.50, static +1.05)
        //   finished object mean 66.675 (clean 68.713, naive 30.588)
        //   motion weighting static MAE k=1 3.3809 vs k=0 3.3789 (delta +0.002)
        //   static finished MAE 2.5205  PSNR 37.913
        //   (drift-only burst, same static mask: MAE 2.515 -- the reclaimed band)
        // First baking, under the pre-#130 mis-lock: ratio 0.272, sweep 4.391, residual
        // +0.41, object 39.735, static 5.4105 / 30.382. The residual-trail ceiling is
        // KEPT at its original generous 1.5: the residual is no longer subject
        // luminance but the noise-lift of the sweep region averaging fewer honest
        // frames (its subject samples are rightly rejected), measured +1.45.
        // Ceilings = actual*1.02, floors = actual*0.98; difference-valued bounds generous.
        const val MAX_TRAIL_VS_NAIVE_RATIO = 0.25
        const val MAX_SWEEP_MAE = 3.64
        const val MAX_RESIDUAL_TRAIL_MEAN = 1.5
        const val MIN_OBJECT_MEAN = 65.3
        const val MIN_OBJECT_VS_NAIVE_MARGIN = 30.0
        const val MOTION_WEIGHT_TOLERANCE = 0.05
        const val MAX_STATIC_MAE = 2.571
        const val MIN_STATIC_PSNR = 37.15
    }
}
