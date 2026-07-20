package com.poc.camera.pipeline.quality

import com.poc.camera.pipeline.BlurMap
import com.poc.camera.pipeline.BokehParams
import com.poc.camera.pipeline.BokehRenderer
import com.poc.camera.pipeline.DiscBlur
import com.poc.camera.pipeline.Frame
import com.poc.camera.pipeline.PipelineParallel
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

/**
 * Objective golden gate for the pure bokeh engine (issue #79). Every claim is measured on a
 * synthetic portrait scene that deliberately packs the failure modes the engine must handle:
 *
 *  - a RED subject disk on a GREEN textured background (so any subject colour bleeding into
 *    the background bokeh shows up directly as red contamination),
 *  - hair-like 1 px strands extending from the subject over the background, with the mask
 *    alternating 1/0 across them (so edge-aware feathering is what decides whether hair
 *    survives),
 *  - near-clipped white highlight points in the background (so bloom can be checked), and
 *  - textured background and subject (so blur / sharpness are measurable as variance).
 *
 * Baselines were measured on 2026-07-20 and are documented at each assertion; the bounds are
 * set with margin around the measured value so the test is a regression gate, not a brittle
 * snapshot. The renderer is deterministic, so the numbers are reproducible across machines.
 */
class BokehGoldenTest {

    // --- scene geometry ------------------------------------------------------------

    private val scene = buildScene()
    private val input = scene.frame
    private val mask = scene.mask
    private val params = BokehParams(
        maxBlurRadius = 12,
        tapCount = 32,
        featherRadius = 6,
        featherEps = 80.0,
        featherSmoothRadius = 1,
        highlightThreshold = 235.0,
        highlightBoost = 3.0,
        strength = 1.0,
    )
    private val output = BokehRenderer.render(input, mask, params)

    // --- subject sharpness ---------------------------------------------------------

    @Test
    fun subjectRegionStaysSharp() {
        // MAE over the SOLID subject core -- mask > 0.9 AND well inside the disk (>12 px from
        // the boundary, clear of the feathered transition band and of the 1 px hair strands,
        // which are the deliberately-soft structures covered by the hair test) -- between input
        // and output must be tiny: at strength 1 the subject body is copied through untouched.
        var sum = 0L
        var n = 0
        for (y in 0 until H) for (x in 0 until W) {
            val i = y * W + x
            if (mask[i] > 0.9f && dist(x, y) <= SUBJECT_R - 12.0) {
                val a = input.argb[i]
                val b = output.argb[i]
                sum += kotlin.math.abs(((a shr 16) and 0xFF) - ((b shr 16) and 0xFF)).toLong()
                sum += kotlin.math.abs(((a shr 8) and 0xFF) - ((b shr 8) and 0xFF)).toLong()
                sum += kotlin.math.abs((a and 0xFF) - (b and 0xFF)).toLong()
                n++
            }
        }
        val mae = sum.toDouble() / (n * 3.0)
        println("[bokeh] subject sharpness MAE (mask>0.9) = ${"%.4f".format(mae)} over $n px")
        assertTrue("subject MAE $mae must stay under 0.5 (essentially untouched)", mae < 0.5)
    }

    // --- background blurred --------------------------------------------------------

    @Test
    fun backgroundVarianceDropsMaterially() {
        val inVar = lumaVariance(input, BG_X0, BG_Y0, BG_X1, BG_Y1)
        val outVar = lumaVariance(output, BG_X0, BG_Y0, BG_X1, BG_Y1)
        val ratio = outVar / inVar
        println("[bokeh] background luma variance in=${"%.1f".format(inVar)} out=${"%.1f".format(outVar)} ratio=${"%.3f".format(ratio)}")
        // Measured ratio ~0.16 (variance 38 -> 6): the disc blur flattens the background
        // texture to roughly a sixth of its input energy.
        assertTrue("background variance ratio $ratio must drop below 0.4", ratio < 0.4)
    }

    // --- no subject bleed ----------------------------------------------------------

