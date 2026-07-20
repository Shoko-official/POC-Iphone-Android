package com.poc.camera.camera

import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Test

class ExifMetadataTest {

    @Test
    fun toExifAttributesFormatsDateTimeTagsInUtc() {
        val metadata = ExifMetadata(captureTimestampMillis = 1700000000000L, widthPx = 4032, heightPx = 3024)

        val result = metadata.toExifAttributes(timeZone = TimeZone.getTimeZone("UTC"))

        assertEquals("2023:11:14 22:13:20", result[ExifMetadata.TAG_DATETIME])
        assertEquals("2023:11:14 22:13:20", result[ExifMetadata.TAG_DATETIME_ORIGINAL])
        assertEquals("2023:11:14 22:13:20", result[ExifMetadata.TAG_DATETIME_DIGITIZED])
    }

    @Test
    fun toExifAttributesFormatsDateTimeTagsInGivenLocalZone() {
        val metadata = ExifMetadata(captureTimestampMillis = 1700000000000L, widthPx = 4032, heightPx = 3024)

        val eastern = metadata.toExifAttributes(timeZone = TimeZone.getTimeZone("America/New_York"))
        val tokyo = metadata.toExifAttributes(timeZone = TimeZone.getTimeZone("Asia/Tokyo"))

        // Same instant, different EXIF wall-clock strings - proves formatting honors the
        // explicit zone rather than always falling back to UTC or the host default.
        assertEquals("2023:11:14 17:13:20", eastern[ExifMetadata.TAG_DATETIME])
        assertEquals("2023:11:15 07:13:20", tokyo[ExifMetadata.TAG_DATETIME])
    }

    @Test
    fun toExifAttributesMapsDimensions() {
        val metadata = ExifMetadata(captureTimestampMillis = 1700000000000L, widthPx = 4032, heightPx = 3024)

        val result = metadata.toExifAttributes(timeZone = TimeZone.getTimeZone("UTC"))

        assertEquals("4032", result[ExifMetadata.TAG_PIXEL_X_DIMENSION])
        assertEquals("3024", result[ExifMetadata.TAG_PIXEL_Y_DIMENSION])
    }

    @Test
    fun toExifAttributesSetsNormalOrientation() {
        val metadata = ExifMetadata(captureTimestampMillis = 1700000000000L, widthPx = 4032, heightPx = 3024)

        val result = metadata.toExifAttributes(timeZone = TimeZone.getTimeZone("UTC"))

        assertEquals("1", result[ExifMetadata.TAG_ORIENTATION])
    }

    @Test
    fun toExifAttributesDefaultsSoftwareTag() {
        val metadata = ExifMetadata(captureTimestampMillis = 1700000000000L, widthPx = 4032, heightPx = 3024)

        val result = metadata.toExifAttributes(timeZone = TimeZone.getTimeZone("UTC"))

        assertEquals("POC Camera", result[ExifMetadata.TAG_SOFTWARE])
        assertEquals("POC Camera", ExifMetadata.DEFAULT_SOFTWARE)
    }

    @Test
    fun toExifAttributesHonorsCustomSoftwareTag() {
        val metadata = ExifMetadata(
            captureTimestampMillis = 1700000000000L,
            widthPx = 4032,
            heightPx = 3024,
            software = "Custom Software",
        )

        val result = metadata.toExifAttributes(timeZone = TimeZone.getTimeZone("UTC"))

        assertEquals("Custom Software", result[ExifMetadata.TAG_SOFTWARE])
    }

    @Test
    fun toExifAttributesMapIsComplete() {
        val metadata = ExifMetadata(captureTimestampMillis = 1700000000000L, widthPx = 4032, heightPx = 3024)

        val result = metadata.toExifAttributes(timeZone = TimeZone.getTimeZone("UTC"))

        assertEquals(
            setOf(
                ExifMetadata.TAG_DATETIME,
                ExifMetadata.TAG_DATETIME_ORIGINAL,
                ExifMetadata.TAG_DATETIME_DIGITIZED,
                ExifMetadata.TAG_ORIENTATION,
                ExifMetadata.TAG_PIXEL_X_DIMENSION,
                ExifMetadata.TAG_PIXEL_Y_DIMENSION,
                ExifMetadata.TAG_SOFTWARE,
            ),
            result.keys,
        )
    }
}
