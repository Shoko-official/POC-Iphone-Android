package com.poc.camera.pipeline.quality

import com.poc.camera.pipeline.Frame
import com.poc.camera.pipeline.SubPixelSampler
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
        return burstOf(clean, baseSeed, count)
    }

    /**
     * A [count]-frame noisy burst of an arbitrary [clean] frame, using the same
     * per-frame seed derivation as [burst]. Lets the AWB gate build a burst from a
     * colour-cast ground truth without touching the named-scene set.
     */
    fun burstOf(clean: Frame, baseSeed: Long, count: Int): List<Frame> {
        require(count >= 1) { "count must be >= 1" }
        return (0 until count).map { i -> noisy(clean, baseSeed + i * SEED_STRIDE) }
    }

    /**
     * Simulates a scene lit by a coloured illuminant: multiplies each channel of
     * [frame] by the per-channel gains ([rGain], [gGain], [bGain]) and clamps to
     * [0, 255], leaving alpha opaque and the timestamp intact. Applied to a CLEAN
     * frame (before the sensor-noise model) it models a real illuminant cast, e.g. a
     * warm 1.25/1.0/0.8 tungsten cast or a cool 0.85/1.0/1.2 shade cast.
     */
    fun withColorCast(frame: Frame, rGain: Double, gGain: Double, bGain: Double): Frame {
        val src = frame.argb
        val out = IntArray(src.size)
        for (i in src.indices) {
            val pixel = src[i]
            val r = ((pixel shr 16) and 0xFF)
            val g = ((pixel shr 8) and 0xFF)
            val b = (pixel and 0xFF)
            val nr = (r * rGain).roundToInt().coerceIn(0, 255)
            val ng = (g * gGain).roundToInt().coerceIn(0, 255)
            val nb = (b * bGain).roundToInt().coerceIn(0, 255)
            out[i] = (0xFF shl 24) or (nr shl 16) or (ng shl 8) or nb
        }
        return Frame(frame.width, frame.height, out, frame.timestampMillis)
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

    // --- Night (high-gain low-light) scene ------------------------------------
    //
    // A dedicated dark scene for the night pipeline gate, kept OUT of [names] so the
    // existing single-EV golden report is unaffected. It is darker than "lowlight"
    // (mean luma in the low twenties) and carries a bright hard-edged light source
    // (a lamp), and it is captured with a heavier noise model and a slight per-frame
    // drift, simulating a long hand-held high-ISO capture.

    /** Read-noise sigma for the night model: 3x the standard [READ_NOISE]. */
    const val NIGHT_READ_NOISE = READ_NOISE * 3.0

    /** Shot-noise gain for the night model: 2x the standard [SHOT_GAIN]. */
    const val NIGHT_SHOT_GAIN = SHOT_GAIN * 2.0

    /** Frames in a night burst (a longer capture than the still default). */
    const val NIGHT_BURST_COUNT = 12

    /** Cumulative global drift (pixels) across a night burst, simulating hand shake. */
    const val NIGHT_DRIFT_PX = 3

    /** Half-side of the square lamp region, centred at ([LAMP_CX], [LAMP_CY]). */
    private const val LAMP_HALF = 9
    private const val LAMP_CX = 92
    private const val LAMP_CY = 30
    private const val LAMP_VALUE = 250

    /** The clean, noise-free night ground truth. */
    fun nightClean(): Frame = nightScene()

    /**
     * A [count]-frame night burst of [nightScene]: heavier ([NIGHT_READ_NOISE],
     * [NIGHT_SHOT_GAIN]) noise with a slight cumulative global drift up to [maxDriftPx]
     * across the burst (frame 0 is undrifted, so it is the merge reference). Per-frame
     * seeds derive from [baseSeed], so the burst is reproducible.
     */
    fun nightBurst(
        baseSeed: Long,
        count: Int = NIGHT_BURST_COUNT,
        maxDriftPx: Int = NIGHT_DRIFT_PX,
    ): List<Frame> {
        require(count >= 1) { "count must be >= 1" }
        val clean = nightScene()
        return (0 until count).map { i ->
            val (dx, dy) = driftAt(i, count, maxDriftPx)
            noisyShifted(clean, baseSeed + i * SEED_STRIDE, dx, dy, NIGHT_READ_NOISE, NIGHT_SHOT_GAIN)
        }
    }

    /**
     * A single night frame carrying LARGE artificial motion: [nightScene] shifted by
     * [shiftPx] in both axes (well beyond the merge's alignment range) then captured
     * under the night noise model with seed [seed]. Used by the robustness proof to
     * inject an un-mergeable outlier into an otherwise clean burst.
     */
    fun nightMotionOutlier(seed: Long, shiftPx: Int = 40): Frame =
        noisyShifted(nightScene(), seed, shiftPx, shiftPx, NIGHT_READ_NOISE, NIGHT_SHOT_GAIN)

    /** Per-frame integer drift for frame [i] of [count], reaching ~[maxDriftPx] by the last frame. */
    private fun driftAt(i: Int, count: Int, maxDriftPx: Int): Pair<Int, Int> {
        if (count <= 1 || maxDriftPx == 0) return 0 to 0
        val t = i.toDouble() / (count - 1)
        val dx = (t * maxDriftPx).roundToInt()
        // Slightly less vertical travel than horizontal, so the drift is not a pure diagonal.
        val dy = (t * maxDriftPx * 0.5).roundToInt()
        return dx to dy
    }

    /**
     * [clean] translated so that the aligner recovers offset ([dx], [dy]) -- i.e.
     * output pixel (x, y) samples clean (x - dx, y - dy) with edge clamping -- then
     * captured under the given noise model with seed [seed].
     */
    fun noisyShifted(
        clean: Frame,
        seed: Long,
        dx: Int,
        dy: Int,
        readNoise: Double,
        shotGain: Double,
    ): Frame = noisy(shift(clean, dx, dy), seed, readNoise, shotGain)

    /** Edge-clamped integer translation: out(x, y) = frame(x - dx, y - dy). */
    private fun shift(frame: Frame, dx: Int, dy: Int): Frame {
        if (dx == 0 && dy == 0) return frame
        val w = frame.width
        val h = frame.height
        val src = frame.argb
        val out = IntArray(src.size)
        for (y in 0 until h) {
            val sy = (y - dy).coerceIn(0, h - 1)
            for (x in 0 until w) {
                val sx = (x - dx).coerceIn(0, w - 1)
                out[y * w + x] = src[sy * w + sx]
            }
        }
        return Frame(w, h, out, frame.timestampMillis)
    }

    /**
     * The night ground truth: a coarse dark texture compressed into [8, 28] (so the
     * whole scene sits in the deep shadows) with a bright hard-edged [LAMP_VALUE] lamp
     * square laid on top (a light source, with a crisp boundary and no bloom halo).
     * The lamp lifts the overall mean luma into the low twenties.
     */
    private fun nightScene(): Frame {
        val canvas = texturedCanvas(seed = 0x9147L, cell = 16, low = 0, high = 255)
        val out = IntArray(canvas.size)
        for (i in canvas.indices) {
            val base = canvas[i] and 0xFF
            out[i] = gray(8 + base * 20 / 255)
        }
        for (y in LAMP_CY - LAMP_HALF..LAMP_CY + LAMP_HALF) {
            if (y < 0 || y >= SIZE) continue
            for (x in LAMP_CX - LAMP_HALF..LAMP_CX + LAMP_HALF) {
                if (x < 0 || x >= SIZE) continue
                out[y * SIZE + x] = gray(LAMP_VALUE)
            }
        }
        return frame(out)
    }

    /** Bounding box (x0, y0, x1, y1) of the lamp region, for the no-bloom golden proof. */
    fun nightLampBounds(): IntArray = intArrayOf(
        LAMP_CX - LAMP_HALF,
        LAMP_CY - LAMP_HALF,
        LAMP_CX + LAMP_HALF,
        LAMP_CY + LAMP_HALF,
    )

    // --- Super-resolution resolution chart ------------------------------------
    //
    // A synthetic resolution chart for the multi-frame super-resolution gate, kept OUT of
    // [names] so the existing golden report is unaffected. The chart is authored at the 2x
    // output grid ([SR_TRUTH_SIZE]); each burst frame is an aliased, sub-pixel-shifted
    // impulse sampling of it back down to input resolution ([SR_INPUT_SIZE]).
    //
    // It carries three layers:
    //  - a smooth coarse BASE (low frequency, survives input sampling) that gives every
    //    tile real gradient for sub-pixel alignment -- the flat high-frequency bands offer
    //    no alignment signal on their own, so without this the aligner could not recover
    //    the fractional shift that IS the super-resolution signal;
    //  - an ABOVE-input-Nyquist sinusoidal bar band a single frame cannot represent (it
    //    aliases to a lower frequency) but a phase-diverse burst reconstructs on the 2x
    //    grid;
    //  - a NEAR-input-Nyquist control band, resolvable by a single frame.
    //
    // Impulse (point) sampling is used deliberately rather than a box/area average: an area
    // pre-filter would attenuate the very super-Nyquist content the proof must recover, so
    // a box downsample of these bands would erase the signal instead of aliasing it.
    // Impulse sampling models an ideal small-aperture sensor and yields honest aliasing.

    /** Side of the 2x super-resolution ground-truth chart (the output-grid resolution). */
    const val SR_TRUTH_SIZE = 256

    /** Side of each super-resolution INPUT frame; SR doubles it back to [SR_TRUTH_SIZE]. */
    const val SR_INPUT_SIZE = SR_TRUTH_SIZE / 2

    /** Frames in a super-resolution burst. */
    const val SR_BURST_COUNT = 6

    /**
     * Period, in truth (2x-grid) pixels, of the ABOVE-input-Nyquist bar band. 8/3 px is
     * 0.375 cyc/truth-px = 0.75 cyc/input-px, above the 0.5 input Nyquist; it aliases to a
     * distinct lower frequency (0.25 cyc/input-px) rather than to DC, so it is genuinely
     * unresolvable by one frame yet does not collapse to a per-frame brightness offset.
     */
    const val SR_ABOVE_NYQUIST_PERIOD = 8.0 / 3.0

    /** Period, in truth pixels, of the NEAR-input-Nyquist control band (resolvable by one frame). */
    const val SR_NEAR_NYQUIST_PERIOD = 5.0

    private const val SR_BAR_AMPLITUDE = 34.0
    private const val SR_BASE_MID = 120.0
    private const val SR_ABOVE_BAND_Y0 = 96
    private const val SR_ABOVE_BAND_Y1 = 160
    private const val SR_NEAR_BAND_Y0 = 24
    private const val SR_NEAR_BAND_Y1 = 88

    // Light sensor noise for the SR burst: present so alignment, ghost gating and merge do
    // real work, but well below the bar amplitude so it does not swamp the recovered detail.
    private const val SR_READ_NOISE = 2.5
    private const val SR_SHOT_GAIN = 0.08

    // Ghost-test bright block: a solid square that sits at "home" in the reference frame and
    // jumps to a disjoint "moved" region in every other frame (object motion, not global
    // shift), so the SR ghost gate must keep it out of the moved region.
    private const val SR_GHOST_BLOCK = 24
    private const val SR_GHOST_BLOCK_VALUE = 235

    // Per-frame sub-pixel phases in TRUTH pixels. Frame 0 is (0,0) = the reference. The x
    // phases span even and odd truth columns ({0,1}), so the burst populates both the even
    // and odd output texels of the vertical bar bands; the half-pixel members add finer
    // sub-pixel diversity. y phases are small (the bars are vertical) but non-zero so 2D
    // alignment is exercised.
    private val SR_PHASES_TRUTH: List<Pair<Double, Double>> = listOf(
        0.0 to 0.0,
        1.0 to 0.0,
        0.0 to 1.0,
        1.0 to 1.0,
        0.5 to 0.5,
        1.5 to 0.5,
    )

    /** Measurement window (x0, y0, x1, y1) of the above-Nyquist band, in 2x output coords. */
    fun srAboveNyquistBandBounds(): IntArray =
        intArrayOf(32, SR_ABOVE_BAND_Y0, SR_TRUTH_SIZE - 32, SR_ABOVE_BAND_Y1)

    /** Measurement window (x0, y0, x1, y1) of the near-Nyquist band, in 2x output coords. */
    fun srNearNyquistBandBounds(): IntArray =
        intArrayOf(32, SR_NEAR_BAND_Y0, SR_TRUTH_SIZE - 32, SR_NEAR_BAND_Y1)

    /** The 2x super-resolution ground truth: coarse base plus the two sinusoidal bar bands. */
    fun resolutionChartTruth(): Frame {
        val n = SR_TRUTH_SIZE
        val out = IntArray(n * n)
        for (y in 0 until n) {
            val row = y * n
            for (x in 0 until n) {
                var v = srBase(x, y)
                if (y in SR_ABOVE_BAND_Y0 until SR_ABOVE_BAND_Y1) {
                    v += SR_BAR_AMPLITUDE * cos(2.0 * PI * x / SR_ABOVE_NYQUIST_PERIOD)
                } else if (y in SR_NEAR_BAND_Y0 until SR_NEAR_BAND_Y1) {
                    v += SR_BAR_AMPLITUDE * cos(2.0 * PI * x / SR_NEAR_NYQUIST_PERIOD)
                }
                out[row + x] = gray(v.roundToInt().coerceIn(0, 255))
            }
        }
        return Frame(n, n, out, timestampMillis = 0L)
    }

    /** Smooth, gradient-rich, band-limited base so no alignment tile is flat. */
    private fun srBase(x: Int, y: Int): Double {
        val sx = 34.0 * sin(2.0 * PI * x / 53.0)
        val sy = 20.0 * sin(2.0 * PI * y / 47.0 + 0.7)
        val ramp = 16.0 * (x + y).toDouble() / (2 * (SR_TRUTH_SIZE - 1))
        return SR_BASE_MID + sx + sy + ramp
    }

    /**
     * A [count]-frame super-resolution burst: each frame is [resolutionChartTruth] impulse
     * sampled at a distinct sub-pixel phase ([SR_PHASES_TRUTH]) back to input resolution,
     * then captured under the light SR sensor-noise model. Frame 0 (phase (0,0)) is the
     * reference. Per-frame seeds derive from [baseSeed] so the burst is reproducible.
     */
    fun resolutionChartBurst(baseSeed: Long, count: Int = SR_BURST_COUNT): List<Frame> {
        require(count >= 1) { "count must be >= 1" }
        val truth = resolutionChartTruth()
        return (0 until count).map { k ->
            val (dxTruth, dyTruth) = SR_PHASES_TRUTH[k % SR_PHASES_TRUTH.size]
            val input = srDownsampleWithPhase(truth, dxTruth, dyTruth)
            noisy(input, baseSeed + k * SEED_STRIDE, SR_READ_NOISE, SR_SHOT_GAIN)
        }
    }

    /** Impulse (bilinear) sampling of [truth] at stride 2 with a truth-pixel phase offset. */
    private fun srDownsampleWithPhase(truth: Frame, dxTruth: Double, dyTruth: Double): Frame {
        val outW = truth.width / 2
        val outH = truth.height / 2
        val out = IntArray(outW * outH)
        val scratch = DoubleArray(3)
        for (yy in 0 until outH) {
            val row = yy * outW
            for (xx in 0 until outW) {
                SubPixelSampler.sampleRgb(truth, 2.0 * xx + dxTruth, 2.0 * yy + dyTruth, scratch)
                out[row + xx] = gray(scratch[0].roundToInt().coerceIn(0, 255))
            }
        }
        return Frame(outW, outH, out, timestampMillis = 0L)
    }

    /** Home block bounds (x0, y0, x1, y1) in INPUT coords: where the reference's block sits. */
    fun srGhostBlockHomeBounds(): IntArray =
        intArrayOf(18, 52, 18 + SR_GHOST_BLOCK, 52 + SR_GHOST_BLOCK)

    /** Moved block bounds (x0, y0, x1, y1) in INPUT coords: where non-reference frames' block sits. */
    fun srGhostBlockMovedBounds(): IntArray =
        intArrayOf(86, 52, 86 + SR_GHOST_BLOCK, 52 + SR_GHOST_BLOCK)

    /**
     * A [count]-frame burst over a static, alignable background with a bright block that is
     * at [srGhostBlockHomeBounds] in the reference (frame 0) and at [srGhostBlockMovedBounds]
     * in every other frame. The global motion is zero (the background is static), so the
     * block is pure local motion the SR ghost gate must reject from the moved region.
     */
    fun resolutionChartMovedObjectBurst(baseSeed: Long, count: Int = SR_BURST_COUNT): List<Frame> {
        require(count >= 1) { "count must be >= 1" }
        val n = SR_INPUT_SIZE
        val background = texturedCanvas(seed = 0x5111L, cell = 20, low = 70, high = 150)
        val home = srGhostBlockHomeBounds()
        val moved = srGhostBlockMovedBounds()
        return (0 until count).map { k ->
            val out = IntArray(n * n) { gray(background[it]) }
            drawBlock(out, n, if (k == 0) home else moved)
            noisy(Frame(n, n, out, timestampMillis = 0L), baseSeed + k * SEED_STRIDE, SR_READ_NOISE, SR_SHOT_GAIN)
        }
    }

    private fun drawBlock(out: IntArray, n: Int, bounds: IntArray) {
        for (y in bounds[1] until bounds[3]) {
            if (y < 0 || y >= n) continue
            for (x in bounds[0] until bounds[2]) {
                if (x < 0 || x >= n) continue
                out[y * n + x] = gray(SR_GHOST_BLOCK_VALUE)
            }
        }
    }

    // --- Skin-tone chart (skin-protection gate) -------------------------------
    //
    // A dedicated COLOUR fixture for the skin-protection gate (SkinProtectionGoldenTest),
    // kept OUT of [names] so the existing golden report is unaffected. It carries, as
    // solid patches and two textured regions:
    //  - SIX skin patches spanning the Fitzpatrick I-VI range (light -> deep). Their RGB
    //    values are chosen to share the SAME reddish-orange skin hue (R > G > B, roughly
    //    constant channel ratios) while DECREASING in luminance, which is exactly how real
    //    human skin varies across the range: tone is a luminance axis, not a hue axis. This
    //    is the fairness property the mask must honour -- a chroma-cluster prior fires on all
    //    six because they share chroma, and the gate proves mean mask > 0.5 on every one,
    //    INCLUDING the deep tone. Values are representative sRGB skin reflectances, not tied
    //    to one product's calibration; they are documented per patch below.
    //  - THREE non-skin patches that are classic false-positive risks or nearby hues:
    //    foliage green, sky blue and a saturated fabric red (saturated red is the textbook
    //    skin false positive) -- the gate proves mean mask < 0.15 on each, i.e. selectivity.
    //  - a NEUTRAL grey control patch (mask must be ~0, no chroma).
    //  - a TEXTURED SKIN region (medium-skin base + fine luma detail) for the sharpening
    //    test: protection must materially reduce detail amplification here.
    //  - a TEXTURED NON-SKIN region (grey base + the same fine detail) proving the sharpen
    //    reduction is SELECTIVE: amplification there is essentially unchanged.

    /** One labelled region of the skin chart. [skin]/[textured] drive the gate's assertions. */
    data class SkinChartRegion(
        val name: String,
        val rgb: Int,
        val x0: Int,
        val y0: Int,
        val x1: Int,
        val y1: Int,
        val skin: Boolean,
        val textured: Boolean,
    )

    /** Fine-detail relative amplitude for the textured skin-chart regions (hue-preserving). */
    private const val SKIN_TEX_AMPLITUDE = 0.18

    /**
     * The labelled regions of [skinChartClean] over the [SIZE]x[SIZE] frame. Six Fitzpatrick
     * skin tones, three non-skin false-positive risks, a neutral control and the two textured
     * regions. Per-patch RGB reasoning (all keep R > G > B skin hue for the skin tones):
     *  - light   (247,214,185): Fitzpatrick I/II, very fair (kept off the 255 clip so its
     *    hue is not distorted by a channel pinned at white).
     *  - fair    (241,194,167): Fitzpatrick II/III.
     *  - medium  (222,168,128): Fitzpatrick III/IV, mid tone (also the textured-skin base).
     *  - olive   (186,129, 92): Fitzpatrick IV, tanned/olive.
     *  - brown   (135, 90, 62): Fitzpatrick V, brown.
     *  - deep    ( 88, 58, 42): Fitzpatrick VI, deep -- same hue as light, far lower luma.
     */
    val skinChartRegions: List<SkinChartRegion> = listOf(
        SkinChartRegion("light", rgb(247, 214, 185), 0, 0, 32, 32, skin = true, textured = false),
        SkinChartRegion("fair", rgb(241, 194, 167), 32, 0, 64, 32, skin = true, textured = false),
        SkinChartRegion("medium", rgb(222, 168, 128), 64, 0, 96, 32, skin = true, textured = false),
        SkinChartRegion("olive", rgb(186, 129, 92), 96, 0, 128, 32, skin = true, textured = false),
        SkinChartRegion("brown", rgb(135, 90, 62), 0, 32, 32, 64, skin = true, textured = false),
        SkinChartRegion("deep", rgb(88, 58, 42), 32, 32, 64, 64, skin = true, textured = false),
        SkinChartRegion("foliage", rgb(60, 110, 45), 64, 32, 96, 64, skin = false, textured = false),
        SkinChartRegion("sky", rgb(110, 155, 210), 96, 32, 128, 64, skin = false, textured = false),
        SkinChartRegion("fabric", rgb(200, 30, 40), 0, 64, 32, 96, skin = false, textured = false),
        SkinChartRegion("neutral", rgb(128, 128, 128), 32, 64, 64, 96, skin = false, textured = false),
        // Textured skin (medium base) and textured non-skin (grey base), both 64px wide.
        SkinChartRegion("texskin", rgb(222, 168, 128), 64, 64, 128, 128, skin = true, textured = true),
        SkinChartRegion("texnonskin", rgb(150, 150, 150), 0, 96, 64, 128, skin = false, textured = true),
    )

    /**
     * The clean, noise-free skin chart. Solid patches are filled flat; the two textured
     * regions carry a fine interpolated detail lattice applied as a HUE-PRESERVING luma
     * factor (all three channels scaled equally by `1 + SKIN_TEX_AMPLITUDE * t`, t in
     * [-1, 1]), so the sharpening test sees genuine detail to amplify without the texture
     * itself shifting hue.
     */
    fun skinChartClean(): Frame {
        val out = IntArray(SIZE * SIZE)
        val detail = texturedCanvas(seed = 0x5C1FL, cell = 3, low = 0, high = 255)
        for (region in skinChartRegions) {
            val br = (region.rgb shr 16) and 0xFF
            val bg = (region.rgb shr 8) and 0xFF
            val bb = region.rgb and 0xFF
            for (y in region.y0 until region.y1) {
                for (x in region.x0 until region.x1) {
                    val i = y * SIZE + x
                    if (region.textured) {
                        val t = (detail[i] / 255.0 - 0.5) * 2.0
                        val f = 1.0 + SKIN_TEX_AMPLITUDE * t
                        val r = (br * f).roundToInt().coerceIn(0, 255)
                        val g = (bg * f).roundToInt().coerceIn(0, 255)
                        val b = (bb * f).roundToInt().coerceIn(0, 255)
                        out[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                    } else {
                        out[i] = (0xFF shl 24) or region.rgb
                    }
                }
            }
        }
        return frame(out)
    }

    // --- Backlit portrait scene (backlit-rescue gate) -------------------------
    //
    // A dedicated scene for the backlit-rescue gate (BacklitRescueGoldenTest), kept OUT of
    // [names] so the existing golden report is unaffected. A dark, textured SUBJECT
    // rectangle (luma ~30-45, carrying a solid skin-toned patch) sits against a bright,
    // textured BACKGROUND (~185-215) separated by a HARD boundary. That is the canonical
    // backlit histogram -- a populated shadow mode (the subject), a populated highlight mode
    // (the background) and an empty midtone valley -- which the [com.poc.camera.pipeline.BacklitDetector]
    // must fire on and [com.poc.camera.pipeline.BacklitRescue] must lift. The subject stays
    // in the recoverable-shadow band (>= the detector's shadow floor), i.e. dark but not
    // crushed, exactly as a real backlit subject a couple of stops down.

    /** Subject rectangle (x0, y0, x1, y1), half-open, in frame coords. */
    private const val BACKLIT_SUBJECT_X0 = 28
    private const val BACKLIT_SUBJECT_Y0 = 24
    private const val BACKLIT_SUBJECT_X1 = 100
    private const val BACKLIT_SUBJECT_Y1 = 112

    /** Skin-toned patch (x0, y0, x1, y1), half-open, inside the subject. */
    private const val BACKLIT_SKIN_X0 = 46
    private const val BACKLIT_SKIN_Y0 = 40
    private const val BACKLIT_SKIN_X1 = 82
    private const val BACKLIT_SKIN_Y1 = 84

    private const val BACKLIT_SUBJECT_LUMA = 37
    private const val BACKLIT_BACKGROUND_LUMA = 200
    private const val BACKLIT_SUBJECT_TEX = 8
    private const val BACKLIT_BACKGROUND_TEX = 15

    /** Darkened skin tone (R > G > B, luma ~40): a backlit face patch, recoverable-shadow dark. */
    private val BACKLIT_SKIN_RGB = rgb(54, 36, 26)

    /** The clean, noise-free backlit portrait ground truth. */
    fun backlitPortraitClean(): Frame {
        val out = IntArray(SIZE * SIZE)
        val bgTex = texturedCanvas(seed = 0xB4C1L, cell = 10, low = 0, high = 255)
        val subTex = texturedCanvas(seed = 0x5B17L, cell = 6, low = 0, high = 255)
        for (y in 0 until SIZE) {
            for (x in 0 until SIZE) {
                val i = y * SIZE + x
                val inSubject = x in BACKLIT_SUBJECT_X0 until BACKLIT_SUBJECT_X1 &&
                    y in BACKLIT_SUBJECT_Y0 until BACKLIT_SUBJECT_Y1
                out[i] = if (inSubject) {
                    val inSkin = x in BACKLIT_SKIN_X0 until BACKLIT_SKIN_X1 &&
                        y in BACKLIT_SKIN_Y0 until BACKLIT_SKIN_Y1
                    if (inSkin) {
                        (0xFF shl 24) or BACKLIT_SKIN_RGB
                    } else {
                        val t = (subTex[i] / 255.0 - 0.5) * 2.0
                        gray((BACKLIT_SUBJECT_LUMA + BACKLIT_SUBJECT_TEX * t).roundToInt().coerceIn(0, 255))
                    }
                } else {
                    val t = (bgTex[i] / 255.0 - 0.5) * 2.0
                    gray((BACKLIT_BACKGROUND_LUMA + BACKLIT_BACKGROUND_TEX * t).roundToInt().coerceIn(0, 255))
                }
            }
        }
        return frame(out)
    }

    /** A [count]-frame noisy burst of [backlitPortraitClean], seeds derived from [baseSeed]. */
    fun backlitPortraitBurst(baseSeed: Long, count: Int): List<Frame> {
        require(count >= 1) { "count must be >= 1" }
        return burstOf(backlitPortraitClean(), baseSeed, count)
    }

    /** Subject-rectangle bounds (x0, y0, x1, y1), half-open, for the lift/halo proofs. */
    fun backlitSubjectBounds(): IntArray =
        intArrayOf(BACKLIT_SUBJECT_X0, BACKLIT_SUBJECT_Y0, BACKLIT_SUBJECT_X1, BACKLIT_SUBJECT_Y1)

    /** Skin-patch bounds (x0, y0, x1, y1), half-open, inside the subject. */
    fun backlitSkinBounds(): IntArray =
        intArrayOf(BACKLIT_SKIN_X0, BACKLIT_SKIN_Y0, BACKLIT_SKIN_X1, BACKLIT_SKIN_Y1)

    // --- Chroma roll-off scene (chroma-roll-off gate) -------------------------
    //
    // A dedicated COLOUR scene for the chroma roll-off gate (ChromaRollOffGoldenTest), kept
    // OUT of [names] so the existing golden report is unaffected. It reproduces the case the
    // roll-off was tuned for (issue #97): an isolated EXTREME-chroma region (saturated red
    // "lips") sitting inside a low-chroma skin-toned "face", surrounded by patches of NORMAL
    // saturation. The face and every normal patch have a chroma magnitude at or under the
    // roll-off knee (so the shoulder is a bit-exact identity there -- "does not touch normal
    // saturation"), while the lips sit far above it (so the shoulder compresses them). All
    // channel values are chosen to keep R > G > B (a coherent warm hue) so the hue-preservation
    // proof is meaningful.
    //
    //  - FACE   rgb(175,152,138): muted skin, chroma magnitude ~26 (< knee 30) -> untouched.
    //  - LIPS   rgb(190, 75, 82): saturated red, chroma magnitude ~85 (>> knee) -> compressed.
    //  - normal patches: teal/gold/rose, all chroma magnitude ~21-24 (< knee) -> untouched.

    /** Muted skin-tone "face" fill (R > G > B), chroma magnitude ~26, below the roll-off knee. */
    private val CHROMA_FACE_RGB = rgb(175, 152, 138)

    /** Saturated red "lips", chroma magnitude ~85 -- the isolated extreme-chroma region. */
    private val CHROMA_LIPS_RGB = rgb(190, 75, 82)

    private const val CHROMA_LIPS_X0 = 44
    private const val CHROMA_LIPS_Y0 = 80
    private const val CHROMA_LIPS_X1 = 84
    private const val CHROMA_LIPS_Y1 = 96

    /** One normal-saturation patch of the chroma-roll-off scene (all below the knee). */
    data class ChromaNormalPatch(val name: String, val rgb: Int, val x0: Int, val y0: Int, val x1: Int, val y1: Int)

    /** Three moderate (below-knee) colour patches proving normal saturation is left untouched. */
    val chromaNormalPatches: List<ChromaNormalPatch> = listOf(
        ChromaNormalPatch("teal", rgb(120, 150, 145), 8, 8, 32, 32),
        ChromaNormalPatch("gold", rgb(160, 150, 128), 40, 8, 64, 32),
        ChromaNormalPatch("rose", rgb(170, 140, 145), 72, 8, 96, 32),
    )

    /**
     * The clean chroma-roll-off scene: a muted skin "face" fill with three below-knee normal
     * patches across the top and a saturated-red "lips" band low-centre. Flat patches, so the
     * roll-off's per-region effect is measured without noise. Alpha opaque.
     */
    fun chromaRollOffClean(): Frame {
        val out = IntArray(SIZE * SIZE) { (0xFF shl 24) or CHROMA_FACE_RGB }
        for (patch in chromaNormalPatches) {
            fillRect(out, patch.x0, patch.y0, patch.x1, patch.y1, patch.rgb)
        }
        fillRect(out, CHROMA_LIPS_X0, CHROMA_LIPS_Y0, CHROMA_LIPS_X1, CHROMA_LIPS_Y1, CHROMA_LIPS_RGB)
        return frame(out)
    }

    /** Lips-patch bounds (x0, y0, x1, y1), half-open: the extreme-chroma region. */
    fun chromaLipsBounds(): IntArray =
        intArrayOf(CHROMA_LIPS_X0, CHROMA_LIPS_Y0, CHROMA_LIPS_X1, CHROMA_LIPS_Y1)

    /** A pure-face sample rectangle (x0, y0, x1, y1), clear of the normal patches and the lips. */
    fun chromaFaceBounds(): IntArray = intArrayOf(30, 40, 98, 72)

    private fun fillRect(out: IntArray, x0: Int, y0: Int, x1: Int, y1: Int, rgb: Int) {
        for (y in y0 until y1) {
            if (y < 0 || y >= SIZE) continue
            for (x in x0 until x1) {
                if (x < 0 || x >= SIZE) continue
                out[y * SIZE + x] = (0xFF shl 24) or rgb
            }
        }
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