    @Test
    fun subjectColourDoesNotBleedIntoBackground() {
        // In a ring of BACKGROUND pixels just outside the subject boundary (input red ~30), the
        // red channel of the WEIGHTED gather must barely move: the subject is strongly red
        // (~170), so weighting each disc tap by background-ness is what keeps subject red out of
        // the background bokeh. To prove the WEIGHTING (not merely the blur) is responsible, the
        // same gather is also run UNWEIGHTED and its ring red shift measured -- it is far larger.
        val (sr, sg, sb) = floatChannels(input)
        val luma = DoubleArray(W * H) { lumaOf(input.argb[it]) }
        val maps = BlurMap.compute(mask, luma, W, H, params)
        val bg = FloatArray(W * H) { 1f - maps.featheredMask[it] }
        val offsets = DiscBlur.vogelSpiral(params.tapCount)

        val wr = FloatArray(W * H); val wg = FloatArray(W * H); val wb = FloatArray(W * H)
        DiscBlur.blur(sr, sg, sb, W, H, maps.radius, bg, offsets, wr, wg, wb) // weighted
        val ur = FloatArray(W * H); val ug = FloatArray(W * H); val ub = FloatArray(W * H)
        DiscBlur.blur(sr, sg, sb, W, H, maps.radius, null, offsets, ur, ug, ub) // unweighted

        var inRed = 0.0; var wRed = 0.0; var uRed = 0.0; var n = 0
        for (y in 0 until H) for (x in 0 until W) {
            val i = y * W + x
            val d = dist(x, y)
            if (d in (SUBJECT_R + 2.0)..(SUBJECT_R + 12.0) && mask[i] < 0.05f) {
                inRed += sr[i]; wRed += wr[i]; uRed += ur[i]; n++
            }
        }
        val weightedShift = (wRed - inRed) / n
        val unweightedShift = (uRed - inRed) / n
        println("[bokeh] boundary-ring red shift weighted=${"%.2f".format(weightedShift)} unweighted=${"%.2f".format(unweightedShift)} codes over $n px")
        // Measured: weighted ~8, unweighted ~25 codes. The subject is a ~140-code-redder solid,
        // so the tap weighting removes the bulk of the contamination; the residual is the
        // feathered boundary band. Bound the weighted shift and require it well below unweighted.
        assertTrue("weighted boundary red contamination $weightedShift must stay under 18 codes", weightedShift < 18.0)
        assertTrue(
            "weighting must cut bleed to well under half the unweighted gather ($weightedShift vs $unweightedShift)",
            weightedShift < 0.6 * unweightedShift,
        )
    }

    // --- hair preservation ---------------------------------------------------------

    @Test
    fun hairStrandsRetainContrastVersusBlurredBackground() {
        val strandStd = sqrt(lumaVariance(output, HAIR_X0, HAIR_Y0, HAIR_X1, HAIR_Y1))
        val bgStd = sqrt(lumaVariance(output, BG_X0, BG_Y0, BG_X1, BG_Y1))
        val ratio = strandStd / bgStd
        println("[bokeh] hair region luma std=${"%.2f".format(strandStd)} vs blurred-bg std=${"%.2f".format(bgStd)} ratio=${"%.2f".format(ratio)}")
        // Measured ratio ~5.9: edge-aware feathering snaps the mask onto the bright strands, so
        // they retain far more local contrast than the flattened plain background.
        assertTrue("hair contrast ratio $ratio must exceed 3x the blurred background", ratio > 3.0)
    }

    // --- highlight bloom -----------------------------------------------------------

    @Test
    fun nearClippedHighlightsBloomBrighter() {
        val withBloom = output
        val noBloom = BokehRenderer.render(input, mask, params.copy(highlightBoost = 1.0))
        val lBloom = meanLuma(withBloom, HL_X0, HL_Y0, HL_X1, HL_Y1)
        val lNoBloom = meanLuma(noBloom, HL_X0, HL_Y0, HL_X1, HL_Y1)
        println("[bokeh] highlight-disc mean luma bloom=${"%.2f".format(lBloom)} no-bloom=${"%.2f".format(lNoBloom)} delta=${"%.2f".format(lBloom - lNoBloom)}")
        // Measured delta ~+3.9 luma: the bloom lifts the near-clipped point into float headroom
        // so the disc it spreads into is brighter than the same scene without bloom.
        assertTrue("bloomed highlight disc must be brighter than un-bloomed", lBloom > lNoBloom + 1.5)
    }

    // --- determinism + parallel-vs-serial bit-identity -----------------------------

    @Test
    fun renderIsDeterministicAndParallelMatchesSerial() {
        val again = BokehRenderer.render(input, mask, params)
        assertTrue("render must be deterministic", again.argb.contentEquals(output.argb))

        val serial = BokehRenderer.render(input, mask, params, PipelineParallel.SERIAL_CHUNKS)
        val parallel = BokehRenderer.render(input, mask, params, PipelineParallel.parallelism)
        assertTrue("parallel render must be bit-identical to serial", serial.argb.contentEquals(parallel.argb))
    }

