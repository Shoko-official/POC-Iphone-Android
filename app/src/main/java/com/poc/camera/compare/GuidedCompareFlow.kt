package com.poc.camera.compare

/**
 * Steps of the guided on-device comparison flow: capture a shot with this app, then
 * pick the matching reference shot from another phone, driving banners/prompts on
 * [com.poc.camera.camera.CameraScreen] and [CompareScreen] without any new navigation
 * destination.
 */
enum class GuidedCompareStep {
    /** No guided flow in progress; the screens show their normal, unguided UI. */
    Idle,

    /** Waiting for the user to capture a burst photo with this app (on Camera). */
    AwaitingCapture,

    /** Slot A is filled; waiting for the user to pick the reference photo (on Compare). */
    AwaitingReference,
}

/** Inputs that can move the guided flow from one [GuidedCompareStep] to the next. */
sealed interface GuidedCompareEvent {
    /** User starts (or restarts) the guided flow from Compare. */
    data object Start : GuidedCompareEvent

    /** The next burst finished processing and slot A (the processed result) is ready. */
    data object CaptureCompleted : GuidedCompareEvent

    /** The user picked a reference photo for slot B. */
    data object ReferencePicked : GuidedCompareEvent

    /** The user dismissed a guided banner, or navigated back out of the flow. */
    data object Cancelled : GuidedCompareEvent
}

/**
 * Pure reducer for the guided comparison flow. Free of Android/Compose dependencies so
 * every transition, including cancel and out-of-order events, is unit-testable.
 */
object GuidedCompareFlow {

    fun advance(step: GuidedCompareStep, event: GuidedCompareEvent): GuidedCompareStep = when (event) {
        GuidedCompareEvent.Start -> GuidedCompareStep.AwaitingCapture
        GuidedCompareEvent.CaptureCompleted -> if (step == GuidedCompareStep.AwaitingCapture) {
            GuidedCompareStep.AwaitingReference
        } else {
            step
        }
        GuidedCompareEvent.ReferencePicked -> if (step == GuidedCompareStep.AwaitingReference) {
            GuidedCompareStep.Idle
        } else {
            step
        }
        GuidedCompareEvent.Cancelled -> GuidedCompareStep.Idle
    }
}
