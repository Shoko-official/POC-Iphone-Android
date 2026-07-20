package com.poc.camera.compare

/**
 * Power-of-two `inSampleSize` calculation for [android.graphics.BitmapFactory],
 * following the standard bounds-then-decode pattern: an imported 12MP+ reference
 * photo is decoded at roughly the target size instead of being fully decoded into
 * memory first, avoiding OOM on typical device heaps. Pure integer math, no
 * Android dependencies.
 */
object ImageDownsampler {

    fun calculateInSampleSize(
        sourceWidth: Int,
        sourceHeight: Int,
        reqWidth: Int,
        reqHeight: Int,
    ): Int {
        var inSampleSize = 1
        if (sourceHeight > reqHeight || sourceWidth > reqWidth) {
            val halfHeight = sourceHeight / 2
            val halfWidth = sourceWidth / 2
            while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }
}
