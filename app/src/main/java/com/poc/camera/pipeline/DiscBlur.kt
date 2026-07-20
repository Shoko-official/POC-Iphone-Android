package com.poc.camera.pipeline

import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Variable-radius DISC blur by sparse spiral gathering, the optical core of the portrait
 * defocus ([BokehRenderer]).
 *
 * ## Why a spiral gather and not a separable blur
 *
 * The bokeh "look" is dominated by the SHAPE of the out-of-focus kernel: a real lens
 * defocuses a point of light into a bright DISC (the aperture), which is why highlights
 * turn into round glowing balls. Separable approximations do not reproduce that shape --
 * a single box blur spreads a point into a square, and three iterated box blurs tend to a
 * Gaussian (a soft round falloff with no hard disc rim), which reads as a flat "smudge",
 * not as bokeh. Getting a true disc from a separable pass is not possible; getting it from
 * an exact disc convolution costs O(pixels * r^2), i.e. ~450 taps per pixel at r = 12.
 *
 * This class takes the honest middle path: a fixed, small number of taps ([offsets], a
 * VOGEL SPIRAL) sampled over the disc of the LOCAL radius. The Vogel (sunflower) spiral
 * places `taps` points at radius `sqrt((i + 0.5) / taps)` and angle `i * goldenAngle`, so
 * the points are equal-area distributed over the unit disc with no directional bias and no
 * clustering -- a disc-shaped support with as few as 24 samples. Cost is
 * O(pixels * taps): bounded, independent of the radius, and fully parallel by output pixel
 * (each output reads only the shared read-only source and writes only its own texel).
 * The trade-off is that the discs are sampled rather than solid, so at very large radii and
 * few taps a bright highlight disc shows its individual samples; 24 taps is tuned to keep
 * that invisible at the portrait radii here while staying cheap.
 *
 * ## Edge-aware background gathering (no subject bleed)
 *
 * The classic portrait-blur artifact is SUBJECT COLOUR BLEEDING into the background bokeh:
 * a naive gather at a background pixel near the subject boundary averages in the subject's
 * (in-focus, differently coloured) pixels, haloing the subject with its own colour. To
 * prevent it, each tap can be weighted by an optional per-pixel [weight] plane sampled at
 * the tap position (the caller passes background-ness = 1 - featheredMask): subject taps
 * get ~0 weight and drop out of the average. Where the entire disc lands on the subject the
 * total weight collapses to ~0; there the result FALLS BACK to the unweighted disc mean
 * (documented below) so the output is always defined and never divides by zero.
 *
 * All sampling is clamped-edge bilinear. Pure Kotlin, deterministic (fixed taps, fixed
 * arithmetic order per output element), no Android dependencies.
 */
object DiscBlur {

    /** Below this per-pixel radius (px) a pixel is copied straight through (kept sharp). */
    const val DEFAULT_MIN_RADIUS = 0.5f

    /** Total tap weight below which the weighted mean is undefined and the fallback kicks in. */
    private const val WEIGHT_FLOOR = 1e-4f

    /**
     * Returns `taps` unit-disc offsets as a flat `[x0, y0, x1, y1, ...]` array following the
     * Vogel spiral (equal-area, golden-angle). Deterministic: the same `taps` always yields
     * the same offsets, so callers can precompute once and reuse across the whole frame.
     */
    fun vogelSpiral(taps: Int): DoubleArray {
        require(taps >= 1) { "taps must be >= 1" }
        val out = DoubleArray(taps * 2)
        // Golden angle in radians: pi * (3 - sqrt 5). Successive taps rotate by this, which
        // is the most irrational turn fraction, so samples never line up into spokes.
        val goldenAngle = Math.PI * (3.0 - sqrt(5.0))
        for (i in 0 until taps) {
            val r = sqrt((i + 0.5) / taps)
            val theta = i * goldenAngle
            out[2 * i] = r * cos(theta)
            out[2 * i + 1] = r * sin(theta)
        }
        return out
    }

