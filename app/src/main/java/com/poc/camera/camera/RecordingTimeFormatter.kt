package com.poc.camera.camera

/**
 * Formats elapsed recording time for on-screen display, kept free of Android
 * framework classes so it can be unit tested without Robolectric.
 */
object RecordingTimeFormatter {
    fun format(elapsedMillis: Long): String {
        val totalSeconds = elapsedMillis / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return "%02d:%02d".format(minutes, seconds)
    }
}
