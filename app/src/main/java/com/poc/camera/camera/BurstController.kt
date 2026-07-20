package com.poc.camera.camera

import com.poc.camera.pipeline.Frame
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Drives a burst as a sequence of full-resolution [ImageCapture][androidx.camera.core.ImageCapture]
 * frames, in one of two modes.
 *
 * A [FrameCapture] supplied at arm time captures and decodes one full-resolution frame
 * asynchronously. Both modes run on a background worker that fires captures back-to-back,
 * awaiting each callback before the next so at most one decoded frame is in flight, which
 * bounds peak memory. This replaces the retired 720p ImageAnalysis path: real photo
 * quality needs the sensor's resolution, not preview-sized analysis frames.
 *
 * Single-EV mode ([arm]): [frameCount] frames are captured and handed to the completion
 * callback with the capture span.
 *
 * Bracketed HDR mode ([armBracketed]): the worker walks an exposure-bracket plan, driving
 * the camera's exposure compensation to each step via a caller-supplied blocking lambda,
 * then captures [framesPerEv] frames tagged with that step's EV before advancing.
 * Compensation is restored to 0 when the burst finishes or fails.
 */
class BurstController(private val frameCount: Int = DEFAULT_FRAME_COUNT) {

    private val armed = AtomicBoolean(false)

    @Volatile
    private var worker: Thread? = null

    /** Captures and decodes a single full-resolution frame, delivering it (or an error) to [onResult]. */
    fun interface FrameCapture {
        fun capture(onResult: (Result<Frame>) -> Unit)
    }

    /** A single-EV burst: the collected frames and how long capturing them took. */
    data class BurstResult(val frames: List<Frame>, val captureSpanMillis: Long)

    /** A bracketed HDR burst: frames, their parallel per-frame relative EVs, and the capture span. */
    data class BracketedBurst(
        val frames: List<Frame>,
        val evs: List<Double>,
        val captureSpanMillis: Long,
    )

    val isArmed: Boolean
        get() = armed.get()

    /** Captures [frameCount] full-resolution frames sequentially, then reports them. */
    fun arm(captureFrame: FrameCapture, onComplete: (BurstResult) -> Unit) {
        if (!armed.compareAndSet(false, true)) return
        val burstWorker = Thread({
            val start = System.currentTimeMillis()
            val frames = ArrayList<Frame>(frameCount)
            try {
                repeat(frameCount) {
                    val frame = captureOne(captureFrame) ?: return@repeat
                    frames.add(frame)
                }
            } finally {
                armed.set(false)
                worker = null
            }
            onComplete(BurstResult(frames, System.currentTimeMillis() - start))
        }, "burst-capture")
        worker = burstWorker
        burstWorker.start()
    }

    /**
     * Arms a bracketed HDR burst over [steps], capturing [framesPerEv] full-resolution
     * frames at each.
     *
     * @param setExposureIndex sets the camera exposure-compensation index and blocks until
     *   the change is applied (called off the main thread by the worker).
     * @param captureFrame captures and decodes one full-resolution frame.
     * @param onComplete receives the collected frames, their per-frame EVs, and the span.
     */
    fun armBracketed(
        steps: List<ExposureBracket.BracketStep>,
        framesPerEv: Int,
        setExposureIndex: (Int) -> Unit,
        captureFrame: FrameCapture,
        onComplete: (BracketedBurst) -> Unit,
    ) {
        require(steps.isNotEmpty()) { "steps must not be empty" }
        require(framesPerEv >= 1) { "framesPerEv must be >= 1" }
        if (!armed.compareAndSet(false, true)) return

        val burstWorker = Thread({
            val start = System.currentTimeMillis()
            val frames = ArrayList<Frame>(steps.size * framesPerEv)
            val evs = ArrayList<Double>(steps.size * framesPerEv)
            try {
                for (step in steps) {
                    setExposureIndex(step.exposureIndex)
                    var collected = 0
                    while (collected < framesPerEv) {
                        val frame = captureOne(captureFrame) ?: break
                        frames.add(frame)
                        evs.add(step.actualEv)
                        collected++
                    }
                }
            } finally {
                // Always restore neutral exposure, even on interruption or capture failure.
                runCatching { setExposureIndex(0) }
                armed.set(false)
                worker = null
            }
            onComplete(BracketedBurst(frames, evs, System.currentTimeMillis() - start))
        }, "hdr-bracket-burst")
        worker = burstWorker
        burstWorker.start()
    }

    // Fires one capture and blocks the worker until its callback lands, so at most one
    // decoded frame is alive at a time. Returns null on timeout or capture error, which
    // stops the current burst leg rather than hanging.
    private fun captureOne(captureFrame: FrameCapture): Frame? {
        val latch = CountDownLatch(1)
        val holder = AtomicReference<Result<Frame>?>(null)
        captureFrame.capture { result ->
            holder.set(result)
            latch.countDown()
        }
        if (!latch.await(CAPTURE_TIMEOUT_MS, TimeUnit.MILLISECONDS)) return null
        return holder.get()?.getOrNull()
    }

    companion object {
        const val DEFAULT_FRAME_COUNT = 6

        // Upper bound on how long the worker waits for a single full-resolution capture
        // (takePicture + JPEG decode) before giving up on the current burst leg.
        private const val CAPTURE_TIMEOUT_MS = 5_000L
    }
}
