package com.poc.camera.camera

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Generates MediaStore display names from a capture timestamp, kept free of Android
 * framework classes so it can be unit tested without Robolectric.
 */
object PhotoCaptureNaming {
    private const val PREFIX = "IMG_"
    private const val EXTENSION = ".jpg"
    private const val PATTERN = "yyyyMMdd_HHmmssSSS"

    fun displayName(timestampMillis: Long): String {
        // UTC keeps generated names deterministic regardless of device/CI timezone.
        val formatter = SimpleDateFormat(PATTERN, Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        return PREFIX + formatter.format(Date(timestampMillis)) + EXTENSION
    }
}
