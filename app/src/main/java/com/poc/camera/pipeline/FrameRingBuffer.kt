package com.poc.camera.pipeline

/**
 * Bounded, thread-safe ring buffer of [Frame]s used to hold a burst in memory.
 *
 * Capacity is fixed at construction so callers can reason about the memory bound up
 * front, e.g. 6 frames at 1280x720 ARGB_8888 is ~22 MB (6 * 1280 * 720 * 4 bytes).
 * Oldest frames are evicted once [capacity] is exceeded.
 */
class FrameRingBuffer(private val capacity: Int) {

    init {
        require(capacity > 0) { "capacity must be positive, was $capacity" }
    }

    private val lock = Any()
    private val frames = ArrayDeque<Frame>(capacity)

    val size: Int
        get() = synchronized(lock) { frames.size }

    fun add(frame: Frame) {
        synchronized(lock) {
            if (frames.size == capacity) {
                frames.removeFirst()
            }
            frames.addLast(frame)
        }
    }

    /** Returns an immutable, oldest-to-newest snapshot decoupled from further [add] calls. */
    fun snapshot(): List<Frame> = synchronized(lock) { frames.toList() }

    fun clear() {
        synchronized(lock) { frames.clear() }
    }
}
