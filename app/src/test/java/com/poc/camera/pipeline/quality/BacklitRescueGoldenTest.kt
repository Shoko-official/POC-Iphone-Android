package com.poc.camera.pipeline.quality

import com.poc.camera.pipeline.BacklitDetector
import com.poc.camera.pipeline.BacklitRescue
import com.poc.camera.pipeline.BacklitRescueParams
import com.poc.camera.pipeline.BurstMergePipeline
import com.poc.camera.pipeline.FinishingParams
import com.poc.camera.pipeline.FinishingPipeline
import com.poc.camera.pipeline.Frame
import com.poc.camera.pipeline.PipelineParallel
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Objective golden gate for the backlit rescue (issue #96), productised from the validated
 * local pair-tuning. Every claim is measured on the synthetic "backlitportrait" scene (a
 * dark, textured subject with a skin patch against a bright textured background, hard
 * boundary) and on the existing named scenes, so the numbers are deterministic and
 * reproducible across machines.
 *
 * The suite proves:
 *  1. LIFT: the rescue lifts the subject region by at least [MIN_SUBJECT_LIFT]x while the
 *     background moves less than [MAX_BACKGROUND_SHIFT] codes.
 *  2. HALO: the boundary halo (ring-band deviation on both sides of the subject edge) stays
 *     under [MAX_HALO_DEVIATION] codes -- the guided base's edge preservation keeps the lift
 *     from ringing.
 *  3. DETECTOR SELECTIVITY (the false-positive gate): the detector fires on backlitportrait
 *     (strength > [FIRE_MIN]) and stays quiet (< [QUIET_MAX]) on the clean AND merged form
 *     of every existing named scene -- including the adversarial "highcontrast" (crushed
 *     shadows beside clipped highlights).
 *  4. INTEGRATION: the full RENDITION pipeline visibly engages the rescue on backlitportrait
 *     (subject markedly brighter than the same pipeline with the rescue off).
 *  5. DETERMINISM + parallel bit-identity.
 *
 * Baselines were measured 2026-07-22 and are documented at each assertion; bounds carry
 * margin so the test is a regression gate, not a brittle snapshot.
 */
class BacklitRescueGoldenTest {

    private val params = BacklitRescueParams.DEFAULT
    private val subject = SyntheticScenes.backlitSubjectBounds()

    // --- lift + background ---------------------------------------------------------

    @Test
    fun subjectLiftsWhileBackgroundHolds() {
        val merged = mergedPortrait()
        val strength = BacklitDetector.detect(merged).strength
        val rescued = BacklitRescue.apply(merged, params, strength)

        val subInBefore = meanLuma(merged, subjectInterior())
        val subInAfter = meanLuma(rescued, subjectInterior())
        val lift = subInAfter / subInBefore

        val bgBefore = backgroundInteriorMean(merged)
        val bgAfter = backgroundInteriorMean(rescued)
        val bgShift = kotlin.math.abs(bgAfter - bgBefore)

        println(
            "[backlit] engagement=${"%.3f".format(strength)} subject ${"%.1f".format(subInBefore)} -> " +
                "${"%.1f".format(subInAfter)} (x${"%.2f".format(lift)}) background ${"%.1f".format(bgBefore)} -> " +
                "${"%.1f".format(bgAfter)} (shift ${"%.2f".format(bgShift)})",
        )
        assertTrue("subject must lift by at least ${MIN_SUBJECT_LIFT}x (was x$lift)", lift >= MIN_SUBJECT_LIFT)
        assertTrue("background must hold within $MAX_BACKGROUND_SHIFT codes (was $bgShift)", bgShift <= MAX_BACKGROUND_SHIFT)
    }

    // --- boundary halo -------------------------------------------------------------

    @Test
    fun boundaryHaloStaysBounded() {
        val merged = mergedPortrait()
        val strength = BacklitDetector.detect(merged).strength
        val rescued = BacklitRescue.apply(merged, params, strength)

        // Subject side: the near-boundary ring must lift almost as much as the deep interior
        // (a large deficit would be a dark halo). Background side: the near-boundary ring must
        // stay near the background interior (a large surplus would be a bright halo).
        val subInterior = meanLuma(rescued, subjectInterior())
        val subRing = meanLumaWhere(rescued) { x, y -> inside(x, y) && ringInside(x, y) }
        val bgInterior = backgroundInteriorMean(rescued)
        val bgRing = meanLumaWhere(rescued) { x, y -> !inside(x, y) && distToSubject(x, y) in 3..12 }

        val subDeviation = kotlin.math.abs(subInterior - subRing)
        val bgDeviation = kotlin.math.abs(bgRing - bgInterior)
        val halo = maxOf(subDeviation, bgDeviation)
        println(
            "[backlit] halo subject interior=${"%.1f".format(subInterior)} ring=${"%.1f".format(subRing)} " +
                "(dev ${"%.2f".format(subDeviation)}) background interior=${"%.1f".format(bgInterior)} " +
                "ring=${"%.1f".format(bgRing)} (dev ${"%.2f".format(bgDeviation)})",
        )
        assertTrue("boundary halo must stay within $MAX_HALO_DEVIATION codes (was $halo)", halo <= MAX_HALO_DEVIATION)
    }

    // --- detector selectivity (the false-positive gate) ----------------------------

    @Test
    fun detectorFiresOnBacklitAndStaysQuietOnEveryExistingScene() {
        val portraitClean = BacklitDetector.detect(SyntheticScenes.backlitPortraitClean()).strength
        val portraitMerged = BacklitDetector.detect(mergedPortrait()).strength
        println("[backlit] detector backlitportrait clean=${"%.3f".format(portraitClean)} merged=${"%.3f".format(portraitMerged)}")
        assertTrue("detector must fire on clean backlitportrait (was $portraitClean)", portraitClean > FIRE_MIN)
        assertTrue("detector must fire on merged backlitportrait (was $portraitMerged)", portraitMerged > FIRE_MIN)

        for ((index, name) in SyntheticScenes.names.withIndex()) {
            val clean = SyntheticScenes.clean(name)
            val merged = BurstMergePipeline.merge(
                SyntheticScenes.burst(name, SEED + index * SCENE_SEED_STRIDE, PipelineQualityReport.DEFAULT_BURST_SIZE),
            ).merged
            val cleanStrength = BacklitDetector.detect(clean).strength
            val mergedStrength = BacklitDetector.detect(merged).strength
            println("[backlit] detector $name clean=${"%.3f".format(cleanStrength)} merged=${"%.3f".format(mergedStrength)}")
            assertTrue("$name (clean) must stay quiet (was $cleanStrength)", cleanStrength < QUIET_MAX)
            assertTrue("$name (merged) must stay quiet (was $mergedStrength)", mergedStrength < QUIET_MAX)
        }
    }

    // --- integration through the finishing pipeline --------------------------------

    @Test
    fun renditionPipelineEngagesTheRescue() {
        val merged = mergedPortrait()
        val withRescue = FinishingPipeline.apply(merged, FinishingParams.RENDITION)
        val withoutRescue = FinishingPipeline.apply(merged, FinishingParams.RENDITION.copy(backlitRescue = 0.0))

        val subWith = meanLuma(withRescue, subjectInterior())
        val subWithout = meanLuma(withoutRescue, subjectInterior())
        val bgWith = backgroundInteriorMean(withRescue)
        val bgWithout = backgroundInteriorMean(withoutRescue)
        println(
            "[backlit] RENDITION subject rescue-on=${"%.1f".format(subWith)} off=${"%.1f".format(subWithout)} " +
                "background on=${"%.1f".format(bgWith)} off=${"%.1f".format(bgWithout)}",
        )
        assertTrue(
            "RENDITION must lift the backlit subject vs rescue-off (on=$subWith off=$subWithout)",
            subWith >= subWithout + MIN_PIPELINE_SUBJECT_GAIN,
        )
        assertTrue(
            "RENDITION must leave the background essentially unchanged vs rescue-off",
            kotlin.math.abs(bgWith - bgWithout) <= MAX_BACKGROUND_SHIFT,
        )
    }

    // --- determinism + parallel bit-identity ---------------------------------------

    @Test
    fun rescueIsDeterministicAndParallelMatchesSerial() {
        val merged = mergedPortrait()
        val strength = BacklitDetector.detect(merged).strength

        val a = BacklitRescue.apply(merged, params, strength)
        val b = BacklitRescue.apply(merged, params, strength)
        assertTrue("rescue must be deterministic", a.argb.contentEquals(b.argb))

        val serial = BacklitRescue.apply(merged, params, strength, PipelineParallel.SERIAL_CHUNKS)
        val parallel = BacklitRescue.apply(merged, params, strength, PipelineParallel.parallelism)
        assertTrue("parallel rescue must be bit-identical to serial", serial.argb.contentEquals(parallel.argb))
    }

    // --- helpers -------------------------------------------------------------------

    private fun mergedPortrait(): Frame =
        BurstMergePipeline.merge(
            SyntheticScenes.backlitPortraitBurst(SEED, PipelineQualityReport.DEFAULT_BURST_SIZE),
        ).merged

    private fun inside(x: Int, y: Int): Boolean =
        x in subject[0] until subject[2] && y in subject[1] until subject[3]

    /** Chebyshev distance from an OUTSIDE point to the subject rectangle (0 when inside). */
    private fun distToSubject(x: Int, y: Int): Int {
        val dx = maxOf(subject[0] - x, x - (subject[2] - 1), 0)
        val dy = maxOf(subject[1] - y, y - (subject[3] - 1), 0)
        return maxOf(dx, dy)
    }

    /** True when an inside point sits within [RING] px of the subject boundary. */
    private fun ringInside(x: Int, y: Int): Boolean {
        val toEdge = minOf(x - subject[0], subject[2] - 1 - x, y - subject[1], subject[3] - 1 - y)
        return toEdge in 0 until RING
    }

    /** Subject-core bounds, inset by [INSET] so the interior clears the boundary halo band. */
    private fun subjectInterior(): IntArray = intArrayOf(
        subject[0] + INSET,
        subject[1] + INSET,
        subject[2] - INSET,
        subject[3] - INSET,
    )

    private fun meanLuma(frame: Frame, bounds: IntArray): Double {
        var sum = 0.0
        var n = 0
        for (y in bounds[1] until bounds[3]) {
            for (x in bounds[0] until bounds[2]) {
                sum += lumaOf(frame.argb[y * frame.width + x])
                n++
            }
        }
        return sum / n
    }

    private fun meanLumaWhere(frame: Frame, predicate: (Int, Int) -> Boolean): Double {
        var sum = 0.0
        var n = 0
        for (y in 0 until frame.height) {
            for (x in 0 until frame.width) {
                if (predicate(x, y)) {
                    sum += lumaOf(frame.argb[y * frame.width + x])
                    n++
                }
            }
        }
        return if (n == 0) 0.0 else sum / n
    }

    /** Mean luma of the background well clear of the boundary halo (Chebyshev distance >= 20). */
    private fun backgroundInteriorMean(frame: Frame): Double =
        meanLumaWhere(frame) { x, y -> !inside(x, y) && distToSubject(x, y) >= 20 }

    private fun lumaOf(px: Int): Double =
        0.299 * ((px shr 16) and 0xFF) + 0.587 * ((px shr 8) and 0xFF) + 0.114 * (px and 0xFF)

    private companion object {
        const val SEED = 0xBACL
        const val SCENE_SEED_STRIDE = 0x1000193L
        const val INSET = 14
        const val RING = 12

        // MEASURED 2026-07-22 (seed 0xBAC, 8-frame merge). Actuals -> baked bound (with margin):
        //   subject lift factor (pure rescue) : x2.94        -> floor 2.5
        //   background shift (pure rescue)    : 0.04 codes   -> ceiling 2.0
        //   boundary halo (max ring dev)      : 2.98 codes   -> ceiling 5.0
        //   detector strength backlitportrait : 1.000        -> floor 0.7
        //   detector strength every named scn : 0.000 (all)  -> ceiling 0.1
        //   RENDITION subject gain vs off     : +72.5 codes  -> floor 40.0
        // The detector reads EXACTLY 0 on every existing named scene (clean and merged),
        // including the adversarial "highcontrast", so RENDITION is a bit-exact passthrough
        // there and no existing golden floor moves.
        const val MIN_SUBJECT_LIFT = 2.5
        const val MAX_BACKGROUND_SHIFT = 2.0
        const val MAX_HALO_DEVIATION = 5.0
        const val FIRE_MIN = 0.7
        const val QUIET_MAX = 0.1
        const val MIN_PIPELINE_SUBJECT_GAIN = 40.0
    }
}
