package com.poc.camera.pipeline

import org.junit.Assert.assertEquals
import kotlin.math.roundToInt
import kotlin.math.sin
import org.junit.Test

class TileAlignerTest {

    private val size = 128
    private val tileSize = 32

    // A band-limited field with strong, near-isotropic gradient in both axes (about
    // one cycle per tile), so sampling the continuous function is an accurate
    // sub-pixel shift and every tile has enough structure in x and y for the MAD
    // minimum to localise well above the quantisation floor.
    private fun field(x: Double, y: Double): Double =
        128.0 + 40.0 * sin(0.20 * x) + 40.0 * sin(0.20 * y) + 25.0 * sin(0.11 * x + 0.17 * y)

    /** Continuous field sampled with a (dx, dy) sub-pixel shift into a luma plane. */
    private fun shiftedPlane(dx: Double, dy: Double): LumaPlane {
        val values = IntArray(size * size)
        for (y in 0 until size) {
            for (x in 0 until size) {
                values[y * size + x] = field(x - dx, y - dy).roundToInt().coerceIn(0, 255)
            }
        }
        return LumaPlane(size, size, values)
    }

    @Test
    fun recoversKnownFractionalOffsetOnInteriorTiles() {
        val trueDx = 0.4
        val trueDy = -0.3
        val reference = shiftedPlane(0.0, 0.0)
        val frame = shiftedPlane(trueDx, trueDy)

        // Dead zone disabled: this test measures raw sub-pixel accuracy, not the
        // golden-tuned tiny-shift suppression exercised elsewhere.
        val offsets = TileAligner(tileSize = tileSize, subPixelDeadZone = 0.0)
            .refine(reference, frame, globalDx = 0, globalDy = 0)

        assertEquals(size / tileSize, offsets.cols)
        assertEquals(size / tileSize, offsets.rows)

        // Interior tiles (not touching the border) have full overlap for the search.
        for (r in 1 until offsets.rows - 1) {
            for (c in 1 until offsets.cols - 1) {
                val recoveredDx = offsets.dx[r * offsets.cols + c]
                val recoveredDy = offsets.dy[r * offsets.cols + c]
                assertEquals("tile ($c,$r) dx", trueDx, recoveredDx, 0.25)
                assertEquals("tile ($c,$r) dy", trueDy, recoveredDy, 0.25)
            }
        }
    }

    @Test
    fun zeroShiftStaysWithinTheDeadZone() {
        val reference = shiftedPlane(0.0, 0.0)
        val frame = shiftedPlane(0.0, 0.0)

        val offsets = TileAligner(tileSize = tileSize).refine(reference, frame, globalDx = 0, globalDy = 0)

        // No motion: every recovered offset is exactly zero (integer best is 0 and
        // the tiny parabola fractions are snapped out by the dead zone).
        for (v in offsets.dx) assertEquals(0.0, v, 1e-9)
        for (v in offsets.dy) assertEquals(0.0, v, 1e-9)
    }

    @Test
    fun interpolatedFieldIsSeamFreeAndClampsPastBorders() {
        // A two-tile-wide grid with distinct corner offsets: interpolation must be
        // continuous inside and flat (clamped) beyond the outer tile centres.
        val offsets = TileAligner.TileOffsets(
            cols = 2,
            rows = 2,
            tileSize = tileSize,
            dx = doubleArrayOf(0.0, 2.0, 0.0, 2.0),
            dy = doubleArrayOf(0.0, 0.0, 4.0, 4.0),
        )
        val center0 = tileSize / 2
        val center1 = tileSize + tileSize / 2
        // At tile centres the field equals the stored offsets.
        assertEquals(0.0, offsets.offsetXAt(center0, center0), 1e-9)
        assertEquals(2.0, offsets.offsetXAt(center1, center0), 1e-9)
        assertEquals(4.0, offsets.offsetYAt(center0, center1), 1e-9)
        // Halfway between the two column centres, x offset is the mean (1.0).
        assertEquals(1.0, offsets.offsetXAt((center0 + center1) / 2, center0), 1e-9)
        // Past the border the field clamps to the nearest tile centre value.
        assertEquals(0.0, offsets.offsetXAt(0, 0), 1e-9)
        assertEquals(2.0, offsets.offsetXAt(size - 1, 0), 1e-9)
    }
}
