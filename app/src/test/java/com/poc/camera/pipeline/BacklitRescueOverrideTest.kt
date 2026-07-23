package com.poc.camera.pipeline

import com.poc.camera.pipeline.quality.SyntheticScenes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Proves the manual backlit-rescue override (issue #147):
 * [FinishingParams.backlitRescueDetectorGated] = false forces [BacklitRescue] to engage at
 * full strength regardless of what [BacklitDetector] reads, working around the real-world
 * gap issue #145 found -- a real backlit capture's subject shadows crush below
 * [BacklitDetector.SHADOW_FLOOR], so the histogram-only detector never fires and the rescue
 * stays inert on the exact photos it exists to rescue.
 *
 * "gradients" is used as the non-backlit control scene:
 * [com.poc.camera.pipeline.quality.BacklitRescueGoldenTest]'s own baked evidence (measured
 * 2026-07-22) documents the detector reading EXACTLY 0.0 on every
 * named [SyntheticScenes] scene, clean and merged, so this is a genuine quiet scene, not a
 * hand-picked edge case, and it carries real low-luma content (its radial/linear ramp
 * reaches down toward 0) for the rescue to visibly act on once forced.
 */
class BacklitRescueOverrideTest {

    private val scene = SyntheticScenes.clean("gradients")

    @Test
    fun detectorReadsQuietOnTheNonBacklitScene() {
        assertEquals(0.0, BacklitDetector.detect(scene).strength, 0.0)
    }

    @Test
    fun defaultGatedProfileIsABitExactNoOpOnANonBacklitScene() {
        // backlitRescueDetectorGated defaults true, matching every existing shipped profile.
        val rescued = FinishingPipeline.applyBacklitRescue(scene, FinishingParams.RENDITION)
        assertSame("gated rescue must be a bit-exact passthrough on a quiet scene", scene, rescued)
    }

    @Test
    fun overrideForcesEngagementOnTheSameNonBacklitScene() {
        val forced = FinishingParams.RENDITION.copy(backlitRescueDetectorGated = false)
        val rescued = FinishingPipeline.applyBacklitRescue(scene, forced)
        assertFalse(
            "override must actually change the output on a scene the detector reads as quiet",
            rescued.argb.contentEquals(scene.argb),
        )
    }

    @Test
    fun overrideDoesNothingWhenTheProfileCarriesNoRescueStrength() {
        // FinishingParams.DEFAULT (and NIGHT, derived from it) ship backlitRescue = 0.0, so
        // the master-strength early return in applyBacklitRescue runs before the gate is
        // even consulted -- forcing the gate off must not matter.
        val forcedDefault = FinishingParams.DEFAULT.copy(backlitRescueDetectorGated = false)
        val rescued = FinishingPipeline.applyBacklitRescue(scene, forcedDefault)
        assertSame("forcing the gate on a zero-strength profile must still no-op", scene, rescued)
    }

    @Test
    fun fullPipelineOnlyDiffersFromTheGatedRunWhenOverridden() {
        val gatedOut = FinishingPipeline.apply(scene, FinishingParams.RENDITION)
        val rescueOffOut = FinishingPipeline.apply(scene, FinishingParams.RENDITION.copy(backlitRescue = 0.0))
        assertTrue(
            "the default gated RENDITION run must equal a rescue-off run on a quiet scene",
            gatedOut.argb.contentEquals(rescueOffOut.argb),
        )

        val overriddenOut = FinishingPipeline.apply(
            scene,
            FinishingParams.RENDITION.copy(backlitRescueDetectorGated = false),
        )
        assertFalse(
            "the overridden run must diverge from the default gated run",
            overriddenOut.argb.contentEquals(gatedOut.argb),
        )
    }
}
