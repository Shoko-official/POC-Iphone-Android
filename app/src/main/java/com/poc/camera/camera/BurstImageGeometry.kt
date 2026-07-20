package com.poc.camera.camera

/**
 * Pure decode-geometry math for the full-resolution burst path, kept free of Android
 * classes so it can be unit tested without Robolectric.
 *
 * Full-sensor JPEGs are far too large to hold several of at once as ARGB [IntArray]s: a
 * 12 MP frame is 48 MB decoded, and a 6-9 frame burst would blow the heap. Each frame is
 * therefore downscaled at decode time to at most [MAX_BURST_PIXELS] pixels, still 3-4x
 * the resolution of the retired 720p ImageAnalysis path (~0.9 MP) while keeping the
 * transient burst footprint bounded.
 */
object BurstImageGeometry {

    /**
     * Upper bound on decoded pixels per burst frame (~3.5 MP). A 12 MP 4032x3024 sensor
     * decodes at inSampleSize 2 to 2016x1512 (~3.0 MP, ~12 MB as ARGB); six such frames
     * peak around 75 MB in flight before merged frames are released to GC. This is a
     * deliberate interim bound: true native-resolution merging needs tiled processing
     * (tracked as the issue #42 performance follow-up), not a full-frame IntArray per
     * capture.
     */
    const val MAX_BURST_PIXELS: Int = 3_500_000

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
