package com.poc.camera.pipeline

import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future

/**
 * Shared worker pool for the data-parallel row/column loops of the pure pipeline.
 *
 * A single fixed-size pool (size = [parallelism], created lazily on first use with
 * daemon worker threads so it never blocks JVM shutdown) is reused across every stage,
 * so a full-resolution capture does not repeatedly spin up and tear down threads.
 * Plain `java.util.concurrent` only -- no Android classes and no kotlinx.coroutines --
 * so the pipeline stays JVM-unit-testable.
 *
 * ## Determinism contract
 *
 * [parallelRows] partitions a 1D extent (image rows, or image columns for a
 * transposed pass) into contiguous chunks and runs each chunk on the pool. It is safe
 * to use ONLY where each unit of work writes exclusively to outputs it owns and reads
 * only shared read-only inputs -- i.e. the result of a chunk does not depend on any
 * other chunk running before, after, or interleaved with it. Under that rule the output
 * is BYTE-FOR-BYTE identical regardless of how many chunks run or how the threads
 * interleave, because the arithmetic for each output element is performed in exactly the
 * same order as the serial loop; only independent elements are spread across threads.
 *
 * It must NOT be used to parallelize a computation with a cross-unit ordering
 * dependency (e.g. a reduction that accumulates across rows, or a running sum whose
 * state carries from one row/column into the next). Such loops either stay serial or
 * must first be restructured so each chunk re-seeds its own state at its boundary (as
 * [BoxBlur] does: every row re-seeds its horizontal running sum at x = 0 and every
 * column re-seeds its vertical running sum at y = 0, so rows and columns are genuinely
 * independent and safe to chunk).
 *
 * [Future.get] on every chunk establishes a happens-before edge back to the caller, so
 * all chunk writes are visible once [parallelRows] returns.
 */
object PipelineParallel {

    /** Worker count, one per available processor (at least one). */
    val parallelism: Int = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)

    /** Default chunk count for a parallel loop: one chunk per worker. */
    const val SERIAL_CHUNKS: Int = 1

    private val pool by lazy {
        Executors.newFixedThreadPool(parallelism) { runnable ->
            Thread(runnable, "pipeline-parallel").apply { isDaemon = true }
        }
    }

    /**
     * Runs [body] over the extent `[0, height)` split into at most [chunkCount]
     * contiguous chunks, invoking `body(chunkStart, chunkEnd)` once per chunk with a
     * half-open row range. Chunks are dispatched to the shared pool (the first runs on
     * the caller thread) and awaited before returning.
     *
     * With [chunkCount] <= 1 the whole extent runs serially on the caller thread, which
     * is exactly the pre-parallelisation code path -- useful as a deterministic serial
     * reference in tests. Chunk sizes differ by at most one row, so tiny extents and
     * `chunkCount > height` degrade gracefully (each chunk gets one row, extras idle).
     *
     * If any chunk throws, the first failure in chunk order is rethrown after all
     * chunks have completed (so no worker is left running).
     *
     * The name says "rows" for the common case; the same call chunks image COLUMNS by
     * passing `width` as [height] (see [BoxBlur]'s vertical pass), since a column-major
     * loop is just a partition of the `[0, width)` extent.
     */
    fun parallelRows(height: Int, chunkCount: Int = parallelism, body: (Int, Int) -> Unit) {
        require(height >= 0) { "height must be >= 0" }
        require(chunkCount >= 1) { "chunkCount must be >= 1" }
        if (height == 0) return
        val chunks = chunkCount.coerceAtMost(height)
        if (chunks == 1) {
            body(0, height)
            return
        }

        val base = height / chunks
        val remainder = height % chunks
        val bounds = IntArray(chunks + 1)
        for (c in 0 until chunks) {
            bounds[c + 1] = bounds[c] + base + if (c < remainder) 1 else 0
        }

        // Dispatch chunks 1.. to the pool; run chunk 0 on the caller thread.
        val futures = ArrayList<Future<*>>(chunks - 1)
        for (c in 1 until chunks) {
            val start = bounds[c]
            val end = bounds[c + 1]
            futures.add(pool.submit { body(start, end) })
        }

        var error: Throwable? = null
        try {
            body(bounds[0], bounds[1])
        } catch (t: Throwable) {
            error = t
        }
        for (f in futures) {
            try {
                f.get()
            } catch (e: ExecutionException) {
                if (error == null) error = e.cause ?: e
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                if (error == null) error = e
            }
        }
        error?.let { throw it }
    }
}
