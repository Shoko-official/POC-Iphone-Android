package com.poc.camera.pipeline

/**
 * Averages aligned burst frames into a single denoised [Frame].
 *
 * Each output pixel is the per-channel mean (integer, rounded) of every accepted
 * frame that overlaps it once its offset is applied, using the offset convention
 * of [FrameAligner]. Overlap counts vary per pixel near the edges; a pixel that no
 * frame covers falls back to the reference value so the output is always defined.
 * The merged frame inherits the reference (first) frame's dimensions and timestamp.
 */
object FrameMerger {

    fun merge(
        frames: List<Frame>,
        offsets: List<Pair<Int, Int>>,
        accepted: List<Boolean>,
    ): Frame {
        require(frames.isNotEmpty()) { "frames must not be empty" }
        require(frames.size == offsets.size && frames.size == accepted.size) {
            "frames, offsets and accepted must have equal size"
        }

        val reference = frames.first()
        val width = reference.width
        val height = reference.height
        val pixelCount = width * height

        val sumR = IntArray(pixelCount)
        val sumG = IntArray(pixelCount)
        val sumB = IntArray(pixelCount)
        val counts = IntArray(pixelCount)

        for (index in frames.indices) {
            if (!accepted[index]) continue
            val frame = frames[index]
            val (dx, dy) = offsets[index]
            val frameWidth = frame.width
            val frameHeight = frame.height
            val argb = frame.argb
            for (y in 0 until height) {
                val fy = y + dy
                if (fy < 0 || fy >= frameHeight) continue
                val outRow = y * width
                val frameRow = fy * frameWidth
                for (x in 0 until width) {
                    val fx = x + dx
                    if (fx < 0 || fx >= frameWidth) continue
                    val pixel = argb[frameRow + fx]
                    val outIndex = outRow + x
                    sumR[outIndex] += (pixel shr 16) and 0xFF
                    sumG[outIndex] += (pixel shr 8) and 0xFF
                    sumB[outIndex] += pixel and 0xFF
                    counts[outIndex]++
                }
            }
        }

        val merged = IntArray(pixelCount)
        for (i in 0 until pixelCount) {
            val count = counts[i]
            if (count == 0) {
                merged[i] = reference.argb[i]
                continue
            }
            val half = count / 2
            val r = (sumR[i] + half) / count
            val g = (sumG[i] + half) / count
            val b = (sumB[i] + half) / count
            merged[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }

        return Frame(width, height, merged, reference.timestampMillis)
    }
}
