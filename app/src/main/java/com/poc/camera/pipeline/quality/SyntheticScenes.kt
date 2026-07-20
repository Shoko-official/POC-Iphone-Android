package com.poc.camera.pipeline.quality

import com.poc.camera.pipeline.Frame
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.pow
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
 *  - "colorchart": the only COLOUR scene -- saturated/pastel/skin/neutral patches and
 *    complementary colour edges, for honest chroma noise gating (see [colorChart]).
 */
object SyntheticScenes {

    /** Every scene is a small-but-meaningful square of this side length. */
    const val SIZE = 128

    /** Scene names in the deterministic order used by the quality report. */
    val names: List<String> = listOf("edges", "texture", "gradients", "lowlight", "highcontrast", "colorchart")

    // --- HDR (exposure-bracketing) scene -------------------------------------
    //
    // A single high-dynamic-range scene whose true radiance exceeds what an 8-bit
    // capture can hold in one exposure, used by the HDR fusion quality gate. It is
    // deliberately kept OUT of [names] so the existing single-EV golden report is
    // unaffected.

    /** Radiance ceiling of the HDR scene: four stops above an 8-bit white (4 * 255). */
    const val HDR_MAX_RADIANCE = 4 * 255

    /** Relative EVs of the simulated bracket, darkest (highlight-preserving) first. */
    val HDR_EVS: List<Double> = listOf(-2.0, 0.0, 2.0)

    /** Frames captured per EV in the simulated HDR burst. */
    const val HDR_FRAMES_PER_EV = 2

    // Reinhard tone-map key. The reference operator is Ld(L) = Ls / (1 + Ls) with
    // Ls = L / HDR_REINHARD_KEY, rescaled so the peak radiance maps to 255. A small
    // key relative to the radiance ceiling lifts the shadows strongly (matching what
    // exposure fusion recovers from the bright captures) while its concave shoulder
    // compresses the highlights, giving an achievable 8-bit rendering that shows
    // detail across the whole range. Tuned against the fusion pipeline.
    private const val HDR_REINHARD_KEY = 170.0

    // Peak-to-peak radiance texture (in radiance units) laid over the smooth ramp so
    // both shadows and highlights carry fine detail that single clipped/crushed
    // exposures destroy but fusion recovers.
    private const val HDR_TEXTURE_AMPLITUDE = 120.0

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
        "colorchart" -> colorChart()
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

    /** A bracketed HDR burst: [HDR_FRAMES_PER_EV] noisy captures at each of [HDR_EVS]. */
    data class HdrBurst(val frames: List<Frame>, val evs: List<Double>)

    /**
     * True scene radiance in [0, [HDR_MAX_RADIANCE]] per pixel: a smooth diagonal
     * ramp spanning the full range (so value deciles are clean) with an interpolated
     * texture laid on top (so shadows and highlights carry recoverable detail).
     */
    fun hdrRadiance(): DoubleArray {
        val texture = texturedCanvas(seed = 0x4DE7L, cell = 8, low = 0, high = 255)
        val out = DoubleArray(SIZE * SIZE)
        val maxIndex = 2 * (SIZE - 1)
        for (y in 0 until SIZE) {
            for (x in 0 until SIZE) {
                val i = y * SIZE + x
                val ramp = (x + y).toDouble() / maxIndex
                val base = ramp * HDR_MAX_RADIANCE
                val detail = (texture[i] / 255.0 - 0.5) * 2.0 * HDR_TEXTURE_AMPLITUDE
                out[i] = (base + detail).coerceIn(0.0, HDR_MAX_RADIANCE.toDouble())
            }
        }
        return out
    }

    /**
     * The deterministic reference tone-mapping of [hdrRadiance]: a global Reinhard
     * operator Ld(L) = Ls / (1 + Ls), Ls = L / [HDR_REINHARD_KEY], rescaled so the
     * peak radiance lands on 255. This is the achievable 8-bit rendering the fused
     * result is scored against; fusion cannot exceed 8 bits, so scoring against the
     * raw radiance would be meaningless.
     */
    fun hdrToneMappedTruth(): Frame {
        val radiance = hdrRadiance()
        val ldMax = reinhard(HDR_MAX_RADIANCE.toDouble())
        val out = IntArray(radiance.size)
        for (i in radiance.indices) {
            val v = (255.0 * reinhard(radiance[i]) / ldMax).roundToInt().coerceIn(0, 255)
            out[i] = gray(v)
        }
        return frame(out)
    }

    /** Reinhard tone-mapped luminance (unscaled) for radiance [l]. */
    private fun reinhard(l: Double): Double {
        val ls = l / HDR_REINHARD_KEY
        return ls / (1.0 + ls)
    }

    /**
     * The clean (noise-free) capture of the HDR scene at relative exposure [evRel]:
     * v = clamp(L * 2^evRel, 0, 255) per pixel. A negative EV darkens and preserves
     * highlights; a positive EV brightens and clips them.
     */
    fun hdrCleanCaptureAtEv(evRel: Double): Frame {
        val radiance = hdrRadiance()
        val scale = 2.0.pow(evRel)
        val out = IntArray(radiance.size)
        for (i in radiance.indices) {
            val v = (radiance[i] * scale).roundToInt().coerceIn(0, 255)
            out[i] = gray(v)
        }
        return frame(out)
    }

