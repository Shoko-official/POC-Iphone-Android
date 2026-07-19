package com.poc.camera.pipeline

/**
 * Deterministic fixtures for pipeline tests. All randomness comes from a fixed-seed
 * LCG so runs are byte-for-byte reproducible across machines and JVMs.
 */
internal object SyntheticImages {

    /** Numerical-Recipes LCG; returns an unsigned byte value in 0..255. */
    class Lcg(seed: Long) {
        private var state = seed and 0xFFFFFFFFL

        fun nextByte(): Int {
            state = (state * 1664525L + 1013904223L) and 0xFFFFFFFFL
            return ((state ushr 24) and 0xFFL).toInt()
        }
    }

    private fun gray(value: Int): Int {
        val v = value and 0xFF
        return (0xFF shl 24) or (v shl 16) or (v shl 8) or v
    }

    /**
     * A [width]x[height] gray canvas of deterministic, spatially smooth texture:
     * a coarse random lattice bilinearly interpolated to full resolution. Smoothness
     * mirrors natural image content (unlike per-pixel white noise, which aliases
     * badly under pyramid downsampling) while still giving unique structure for
     * alignment to lock onto.
     */
    fun texturedCanvas(width: Int, height: Int, seed: Long): IntArray {
        val lcg = Lcg(seed)
        val cell = 12
        val gridWidth = width / cell + 2
        val gridHeight = height / cell + 2
        val grid = IntArray(gridWidth * gridHeight) { lcg.nextByte() }

        val out = IntArray(width * height)
        for (y in 0 until height) {
            val gy = y / cell
            val fy = (y % cell).toDouble() / cell
            for (x in 0 until width) {
                val gx = x / cell
                val fx = (x % cell).toDouble() / cell
                val v00 = grid[gy * gridWidth + gx]
                val v10 = grid[gy * gridWidth + gx + 1]
                val v01 = grid[(gy + 1) * gridWidth + gx]
                val v11 = grid[(gy + 1) * gridWidth + gx + 1]
                val top = v00 + (v10 - v00) * fx
                val bottom = v01 + (v11 - v01) * fx
                val value = (top + (bottom - top) * fy)
                out[y * width + x] = gray(value.toInt().coerceIn(0, 255))
            }
        }
        return out
    }

    /** Crops a [width]x[height] window out of [canvas] starting at (startX, startY). */
    fun crop(
        canvas: IntArray,
        canvasWidth: Int,
        startX: Int,
        startY: Int,
        width: Int,
        height: Int,
        timestampMillis: Long = 0L,
    ): Frame {
        val argb = IntArray(width * height)
        for (y in 0 until height) {
            val srcRow = (startY + y) * canvasWidth + startX
            val dstRow = y * width
            for (x in 0 until width) {
                argb[dstRow + x] = canvas[srcRow + x]
            }
        }
        return Frame(width, height, argb, timestampMillis)
    }

    /** A frame of independent pseudo-random gray noise (an unrelated image). */
    fun noiseFrame(width: Int, height: Int, seed: Long, timestampMillis: Long = 0L): Frame {
        val lcg = Lcg(seed)
        val argb = IntArray(width * height) { gray(lcg.nextByte()) }
        return Frame(width, height, argb, timestampMillis)
    }

    /**
     * A copy of a gray [base] canvas with deterministic additive noise in
     * [-amplitude, amplitude] applied identically to all three channels.
     */
    fun noisyVariant(
        base: IntArray,
        width: Int,
        height: Int,
        seed: Long,
        amplitude: Int,
        timestampMillis: Long = 0L,
    ): Frame {
        val lcg = Lcg(seed)
        val span = 2 * amplitude + 1
        val argb = IntArray(base.size) { index ->
            val delta = lcg.nextByte() % span - amplitude
            gray(((base[index] and 0xFF) + delta).coerceIn(0, 255))
        }
        return Frame(width, height, argb, timestampMillis)
    }

    /** Mean absolute per-channel difference between a frame and a gray base canvas. */
    fun meanAbsError(frame: Frame, base: IntArray): Double {
        var sum = 0L
        for (i in base.indices) {
            val baseValue = base[i] and 0xFF
            val pixel = frame.argb[i]
            sum += kotlin.math.abs(((pixel shr 16) and 0xFF) - baseValue)
            sum += kotlin.math.abs(((pixel shr 8) and 0xFF) - baseValue)
            sum += kotlin.math.abs((pixel and 0xFF) - baseValue)
        }
        return sum.toDouble() / (base.size * 3)
    }
}
