package com.poc.camera.pipeline

import kotlin.math.roundToInt

/**
 * Tunable parameters for [LocalToneMapper].
 *
 * @property radius guided-filter window half-width on the luma plane. ~16 keeps the
 *   base "large-scale" so detail stays local at [SyntheticScenes]-class resolutions.
 * @property eps guided-filter edge regulariser in squared 8-bit intensity units. The
 *   default (0.03 * 255)^2 treats swings under ~3% of full scale as flat (smoothed
 *   into the base) and preserves anything stronger as an edge, which is what keeps
 *   the base halo-free.
 * @property baseCompression fraction the base layer is pulled toward mid-gray, in
 *   [0, 1). This is what lifts local shadows and tames local highlights.
 * @property detailGain multiplier (>1) on the detail layer, restoring local punch the
 *   base compression would otherwise flatten.
 */
data class LocalToneParams(
    val radius: Int = DEFAULT_RADIUS,
    val eps: Double = DEFAULT_EPS,
    val baseCompression: Double = DEFAULT_BASE_COMPRESSION,
    val detailGain: Double = DEFAULT_DETAIL_GAIN,
) {
    init {
        require(radius >= 0) { "radius must be >= 0" }
        require(eps > 0.0) { "eps must be > 0" }
        require(baseCompression in 0.0..1.0) { "baseCompression must be in [0, 1]" }
        require(detailGain >= 0.0) { "detailGain must be >= 0" }
    }

    companion object {
        const val DEFAULT_RADIUS = 16
        const val DEFAULT_EPS = (0.03 * 255.0) * (0.03 * 255.0)
        const val DEFAULT_BASE_COMPRESSION = 0.15
        const val DEFAULT_DETAIL_GAIN = 1.15
    }
}

/**
 * Guided local contrast enhancement complementing the global [ToneCurve]. It works
 * on luma only, splitting it into a base and a detail layer with an edge-preserving
 * [GuidedFilter], compressing the base toward mid-gray while amplifying detail, then
 * writing the new luma back per channel so hue and saturation are preserved:
 *
 *   base       = guidedFilter(luma)                       // large-scale
 *   detail     = luma - base                              // local residual
 *   base'      = 128 + (base - 128) * (1 - baseCompression)
 *   outLuma    = base' + detailGain * detail
 *   out_c      = clamp(in_c * (outLuma + eps2) / (inLuma + eps2))
 *
 * Because the base comes from a guided filter (a -> 1 at strong edges), compressing
 * it does not smear bright/dark regions across edges, so local shadow lift and
 * highlight taming happen without the halos an unguided base/detail split produces.
 *
 * The per-channel ratio is clamped to [[RATIO_MIN], [RATIO_MAX]] and both luma terms
 * carry an [RATIO_EPS] floor so near-black pixels (tiny inLuma) cannot blow chroma up
 * through division; without the clamp a shadow lift would push near-black pixels to
 * wildly saturated colours. Pure Kotlin, deterministic, no Android dependencies.
 */
object LocalToneMapper {

    /** Floor added to both luma terms of the per-channel ratio (avoids /0 at black). */
    private const val RATIO_EPS = 1.0

    /** Bounds on the per-channel luma ratio, guarding chroma against blow-up. */
    private const val RATIO_MIN = 0.25
    private const val RATIO_MAX = 4.0

    // Rec. 601 luma weights, matching [Luminance] (double precision here so the
    // base/detail split is smooth rather than quantised to integer luma).
    private const val R_WEIGHT = 0.299
    private const val G_WEIGHT = 0.587
    private const val B_WEIGHT = 0.114

    /** Applies local tone mapping to [frame], preserving its dimensions and alpha. */
    fun apply(frame: Frame, params: LocalToneParams = LocalToneParams()): Frame {
        val src = frame.argb
        val luma = DoubleArray(src.size) { i ->
            val pixel = src[i]
            R_WEIGHT * ((pixel shr 16) and 0xFF) +
                G_WEIGHT * ((pixel shr 8) and 0xFF) +
                B_WEIGHT * (pixel and 0xFF)
        }

        val base = GuidedFilter.selfGuided(luma, frame.width, frame.height, params.radius, params.eps)
        val out = IntArray(src.size)
        for (i in src.indices) {
            val inLuma = luma[i]
            val detail = inLuma - base[i]
            val compressedBase = 128.0 + (base[i] - 128.0) * (1.0 - params.baseCompression)
            val outLuma = compressedBase + params.detailGain * detail
            val ratio = ((outLuma + RATIO_EPS) / (inLuma + RATIO_EPS)).coerceIn(RATIO_MIN, RATIO_MAX)

            val pixel = src[i]
            val a = (pixel ushr 24) and 0xFF
            val r = (((pixel shr 16) and 0xFF) * ratio).roundToInt().coerceIn(0, 255)
            val g = (((pixel shr 8) and 0xFF) * ratio).roundToInt().coerceIn(0, 255)
            val b = ((pixel and 0xFF) * ratio).roundToInt().coerceIn(0, 255)
            out[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
        }
        return Frame(frame.width, frame.height, out, frame.timestampMillis)
    }
}
