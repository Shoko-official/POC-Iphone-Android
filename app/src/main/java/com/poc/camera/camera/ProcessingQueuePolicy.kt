package com.poc.camera.camera

/**
 * Coarse phase of a single burst/portrait job (issue #86), tracked per job and reported on
 * [ProcessingQueuePolicy.chipText]/[ProcessingQueuePolicy.stageLabel] as the MOST RECENT
 * job's stage - CameraScreen holds one shared `currentProcessingStage` var that every job
 * overwrites at its own phase boundaries, so whichever job updates last wins, with no
 * per-job breakdown or percent progress.
 *
 * [Capturing] covers the span [BurstController] is armed - set the moment a capture starts,
 * before [ProcessingQueuePolicy.canCapture] would ever let a second job begin, so it is
 * never actually visible on the processing chip (that only shows once
 * `processingCount > 0`, which is strictly after capturing ends - see
 * [ProcessingQueuePolicy]'s class doc). It is kept as a real state, not a dead enum member,
 * so the type still models a job's whole lifecycle.
 */
enum class ProcessingStage { Capturing, Merging, Finishing, Saving }

/**
 * Pure decision logic for issue #86's capture/processing queue: whether the shutter/volume
 * key may start a new capture, and how the processing [ProcessingStage]
 * should be presented on the StatusChip near the last-capture thumbnail. Kept free of
 * Compose/Android classes, like [ZoomLogic]/[CinematicOverlayText], so the counter bounds
 * and label composition are unit tested without Robolectric; callers resolve every label via
 * `stringResource` first and pass the plain strings in, the same pattern
 * [CinematicOverlayText.format] uses.
 *
 * ## The isCapturing / processingCount split
 *
 * Before issue #86 a single `isBurstInProgress` flag stayed true from the first frame of a
 * burst all the way through merge, finish and save, so the shutter stayed disabled for the
 * whole span even though [BurstController] itself only needs exclusivity
 * while frames are being physically acquired (`armed`, one worker thread, see
 * [BurstController]'s own doc). This split makes that distinction real:
 *
 *  - `isCapturing` mirrors [BurstController.isArmed]: the camera hardware is busy, so
 *    [canCapture] ALWAYS refuses a new capture while it is true, regardless of
 *    [maxConcurrentProcessing].
 *  - `processingCount` counts merge/segment/finish/save jobs running on
 *    `Dispatchers.Default`, now decoupled from hardware capture - once
 *    [BurstController]'s completion callback fires, a new capture can be armed immediately
 *    while the previous burst keeps merging in the background.
 *
 * ## The memory bound
 *
 * [canCapture] still refuses once [maxConcurrentProcessing] jobs are already in flight - an
 * honestly-disclosed MEMORY bound, not a UX failure dressed up as one: every decoded burst
 * frame set stays resident until its merge/finish chain finishes, so two 8MP+ bursts held at
 * once is already a meaningful fraction of a mid-range device's heap, and a third risks OOM
 * on the very devices this POC targets. When the bound is hit the shutter stays disabled
 * with the SAME visible behaviour a capture-busy shutter already had before this issue.
 */
object ProcessingQueuePolicy {

    /** See the class doc's "memory bound" section for why 2, not an arbitrary/unlimited count. */
    const val DEFAULT_MAX_CONCURRENT_PROCESSING = 2

    /**
     * Whether the shutter/volume key may start a new capture right now.
     * Refused while frames are still being acquired ([isCapturing]) or while
     * [maxConcurrentProcessing] merge/finish jobs are already in flight ([processingCount]).
     */
    fun canCapture(
        isCapturing: Boolean,
        processingCount: Int,
        maxConcurrentProcessing: Int = DEFAULT_MAX_CONCURRENT_PROCESSING,
    ): Boolean = !isCapturing && processingCount < maxConcurrentProcessing

    /** Maps [stage] to its caller-resolved label - a plain lookup, no suffix/count logic. */
    fun stageLabel(
        stage: ProcessingStage,
        capturingLabel: String,
        mergingLabel: String,
        finishingLabel: String,
        savingLabel: String,
    ): String = when (stage) {
        ProcessingStage.Capturing -> capturingLabel
        ProcessingStage.Merging -> mergingLabel
        ProcessingStage.Finishing -> finishingLabel
        ProcessingStage.Saving -> savingLabel
    }

    /**
     * Whether the chip should carry the "xN" multiplier suffix: only once more than one job
     * is actually in flight - a single job's stage reads on its own, with no "x1" noise.
     */
    fun showsCountSuffix(processingCount: Int): Boolean = processingCount > 1

    /**
     * The processing StatusChip's full text: [stage]'s label, plus [countSuffixTemplate] (a
     * "%1$s x%2$d" - style placeholder string) once [showsCountSuffix] is true. There is no
     * per-job breakdown beyond the count - tracking per-percent progress across concurrent
     * jobs is out of scope for this coarse indicator.
     */
    fun chipText(
        stage: ProcessingStage,
        processingCount: Int,
        capturingLabel: String,
        mergingLabel: String,
        finishingLabel: String,
        savingLabel: String,
        countSuffixTemplate: String,
    ): String {
        val label = stageLabel(stage, capturingLabel, mergingLabel, finishingLabel, savingLabel)
        return if (showsCountSuffix(processingCount)) {
            String.format(countSuffixTemplate, label, processingCount)
        } else {
            label
        }
    }
}
