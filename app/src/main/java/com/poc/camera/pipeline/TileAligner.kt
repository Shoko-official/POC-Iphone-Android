package com.poc.camera.pipeline

import kotlin.math.floor
import kotlin.math.min

/**
 * Refines a frame's global integer offset into a smooth field of per-tile
 * fractional offsets, so local motion and lens/rolling-shutter warp that a single
 * translation cannot capture are corrected before merging.
 *
 * The reference is divided into [tileSize]x[tileSize] tiles. For each tile the MAD
 * of the reference tile against the frame is searched over +/-[searchRadius] around
 * the global offset (offset convention of [FrameAligner]: reference pixel (x, y)
 * maps to frame pixel (x + dx, y + dy)). The best integer offset is then refined to
 * sub-pixel precision by fitting a [Parabola] through the MAD at that offset and its
 * +/-1 neighbours in x and y independently. Tiles with no valid overlap fall back to
 * the global offset with a zero fraction.
 *
 * Output offsets live at tile centres; [TileOffsets] bilinearly interpolates them
 * into a seam-free per-pixel field.
 *
 * Cost per frame is bounded by tiles * ((2*searchRadius+1)^2 + 4) MAD evaluations of
 * tileSize^2 pixels each, i.e. O(pixels * searchRadius^2) -- the same order as the
 * global aligner. With the defaults (32px tiles, radius 2) a 720p frame is ~920
 * tiles * ~29 evaluations * 1024px, comfortably interactive for a 6-frame burst.
 */
