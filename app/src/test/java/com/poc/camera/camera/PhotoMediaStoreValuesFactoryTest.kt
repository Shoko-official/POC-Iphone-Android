package com.poc.camera.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotoMediaStoreValuesFactoryTest {

    @Test
    fun createBuildsExpectedValuesWithPendingFlagSupported() {
        val result = PhotoMediaStoreValuesFactory.create(
            timestampMillis = 1700000000000L,
            supportsPendingFlag = true,
        )

        assertEquals("IMG_20231114_221320000.jpg", result.displayName)
        assertEquals("image/jpeg", result.mimeType)
        assertEquals("Pictures/POC-Camera", result.relativePath)
        assertTrue(result.isPending)
    }

    @Test
    fun createOmitsPendingFlagWhenNotSupported() {
        val result = PhotoMediaStoreValuesFactory.create(
            timestampMillis = 1700000000000L,
            supportsPendingFlag = false,
        )

        assertFalse(result.isPending)
    }

    @Test
    fun createHonorsCustomRelativePath() {
        val result = PhotoMediaStoreValuesFactory.create(
            timestampMillis = 1700000000000L,
            supportsPendingFlag = true,
            relativePath = "Pictures/Custom",
        )

        assertEquals("Pictures/Custom", result.relativePath)
    }
}
