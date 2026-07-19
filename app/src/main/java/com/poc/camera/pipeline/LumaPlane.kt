package com.poc.camera.pipeline

/**
 * A single-channel luminance image with its dimensions. Pure data holder used by
 * [FrameAligner]; kept separate from [Frame] so alignment never touches ARGB.
 */
data class LumaPlane(
    val width: Int,
    val height: Int,
    val values: IntArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is LumaPlane) return false
        return width == other.width &&
            height == other.height &&
            values.contentEquals(other.values)
    }

    override fun hashCode(): Int {
        var result = width
        result = 31 * result + height
        result = 31 * result + values.contentHashCode()
        return result
    }
}
