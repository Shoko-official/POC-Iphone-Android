package com.poc.camera.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VideoOverlayLabelTest {

    private val joinTemplate = "%1\$s - %2\$s"

    @Test
    fun combinesBothLabelsWhenPresent() {
        val result = VideoOverlayLabel.combine("HLG10", "1080p", joinTemplate)

        assertEquals("HLG10 - 1080p", result)
    }

    @Test
    fun returnsOnlyRangeLabelWhenQualityLabelIsNull() {
        val result = VideoOverlayLabel.combine("HLG10", null, joinTemplate)

        assertEquals("HLG10", result)
    }

    @Test
    fun returnsOnlyQualityLabelWhenRangeLabelIsNull() {
        val result = VideoOverlayLabel.combine(null, "1080p", joinTemplate)

        assertEquals("1080p", result)
    }

    @Test
    fun returnsNullWhenBothLabelsAreNull() {
        val result = VideoOverlayLabel.combine(null, null, joinTemplate)

        assertNull(result)
    }
}
