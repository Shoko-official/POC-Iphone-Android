package com.poc.camera.camera

import com.poc.camera.pipeline.Frame
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * BurstController spawns a real background worker thread (see its class doc), so these
 * tests synchronize on the [onComplete] callback via a [CountDownLatch] rather than
 * asserting on state read straight back on the test thread. The decode executor is a
 * synchronous stand-in (runs inline on the calling thread) unless a test is specifically
 * exercising the acquire/decode overlap or the memory bound, matching the "fake
 * executors/synchronous stubs where feasible" approach called out for this issue.
 */
class BurstControllerTest {

    private val synchronousExecutor = Executor { it.run() }

    private fun frame(id: Int) = Frame(width = id, height = id, argb = IntArray(1), timestampMillis = id.toLong())

    // A FrameCapture whose decode thunk returns frame(N) for the Nth capture() call (0-based,
    // counting every invocation including ones that end up "failing"), optionally failing the
    // acquire or decode phase for specific call indices.
    private fun fakeCapture(
        failAcquireIndices: Set<Int> = emptySet(),
        failDecodeIndices: Set<Int> = emptySet(),
    ): BurstController.FrameCapture {
        val callIndex = AtomicInteger(0)
        return BurstController.FrameCapture { onResult ->
            val id = callIndex.getAndIncrement()
            if (id in failAcquireIndices) {
                onResult(Result.failure(RuntimeException("acquire failed at $id")))
            } else {
                onResult(
                    Result.success {
                        if (id in failDecodeIndices) throw RuntimeException("decode failed at $id")
                        frame(id)
                    },
                )
            }
        }
    }

    private fun armAndAwait(
        controller: BurstController,
        captureFrame: BurstController.FrameCapture,
        decodeExecutor: Executor = synchronousExecutor,
    ): BurstController.BurstResult {
        val latch = CountDownLatch(1)
        var result: BurstController.BurstResult? = null
        controller.arm(captureFrame, decodeExecutor) {
            result = it
            latch.countDown()
        }
        assertTrue("burst did not complete in time", latch.await(2, TimeUnit.SECONDS))
        return result!!
    }

    // -- arm (single-EV) ----------------------------------------------------------------------

    @Test
    fun `arm captures frameCount frames in capture order`() {
        val result = armAndAwait(BurstController(frameCount = 4), fakeCapture())

        assertEquals(listOf(0, 1, 2, 3), result.frames.map { it.width })
    }

    @Test
    fun `an acquire failure drops that frame but the burst still attempts frameCount captures`() {
        val result = armAndAwait(BurstController(frameCount = 3), fakeCapture(failAcquireIndices = setOf(1)))

        assertEquals(listOf(0, 2), result.frames.map { it.width })
    }

    @Test
    fun `a decode failure drops only that frame, keeping the rest in capture order`() {
        val result = armAndAwait(BurstController(frameCount = 3), fakeCapture(failDecodeIndices = setOf(1)))

        assertEquals(listOf(0, 2), result.frames.map { it.width })
    }

    @Test
    fun `spans leave merge finish and save at zero until the caller fills them in`() {
        val result = armAndAwait(BurstController(frameCount = 2), fakeCapture())

        assertEquals(0L, result.spans.mergeMillis)
        assertEquals(0L, result.spans.finishMillis)
        assertEquals(0L, result.spans.saveMillis)
    }

    @Test
    fun `isArmed is true only while a burst is in flight`() {
        val controller = BurstController(frameCount = 1)
        val acquireStarted = CountDownLatch(1)
        val releaseAcquire = CountDownLatch(1)
        val completed = CountDownLatch(1)

        assertFalse(controller.isArmed)

        val blockingCapture = BurstController.FrameCapture { onResult ->
            acquireStarted.countDown()
            Thread {
                releaseAcquire.await()
                onResult(Result.success { frame(0) })
            }.start()
        }
        controller.arm(blockingCapture, synchronousExecutor) { completed.countDown() }

        assertTrue(acquireStarted.await(2, TimeUnit.SECONDS))
        assertTrue(controller.isArmed)

        releaseAcquire.countDown()
        assertTrue(completed.await(2, TimeUnit.SECONDS))
        assertFalse(controller.isArmed)
    }

    @Test
    fun `re-arming while already armed is a no-op`() {
        val controller = BurstController(frameCount = 1)
        val acquireStarted = CountDownLatch(1)
        val releaseAcquire = CountDownLatch(1)
        val completed = CountDownLatch(1)
        val completions = AtomicInteger(0)

        val blockingCapture = BurstController.FrameCapture { onResult ->
            acquireStarted.countDown()
            Thread {
                releaseAcquire.await()
                onResult(Result.success { frame(0) })
            }.start()
        }
        controller.arm(blockingCapture, synchronousExecutor) {
            completions.incrementAndGet()
            completed.countDown()
        }
        assertTrue(acquireStarted.await(2, TimeUnit.SECONDS))

        // Ignored: a burst is already in flight.
        controller.arm(fakeCapture(), synchronousExecutor) { completions.incrementAndGet() }

        releaseAcquire.countDown()
        assertTrue(completed.await(2, TimeUnit.SECONDS))
        assertEquals(1, completions.get())
    }

