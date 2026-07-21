package com.poc.camera.camera

import org.junit.Assert.assertEquals
import org.junit.Test

class CaptureSpansTest {

    // -- formatMillis -------------------------------------------------------------------------

    @Test
    fun `formats sub-second spans as whole milliseconds`() {
        assertEquals("0ms", CaptureSpans.formatMillis(0))
        assertEquals("1ms", CaptureSpans.formatMillis(1))
        assertEquals("820ms", CaptureSpans.formatMillis(820))
        assertEquals("999ms", CaptureSpans.formatMillis(999))
    }

    @Test
    fun `formats spans at or above one second in seconds to one decimal place`() {
        assertEquals("1.0s", CaptureSpans.formatMillis(1_000))
        assertEquals("1.2s", CaptureSpans.formatMillis(1_200))
        assertEquals("12.5s", CaptureSpans.formatMillis(12_500))
    }

    @Test
    fun `rounds to the nearest tenth of a second`() {
        assertEquals("1.1s", CaptureSpans.formatMillis(1_050))
        assertEquals("1.0s", CaptureSpans.formatMillis(1_040))
    }

    // -- totalMillis ----------------------------------------------------------------------------

    @Test
    fun `totalMillis sums every phase`() {
        val spans = CaptureSpans(
            captureMillis = 820,
            decodeMillis = 310,
            mergeMillis = 1_200,
            finishMillis = 900,
            saveMillis = 150,
        )

        assertEquals(820L + 310L + 1_200L + 900L + 150L, spans.totalMillis)
    }

    @Test
    fun `totalMillis is zero for a fresh CaptureSpans`() {
        assertEquals(0L, CaptureSpans().totalMillis)
    }

    // -- formatBreakdown --------------------------------------------------------------------

    @Test
    fun `formatBreakdown lists every phase in order with its own unit`() {
        val spans = CaptureSpans(
            captureMillis = 820,
            decodeMillis = 310,
            mergeMillis = 1_200,
            finishMillis = 900,
            saveMillis = 150,
        )

        assertEquals(
            "capture 820ms - decode 310ms - merge 1.2s - finish 900ms - save 150ms",
            spans.formatBreakdown(),
        )
    }

    @Test
    fun `formatBreakdown shows zero-valued phases rather than omitting them`() {
        val spans = CaptureSpans(captureMillis = 500, decodeMillis = 200)

        assertEquals(
            "capture 500ms - decode 200ms - merge 0ms - finish 0ms - save 0ms",
            spans.formatBreakdown(),
        )
    }

    // -- Builder ------------------------------------------------------------------------------

    @Test
    fun `builder sums capture and decode across every frame added`() {
        val builder = CaptureSpans.Builder()
        builder.addFrame(captureMillis = 100, decodeMillis = 50)
        builder.addFrame(captureMillis = 120, decodeMillis = 60)
        builder.addFrame(captureMillis = 90, decodeMillis = 40)

        val spans = builder.build()

        assertEquals(100L + 120L + 90L, spans.captureMillis)
        assertEquals(50L + 60L + 40L, spans.decodeMillis)
    }

    @Test
    fun `builder captures the first frame's combined acquire and decode time separately`() {
        val builder = CaptureSpans.Builder()
        builder.addFrame(captureMillis = 300, decodeMillis = 150)
        builder.addFrame(captureMillis = 80, decodeMillis = 40)

        val spans = builder.build()

        assertEquals(450L, spans.firstFrameMillis)
    }

    @Test
    fun `builder with no frames added produces all-zero capture and decode spans`() {
        val spans = CaptureSpans.Builder().build()

        assertEquals(0L, spans.captureMillis)
        assertEquals(0L, spans.decodeMillis)
        assertEquals(0L, spans.firstFrameMillis)
    }

    @Test
    fun `builder build passes through the caller-measured merge finish and save spans`() {
        val builder = CaptureSpans.Builder()
        builder.addFrame(captureMillis = 100, decodeMillis = 50)

        val spans = builder.build(mergeMillis = 1_200, finishMillis = 900, saveMillis = 150)

        assertEquals(1_200L, spans.mergeMillis)
        assertEquals(900L, spans.finishMillis)
        assertEquals(150L, spans.saveMillis)
    }
}
