package com.poc.camera.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class MediaNamingTest {

    @Test
    fun displayNameFormatsTimestampAsUtcFileName() {
        val result = MediaNaming.displayName(timestampMillis = 1700000000000L, prefix = "IMG_", extension = ".jpg")

        assertEquals("IMG_20231114_221320000.jpg", result)
    }

    @Test
    fun displayNameIncludesMillisecondPrecision() {
        val result = MediaNaming.displayName(timestampMillis = 1700000000123L, prefix = "IMG_", extension = ".jpg")

        assertEquals("IMG_20231114_221320123.jpg", result)
    }

    @Test
    fun displayNameDiffersForDifferentTimestamps() {
        val first = MediaNaming.displayName(timestampMillis = 1700000000000L, prefix = "IMG_", extension = ".jpg")
        val second = MediaNaming.displayName(timestampMillis = 1700000001000L, prefix = "IMG_", extension = ".jpg")

        assertNotEquals(first, second)
    }

    @Test
    fun displayNameHonorsVideoPrefixAndExtension() {
        val result = MediaNaming.displayName(timestampMillis = 1700000000000L, prefix = "VID_", extension = ".mp4")

        assertEquals("VID_20231114_221320000.mp4", result)
    }
}
