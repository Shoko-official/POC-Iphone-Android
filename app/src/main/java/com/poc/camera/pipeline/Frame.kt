package com.poc.camera.pipeline

/**
 * A single decoded frame captured during a burst. Pure data holder, no Android
 * dependencies, so it can be built and asserted on in JVM unit tests.
 */
data class Frame(
    val width: Int,
    val height: Int,
    val argb: IntArray,
    val timestampMillis: Long,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Frame) return false
        return width == other.width &&
            height == other.height &&
            timestampMillis == other.timestampMillis &&
            argb.contentEquals(other.argb)
    }

    override fun hashCode(): Int {
        var result = width
        result = 31 * result + height
        result = 31 * result + timestampMillis.hashCode()
        result = 31 * result + argb.contentHashCode()
        return result
    }
}