class TileAligner(
    private val tileSize: Int = 32,
    private val searchRadius: Int = 2,
    private val subPixelDeadZone: Double = 0.1,
) {
    init {
        require(tileSize >= 2) { "tileSize must be >= 2" }
        require(searchRadius >= 1) { "searchRadius must be >= 1" }
        require(subPixelDeadZone in 0.0..0.5) { "subPixelDeadZone must be in [0, 0.5]" }
    }

    /**
     * A grid of per-tile fractional offsets over one frame relative to the
     * reference. Offsets are anchored at tile centres and interpolated per pixel.
     */
    data class TileOffsets(
        val cols: Int,
        val rows: Int,
        val tileSize: Int,
        val dx: DoubleArray,
        val dy: DoubleArray,
    ) {
        /** Bilinearly interpolated x offset for reference pixel (px, py). */
        fun offsetXAt(px: Int, py: Int): Double = interpolate(dx, px.toDouble(), py.toDouble())

        /** Bilinearly interpolated y offset for reference pixel (px, py). */
        fun offsetYAt(px: Int, py: Int): Double = interpolate(dy, px.toDouble(), py.toDouble())

        /**
         * [offsetXAt] at a FRACTIONAL reference position, for scatter-side consumers
         * ([SuperResolution]) that must evaluate the field at a computed, non-integer
         * reference coordinate.
         */
        fun offsetXAt(px: Double, py: Double): Double = interpolate(dx, px, py)

        /** [offsetYAt] at a fractional reference position (see [offsetXAt]). */
        fun offsetYAt(px: Double, py: Double): Double = interpolate(dy, px, py)

        private fun interpolate(grid: DoubleArray, px: Double, py: Double): Double {
            // Tile c is centred at c*tileSize + tileSize/2, so this maps a pixel to
            // fractional tile-centre coordinates; corners are clamped for a smooth,
            // seam-free field that extends flat past the border tiles.
            val gx = (px - tileSize / 2.0) / tileSize
            val gy = (py - tileSize / 2.0) / tileSize
            val c0 = floor(gx).toInt()
            val r0 = floor(gy).toInt()
            val fx = (gx - c0).coerceIn(0.0, 1.0)
            val fy = (gy - r0).coerceIn(0.0, 1.0)
            val c0c = c0.coerceIn(0, cols - 1)
            val c1c = (c0 + 1).coerceIn(0, cols - 1)
            val r0c = r0.coerceIn(0, rows - 1)
            val r1c = (r0 + 1).coerceIn(0, rows - 1)
            val v00 = grid[r0c * cols + c0c]
            val v10 = grid[r0c * cols + c1c]
            val v01 = grid[r1c * cols + c0c]
            val v11 = grid[r1c * cols + c1c]
            val top = v00 + (v10 - v00) * fx
            val bottom = v01 + (v11 - v01) * fx
            return top + (bottom - top) * fy
        }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is TileOffsets) return false
            return cols == other.cols &&
                rows == other.rows &&
                tileSize == other.tileSize &&
                dx.contentEquals(other.dx) &&
                dy.contentEquals(other.dy)
        }

        override fun hashCode(): Int {
            var result = cols
            result = 31 * result + rows
            result = 31 * result + tileSize
            result = 31 * result + dx.contentHashCode()
            result = 31 * result + dy.contentHashCode()
            return result
        }
    }

    /**
     * Refines [globalDx]/[globalDy] into a per-tile fractional offset grid for
     * [frame] against [reference].
     */
    fun refine(
        reference: LumaPlane,
        frame: LumaPlane,
        globalDx: Int,
        globalDy: Int,
    ): TileOffsets {
        val cols = (reference.width + tileSize - 1) / tileSize
        val rows = (reference.height + tileSize - 1) / tileSize
        val dxOut = DoubleArray(cols * rows)
        val dyOut = DoubleArray(cols * rows)

        val window = 2 * searchRadius + 1
        val grid = DoubleArray(window * window)

        for (r in 0 until rows) {
            val y0 = r * tileSize
            val y1 = min(y0 + tileSize, reference.height)
            for (c in 0 until cols) {
                val x0 = c * tileSize
                val x1 = min(x0 + tileSize, reference.width)

                var bestMad = Double.MAX_VALUE
                var bestI = 0
                var bestJ = 0
                for (j in -searchRadius..searchRadius) {
                    for (i in -searchRadius..searchRadius) {
                        val mad = madOverTile(
                            reference, frame, x0, y0, x1, y1, globalDx + i, globalDy + j,
                        )
                        grid[(j + searchRadius) * window + (i + searchRadius)] = mad
                        if (!mad.isNaN() && mad < bestMad) {
                            bestMad = mad
                            bestI = i
                            bestJ = j
                        }
                    }
                }

                val index = r * cols + c
                if (bestMad == Double.MAX_VALUE) {
                    // No overlap for any candidate: keep the global offset.
                    dxOut[index] = globalDx.toDouble()
                    dyOut[index] = globalDy.toDouble()
                    continue
                }

                val center = bestMad
                val xMinus = madAt(grid, window, reference, frame, x0, y0, x1, y1, globalDx, globalDy, bestI - 1, bestJ)
                val xPlus = madAt(grid, window, reference, frame, x0, y0, x1, y1, globalDx, globalDy, bestI + 1, bestJ)
                val yMinus = madAt(grid, window, reference, frame, x0, y0, x1, y1, globalDx, globalDy, bestI, bestJ - 1)
                val yPlus = madAt(grid, window, reference, frame, x0, y0, x1, y1, globalDx, globalDy, bestI, bestJ + 1)

                val fracX = subPixel(xMinus, center, xPlus)
                val fracY = subPixel(yMinus, center, yPlus)

                dxOut[index] = (globalDx + bestI) + fracX
                dyOut[index] = (globalDy + bestJ) + fracY
            }
        }

        return TileOffsets(cols, rows, tileSize, dxOut, dyOut)
    }

    /**
     * Sub-pixel fraction from the MAD at the best offset ([center]) and its +/-1
     * neighbours. A missing neighbour (no overlap) yields 0.0. A fraction whose
     * magnitude falls inside [subPixelDeadZone] is snapped to 0.0: for such a tiny
     * shift the bilinear resampling blur costs more than the alignment gain, which
     * would otherwise soften static high-contrast edges.
     */
    private fun subPixel(neighbourMinus: Double, center: Double, neighbourPlus: Double): Double {
        if (neighbourMinus.isNaN() || neighbourPlus.isNaN()) return 0.0
        val frac = Parabola.vertex(neighbourMinus, center, neighbourPlus)
        return if (kotlin.math.abs(frac) < subPixelDeadZone) 0.0 else frac
    }

    /** MAD at grid offset (i, j) relative to global, reusing the cached window where possible. */
    @Suppress("LongParameterList")
    private fun madAt(
        grid: DoubleArray,
        window: Int,
        reference: LumaPlane,
        frame: LumaPlane,
        x0: Int,
        y0: Int,
        x1: Int,
        y1: Int,
        globalDx: Int,
        globalDy: Int,
        i: Int,
        j: Int,
    ): Double {
        if (i in -searchRadius..searchRadius && j in -searchRadius..searchRadius) {
            val cached = grid[(j + searchRadius) * window + (i + searchRadius)]
            if (!cached.isNaN()) return cached
        }
        return madOverTile(reference, frame, x0, y0, x1, y1, globalDx + i, globalDy + j)
    }

    /**
     * Mean absolute luma difference of the reference tile [x0, x1) x [y0, y1)
     * against [frame] shifted by (dx, dy). Returns NaN when the shift leaves no
     * valid overlap, so callers can fall back rather than trust an empty match.
     */
    @Suppress("LongParameterList")
    private fun madOverTile(
        reference: LumaPlane,
        frame: LumaPlane,
        x0: Int,
        y0: Int,
        x1: Int,
        y1: Int,
        dx: Int,
        dy: Int,
    ): Double {
        var sum = 0L
        var count = 0
        for (y in y0 until y1) {
            val fy = y + dy
            if (fy < 0 || fy >= frame.height) continue
            val refRow = y * reference.width
            val frameRow = fy * frame.width
            for (x in x0 until x1) {
                val fx = x + dx
                if (fx < 0 || fx >= frame.width) continue
                val diff = reference.values[refRow + x] - frame.values[frameRow + fx]
                sum += if (diff < 0) -diff else diff
                count++
            }
        }
        return if (count == 0) Double.NaN else sum.toDouble() / count
    }
}
