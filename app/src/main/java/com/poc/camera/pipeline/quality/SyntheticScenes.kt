package com.poc.camera.pipeline.quality

import com.poc.camera.pipeline.Frame
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Deterministic ground-truth scenes plus a signal-dependent sensor-noise model for
 * the quality harness. Every scene is [SIZE]x[SIZE] grayscale and all randomness
 * comes from a fixed-seed LCG (Numerical-Recipes constants, matching the existing
 * test fixtures), so reports are byte-for-byte reproducible. Pure Kotlin, no
 * Android dependencies.
 *
 * Each named scene targets a distinct failure mode:
 *  - "edges": hard steps and thin diagonal lines (ringing, mis-registration).
 *  - "texture": fine interpolated LCG detail (over-smoothing, detail loss).
 *  - "gradients": smooth linear and radial ramps (banding, posterisation).
 *  - "lowlight": a dark scene where shot noise dominates the shadows.
 *  - "highcontrast": deep shadows beside near-clipped highlights (clipping).
 */
object SyntheticScenes {

    /** Every scene is a small-but-meaningful square of this side length. */
    const val SIZE = 128

    /** Scene names in the deterministic order used by the quality report. */
    val names: List<String> = listOf("edges", "texture", "gradients", "lowlight", "highcontrast")

    // Signal-dependent Gaussian noise defaults: sigma(luma) = sqrt(read^2 + gain*luma),
    // i.e. a read-noise floor plus shot noise that grows with sqrt(signal). Tuned so
    // the burst merge has real work to do without erasing scene structure.
    private const val READ_NOISE = 6.0
    private const val SHOT_GAIN = 0.5

    /** The clean, noise-free ground truth for [name]. */
    fun clean(name: String): Frame = when (name) {
        "edges" -> edges()
        "texture" -> texture()
        "gradients" -> gradients()
        "lowlight" -> lowlight()
        "highcontrast" -> highContrast()
        else -> throw IllegalArgumentException("unknown scene: $name")
    }

    /**
     * A single noisy capture of [clean] under the default sensor-noise model,
     * seeded by [seed]. Alpha is forced opaque and the timestamp is preserved.
     */
    fun noisy(clean: Frame, seed: Long): Frame =
        noisy(clean, seed, READ_NOISE, SHOT_GAIN)

    /**
     * A single noisy capture of [clean] with an explicit noise model, seeded by
     * [seed]: per-channel additive Gaussian noise with sigma =
     * sqrt(readNoise^2 + shotGain * luma), so it scales with sqrt(signal).
     */
    fun noisy(clean: Frame, seed: Long, readNoise: Double, shotGain: Double): Frame {
        val rng = Lcg(seed)
        val src = clean.argb
        val out = IntArray(src.size)
        for (i in src.indices) {
            val pixel = src[i]
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF
            val luma = 0.299 * r + 0.587 * g + 0.114 * b
            val sigma = sqrt(readNoise * readNoise + shotGain * luma)
            val nr = (r + sigma * rng.nextGaussian()).roundToInt().coerceIn(0, 255)
            val ng = (g + sigma * rng.nextGaussian()).roundToInt().coerceIn(0, 255)
            val nb = (b + sigma * rng.nextGaussian()).roundToInt().coerceIn(0, 255)
            out[i] = (0xFF shl 24) or (nr shl 16) or (ng shl 8) or nb
        }
        return Frame(clean.width, clean.height, out, clean.timestampMillis)
    }

    /**
     * A [count]-frame noisy burst of scene [name]: independent noisy captures of
     * the same clean ground truth, with per-frame seeds derived from [baseSeed].
     */
    fun burst(name: String, baseSeed: Long, count: Int): List<Frame> {
        require(count >= 1) { "count must be >= 1" }
        val clean = clean(name)
        return (0 until count).map { i -> noisy(clean, baseSeed + i * SEED_STRIDE) }
    }

    private fun edges(): Frame {
        val out = IntArray(SIZE * SIZE)
        val half = SIZE / 2
        for (y in 0 until SIZE) {
            for (x in 0 until SIZE) {
                // Four flat quadrants separated by hard steps.
                var v = when {
                    x < half && y < half -> 40
                    x >= half && y < half -> 200
                    x < half -> 128
                    else -> 90
                }
                // Thin one-pixel lines at three angles laid over the steps.
                if ((x - y).mod(24) == 0) v = 230 // +45 degrees
                if ((x + y).mod(24) == 0) v = 20 // -45 degrees
                if ((x - 2 * y).mod(31) == 0) v = 255 // shallow angle
                out[y * SIZE + x] = gray(v)
            }
        }
        return frame(out)
    }

    private fun texture(): Frame =
        frame(texturedCanvas(seed = 0x7E57L, cell = 4, low = 0, high = 255))