    /**
     * Disc-blurs the [srcR]/[srcG]/[srcB] channel planes (all [width]x[height], row-major,
     * float so [HighlightBloom] headroom above 255 survives) into [outR]/[outG]/[outB].
     *
     * Per output pixel the gather radius is [radiusMap] at that pixel. Where the radius is
     * below [minRadius] the source pixel is copied verbatim (a sharp foreground pixel does no
     * work and stays bit-exact). Otherwise every offset in [offsets] (from [vogelSpiral]) is
     * scaled by the local radius, bilinearly sampled, and averaged. When [weight] is non-null
     * it is bilinearly sampled at each tap and used as that tap's weight (see class KDoc):
     * the output is the weight-normalised mean, falling back to the plain (unweighted) mean
     * where the summed weight is below [WEIGHT_FLOOR].
     *
     * Output pixels are independent (each reads only the shared read-only inputs and writes
     * only its own texel), so the [chunkCount] split is bit-identical to the serial path
     * (see [PipelineParallel]); the default is one chunk per worker.
     */
    @Suppress("LongParameterList", "LongMethod", "CyclomaticComplexMethod")
    fun blur(
        srcR: FloatArray,
        srcG: FloatArray,
        srcB: FloatArray,
        width: Int,
        height: Int,
        radiusMap: FloatArray,
        weight: FloatArray?,
        offsets: DoubleArray,
        outR: FloatArray,
        outG: FloatArray,
        outB: FloatArray,
        minRadius: Float = DEFAULT_MIN_RADIUS,
        chunkCount: Int = PipelineParallel.parallelism,
    ) {
        val size = width * height
        require(srcR.size == size && srcG.size == size && srcB.size == size) {
            "source planes must be width*height"
        }
        require(outR.size == size && outG.size == size && outB.size == size) {
            "output planes must be width*height"
        }
        require(radiusMap.size == size) { "radiusMap must be width*height" }
        require(weight == null || weight.size == size) { "weight must be width*height" }
        require(offsets.size % 2 == 0 && offsets.isNotEmpty()) { "offsets must be non-empty pairs" }
        val taps = offsets.size / 2
        val maxX = width - 1
        val maxY = height - 1

        PipelineParallel.parallelRows(height, chunkCount) { yStart, yEnd ->
            for (y in yStart until yEnd) {
                val rowBase = y * width
                for (x in 0 until width) {
                    val idx = rowBase + x
                    val radius = radiusMap[idx]
                    if (radius < minRadius) {
                        // Sharp: copy the source pixel untouched (foreground / near-subject).
                        outR[idx] = srcR[idx]
                        outG[idx] = srcG[idx]
                        outB[idx] = srcB[idx]
                        continue
                    }
                    var wSumR = 0f
                    var wSumG = 0f
                    var wSumB = 0f
                    var wTotal = 0f
                    var pSumR = 0f
                    var pSumG = 0f
                    var pSumB = 0f
                    val cx = x.toDouble()
                    val cy = y.toDouble()
                    var t = 0
                    while (t < taps) {
                        val sx = cx + offsets[2 * t] * radius
                        val sy = cy + offsets[2 * t + 1] * radius
                        // Clamped-edge bilinear address.
                        val gx = if (sx < 0.0) 0.0 else if (sx > maxX.toDouble()) maxX.toDouble() else sx
                        val gy = if (sy < 0.0) 0.0 else if (sy > maxY.toDouble()) maxY.toDouble() else sy
                        val x0 = floor(gx).toInt()
                        val y0 = floor(gy).toInt()
                        val x1 = if (x0 < maxX) x0 + 1 else x0
                        val y1 = if (y0 < maxY) y0 + 1 else y0
                        val fx = (gx - x0).toFloat()
                        val fy = (gy - y0).toFloat()
                        val w00 = (1f - fx) * (1f - fy)
                        val w10 = fx * (1f - fy)
                        val w01 = (1f - fx) * fy
                        val w11 = fx * fy
                        val i00 = y0 * width + x0
                        val i10 = y0 * width + x1
                        val i01 = y1 * width + x0
                        val i11 = y1 * width + x1
                        val sr = w00 * srcR[i00] + w10 * srcR[i10] + w01 * srcR[i01] + w11 * srcR[i11]
                        val sg = w00 * srcG[i00] + w10 * srcG[i10] + w01 * srcG[i01] + w11 * srcG[i11]
                        val sb = w00 * srcB[i00] + w10 * srcB[i10] + w01 * srcB[i01] + w11 * srcB[i11]
                        pSumR += sr
                        pSumG += sg
                        pSumB += sb
                        val tw = if (weight == null) {
                            1f
                        } else {
                            w00 * weight[i00] + w10 * weight[i10] + w01 * weight[i01] + w11 * weight[i11]
                        }
                        wSumR += tw * sr
                        wSumG += tw * sg
                        wSumB += tw * sb
                        wTotal += tw
                        t++
                    }
                    if (wTotal > WEIGHT_FLOOR) {
                        val inv = 1f / wTotal
                        outR[idx] = wSumR * inv
                        outG[idx] = wSumG * inv
                        outB[idx] = wSumB * inv
                    } else {
                        // Whole disc landed on ~zero-weight (subject) pixels: fall back to the
                        // plain disc mean so the output is defined instead of dividing by ~0.
                        val invT = 1f / taps
                        outR[idx] = pSumR * invT
                        outG[idx] = pSumG * invT
                        outB[idx] = pSumB * invT
                    }
                }
            }
        }
    }
}
