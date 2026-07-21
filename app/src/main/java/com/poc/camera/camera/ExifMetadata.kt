package com.poc.camera.camera

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * EXIF metadata for a processed (merged or super-resolved) photo.
 *
 * Frames are decoded upright before processing - rotation is applied at decode time in
 * [JpegBurstFrameDecoder] - so the built [TAG_ORIENTATION] is always the EXIF "normal"
 * value (1). That is not a placeholder; it is the honest description of pixels that are
 * already the right way up by the time this metadata is attached. Selfie mirroring (issue
 * #88, [com.poc.camera.pipeline.mirrorHorizontal]) does not change that: a mirrored front-
 * camera frame's pixels are physically flipped by the pipeline before the JPEG is encoded, so
 * orientation stays 1 here too - there is no EXIF mirror flag to set instead (EXIF orientation
 * values 2/4/5/7 encode a flip, but this project writes literal pixels, never a flip-by-tag,
 * to keep every consumer that ignores EXIF orientation - like [BitmapFrameConverter][com.poc.camera.imaging.BitmapFrameConverter]
 * reading a saved file back in - seeing the correct, already-mirrored image).
 *
 * Pure data holder plus a map builder, no Android imports, so tag formatting can be unit
 * tested without Robolectric. Map keys are the tag name strings documented by
 * androidx.exifinterface's `ExifInterface.TAG_*` constants (e.g. "DateTime", "Orientation"),
 * duplicated here as constants so the thin writer can pass entries straight to
 * `ExifInterface.setAttribute` without a name-translation table.
 */
data class ExifMetadata(
    val captureTimestampMillis: Long,
    val widthPx: Int,
    val heightPx: Int,
    val software: String = DEFAULT_SOFTWARE,
) {

    /**
     * Builds the EXIF tag map for this metadata.
     *
     * EXIF's DateTime/DateTimeOriginal/DateTimeDigitized tags store capture time as local
     * wall-clock time with no timezone offset recorded, so [timeZone] determines what
     * "local" means when rendering [captureTimestampMillis]. It defaults to the device's
     * current timezone; tests pass an explicit zone so formatting stays deterministic
     * regardless of the host timezone.
     */
    fun toExifAttributes(timeZone: TimeZone = TimeZone.getDefault()): Map<String, String> {
        val formatter = SimpleDateFormat(DATETIME_PATTERN, Locale.US).apply {
            this.timeZone = timeZone
        }
        val formattedTimestamp = formatter.format(Date(captureTimestampMillis))
        return mapOf(
            TAG_DATETIME to formattedTimestamp,
            TAG_DATETIME_ORIGINAL to formattedTimestamp,
            TAG_DATETIME_DIGITIZED to formattedTimestamp,
            TAG_ORIENTATION to ORIENTATION_NORMAL,
            TAG_PIXEL_X_DIMENSION to widthPx.toString(),
            TAG_PIXEL_Y_DIMENSION to heightPx.toString(),
            TAG_SOFTWARE to software,
        )
    }

    companion object {
        const val DEFAULT_SOFTWARE = "POC Camera"

        private const val DATETIME_PATTERN = "yyyy:MM:dd HH:mm:ss"
        private const val ORIENTATION_NORMAL = "1"

        // Mirrors androidx.exifinterface.media.ExifInterface.TAG_* string values.
        const val TAG_DATETIME = "DateTime"
        const val TAG_DATETIME_ORIGINAL = "DateTimeOriginal"
        const val TAG_DATETIME_DIGITIZED = "DateTimeDigitized"
        const val TAG_ORIENTATION = "Orientation"
        const val TAG_PIXEL_X_DIMENSION = "PixelXDimension"
        const val TAG_PIXEL_Y_DIMENSION = "PixelYDimension"
        const val TAG_SOFTWARE = "Software"
    }
}
