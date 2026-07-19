package com.poc.camera.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class PhotoCaptureNamingTest {

    @Test
    fun displayNameFormatsTimestampAsUtcFileName() {
        val result = PhotoCaptureNaming.displayName(timestampMillis = 1700000000000L)

        assertEquals("IMG_20231114_221320000.jpg", result)
    }

    @Test
    fun displayNameIncludesMillisecondPrecision() {
        val result = PhotoCaptureNaming.displayName(timestampMillis = 1700000000123L)

        assertEquals("IMG_20231114_221320123.jpg", result)
    }

    @Test
    fun displayNameDiffersForDifferentTimestamps() {
        val first = PhotoCaptureNaming.displayName(timestampMillis = 1700000000000L)
        val second = PhotoCaptureNaming.displayName(timestampMillis = 1700000001000L)

        assertNotEquals(first, second)
    }
}
