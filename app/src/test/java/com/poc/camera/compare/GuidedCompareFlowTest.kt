package com.poc.camera.compare

import org.junit.Assert.assertEquals
import org.junit.Test

class GuidedCompareFlowTest {

    @Test
    fun startMovesIdleToAwaitingCapture() {
        val next = GuidedCompareFlow.advance(GuidedCompareStep.Idle, GuidedCompareEvent.Start)

        assertEquals(GuidedCompareStep.AwaitingCapture, next)
    }

    @Test
    fun captureCompletedMovesAwaitingCaptureToAwaitingReference() {
        val next = GuidedCompareFlow.advance(GuidedCompareStep.AwaitingCapture, GuidedCompareEvent.CaptureCompleted)

        assertEquals(GuidedCompareStep.AwaitingReference, next)
    }

    @Test
    fun referencePickedMovesAwaitingReferenceToIdle() {
        val next = GuidedCompareFlow.advance(GuidedCompareStep.AwaitingReference, GuidedCompareEvent.ReferencePicked)

        assertEquals(GuidedCompareStep.Idle, next)
    }

    @Test
    fun fullHappyPathReachesIdleAgain() {
        var step = GuidedCompareStep.Idle
        step = GuidedCompareFlow.advance(step, GuidedCompareEvent.Start)
        step = GuidedCompareFlow.advance(step, GuidedCompareEvent.CaptureCompleted)
        step = GuidedCompareFlow.advance(step, GuidedCompareEvent.ReferencePicked)

        assertEquals(GuidedCompareStep.Idle, step)
    }

    @Test
    fun cancelledResetsToIdleFromAnyStep() {
        for (step in GuidedCompareStep.entries) {
            assertEquals(
                "Cancelled must reset $step to Idle",
                GuidedCompareStep.Idle,
                GuidedCompareFlow.advance(step, GuidedCompareEvent.Cancelled),
            )
        }
    }

    @Test
    fun captureCompletedIsIgnoredOutsideAwaitingCapture() {
        assertEquals(
            GuidedCompareStep.Idle,
            GuidedCompareFlow.advance(GuidedCompareStep.Idle, GuidedCompareEvent.CaptureCompleted),
        )
        assertEquals(
            GuidedCompareStep.AwaitingReference,
            GuidedCompareFlow.advance(GuidedCompareStep.AwaitingReference, GuidedCompareEvent.CaptureCompleted),
        )
    }

    @Test
    fun referencePickedIsIgnoredOutsideAwaitingReference() {
        assertEquals(
            GuidedCompareStep.Idle,
            GuidedCompareFlow.advance(GuidedCompareStep.Idle, GuidedCompareEvent.ReferencePicked),
        )
        assertEquals(
            GuidedCompareStep.AwaitingCapture,
            GuidedCompareFlow.advance(GuidedCompareStep.AwaitingCapture, GuidedCompareEvent.ReferencePicked),
        )
    }

    @Test
    fun startRestartsTheFlowFromAnyStep() {
        for (step in GuidedCompareStep.entries) {
            assertEquals(
                "Start must (re)start the flow from $step",
                GuidedCompareStep.AwaitingCapture,
                GuidedCompareFlow.advance(step, GuidedCompareEvent.Start),
            )
        }
    }
}