    private fun gradients(): Frame {
        val out = IntArray(SIZE * SIZE)
        val cx = (SIZE - 1) / 2.0
        val cy = (SIZE - 1) / 2.0
        val maxR = sqrt(cx * cx + cy * cy)
        for (y in 0 until SIZE) {
            for (x in 0 until SIZE) {
                val linear = x.toDouble() / (SIZE - 1) * 255.0
                val r = sqrt((x - cx) * (x - cx) + (y - cy) * (y - cy)) / maxR
                val radial = (1.0 - r) * 255.0
                val v = (0.5 * linear + 0.5 * radial).roundToInt().coerceIn(0, 255)
                out[y * SIZE + x] = gray(v)
            }
        }
        return frame(out)
    }

    private fun lowlight(): Frame {
        // Coarse structure compressed into a dark [3, 51] range so the shadows,
        // not the signal, dominate once shot noise is added.
        val canvas = texturedCanvas(seed = 0x10E0L, cell = 16, low = 0, high = 255)
        val out = IntArray(canvas.size)
        for (i in canvas.indices) {
            val base = canvas[i] and 0xFF
            val v = 3 + base * 48 / 255
            out[i] = gray(v)
        }
        return frame(out)
    }

    private fun highContrast(): Frame {
        val out = IntArray(SIZE * SIZE)
        val half = SIZE / 2
        for (y in 0 until SIZE) {
            // Slight per-row modulation so windows carry structure, not flat columns.
            val jitter = (y % 8) - 4
            for (x in 0 until SIZE) {
                val v = if (x < half) {
                    // Deep-shadow ramp 0..25.
                    (x.toDouble() / (half - 1) * 25.0).roundToInt() + jitter
                } else {
                    // Near-clipped highlight ramp 230..255.
                    (230.0 + (x - half).toDouble() / (half - 1) * 25.0).roundToInt() + jitter
                }
                out[y * SIZE + x] = gray(v.coerceIn(0, 255))
            }
        }
        return frame(out)
    }

    /**
     * A [SIZE]x[SIZE] grid of a coarse random lattice bilinearly interpolated to
     * full resolution and remapped into [low, high]. Smoothness mirrors natural
     * image content (unlike per-pixel white noise) while giving unique structure.
     */
    private fun texturedCanvas(seed: Long, cell: Int, low: Int, high: Int): IntArray {
        val lcg = Lcg(seed)
        val gridWidth = SIZE / cell + 2
        val gridHeight = SIZE / cell + 2
        val grid = IntArray(gridWidth * gridHeight) { lcg.nextByte() }

        val out = IntArray(SIZE * SIZE)
        val span = high - low
        for (y in 0 until SIZE) {
            val gy = y / cell
            val fy = (y % cell).toDouble() / cell
            for (x in 0 until SIZE) {
                val gx = x / cell
                val fx = (x % cell).toDouble() / cell
                val v00 = grid[gy * gridWidth + gx]
                val v10 = grid[gy * gridWidth + gx + 1]
                val v01 = grid[(gy + 1) * gridWidth + gx]
                val v11 = grid[(gy + 1) * gridWidth + gx + 1]
                val top = v00 + (v10 - v00) * fx
                val bottom = v01 + (v11 - v01) * fx
                val unit = (top + (bottom - top) * fy) / 255.0
                out[y * SIZE + x] = (low + (unit * span)).roundToInt().coerceIn(0, 255)
            }
        }
        return out
    }

    private fun frame(gray: IntArray): Frame = Frame(SIZE, SIZE, gray, timestampMillis = 0L)

    private fun gray(value: Int): Int {
        val v = value and 0xFF
        return (0xFF shl 24) or (v shl 16) or (v shl 8) or v
    }

    // Golden-ratio odd stride keeps per-frame burst seeds far apart in LCG space.
    private const val SEED_STRIDE = 0x9E3779B1L

    /**
     * Numerical-Recipes LCG extended with a Box-Muller Gaussian generator. Returns
     * unsigned bytes for lattice generation and standard-normal doubles for noise.
     */
    private class Lcg(seed: Long) {
        private var state = seed and 0xFFFFFFFFL
        private var hasSpare = false
        private var spare = 0.0

        fun nextByte(): Int {
            state = (state * 1664525L + 1013904223L) and 0xFFFFFFFFL
            return ((state ushr 24) and 0xFFL).toInt()
        }

        /** Uniform double in [0, 1) from the top 24 bits of the next state. */
        private fun nextUnit(): Double {
            state = (state * 1664525L + 1013904223L) and 0xFFFFFFFFL
            return ((state ushr 8) and 0xFFFFFFL).toDouble() / 0x1000000L.toDouble()
        }

        /** Standard-normal sample via Box-Muller, caching the paired value. */
        fun nextGaussian(): Double {
            if (hasSpare) {
                hasSpare = false
                return spare
            }
            var u1 = nextUnit()
            val u2 = nextUnit()
            if (u1 < 1e-12) u1 = 1e-12 // guard ln(0)
            val magnitude = sqrt(-2.0 * ln(u1))
            val angle = 2.0 * PI * u2
            spare = magnitude * sin(angle)
            hasSpare = true
            return magnitude * cos(angle)
        }
    }
}
