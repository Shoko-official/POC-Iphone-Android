package com.poc.camera.camera

/**
 * Tap-to-focus reticle state machine and tap-coordinate geometry, kept free of
 * Android/Compose classes so it can be unit tested without Robolectric. The UI layer
 * (CameraScreen) owns the [FocusReticleState] as Compose state, maps taps to
 * [FocusPoint]s in the preview's own pixel space, drives [FocusMeteringEvent]s from the
 * tap and the CameraX metering future's result, and schedules the timed
 * [FocusMeteringEvent.Settled] event that fades a resolved reticle back to idle.
 */

/** A tap position in the preview's local pixel space (same space the CameraX
 * MeteringPointFactory expects). */
data class FocusPoint(val x: Float, val y: Float)

sealed interface FocusReticleState {
    data object Idle : FocusReticleState
    data class Focusing(val point: FocusPoint, val requestId: Long) : FocusReticleState
    data class Focused(val point: FocusPoint, val requestId: Long) : FocusReticleState
    data class Failed(val point: FocusPoint, val requestId: Long) : FocusReticleState
}

sealed interface FocusMeteringEvent {
    /** A new tap always takes over the reticle, wherever the previous one was. */
    data class TapStarted(val point: FocusPoint, val requestId: Long) : FocusMeteringEvent

    /** Result of [androidx.camera.core.CameraControl.startFocusAndMetering] for [requestId]. */
    data class MeteringSucceeded(val requestId: Long) : FocusMeteringEvent
    data class MeteringFailed(val requestId: Long) : FocusMeteringEvent

    /** Fired once the resolved (Focused/Failed) reticle has shown long enough to fade out. */
    data class Settled(val requestId: Long) : FocusMeteringEvent
}

object FocusReticleReducer {

    /**
     * Pure transition function. Metering results and settle timers carry the
     * [FocusReticleEvent's][FocusMeteringEvent] requestId so a result or timeout that
     * belongs to a stale request (superseded by a later tap) is a no-op rather than
     * corrupting the reticle currently on screen.
     */
    fun reduce(state: FocusReticleState, event: FocusMeteringEvent): FocusReticleState = when (event) {
        is FocusMeteringEvent.TapStarted -> FocusReticleState.Focusing(event.point, event.requestId)

        is FocusMeteringEvent.MeteringSucceeded ->
            state.resolveIfFocusing(event.requestId) { point -> FocusReticleState.Focused(point, event.requestId) }

        is FocusMeteringEvent.MeteringFailed ->
            state.resolveIfFocusing(event.requestId) { point -> FocusReticleState.Failed(point, event.requestId) }

        is FocusMeteringEvent.Settled -> state.settleIfCurrent(event.requestId)
    }

    private inline fun FocusReticleState.resolveIfFocusing(
        requestId: Long,
        resolve: (FocusPoint) -> FocusReticleState,
    ): FocusReticleState = if (this is FocusReticleState.Focusing && this.requestId == requestId) {
        resolve(point)
    } else {
        this
    }

    private fun FocusReticleState.settleIfCurrent(requestId: Long): FocusReticleState = when {
        this is FocusReticleState.Focused && this.requestId == requestId -> FocusReticleState.Idle
        this is FocusReticleState.Failed && this.requestId == requestId -> FocusReticleState.Idle
        else -> this
    }
}

object FocusReticleGeometry {

    /**
     * Keeps a reticle centred on [x]/[y] fully inside [boundsWidth] x [boundsHeight] by
     * clamping its centre at least [halfSize] away from every edge, so a tap near the
     * viewfinder's border never renders the reticle half off-screen. When the bounds
     * are smaller than the reticle itself, the centre is pinned to the middle of that
     * axis rather than left unclamped.
     */
    fun clamp(x: Float, y: Float, boundsWidth: Float, boundsHeight: Float, halfSize: Float): FocusPoint {
        return FocusPoint(
            x = clampAxis(x, boundsWidth, halfSize),
            y = clampAxis(y, boundsHeight, halfSize),
        )
    }

    private fun clampAxis(value: Float, boundsExtent: Float, halfSize: Float): Float {
        if (boundsExtent <= 2f * halfSize) return boundsExtent / 2f
        return value.coerceIn(halfSize, boundsExtent - halfSize)
    }
}
