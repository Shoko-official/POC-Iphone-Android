package com.poc.camera.pipeline

/**
 * Mirrors [this] horizontally: pixel `(x, y)` moves to `(width - 1 - x, y)`. Pure geometry,
 * no Android dependencies.
 *
 * Selfie consistency (issue #88): a front-camera JPEG comes off the sensor un-mirrored, but
 * `PreviewView` mirrors the live front preview automatically, so a saved-as-is selfie reads
 * as flipped relative to what the user framed and saw (text backwards, part on the "wrong"
 * side). Every mainstream camera app defaults selfies to match the mirrored preview instead,
 * so [com.poc.camera.camera.CameraScreen] calls this once on the merged frame, right after
 * merge/super-resolution and before segmentation, whenever the capture used the front lens -
 * see its `startPortraitCapture`/`mergeAndSave` docs for exactly where and why.
 *
 * Applying this twice is an exact identity (involution):
 * `frame.mirrorHorizontal().mirrorHorizontal() == frame`, byte-for-byte, since the mapping is
 * its own inverse.
 */
fun Frame.mirrorHorizontal(): Frame {
    val out = IntArray(argb.size)
    val w = width
    PipelineParallel.parallelRows(height) { startRow, endRow ->
        for (y in startRow until endRow) {
            val rowStart = y * w
            for (x in 0 until w) {
                out[rowStart + x] = argb[rowStart + (w - 1 - x)]
            }
        }
    }
    return Frame(width, height, out, timestampMillis)
}
