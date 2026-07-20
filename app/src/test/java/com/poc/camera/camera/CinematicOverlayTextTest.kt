package com.poc.camera.camera

import org.junit.Assert.assertEquals
import org.junit.Test

class CinematicOverlayTextTest {

    private val fps24Label = "24 fps"
    private val fpsDefaultLabel = "Default fps"
    private val stabilizedTemplate = "%1\$s - stabilized"
    private val unstabilizedTemplate = "%1\$s - unstabilized"
    private val rangeSuffixTemplate = "%1\$s - %2\$s"
    private val hlg10Label = "HLG10"
    private val sdrLabel = "SDR"

    @Test
    fun formatsTwentyFourFpsStabilized() {
        val text = CinematicOverlayText.format(
            config = CinematicConfig(use24Fps = true, stabilization = StabilizationChoice.ON),
            fps24Label = fps24Label,
            fpsDefaultLabel = fpsDefaultLabel,
            stabilizedTemplate = stabilizedTemplate,
            unstabilizedTemplate = unstabilizedTemplate,
        )

        assertEquals("24 fps - stabilized", text)
    }

    @Test
    fun formatsDefaultFpsUnstabilized() {
        val text = CinematicOverlayText.format(
            config = CinematicConfig(use24Fps = false, stabilization = StabilizationChoice.OFF),
            fps24Label = fps24Label,
            fpsDefaultLabel = fpsDefaultLabel,
            stabilizedTemplate = stabilizedTemplate,
            unstabilizedTemplate = unstabilizedTemplate,
        )

        assertEquals("Default fps - unstabilized", text)
    }

    @Test
    fun formatsTwentyFourFpsUnstabilized() {
        val text = CinematicOverlayText.format(
            config = CinematicConfig(use24Fps = true, stabilization = StabilizationChoice.OFF),
            fps24Label = fps24Label,
            fpsDefaultLabel = fpsDefaultLabel,
            stabilizedTemplate = stabilizedTemplate,
            unstabilizedTemplate = unstabilizedTemplate,
        )

        assertEquals("24 fps - unstabilized", text)
    }

    @Test
    fun formatsDefaultFpsStabilized() {
        val text = CinematicOverlayText.format(
            config = CinematicConfig(use24Fps = false, stabilization = StabilizationChoice.ON),
            fps24Label = fps24Label,
            fpsDefaultLabel = fpsDefaultLabel,
            stabilizedTemplate = stabilizedTemplate,
            unstabilizedTemplate = unstabilizedTemplate,
        )

        assertEquals("Default fps - stabilized", text)
    }

    @Test
    fun omitsRangeSuffixWhenRangeLabelIsNull() {
        val text = CinematicOverlayText.format(
            config = CinematicConfig(use24Fps = true, stabilization = StabilizationChoice.ON),
            fps24Label = fps24Label,
            fpsDefaultLabel = fpsDefaultLabel,
            stabilizedTemplate = stabilizedTemplate,
            unstabilizedTemplate = unstabilizedTemplate,
        )

        assertEquals("24 fps - stabilized", text)
    }

    @Test
    fun appendsHlg10SuffixWhenRangeLabelIsHlg10() {
        val text = CinematicOverlayText.format(
            config = CinematicConfig(use24Fps = true, stabilization = StabilizationChoice.ON),
            fps24Label = fps24Label,
            fpsDefaultLabel = fpsDefaultLabel,
            stabilizedTemplate = stabilizedTemplate,
            unstabilizedTemplate = unstabilizedTemplate,
            rangeLabel = hlg10Label,
            rangeSuffixTemplate = rangeSuffixTemplate,
        )

        assertEquals("24 fps - stabilized - HLG10", text)
    }

    @Test
    fun appendsSdrSuffixWhenRangeLabelIsSdr() {
        val text = CinematicOverlayText.format(
            config = CinematicConfig(use24Fps = false, stabilization = StabilizationChoice.OFF),
            fps24Label = fps24Label,
            fpsDefaultLabel = fpsDefaultLabel,
            stabilizedTemplate = stabilizedTemplate,
            unstabilizedTemplate = unstabilizedTemplate,
            rangeLabel = sdrLabel,
            rangeSuffixTemplate = rangeSuffixTemplate,
        )

        assertEquals("Default fps - unstabilized - SDR", text)
    }
}
