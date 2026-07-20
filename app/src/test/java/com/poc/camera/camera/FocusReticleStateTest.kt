package com.poc.camera.camera

import org.junit.Assert.assertEquals
import org.junit.Test

class FocusReticleStateTest {

    private val pointA = FocusPoint(10f, 20f)
    private val pointB = FocusPoint(50f, 60f)

    @Test
    fun `tap from idle starts focusing at the tapped point`() {
        val next = FocusReticleReducer.reduce(
            FocusReticleState.Idle,
            FocusMeteringEvent.TapStarted(pointA, requestId = 1L),
        )

        assertEquals(FocusReticleState.Focusing(pointA, requestId = 1L), next)
    }

    @Test
    fun `metering success resolves the matching focusing request`() {
        val focusing = FocusReticleState.Focusing(pointA, requestId = 1L)

        val next = FocusReticleReducer.reduce(focusing, FocusMeteringEvent.MeteringSucceeded(requestId = 1L))

        assertEquals(FocusReticleState.Focused(pointA, requestId = 1L), next)
    }

    @Test
    fun `metering failure resolves the matching focusing request`() {
        val focusing = FocusReticleState.Focusing(pointA, requestId = 1L)

        val next = FocusReticleReducer.reduce(focusing, FocusMeteringEvent.MeteringFailed(requestId = 1L))

        assertEquals(FocusReticleState.Failed(pointA, requestId = 1L), next)
    }

    @Test
    fun `settle after focused returns to idle`() {
        val focused = FocusReticleState.Focused(pointA, requestId = 1L)

        val next = FocusReticleReducer.reduce(focused, FocusMeteringEvent.Settled(requestId = 1L))

        assertEquals(FocusReticleState.Idle, next)
    }

    @Test
    fun `settle after failed returns to idle`() {
        val failed = FocusReticleState.Failed(pointA, requestId = 1L)

        val next = FocusReticleReducer.reduce(failed, FocusMeteringEvent.Settled(requestId = 1L))

        assertEquals(FocusReticleState.Idle, next)
    }

    @Test
    fun `a second tap while focusing replaces the first request`() {
        val firstTap = FocusReticleReducer.reduce(
            FocusReticleState.Idle,
            FocusMeteringEvent.TapStarted(pointA, requestId = 1L),
        )

        val secondTap = FocusReticleReducer.reduce(
            firstTap,
            FocusMeteringEvent.TapStarted(pointB, requestId = 2L),
        )

        assertEquals(FocusReticleState.Focusing(pointB, requestId = 2L), secondTap)
    }

    @Test
    fun `a stale metering result for a superseded request is ignored`() {
        val supersededByRetap = FocusReticleState.Focusing(pointB, requestId = 2L)

        // The first request's future resolves after the second tap already took over.
        val next = FocusReticleReducer.reduce(
            supersededByRetap,
            FocusMeteringEvent.MeteringSucceeded(requestId = 1L),
        )

        assertEquals(supersededByRetap, next)
    }

    @Test
    fun `a stale metering failure for a superseded request is ignored`() {
        val supersededByRetap = FocusReticleState.Focusing(pointB, requestId = 2L)

        val next = FocusReticleReducer.reduce(
            supersededByRetap,
            FocusMeteringEvent.MeteringFailed(requestId = 1L),
        )

        assertEquals(supersededByRetap, next)
    }

    @Test
    fun `a stale settle for a superseded request is ignored`() {
        // A fresh tap arrived before the previous request's fade timer fired.
        val refocusing = FocusReticleState.Focusing(pointB, requestId = 2L)

        val next = FocusReticleReducer.reduce(refocusing, FocusMeteringEvent.Settled(requestId = 1L))

        assertEquals(refocusing, next)
    }

    @Test
    fun `metering result while idle is a no-op`() {
        val next = FocusReticleReducer.reduce(FocusReticleState.Idle, FocusMeteringEvent.MeteringSucceeded(requestId = 1L))

        assertEquals(FocusReticleState.Idle, next)
    }

    @Test
    fun `settle while idle is a no-op`() {
        val next = FocusReticleReducer.reduce(FocusReticleState.Idle, FocusMeteringEvent.Settled(requestId = 1L))

        assertEquals(FocusReticleState.Idle, next)
    }

    @Test
    fun `clamp keeps a centred tap unchanged`() {
        val point = FocusReticleGeometry.clamp(x = 100f, y = 100f, boundsWidth = 400f, boundsHeight = 800f, halfSize = 36f)

        assertEquals(FocusPoint(100f, 100f), point)
    }

    @Test
    fun `clamp pulls a near-edge tap inside the bounds`() {
        val point = FocusReticleGeometry.clamp(x = 5f, y = 795f, boundsWidth = 400f, boundsHeight = 800f, halfSize = 36f)

        assertEquals(FocusPoint(36f, 764f), point)
    }

    @Test
    fun `clamp pulls a tap beyond the bounds back to the edge`() {
        val point = FocusReticleGeometry.clamp(x = -50f, y = 900f, boundsWidth = 400f, boundsHeight = 800f, halfSize = 36f)

        assertEquals(FocusPoint(36f, 764f), point)
    }

    @Test
    fun `clamp centres on an axis smaller than the reticle`() {
        val point = FocusReticleGeometry.clamp(x = 10f, y = 10f, boundsWidth = 50f, boundsHeight = 800f, halfSize = 36f)

        assertEquals(FocusPoint(25f, 36f), point)
    }
}
