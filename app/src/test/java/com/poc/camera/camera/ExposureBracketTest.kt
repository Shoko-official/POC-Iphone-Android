package com.poc.camera.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ExposureBracketTest {

    @Test
    fun indexForEvUsesNearestIndexForAThirdStopStep() {
        // step = 1/3 EV, a very common CameraX exposure-compensation step.
        assertEquals(6, ExposureBracket.indexForEv(2.0, 1, 3, -18, 18))
        assertEquals(-6, ExposureBracket.indexForEv(-2.0, 1, 3, -18, 18))
        assertEquals(0, ExposureBracket.indexForEv(0.0, 1, 3, -18, 18))
    }

    @Test
    fun indexForEvUsesNearestIndexForASixthStopStep() {
        assertEquals(12, ExposureBracket.indexForEv(2.0, 1, 6, -24, 24))
        assertEquals(-12, ExposureBracket.indexForEv(-2.0, 1, 6, -24, 24))
    }

    @Test
    fun indexForEvRoundsToTheNearestAchievableIndex() {
        // step = 1/2 EV: 2 EV -> index 4 exactly; 1.75 EV -> round(3.5) = 4.
        assertEquals(4, ExposureBracket.indexForEv(2.0, 1, 2, -12, 12))
        assertEquals(4, ExposureBracket.indexForEv(1.75, 1, 2, -12, 12))
    }

    @Test
    fun indexForEvClampsToTheSupportedRange() {
        // step = 1/3 EV but the range only reaches +/-1 EV (index +/-3).
        assertEquals(3, ExposureBracket.indexForEv(2.0, 1, 3, -3, 3))
        assertEquals(-3, ExposureBracket.indexForEv(-2.0, 1, 3, -3, 3))
    }

    @Test
    fun indexForEvReturnsZeroWhenCompensationIsUnsupported() {
        // A device with no exposure compensation reports step 0/1 and range 0..0.
        assertEquals(0, ExposureBracket.indexForEv(2.0, 0, 1, 0, 0))
        assertEquals(0, ExposureBracket.indexForEv(-2.0, 0, 1, 0, 0))
    }

    @Test
    fun indexForEvRejectsZeroDenominator() {
        assertThrows(IllegalArgumentException::class.java) {
            ExposureBracket.indexForEv(2.0, 1, 0, -18, 18)
        }
    }

    @Test
    fun indexForEvRejectsInvertedRange() {
        assertThrows(IllegalArgumentException::class.java) {
            ExposureBracket.indexForEv(0.0, 1, 3, 5, -5)
        }
    }

    @Test
    fun planReachesEveryTargetOnAWideRange() {
        val plan = ExposureBracket.plan(stepNumerator = 1, stepDenominator = 3, rangeLower = -18, rangeUpper = 18)

        assertEquals(listOf(-2.0, 0.0, 2.0), plan.map { it.targetEv })
        assertEquals(listOf(-6, 0, 6), plan.map { it.exposureIndex })
        assertEquals(listOf(-2.0, 0.0, 2.0), plan.map { it.actualEv })
        assertTrue(plan.all { it.reachable })
    }

    @Test
    fun planFlagsUnreachableTargetsOnANarrowRange() {
        // step 1/3 EV, range only +/-4 indices => +/-1.33 EV; +/-2 EV is unreachable.
        val plan = ExposureBracket.plan(stepNumerator = 1, stepDenominator = 3, rangeLower = -4, rangeUpper = 4)

        val minus2 = plan.first { it.targetEv == -2.0 }
        val zero = plan.first { it.targetEv == 0.0 }
        val plus2 = plan.first { it.targetEv == 2.0 }

        assertEquals(-4, minus2.exposureIndex)
        assertFalse(minus2.reachable)
        assertEquals(4, plus2.exposureIndex)
        assertFalse(plus2.reachable)
        assertEquals(0, zero.exposureIndex)
        assertTrue(zero.reachable)
    }

    @Test
    fun planCollapsesToNeutralWhenCompensationIsUnsupported() {
        val plan = ExposureBracket.plan(stepNumerator = 0, stepDenominator = 1, rangeLower = 0, rangeUpper = 0)

        assertTrue(plan.all { it.exposureIndex == 0 })
        assertTrue(plan.all { it.actualEv == 0.0 })
        // Only the neutral target is genuinely reachable.
        assertTrue(plan.first { it.targetEv == 0.0 }.reachable)
        assertFalse(plan.first { it.targetEv == 2.0 }.reachable)
    }
}