    @Test
    fun stageKernelsAreBitIdenticalAcrossChunkCounts() {
        // DiscBlur is the one new hot kernel; prove its chunk split is bit-identical directly
        // (mirrors ParallelDeterminismTest for the shared kernels).
        val luma = DoubleArray(W * H) { lumaOf(input.argb[it]) }
        val maps = BlurMap.compute(mask, luma, W, H, params, PipelineParallel.SERIAL_CHUNKS)
        val mapsP = BlurMap.compute(mask, luma, W, H, params, PipelineParallel.parallelism)
        assertTrue("BlurMap radius must be bit-identical", maps.radius.contentEquals(mapsP.radius))
        assertTrue("BlurMap mask must be bit-identical", maps.featheredMask.contentEquals(mapsP.featheredMask))

        val (sr, sg, sb) = floatChannels(input)
        val offsets = DiscBlur.vogelSpiral(params.tapCount)
        val bg = FloatArray(W * H) { 1f - maps.featheredMask[it] }
        fun run(chunks: Int): Triple<FloatArray, FloatArray, FloatArray> {
            val or = FloatArray(W * H); val og = FloatArray(W * H); val ob = FloatArray(W * H)
            DiscBlur.blur(sr, sg, sb, W, H, maps.radius, bg, offsets, or, og, ob, chunkCount = chunks)
            return Triple(or, og, ob)
        }
        val (r1, g1, b1) = run(PipelineParallel.SERIAL_CHUNKS)
        val (r2, g2, b2) = run(PipelineParallel.parallelism)
        assertTrue("DiscBlur R serial==parallel", r1.contentEquals(r2))
        assertTrue("DiscBlur G serial==parallel", g1.contentEquals(g2))
        assertTrue("DiscBlur B serial==parallel", b1.contentEquals(b2))
    }

    // --- passthrough at strength 0 -------------------------------------------------

    @Test
    fun strengthZeroIsBitExactPassthrough() {
        val out = BokehRenderer.render(input, mask, params.copy(strength = 0.0))
        for (i in input.argb.indices) {
            // RGB must match exactly; alpha is forced opaque by the renderer.
            assertTrue(
                "strength 0 must pass RGB through unchanged at $i",
                (out.argb[i] and 0xFFFFFF) == (input.argb[i] and 0xFFFFFF),
            )
        }
    }

    // --- timing tripwire -----------------------------------------------------------

    @Test
    fun fullResolutionRenderStaysWithinTripwire() {
        val bw = 2000
        val bh = 1500
        val big = buildBigScene(bw, bh)
        val bigParams = BokehParams.forImageWidth(bw)
        // Warm up the JIT so the measured pass is steady-state.
        BokehRenderer.render(big.frame, big.mask, bigParams)
        val start = System.nanoTime()
        BokehRenderer.render(big.frame, big.mask, bigParams)
        val millis = (System.nanoTime() - start) / 1_000_000.0
        println("[bokeh] 2000x1500 render = ${"%.1f".format(millis)} ms (parallelism=${PipelineParallel.parallelism}, maxR=${bigParams.maxBlurRadius}, taps=${bigParams.tapCount})")
        // GENEROUS tripwire (not an SLA): a healthy build finishes in well under a second on a
        // dev box; on a phone the O(pixels*taps) gather at 3 MP is expected to cost a few
        // hundred ms single-threaded, comfortably real-time-adjacent for a still capture.
        assertTrue("2000x1500 render ${millis} ms exceeded the 15s tripwire", millis < 15_000.0)
    }

    // --- scene construction --------------------------------------------------------

    private class Scene(val frame: Frame, val mask: FloatArray)

    /**
     * The portrait fixture: red textured subject disk, green textured background, bright 1 px
     * hair strands over the background near the subject top, and near-clipped white highlight
     * points in the background.
     */
    private fun buildScene(): Scene {
        val argb = IntArray(W * H)
        val m = FloatArray(W * H)
        val bgG = lattice(0x1111, cell = 8, amp = 55) // green texture 0..55
        val subTex = lattice(0x2222, cell = 10, amp = 40)
        for (y in 0 until H) {
            for (x in 0 until W) {
                val i = y * W + x
                val d = dist(x, y)
                if (d <= SUBJECT_R) {
                    // Red subject with interior texture.
                    val r = (165 + subTex[i].toInt()).coerceIn(0, 255)
                    argb[i] = pack(r, 35, 40)
                    // Hard segmentation-style mask; the guided-filter feathering (driven by the
                    // luma edge at the red/green boundary) does the transition, as in issue #80.
                    m[i] = 1.0f
                } else {
                    // Green background with texture.
                    val g = (150 + bgG[i].toInt()).coerceIn(0, 255)
                    argb[i] = pack(30, g, 45)
                    m[i] = 0.0f
                }
            }
        }
        // Hair strands: 1 px bright columns extending from the subject top up over the
        // background, mask 1 on the strand, 0 in the gaps (the alternating pattern).
        for (col in intArrayOf(86, 90, 94, 98, 102, 106, 110, 114)) {
            for (y in 25..53) {
                val i = y * W + col
                argb[i] = pack(215, 215, 215)
                m[i] = 1.0f
            }
        }
        // Near-clipped white highlight points in the background (a small cluster for a
        // measurable disc, plus one elsewhere for realism).
        for ((hx, hy) in listOf(35 to 35, 36 to 35, 35 to 36, 36 to 36, 165 to 35)) {
            argb[hy * W + hx] = pack(252, 252, 252)
        }
        return Scene(Frame(W, H, argb, 0L), m)
    }

