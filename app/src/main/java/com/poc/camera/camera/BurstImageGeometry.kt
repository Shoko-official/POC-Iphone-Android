package com.poc.camera.camera

/**
 * Pure decode-geometry math for the full-resolution burst path, kept free of Android
 * classes so it can be unit tested without Robolectric.
 *
 * Full-sensor JPEGs are far too large to hold several of at once as ARGB [IntArray]s: a
 * 12 MP frame is 48 MB decoded, and a 6-9 frame burst would blow the heap. Each frame is
 * therefore downscaled at decode time to at most [MAX_BURST_PIXELS] pixels, keeping the
 * transient burst footprint bounded while staying well above the retired 720p
 * ImageAnalysis path (~0.9 MP).
 */
object BurstImageGeometry {

    /**
     * Upper bound on decoded pixels per burst frame (~8 MP).
     *
     * Raised from the former ~3.5 MP interim bound after the issue #42 pipeline work
     * made the pure merge + finishing stages row-parallel with deterministic (bit-
     * identical) output, so the per-frame CPU cost of a larger frame now scales
     * near-linearly across cores instead of serially. Measured on the pure pipeline,
     * finishing scales ~4.3x from 3 MP to 12 MP (a 4x pixel jump), confirming there is
     * headroom above the old bound.
     *
     * ## Memory math (the real constraint, honestly)
     *
     * An ARGB frame costs 4 bytes/pixel, so at this bound one decoded frame is
     * ~8 MP * 4 = ~32 MB. Decoding is one-in-flight (each JPEG is decoded and downscaled
     * before the next), so decode itself peaks at ~32 MB, but the MERGE holds all N
     * frames at once: a 6-frame burst is ~6 * 32 = ~192 MB of IntArrays resident, plus
     * the merge accumulators (four DoubleArrays over the pixel count, ~256 MB at 8 MP)
     * and the finishing guided-filter intermediates (several transient DoubleArrays).
     * That is a heavy but workable footprint on a mid-range device and a deliberate step
     * DOWN from a full 12 MP bound, where six frames alone would be ~288 MB of IntArrays
     * before any working memory -- too aggressive for low-RAM devices.
     *
     * ## Chosen compromise and the device-side follow-up
     *
     * 8 MP is a measured compromise: it roughly doubles usable resolution over the old
     * 3.5 MP bound while keeping the resident merge footprint under ~200 MB of frame
     * data. A larger 4032x3024 sensor therefore still decodes at inSampleSize 2 to
     * ~3.0 MP (unchanged), but sensors between ~8 and ~12 MP now decode at full (sample
     * 1) instead of being halved. The proper device-side fix -- picking the bound from
     * the device's memory class ([android.app.ActivityManager.getMemoryClass], so a
     * high-RAM phone can go to native 12 MP while a low-RAM phone stays conservative),
     * and/or tiled/streaming merge to drop the "all frames resident" cost -- is the
     * remaining issue #42 follow-up and intentionally out of scope for this pure-pipeline
     * change.
     */
    const val MAX_BURST_PIXELS: Int = 8_000_000

    /**
     * Smallest power-of-two `inSampleSize` for which a [sourceWidth] x [sourceHeight]
     * image decodes to at most [maxPixels] pixels. `BitmapFactory` rounds any requested
     * sample size down to a power of two, so only powers of two are considered. Returns 1
     * for images already within the bound or for non-positive dimensions.
     */
    fun inSampleSizeFor(sourceWidth: Int, sourceHeight: Int, maxPixels: Int = MAX_BURST_PIXELS): Int {
        require(maxPixels > 0) { "maxPixels must be positive" }
        if (sourceWidth <= 0 || sourceHeight <= 0) return 1
        var sample = 1
        while (pixelsAt(sourceWidth, sourceHeight, sample) > maxPixels) {
            sample *= 2
        }
        return sample
    }

    /** True when [rotationDegrees] (any multiple of 90, positive or negative) swaps width and height. */
    fun swapsDimensions(rotationDegrees: Int): Boolean {
        val normalized = ((rotationDegrees % 360) + 360) % 360
        return normalized == 90 || normalized == 270
    }

    // Mirrors BitmapFactory's integer-division downscale for a given sample size.
    private fun pixelsAt(width: Int, height: Int, sample: Int): Long =
        (width / sample).toLong() * (height / sample).toLong()
}
