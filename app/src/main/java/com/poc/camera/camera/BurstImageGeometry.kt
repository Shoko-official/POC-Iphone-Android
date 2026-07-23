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
     * Default upper bound on decoded pixels per burst frame (~12.5 MP), i.e. native
     * resolution for a typical 12 MP sensor. This is the HIGH-memory-class value; a
     * low-memory device gets the conservative ~8 MP bound instead via
     * [maxBurstPixelsFor]. Callers that know the device memory class should prefer that
     * helper; this constant is the ceiling and the default for the pure geometry tests.
     *
     * Raised from the former ~8 MP bound once issue #54 moved finishing to a bounded-
     * memory tiled path ([com.poc.camera.pipeline.TiledFinishing]): the finishing
     * guided-filter intermediates -- previously the dominant transient allocation, near a
     * gigabyte of `DoubleArray`s at 12.5 MP if run whole-frame -- are now capped at a
     * per-tile working set independent of frame size. That removes the finishing peak
     * that had held the decode bound down, leaving the resident burst frames as the
     * governing cost.
     *
     * ## Memory math (the real constraint, honestly)
     *
     * An ARGB frame costs 4 bytes/pixel, so at this bound one decoded frame is
     * ~12.5 MP * 4 = ~50 MB. Decoding is one-in-flight (each JPEG is decoded and
     * downscaled before the next), so decode itself peaks at ~50 MB, but the MERGE holds
     * all N frames at once: a 6-frame burst is ~6 * 50 = ~300 MB of IntArrays resident,
     * plus the merge accumulators (four `DoubleArray`s over the pixel count, ~400 MB at
     * 12.5 MP). The finishing stage no longer adds a full-resolution float peak on top --
     * [com.poc.camera.pipeline.TiledFinishing] keeps it near ~180 MB regardless -- so the
     * merge itself is now the peak. ~300 MB of resident frame data plus the merge
     * accumulators is why this native bound is gated to high-memory devices only.
     *
     * ## Device-class gating
     *
     * [maxBurstPixelsFor] picks the bound from the device memory class: a device below
     * [HIGH_MEMORY_CLASS_MB] stays at the conservative [CONSERVATIVE_MAX_BURST_PIXELS]
     * (~8 MP, ~192 MB of resident frames for a 6-frame burst), while a higher-RAM device
     * takes this native bound. A 4032x3024 (~12.2 MP) sensor now decodes at full (sample
     * 1) on a high-memory device and at inSampleSize 2 (~3.0 MP) on a low-memory one.
     */
    const val MAX_BURST_PIXELS: Int = 12_500_000

    /**
     * Floor on the per-frame decoded-pixel bound (~2 MP, 1080p-class). Below this the
     * merged output stops being a usable photo, so the budget never pushes under it even
     * on a tiny heap - the capture coroutines' failure handling (issue #171) is the
     * backstop if such a device still cannot hold the burst.
     */
    const val MIN_BURST_PIXELS: Int = 2_000_000

    /**
     * Heap fallback (MB) when the reported memory class is non-positive (unknown):
     * a standard-heap baseline, deliberately pessimistic.
     */
    const val FALLBACK_MEMORY_CLASS_MB: Int = 192

    /**
     * Merge-accumulator cost per pixel: [com.poc.camera.pipeline.RobustFrameMerger] holds
     * four full-frame `DoubleArray` planes (R/G/B sums + weight) while merging, 4 * 8 bytes.
     */
    const val MERGE_ACCUMULATOR_BYTES_PER_PIXEL: Int = 32

    /**
     * The per-frame decoded-pixel bound for a capture of [frameCount] frames on a device
     * whose per-app heap is [memoryClassMb] MB (read from
     * [android.app.ActivityManager.getLargeMemoryClass] - the app declares
     * `largeHeap`, issue #171).
     *
     * The former step function ([HIGH_MEMORY_CLASS_MB]-gated native-vs-8MP bound) ignored
     * the frame count, and its own memory math shows why that crashed real devices
     * (26 recorded OOMs, issue #171): the merge holds ALL frames at once, so night mode's
     * 12-frame burst at the 8 MP bound is 12 * 32 MB = 384 MB of resident IntArrays plus
     * ~256 MB of merge accumulators - far past even a 512 MB large heap.
     *
     * This bound solves the actual constraint instead: the merge peak
     * (`frameCount` ARGB inputs at 4 B/px + [MERGE_ACCUMULATOR_BYTES_PER_PIXEL]) must fit
     * in half the heap, leaving the other half for the camera stack, decode transients and
     * the (whole-frame, sub-[com.poc.camera.pipeline.FinishingPipeline.TILED_THRESHOLD_PIXELS])
     * finishing chain:
     *
     *   pixels = (heap / 2) / (4 * frames + 32)
     *
     * clamped to [[MIN_BURST_PIXELS], [MAX_BURST_PIXELS]]. On a 512 MB large heap that
     * yields ~4.8 MP for a 6-frame burst, ~3.9 MP for 9 frames and ~3.3 MP for the
     * 12-frame night burst; a 1 GB heap keeps ~9.6 MP for 6 frames. Pure math, unit
     * tested without Android.
     */
    fun maxBurstPixelsFor(memoryClassMb: Int, frameCount: Int): Int {
        val classMb = if (memoryClassMb > 0) memoryClassMb else FALLBACK_MEMORY_CLASS_MB
        val frames = frameCount.coerceAtLeast(1)
        val budgetBytes = classMb.toLong() * 1024L * 1024L / 2L
        val bytesPerPixel = 4L * frames + MERGE_ACCUMULATOR_BYTES_PER_PIXEL
        return (budgetBytes / bytesPerPixel)
            .coerceIn(MIN_BURST_PIXELS.toLong(), MAX_BURST_PIXELS.toLong())
            .toInt()
    }

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
