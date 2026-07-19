package com.poc.camera.camera

import androidx.camera.core.ImageProxy
import com.poc.camera.pipeline.Frame
import com.poc.camera.pipeline.FrameRingBuffer
import com.poc.camera.pipeline.YuvToArgbConverter
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Feeds ImageAnalysis frames into a bounded [FrameRingBuffer] while a burst is armed.
 *
 * When idle, frames are closed immediately without conversion so preview stays cheap.
 * Once armed, the next [frameCount] frames are decoded to ARGB, collected, and handed
 * to the completion callback for the caller (a future multi-frame merge step) to consume.
 */
class BurstController(private val frameCount: Int = DEFAULT_FRAME_COUNT) {

    private val buffer = FrameRingBuffer(capacity = frameCount)
    private val armed = AtomicBoolean(false)

    @Volatile
    private var onComplete: ((List<Frame>) -> Unit)? = null

    val isArmed: Boolean
        get() = armed.get()

    fun arm(onComplete: (List<Frame>) -> Unit) {
        buffer.clear()
        this.onComplete = onComplete
        armed.set(true)
    }

    fun onFrame(image: ImageProxy) {
        if (!armed.get()) {
            image.close()
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
    }
}