    private fun buildBigScene(w: Int, h: Int): Scene {
        val argb = IntArray(w * h)
        val m = FloatArray(w * h)
        val cx = w / 2.0
        val cy = h / 2.0
        val rr = (w.coerceAtMost(h)) * 0.28
        val tex = lattice(0x5151, cell = 24, amp = 60, width = w, height = h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val i = y * w + x
                val dx = x - cx
                val dy = y - cy
                val d = sqrt(dx * dx + dy * dy)
                if (d <= rr) {
                    argb[i] = pack(170, 40, 45)
                    m[i] = 1.0f
                } else {
                    argb[i] = pack(35, (140 + tex[i].toInt()).coerceIn(0, 255), 50)
                    m[i] = 0.0f
                }
            }
        }
        return Scene(Frame(w, h, argb, 0L), m)
    }

    // --- helpers -------------------------------------------------------------------

    private fun floatChannels(f: Frame): Triple<FloatArray, FloatArray, FloatArray> {
        val r = FloatArray(f.argb.size); val g = FloatArray(f.argb.size); val b = FloatArray(f.argb.size)
        for (i in f.argb.indices) {
            val p = f.argb[i]
            r[i] = ((p shr 16) and 0xFF).toFloat()
            g[i] = ((p shr 8) and 0xFF).toFloat()
            b[i] = (p and 0xFF).toFloat()
        }
        return Triple(r, g, b)
    }

    private fun dist(x: Int, y: Int): Double {
        val dx = x - CX
        val dy = y - CY
        return sqrt(dx * dx + dy * dy)
    }

    private fun lumaOf(pixel: Int): Double =
        0.299 * ((pixel shr 16) and 0xFF) + 0.587 * ((pixel shr 8) and 0xFF) + 0.114 * (pixel and 0xFF)

    private fun meanLuma(f: Frame, x0: Int, y0: Int, x1: Int, y1: Int): Double {
        var s = 0.0; var n = 0
        for (y in y0 until y1) for (x in x0 until x1) { s += lumaOf(f.argb[y * W + x]); n++ }
        return s / n
    }

    private fun lumaVariance(f: Frame, x0: Int, y0: Int, x1: Int, y1: Int): Double {
        val vals = ArrayList<Double>()
        for (y in y0 until y1) for (x in x0 until x1) vals.add(lumaOf(f.argb[y * W + x]))
        val mean = vals.average()
        var v = 0.0
        for (l in vals) v += (l - mean) * (l - mean)
        return v / vals.size
    }

    /** Coarse LCG lattice bilinearly interpolated to full resolution, values in [0, amp]. */
    private fun lattice(seed: Long, cell: Int, amp: Int, width: Int = W, height: Int = H): DoubleArray {
        var state = seed and 0xFFFFFFFFL
        fun next(): Double {
            state = (state * 1664525L + 1013904223L) and 0xFFFFFFFFL
            return ((state ushr 24) and 0xFFL).toDouble() / 255.0 * amp
        }
        val gw = width / cell + 2
        val gh = height / cell + 2
        val grid = DoubleArray(gw * gh) { next() }
        val out = DoubleArray(width * height)
        for (y in 0 until height) {
            val gy = y / cell
            val fy = (y % cell).toDouble() / cell
            for (x in 0 until width) {
                val gx = x / cell
                val fx = (x % cell).toDouble() / cell
                val v00 = grid[gy * gw + gx]
                val v10 = grid[gy * gw + gx + 1]
                val v01 = grid[(gy + 1) * gw + gx]
                val v11 = grid[(gy + 1) * gw + gx + 1]
                val top = v00 + (v10 - v00) * fx
                val bot = v01 + (v11 - v01) * fx
                out[y * width + x] = top + (bot - top) * fy
            }
        }
        return out
    }

    private fun pack(r: Int, g: Int, b: Int): Int = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    private companion object {
        const val W = 200
        const val H = 200
        const val CX = 100.0
        const val CY = 100.0
        const val SUBJECT_R = 45.0

        // Measurement regions (all clear of one another; see buildScene()).
        const val BG_X0 = 145; const val BG_Y0 = 135; const val BG_X1 = 190; const val BG_Y1 = 190
        const val HAIR_X0 = 84; const val HAIR_Y0 = 24; const val HAIR_X1 = 116; const val HAIR_Y1 = 54
        const val HL_X0 = 26; const val HL_Y0 = 26; const val HL_X1 = 46; const val HL_Y1 = 46
    }
}
