package com.poc.camera.camera

import org.junit.Assert.assertEquals
import org.junit.Test

class CameraPermissionEvaluatorTest {

    @Test
    fun grantedTakesPriorityRegardlessOfOtherSignals() {
        val result = CameraPermissionEvaluator.evaluate(
            isGranted = true,
            hasRequestedBefore = true,
            shouldShowRationale = true,
        )

        assertEquals(CameraPermissionState.Granted, result)
    }

    @Test
    fun firstLaunchBeforeAnyRequestIsDenied() {
        val result = CameraPermissionEvaluator.evaluate(
            isGranted = false,
            hasRequestedBefore = false,
            shouldShowRationale = false,
        )

        assertEquals(CameraPermissionState.Denied, result)
    }

    @Test
    fun firstDenialWithRationaleIsDenied() {
        val result = CameraPermissionEvaluator.evaluate(
            isGranted = false,
            hasRequestedBefore = true,
            shouldShowRationale = true,
        )

        assertEquals(CameraPermissionState.Denied, result)
    }

    @Test
    fun denialWithoutRationaleAfterRequestIsPermanentlyDenied() {
        val result = CameraPermissionEvaluator.evaluate(
            isGranted = false,
            hasRequestedBefore = true,
            shouldShowRationale = false,
        )

        assertEquals(CameraPermissionState.PermanentlyDenied, result)
    }
}
