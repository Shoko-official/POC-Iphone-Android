package com.poc.camera.camera

import java.util.Locale

/**
 * Per-phase latency breakdown for one burst, end to end: raw JPEG acquisition, JPEG decode,
 * merge, finishing and save (issue #87). Pure data + formatting, no Android dependencies, so
 * the breakdown string is unit tested without Robolectric.
 *
 * [captureMillis] and [decodeMillis] are sums across every frame in the burst - see
 * [Builder], which [BurstController] drives once per frame as its decode executor finishes
 * each one (see that class's "decode pipelining" doc). [mergeMillis], [finishMillis] and
 * [saveMillis] are each a single span CameraScreen measures itself around its own
 * [ProcessingStage] transitions, since those run once per burst rather than once per frame.
 *
 * [firstFrameMillis] isolates just the first frame's acquire+decode time, separate from the
 * summed [captureMillis]/[decodeMillis] above. It exists purely to answer a question, not to
 * drive behaviour: whether the first frame of a burst is consistently slower than the rest
 * (a "cold JPEG pipeline" effect a pre-warm capture could hide). No pre-warm is implemented
 * here - firing a real takePicture at arm time just to discard it would waste a frame and
 * real capture time for a benefit that has never actually been measured on a device. This
 * field is what would make that decision evidence-based once one is.
 */
data class CaptureSpans(
    val captureMillis: Long = 0,
    val decodeMillis: Long = 0,
    val mergeMillis: Long = 0,
    val finishMillis: Long = 0,
    val saveMillis: Long = 0,
    val firstFrameMillis: Long = 0,
) {
    val totalMillis: Long
        get() = captureMillis + decodeMillis + mergeMillis + finishMillis + saveMillis

    /**
     * "capture 820ms - decode 310ms - merge 1.2s - finish 0.9s - save 150ms" - every phase,
     * fixed order, each formatted by [formatMillis]. Logged unconditionally (Log.d) and
     * appended to the success snackbar only when Settings' verboseTimings switch is on - see
     * CameraScreen's capture flows.
     */
    fun formatBreakdown(): String = listOf(
        "capture" to captureMillis,
        "decode" to decodeMillis,
        "merge" to mergeMillis,
        "finish" to finishMillis,
        "save" to saveMillis,
    ).joinToString(separator = " - ") { (label, millis) -> "$label ${formatMillis(millis)}" }

    /**
     * Accumulates per-frame capture/decode timings as a burst's frames finish decoding, then
     * hands the sums to [build] alongside the caller-measured merge/finish/save spans.
     *
     * Driven exclusively from [BurstController]'s single-threaded decode executor, where
     * frames complete strictly in capture order (see that class's doc) - so this is
     * deliberately NOT synchronized. A second writer thread reaching [addFrame] concurrently
     * would be a bug to fix at the call site, not a race worth paying lock overhead to paper
     * over here.
     */
    class Builder {
        private var captureMillis = 0L
        private var decodeMillis = 0L
        private var firstFrameMillis = 0L
        private var frameCount = 0

        fun addFrame(captureMillis: Long, decodeMillis: Long) {
            if (frameCount == 0) firstFrameMillis = captureMillis + decodeMillis
            this.captureMillis += captureMillis
            this.decodeMillis += decodeMillis
            frameCount++
        }

        fun build(mergeMillis: Long = 0, finishMillis: Long = 0, saveMillis: Long = 0): CaptureSpans = CaptureSpans(
            captureMillis = captureMillis,
            decodeMillis = decodeMillis,
            mergeMillis = mergeMillis,
            finishMillis = finishMillis,
            saveMillis = saveMillis,
            firstFrameMillis = firstFrameMillis,
        )
    }

    companion object {
        private const val SECOND_THRESHOLD_MILLIS = 1_000L

        /** Below the threshold as whole milliseconds ("820ms"); at or above, seconds to one decimal place ("1.2s"). */
        fun formatMillis(millis: Long): String = if (millis >= SECOND_THRESHOLD_MILLIS) {
            String.format(Locale.ROOT, "%.1fs", millis / 1000.0)
        } else {
            "${millis}ms"
        }
    }
}