    // -- decode pipelining / memory bound -----------------------------------------------------

    @Test
    fun `at most MAX_UNDECODED_JPEGS_IN_FLIGHT acquires run ahead of decode`() {
        val frameCount = 4
        val controller = BurstController(frameCount = frameCount)
        val acquireCount = AtomicInteger(0)
        val decodeGate = CountDownLatch(1)
        val decodeExecutor = Executors.newSingleThreadExecutor()
        val capture = BurstController.FrameCapture { onResult ->
            val id = acquireCount.getAndIncrement()
            onResult(
                Result.success {
                    decodeGate.await()
                    frame(id)
                },
            )
        }
        val completed = CountDownLatch(1)

        try {
            controller.arm(capture, decodeExecutor) { completed.countDown() }

            assertTrue(
                "expected the worker to reach the memory bound",
                pollUntil(timeoutMs = 2_000) { acquireCount.get() >= BurstController.MAX_UNDECODED_JPEGS_IN_FLIGHT },
            )
            // Give a broken bound a chance to over-acquire before asserting it didn't.
            Thread.sleep(200)
            assertEquals(BurstController.MAX_UNDECODED_JPEGS_IN_FLIGHT, acquireCount.get())

            decodeGate.countDown()
            assertTrue(completed.await(3, TimeUnit.SECONDS))
            assertEquals(frameCount, acquireCount.get())
        } finally {
            decodeExecutor.shutdown()
        }
    }

    private fun pollUntil(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(5)
        }
        return condition()
    }

    // -- armBracketed -------------------------------------------------------------------------

    private fun step(exposureIndex: Int, ev: Double) =
        ExposureBracket.BracketStep(exposureIndex = exposureIndex, targetEv = ev, actualEv = ev, reachable = true)

    @Test
    fun `armBracketed tags each frame with its steps EV, aligned by index`() {
        val controller = BurstController()
        val steps = listOf(step(-2, -2.0), step(0, 0.0), step(2, 2.0))
        val appliedIndices = mutableListOf<Int>()
        val latch = CountDownLatch(1)
        var result: BurstController.BracketedBurst? = null

        controller.armBracketed(
            steps = steps,
            framesPerEv = 2,
            setExposureIndex = { appliedIndices.add(it) },
            captureFrame = fakeCapture(),
            decodeExecutor = synchronousExecutor,
        ) {
            result = it
            latch.countDown()
        }

        assertTrue(latch.await(2, TimeUnit.SECONDS))
        assertEquals(listOf(0, 1, 2, 3, 4, 5), result!!.frames.map { it.width })
        assertEquals(listOf(-2.0, -2.0, 0.0, 0.0, 2.0, 2.0), result!!.evs)
        // One setExposureIndex call per step, plus the neutral reset in the finally block.
        assertEquals(listOf(-2, 0, 2, 0), appliedIndices)
    }

    @Test
    fun `an acquire failure ends that EV steps attempts early, but the next step still runs`() {
        val controller = BurstController()
        val steps = listOf(step(0, 0.0), step(2, 2.0))
        val latch = CountDownLatch(1)
        var result: BurstController.BracketedBurst? = null

        // Capture-call index 1 is step 0's second attempt (framesPerEv = 3) - failing it
        // should stop step 0 at one frame and leave step 1 unaffected.
        controller.armBracketed(
            steps = steps,
            framesPerEv = 3,
            setExposureIndex = {},
            captureFrame = fakeCapture(failAcquireIndices = setOf(1)),
            decodeExecutor = synchronousExecutor,
        ) {
            result = it
            latch.countDown()
        }

        assertTrue(latch.await(2, TimeUnit.SECONDS))
        assertEquals(listOf(0, 2, 3, 4), result!!.frames.map { it.width })
        assertEquals(listOf(0.0, 2.0, 2.0, 2.0), result!!.evs)
    }

    @Test
    fun `exposure is always restored to neutral, even after a capture failure`() {
        val controller = BurstController()
        val steps = listOf(step(2, 2.0))
        val appliedIndices = mutableListOf<Int>()
        val latch = CountDownLatch(1)

        controller.armBracketed(
            steps = steps,
            framesPerEv = 1,
            setExposureIndex = { appliedIndices.add(it) },
            captureFrame = fakeCapture(failAcquireIndices = setOf(0)),
            decodeExecutor = synchronousExecutor,
        ) {
            latch.countDown()
        }

        assertTrue(latch.await(2, TimeUnit.SECONDS))
        assertEquals(listOf(2, 0), appliedIndices)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `armBracketed rejects an empty steps list`() {
        BurstController().armBracketed(
            steps = emptyList(),
            framesPerEv = 1,
            setExposureIndex = {},
            captureFrame = fakeCapture(),
            decodeExecutor = synchronousExecutor,
        ) {}
    }

    @Test(expected = IllegalArgumentException::class)
    fun `armBracketed rejects a non-positive framesPerEv`() {
        BurstController().armBracketed(
            steps = listOf(step(0, 0.0)),
            framesPerEv = 0,
            setExposureIndex = {},
            captureFrame = fakeCapture(),
            decodeExecutor = synchronousExecutor,
        ) {}
    }
}