    /**
     * A full simulated bracketed burst: [HDR_FRAMES_PER_EV] independent noisy
     * captures at each EV in [HDR_EVS] (reusing the shared [noisy] sensor model),
     * with the parallel per-frame EV list. Per-frame seeds are derived from
     * [baseSeed] so the burst is reproducible.
     */
    fun hdrBurst(baseSeed: Long): HdrBurst {
        val frames = ArrayList<Frame>(HDR_EVS.size * HDR_FRAMES_PER_EV)
        val evs = ArrayList<Double>(HDR_EVS.size * HDR_FRAMES_PER_EV)
        var slot = 0
        for (ev in HDR_EVS) {
            val clean = hdrCleanCaptureAtEv(ev)
            for (k in 0 until HDR_FRAMES_PER_EV) {
                frames.add(noisy(clean, baseSeed + slot * SEED_STRIDE))
                evs.add(ev)
                slot++
            }
        }
        return HdrBurst(frames, evs)
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

    private fun texture(): Frame {
        // Gray-pack the raw intensity lattice into opaque R=G=B pixels. Every other
        // scene routes its values through [gray]; texture historically passed the raw
        // canvas straight to [frame], leaving intensity in the blue channel only
        // (R=G=0), which violates this fixture's grayscale invariant and gives every
        // pixel a tiny Rec.601 luma (0.114*B). That was invisible to per-channel
        // finishing but breaks any luma-based op (e.g. [LocalToneMapper]); fixed and
        // re-baselined 2026-07-20.
        val canvas = texturedCanvas(seed = 0x7E57L, cell = 4, low = 0, high = 255)
        return frame(IntArray(canvas.size) { gray(canvas[it]) })
    }

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
     * The only COLOUR scene, for honest chroma noise gating. The default sensor model
     * draws noise per channel, so a merged burst of any scene carries residual chroma
     * speckle -- but only a saturated colour scene exercises the two properties the
     * chroma denoiser must trade off: flattening speckle inside a uniform saturated
     * patch WITHOUT desaturating it, and keeping a colour edge crisp.
     *
     * Layout: a 6x6 grid of 36 distinct ~21x21 RGB patches over the whole frame,
     * spanning the hue circle, deeper hues, pastels, skin tones, a neutral ramp and
     * darker saturated tones. Patches are large so their flat interiors (where chroma
     * speckle lives) dominate over the boundaries. Row 0 is six SATURATED patches
     * arranged so adjacent cells are complementary pairs (red|cyan, green|magenta,
     * blue|yellow), giving the colour edges -- with strong co-occurring luma steps --
     * that the sharpness test guards; the red|cyan boundary is the one it samples.
     */
    private fun colorChart(): Frame {
        val out = IntArray(SIZE * SIZE)
        for (y in 0 until SIZE) {
            val gy = (y * GRID / SIZE).coerceAtMost(GRID - 1)
            for (x in 0 until SIZE) {
                val gx = (x * GRID / SIZE).coerceAtMost(GRID - 1)
                out[y * SIZE + x] = (0xFF shl 24) or COLOR_PATCHES[gy * GRID + gx]
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

    /** Side of the [colorChart] patch grid (6x6 = 36 distinct RGB patches). */
    private const val GRID = 6

    private fun rgb(r: Int, g: Int, b: Int): Int = (r shl 16) or (g shl 8) or b

    /**
     * The 36 [colorChart] patches in row-major order: saturated hues, pastels, skin
     * tones and a neutral ramp, covering the hue circle. Packed 0xRRGGBB (alpha added
     * at draw time). Luma is deliberately laid out in a CHECKERBOARD by (row+col)
     * parity -- dark cells (~45-95) beside light cells (~185-225) -- so EVERY patch
     * boundary carries a strong luma step. The luma-guided [ChromaDenoiser] preserves
     * exactly those edges, so the chart flattens chroma speckle in patch interiors
     * without smearing colour across boundaries; a chart with equiluminant neighbours
     * would instead be smeared, since luma guidance cannot see a chroma-only edge.
     */
    private val COLOR_PATCHES: IntArray = intArrayOf(
        // Row 0 -- saturated hues (dark/light checkerboard by column parity).
        rgb(150, 20, 20), rgb(120, 210, 215), rgb(20, 110, 30),
        rgb(240, 190, 205), rgb(25, 35, 140), rgb(225, 215, 90),
        // Row 1 -- more hues + pastels.
        rgb(150, 200, 240), rgb(90, 25, 120), rgb(190, 225, 180),
        rgb(110, 70, 45), rgb(245, 205, 175), rgb(20, 90, 95),
        // Row 2 -- hues, pastels and skin (light).
        rgb(140, 25, 60), rgb(210, 195, 235), rgb(85, 80, 20),
        rgb(255, 220, 180), rgb(30, 60, 150), rgb(235, 230, 185),
        // Row 3 -- skin tones and pastels.
        rgb(245, 215, 195), rgb(30, 95, 55), rgb(195, 230, 235),
        rgb(130, 30, 110), rgb(225, 180, 140), rgb(60, 30, 120),
        // Row 4 -- deeper hues and pastels.
        rgb(150, 80, 20), rgb(170, 225, 150), rgb(20, 100, 110),
        rgb(245, 200, 210), rgb(100, 40, 150), rgb(195, 215, 240),
        // Row 5 -- neutral ramp (alternating dark/light grays keep the luma step).
        rgb(50, 50, 50), rgb(210, 210, 210), rgb(75, 75, 75),
        rgb(195, 195, 195), rgb(40, 40, 40), rgb(225, 225, 225),
    )

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
