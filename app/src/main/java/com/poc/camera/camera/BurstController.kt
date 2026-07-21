package com.poc.camera.camera

import com.poc.camera.pipeline.Frame
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Drives a burst as a sequence of full-resolution [ImageCapture][androidx.camera.core.ImageCapture]
 * frames, in one of two modes.
 *
 * A [FrameCapture] supplied at arm time captures one full-resolution frame asynchronously,
 * split into a fast **acquire** phase and a slower **decode** phase - see "decode pipelining"
 * below. Both modes run on a background worker that fires captures back-to-back.
 *
 * Single-EV mode ([arm]): [frameCount] frames are captured and handed to the completion
 * callback with the capture spans.
 *
 * Bracketed HDR mode ([armBracketed]): the worker walks an exposure-bracket plan, driving
 * the camera's exposure compensation to each step via a caller-supplied blocking lambda,
 * then captures [framesPerEv] frames tagged with that step's EV before advancing.
 * Compensation is restored to 0 when the burst finishes or fails.
 *
 * Lens-agnostic (issue #71): [FrameCapture] just drives whichever [ImageCapture][androidx.camera.core.ImageCapture]
 * CameraPreview currently has bound, so a burst - and downstream frame-merge/guided-compare
 * (see com.poc.camera.pipeline.BurstMergePipeline, com.poc.camera.compare.GuidedCompareFlow) -
 * works identically whether that use case is bound to the back or front lens. No facing-
 * specific casing exists or is needed anywhere in this class or the merge pipelines.
 *
 * ## Decode pipelining (issue #87)
 *
 * Before issue #87 the worker awaited BOTH capture and decode (one latch covering both)
 * before firing the next `takePicture` - decode never overlapped anything. That's wasted
 * time: the camera session is free to expose the next frame as soon as its JPEG bytes are
 * off the sensor, well before those bytes are decoded into an ARGB [Frame]. Now the worker
 * awaits only the **acquire** latch (fast: an [android.media.Image] plane copy) before
 * firing the next capture; decode (slow: JPEG inflate + resample + rotate) is posted to a
 * caller-supplied [Executor] and runs concurrently with the next frame's acquire.
 *
 * That executor is expected to be single-threaded (see CameraScreen's remembered
 * `burstDecodeExecutor`), which gives two things this class relies on: decodes complete in
 * the same order they were submitted (capture order), and [CaptureSpans.Builder] never needs
 * synchronization since only one thread ever calls it. [joinDecodes] still awaits decodes in
 * that same order at the end - a burst never reports back until every frame has settled.
 *
 * Frames are still assembled from index-addressed slots, not decode-completion order, as
 * defense in depth against that single-threaded assumption ever changing silently.
 *
 * ### The memory bound
 *
 * Letting acquire run arbitrarily far ahead of decode would mean arbitrarily many undecoded
 * JPEG byte arrays alive at once. [MAX_UNDECODED_JPEGS_IN_FLIGHT] (a `Semaphore`, acquired
 * before firing a capture and released once that frame's decode finishes) caps this at 2:
 * enough for decode of frame N to overlap acquire of frame N+1 (the actual latency win)
 * without acquisition running away from decode on a slow device. JPEG bytes are ~2-6 MB each
 * - two of them is cheap next to a single decoded [Frame] (~50 MB at [BurstImageGeometry]'s
 * native bound), so this is a throttle on how far ahead of decode capture can get, not a
 * meaningful contributor to peak memory on its own.
 *
 * ### Pre-warm (or rather, why there isn't one)
 *
 * A cheap "pre-warm" would fire one `takePicture` at arm time and discard it, hoping to
 * absorb a cold-pipeline first-frame penalty before the user's frames start. That wastes a
 * real frame and real time for a benefit nobody has actually measured on a device - this POC
 * has no CI device farm this change could run against. Instead, [CaptureSpans.firstFrameMillis]
 * isolates the first frame's acquire+decode time so the verbose-timings breakdown can show
 * whether a cold-pipeline effect is real before anyone spends a frame guarding against it.
 *
 * ### A documented behaviour shift: acquire failure vs. decode failure
 *
 * Before pipelining, a single latch covered both phases, so "this frame failed" was known
 * synchronously and could stop a bracketed EV step's inner loop immediately (see
 * [armBracketed]'s `while` loop). Now only ACQUIRE failure is known synchronously; decode
 * failure is only known once its (async) future settles, by which point the worker has
 * already moved on to later captures/steps. So the acquire/decode split changes what "stop
 * trying more frames for this EV step" reacts to: an acquire failure (camera/hardware level,
 * the more disruptive kind) still ends that step's inner loop early, exactly as before; a
 * decode failure (a corrupt or slow-to-decode JPEG) no longer can, and instead just drops
 * that one frame from the final result once [joinDecodes] settles - "failure of any decode
 * ends the burst leg with the frames that succeeded," not the whole burst.
 */
class BurstController(private val frameCount: Int = DEFAULT_FRAME_COUNT) {

    private val armed = AtomicBoolean(false)

    @Volatile
    private var worker: Thread? = null

    /** Captures a single full-resolution frame, in two phases. */
    fun interface FrameCapture {
        /**
         * Fires the capture and hands back, fast, either a failure or a **decode thunk** - a
         * closure that performs the (slow) JPEG decode when invoked. [onResult] is expected
         * to fire as soon as the raw bytes are off the [android.media.Image] (the camera
         * session is free again at that point), NOT once decoded - the thunk itself is what
         * defers the actual decode work to [arm]/[armBracketed]'s `decodeExecutor`. See this
         * class's "decode pipelining" doc.
         */
        fun capture(onResult: (Result<() -> Frame>) -> Unit)
    }

    /** A single-EV burst: the collected frames, how long capturing them took, and the phase breakdown. */
    data class BurstResult(val frames: List<Frame>, val captureSpanMillis: Long, val spans: CaptureSpans)

    /** A bracketed HDR burst: frames, their parallel per-frame relative EVs, the capture span, and the phase breakdown. */
    data class BracketedBurst(
        val frames: List<Frame>,
        val evs: List<Double>,
        val captureSpanMillis: Long,
        val spans: CaptureSpans,
    )

    val isArmed: Boolean
        get() = armed.get()

    /** Captures [frameCount] full-resolution frames sequentially, then reports them. */
    fun arm(captureFrame: FrameCapture, decodeExecutor: Executor, onComplete: (BurstResult) -> Unit) {
        if (!armed.compareAndSet(false, true)) return
        val burstWorker = Thread({
            val start = System.currentTimeMillis()
            val semaphore = Semaphore(MAX_UNDECODED_JPEGS_IN_FLIGHT)
            // Fixed-size, never resized once created: every write is an index-set from
            // exactly one of two threads (this worker before pipelining begins each slot;
            // that slot's own decode task afterwards), so there is no concurrent structural
            // modification to race - see class doc.
            val slots = arrayOfNulls<Result<Frame>?>(frameCount)
            val decodeLatches = ArrayList<CountDownLatch>(frameCount)
            val spanBuilder = CaptureSpans.Builder()
            try {
                for (index in 0 until frameCount) {
                    acquireOne(captureFrame, decodeExecutor, semaphore, slots, index, decodeLatches, spanBuilder)
                }
                joinDecodes(decodeLatches)
            } finally {
                armed.set(false)
                worker = null
            }
            val frames = slots.mapNotNull { it?.getOrNull() }
            onComplete(BurstResult(frames, System.currentTimeMillis() - start, spanBuilder.build()))
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
     * @param captureFrame captures a single full-resolution frame (see [FrameCapture]).
     * @param decodeExecutor where each frame's decode phase runs - see class doc.
     * @param onComplete receives the collected frames, their per-frame EVs, the span, and the phase breakdown.
     */
    fun armBracketed(
        steps: List<ExposureBracket.BracketStep>,
        framesPerEv: Int,
        setExposureIndex: (Int) -> Unit,
        captureFrame: FrameCapture,
        decodeExecutor: Executor,
        onComplete: (BracketedBurst) -> Unit,
    ) {
        require(steps.isNotEmpty()) { "steps must not be empty" }
        require(framesPerEv >= 1) { "framesPerEv must be >= 1" }
        if (!armed.compareAndSet(false, true)) return

        val burstWorker = Thread({
            val start = System.currentTimeMillis()
            val semaphore = Semaphore(MAX_UNDECODED_JPEGS_IN_FLIGHT)
            // Upper bound: every step may fall short of framesPerEv (an acquire failure
            // breaks that step's loop early - see class doc), never exceed it. Unused
            // trailing slots stay null and are dropped exactly like a decode failure below.
            val maxSlots = steps.size * framesPerEv
            val slots = arrayOfNulls<Result<Frame>?>(maxSlots)
            val evSlots = DoubleArray(maxSlots)
            val decodeLatches = ArrayList<CountDownLatch>(maxSlots)
            val spanBuilder = CaptureSpans.Builder()
            var index = 0
            try {
                for (step in steps) {
                    setExposureIndex(step.exposureIndex)
                    var attempted = 0
                    while (attempted < framesPerEv) {
                        evSlots[index] = step.actualEv
                        val acquired =
                            acquireOne(captureFrame, decodeExecutor, semaphore, slots, index, decodeLatches, spanBuilder)
                        if (!acquired) break
                        index++
                        attempted++
                    }
                }
                joinDecodes(decodeLatches)
            } finally {
                // Always restore neutral exposure, even on interruption or capture failure.
                runCatching { setExposureIndex(0) }
                armed.set(false)
                worker = null
            }
            val frames = ArrayList<Frame>(slots.size)
            val evs = ArrayList<Double>(slots.size)
            for (i in slots.indices) {
                val frame = slots[i]?.getOrNull() ?: continue
                frames.add(frame)
                evs.add(evSlots[i])
            }
            onComplete(BracketedBurst(frames, evs, System.currentTimeMillis() - start, spanBuilder.build()))
        }, "hdr-bracket-burst")
        worker = burstWorker
        burstWorker.start()
    }

    // Fires one capture's fast acquire phase and, on success, posts its decode to
    // decodeExecutor - returns once acquisition (not decode) settles, so the worker can move
    // on to the next capture immediately (see class doc). Returns false on acquire timeout
    // or failure, mirroring the pre-pipelining captureOne's null return for that case: a
    // slot is only ever reserved (and its decode only ever started) for a capture whose
    // acquire actually succeeded. Once acquisition succeeds, though, this method commits to
    // decoding it - whether that frame survives the burst is now the async decode's call,
    // not this method's; see the class doc's "documented behaviour shift" section.
    private fun acquireOne(
        captureFrame: FrameCapture,
        decodeExecutor: Executor,
        semaphore: Semaphore,
        slots: Array<Result<Frame>?>,
        index: Int,
        decodeLatches: MutableList<CountDownLatch>,
        spanBuilder: CaptureSpans.Builder,
    ): Boolean {
        // Blocks here - before firing the capture - if MAX_UNDECODED_JPEGS_IN_FLIGHT JPEGs
        // are already acquired-but-undecoded, so acquisition never runs away from decode.
        if (!semaphore.tryAcquire(CAPTURE_TIMEOUT_MS, TimeUnit.MILLISECONDS)) return false

        val latch = CountDownLatch(1)
        val holder = AtomicReference<Result<() -> Frame>?>(null)
        val acquireStart = System.currentTimeMillis()
        captureFrame.capture { result ->
            holder.set(result)
            latch.countDown()
        }
        val settled = latch.await(CAPTURE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        val captureMillis = System.currentTimeMillis() - acquireStart
        val decodeThunk = if (settled) holder.get()?.getOrNull() else null
        if (decodeThunk == null) {
            semaphore.release()
            return false
        }

        val decodeLatch = CountDownLatch(1)
        decodeLatches.add(decodeLatch)
        decodeExecutor.execute {
            val decodeStart = System.currentTimeMillis()
            try {
                val frame = decodeThunk()
                spanBuilder.addFrame(captureMillis, System.currentTimeMillis() - decodeStart)
                slots[index] = Result.success(frame)
            } catch (t: Throwable) {
                slots[index] = Result.failure(t)
            } finally {
                semaphore.release()
                decodeLatch.countDown()
            }
        }
        return true
    }

    // Joins every decode in capture order, so a burst never reports back before all its
    // frames have actually settled. decodeExecutor is single-threaded by contract (see class
    // doc), so a decode stuck past CAPTURE_TIMEOUT_MS blocks every decode queued behind it
    // too - the first timeout here is treated as "the rest didn't happen either" and stops
    // waiting immediately, rather than summing frameCount * CAPTURE_TIMEOUT_MS in the worst
    // case. Slots left unfilled by then simply have no Result, so are dropped exactly like
    // any other decode failure once the caller assembles its frame list.
    private fun joinDecodes(decodeLatches: List<CountDownLatch>) {
        for (decodeLatch in decodeLatches) {
            if (!decodeLatch.await(CAPTURE_TIMEOUT_MS, TimeUnit.MILLISECONDS)) break
        }
    }

    companion object {
        const val DEFAULT_FRAME_COUNT = 6

        // Upper bound on how long the worker waits for a single frame's acquire phase, or
        // for one decode to settle while joining, before giving up on it.
        private const val CAPTURE_TIMEOUT_MS = 5_000L

        // At most this many acquired-but-not-yet-decoded JPEG byte arrays exist at once -
        // see the class doc's "memory bound" section.
        const val MAX_UNDECODED_JPEGS_IN_FLIGHT = 2
    }
}
