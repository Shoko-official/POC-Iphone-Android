package com.poc.camera.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProcessingQueuePolicyTest {

    // -- canCapture -------------------------------------------------------------------------

    @Test
    fun `capturing always blocks a new capture regardless of processing count`() {
        assertFalse(ProcessingQueuePolicy.canCapture(isCapturing = true, processingCount = 0))
        assertFalse(ProcessingQueuePolicy.canCapture(isCapturing = true, processingCount = 1))
        assertFalse(ProcessingQueuePolicy.canCapture(isCapturing = true, processingCount = 2))
    }

    @Test
    fun `capture is allowed while not capturing and below the default max`() {
        assertTrue(ProcessingQueuePolicy.canCapture(isCapturing = false, processingCount = 0))
        assertTrue(ProcessingQueuePolicy.canCapture(isCapturing = false, processingCount = 1))
    }

    @Test
    fun `capture is refused once the default max concurrent processing jobs are in flight`() {
        assertFalse(ProcessingQueuePolicy.canCapture(isCapturing = false, processingCount = 2))
    }

    @Test
    fun `capture stays refused above the default max`() {
        assertFalse(ProcessingQueuePolicy.canCapture(isCapturing = false, processingCount = 3))
    }

    @Test
    fun `a custom max concurrent processing bound is respected`() {
        assertTrue(ProcessingQueuePolicy.canCapture(isCapturing = false, processingCount = 0, maxConcurrentProcessing = 1))
        assertFalse(ProcessingQueuePolicy.canCapture(isCapturing = false, processingCount = 1, maxConcurrentProcessing = 1))
    }

    @Test
    fun `a zero max concurrent processing bound refuses every capture while idle`() {
        assertFalse(ProcessingQueuePolicy.canCapture(isCapturing = false, processingCount = 0, maxConcurrentProcessing = 0))
    }

    @Test
    fun `default max concurrent processing is 2`() {
        assertEquals(2, ProcessingQueuePolicy.DEFAULT_MAX_CONCURRENT_PROCESSING)
    }

    // -- stageLabel -------------------------------------------------------------------------

    @Test
    fun `stageLabel maps each stage to its own label`() {
        assertEquals(
            "Capturing",
            ProcessingQueuePolicy.stageLabel(
                stage = ProcessingStage.Capturing,
                capturingLabel = "Capturing",
                mergingLabel = "Merging",
                finishingLabel = "Finishing",
                savingLabel = "Saving",
            ),
        )
        assertEquals(
            "Merging",
            ProcessingQueuePolicy.stageLabel(
                stage = ProcessingStage.Merging,
                capturingLabel = "Capturing",
                mergingLabel = "Merging",
                finishingLabel = "Finishing",
                savingLabel = "Saving",
            ),
        )
        assertEquals(
            "Finishing",
            ProcessingQueuePolicy.stageLabel(
                stage = ProcessingStage.Finishing,
                capturingLabel = "Capturing",
                mergingLabel = "Merging",
                finishingLabel = "Finishing",
                savingLabel = "Saving",
            ),
        )
        assertEquals(
            "Saving",
            ProcessingQueuePolicy.stageLabel(
                stage = ProcessingStage.Saving,
                capturingLabel = "Capturing",
                mergingLabel = "Merging",
                finishingLabel = "Finishing",
                savingLabel = "Saving",
            ),
        )
    }

    // -- showsCountSuffix ---------------------------------------------------------------------

    @Test
    fun `no count suffix while zero or one job is in flight`() {
        assertFalse(ProcessingQueuePolicy.showsCountSuffix(0))
        assertFalse(ProcessingQueuePolicy.showsCountSuffix(1))
    }

    @Test
    fun `count suffix shows once more than one job is in flight`() {
        assertTrue(ProcessingQueuePolicy.showsCountSuffix(2))
        assertTrue(ProcessingQueuePolicy.showsCountSuffix(3))
    }

    // -- chipText ---------------------------------------------------------------------------

    @Test
    fun `chipText is the plain stage label for a single job`() {
        val text = ProcessingQueuePolicy.chipText(
            stage = ProcessingStage.Merging,
            processingCount = 1,
            capturingLabel = "Capturing",
            mergingLabel = "Merging",
            finishingLabel = "Finishing",
            savingLabel = "Saving",
            countSuffixTemplate = "%1\$s x%2\$d",
        )

        assertEquals("Merging", text)
    }

    @Test
    fun `chipText appends the count suffix once more than one job is in flight`() {
        val text = ProcessingQueuePolicy.chipText(
            stage = ProcessingStage.Finishing,
            processingCount = 2,
            capturingLabel = "Capturing",
            mergingLabel = "Merging",
            finishingLabel = "Finishing",
            savingLabel = "Saving",
            countSuffixTemplate = "%1\$s x%2\$d",
        )

        assertEquals("Finishing x2", text)
    }

    @Test
    fun `chipText reflects the most recent stage passed in, independent of which job it came from`() {
        val text = ProcessingQueuePolicy.chipText(
            stage = ProcessingStage.Saving,
            processingCount = 3,
            capturingLabel = "Capturing",
            mergingLabel = "Merging",
            finishingLabel = "Finishing",
            savingLabel = "Saving",
            countSuffixTemplate = "%1\$s x%2\$d",
        )

        assertEquals("Saving x3", text)
    }
}
