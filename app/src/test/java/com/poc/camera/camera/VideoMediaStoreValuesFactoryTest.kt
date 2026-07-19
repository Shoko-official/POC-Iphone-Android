package com.poc.camera.camera

import org.junit.Assert.assertEquals
import org.junit.Test

class VideoMediaStoreValuesFactoryTest {

    @Test
    fun createBuildsExpectedValues() {
        val result = VideoMediaStoreValuesFactory.create(timestampMillis = 1700000000000L)

        assertEquals("VID_20231114_221320000.mp4", result.displayName)
        assertEquals("video/mp4", result.mimeType)
        assertEquals("Movies/POC-Camera", result.relativePath)
    }

    @Test
    fun createHonorsCustomRelativePath() {
        val result = VideoMediaStoreValuesFactory.create(
            timestampMillis = 1700000000000L,
            relativePath = "Movies/Custom",
        )

        assertEquals("Movies/Custom", result.relativePath)
    }
}
