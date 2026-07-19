package com.poc.camera.camera

import org.junit.Assert.assertEquals
import org.junit.Test

class RecordingTimeFormatterTest {

    @Test
    fun formatsZeroAsMinutesAndSeconds() {
        assertEquals("00:00", RecordingTimeFormatter.format(elapsedMillis = 0L))
    }

    @Test
    fun formatsSubMinuteDurations() {
        assertEquals("00:07", RecordingTimeFormatter.format(elapsedMillis = 7_000L))
    }

    @Test
    fun formatsMinutesAndSecondsPastOneMinute() {
        assertEquals("01:05", RecordingTimeFormatter.format(elapsedMillis = 65_000L))
    }

    @Test
    fun truncatesPartialSeconds() {
        assertEquals("00:02", RecordingTimeFormatter.format(elapsedMillis = 2_999L))
    }

    @Test
    fun formatsDurationsPastTenMinutes() {
        assertEquals("12:34", RecordingTimeFormatter.format(elapsedMillis = 754_000L))
    }
}
