package com.poc.camera.camera

import androidx.camera.core.ImageProxy
import com.poc.camera.pipeline.Frame
import com.poc.camera.pipeline.FrameRingBuffer
import com.poc.camera.pipeline.YuvToArgbConverter
import java.nio.ByteBuffer
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Feeds ImageAnalysis frames into a burst, in one of two modes.
 *
 * When idle, frames are closed immediately without conversion so preview stays cheap.
 *
 * Single-EV mode ([arm]): the next [frameCount] frames are decoded to ARGB, collected
 * in a bounded [FrameRingBuffer], and handed to the completion callback.
 *
 * Bracketed HDR mode ([armBracketed]): a background worker walks an exposure-bracket
 * plan, driving the camera's exposure compensation to each step via a caller-supplied
 * lambda (which sets the index and blocks on its ListenableFuture), then collecting
 * [ExposureBracket.DEFAULT_FRAMES_PER_EV] frames tagged with that step's EV before
 * advancing. Compensation is restored to 0 when the burst finishes or fails. Frames
 * arriving mid-flight when an exposure change lands are drained so each collected
 * frame reflects the requested exposure as closely as the future-await settle allows.
 */
class BurstController(private val frameCount: Int = DEFAULT_FRAME_COUNT) {

    private val buffer = FrameRingBuffer(capacity = frameCount)
    private val armed = AtomicBoolean(false)
    private val bracketed = AtomicBoolean(false)

    @Volatile
    private var onComplete: ((List<Frame>) -> Unit)? = null

    // Bracketed-mode state, only touched while [bracketed] is true.
    private val frameQueue = LinkedBlockingQueue<Frame>()

    @Volatile
    private var bracketWorker: Thread? = null

    /** A bracketed HDR burst: frames with their parallel per-frame relative EVs. */
    data class BracketedBurst(val frames: List<Frame>, val evs: List<Double>)

    val isArmed: Boolean
        get() = armed.get()

    fun arm(onComplete: (List<Frame>) -> Unit) {
        buffer.clear()
        this.onComplete = onComplete
        bracketed.set(false)
        armed.set(true)
    }

    /**
     * Arms a bracketed HDR burst over [steps], capturing [framesPerEv] frames at each.
     *
     * @param setExposureIndex sets the camera exposure-compensation index and blocks
     *   until the change is applied (called off the main thread by the worker).
     * @param onComplete receives the collected frames and their per-frame EVs.
     */
    fun armBracketed(
        steps: List<ExposureBracket.BracketStep>,
        framesPerEv: Int,
        setExposureIndex: (Int) -> Unit,
        onComplete: (BracketedBurst) -> Unit,
    ) {
        require(steps.isNotEmpty()) { "steps must not be empty" }
        require(framesPerEv >= 1) { "framesPerEv must be >= 1" }
        frameQueue.clear()
        bracketed.set(true)
        armed.set(true)

        val worker = Thread({
            val frames = ArrayList<Frame>(steps.size * framesPerEv)
            val evs = ArrayList<Double>(steps.size * framesPerEv)
            try {
                for (step in steps) {
                    setExposureIndex(step.exposureIndex)
                    // Discard frames captured before the exposure change landed.
                    frameQueue.clear()
                    var collected = 0
                    while (collected < framesPerEv) {
                        val frame = frameQueue.poll(FRAME_TIMEOUT_MS, TimeUnit.MILLISECONDS) ?: break
                        frames.add(frame)
                        evs.add(step.actualEv)
                        collected++
                    }
                }
            } finally {
                // Always restore neutral exposure, even on interruption or timeout.
                runCatching { setExposureIndex(0) }
                armed.set(false)
                bracketed.set(false)
                bracketWorker = null
                onComplete(BracketedBurst(frames, evs))
            }
        }, "hdr-bracket-burst")
        bracketWorker = worker
        worker.start()
    }

    fun onFrame(image: ImageProxy) {
        if (!armed.get()) {
            image.close()
            return
        }
        if (bracketed.get()) {
            val frame = try {
                image.toFrame()
            } finally {
                image.close()
            }
            frameQueue.offer(frame)
            return
        }

        try {
            buffer.add(image.toFrame())
        } finally {
            image.close()
        }

        if (buffer.size >= frameCount) {
            armed.set(false)
            val callback = onComplete
            onComplete = null
            callback?.invoke(buffer.snapshot())
        }
    }

    private fun ImageProxy.toFrame(): Frame {
        val yPlane = planes[0]
        val uPlane = planes[1]
        val vPlane = planes[2]
        val argb = YuvToArgbConverter.convert(
            width = width,
            height = height,
            yBytes = yPlane.buffer.toByteArray(),
            yRowStride = yPlane.rowStride,
            yPixelStride = yPlane.pixelStride,
            uBytes = uPlane.buffer.toByteArray(),
            uRowStride = uPlane.rowStride,
            uPixelStride = uPlane.pixelStride,
            vBytes = vPlane.buffer.toByteArray(),
            vRowStride = vPlane.rowStride,
            vPixelStride = vPlane.pixelStride,
        )
        return Frame(
            width = width,
            height = height,
            argb = argb,
            timestampMillis = System.currentTimeMillis(),
        )
    }

    private fun ByteBuffer.toByteArray(): ByteArray {
        val duplicate = duplicate().apply { rewind() }
        val bytes = ByteArray(duplicate.remaining())
        duplicate.get(bytes)
        return bytes
    }

    companion object {
        const val DEFAULT_FRAME_COUNT = 6

        // Upper bound on how long the worker waits for the next analysis frame before
        // giving up on a bracket step (e.g. the camera was unbound mid-burst).
        private const val FRAME_TIMEOUT_MS = 2_000L
    }
}
