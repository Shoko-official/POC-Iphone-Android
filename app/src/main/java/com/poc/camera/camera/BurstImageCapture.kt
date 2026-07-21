package com.poc.camera.camera

import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import com.poc.camera.pipeline.Frame
import java.nio.ByteBuffer
import java.util.concurrent.Executor

/**
 * Captures one full-resolution frame in memory via
 * `ImageCapture.takePicture(Executor, OnImageCapturedCallback)`, split into the two phases
 * [BurstController]'s decode pipelining needs (see that class's doc): [acquire] copies the
 * JPEG bytes off the [ImageProxy] on [callbackExecutor] - fast, the camera session is free
 * again the instant this fires - and [decode] turns previously-acquired bytes into a
 * pipeline [Frame] - slow, and left to the caller's own decode executor.
 *
 * Thin Android adapter (device-only); the decode geometry it delegates to is unit tested
 * in [BurstImageGeometry] and the byte-to-[Frame] math in [JpegBurstFrameDecoder]. The
 * returned [ImageProxy] is always closed in [acquire], before [onResult] fires.
 */
object BurstImageCapture {

    /** Raw JPEG bytes plus the source [ImageProxy]'s rotation - decode is deferred, see [decode]. */
    class AcquiredJpeg(val bytes: ByteArray, val rotationDegrees: Int)

    fun acquire(
        imageCapture: ImageCapture,
        callbackExecutor: Executor,
        onResult: (Result<AcquiredJpeg>) -> Unit,
    ) {
        imageCapture.takePicture(
            callbackExecutor,
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    val result = try {
                        Result.success(
                            AcquiredJpeg(
                                bytes = image.planes[0].buffer.toByteArray(),
                                rotationDegrees = image.imageInfo.rotationDegrees,
                            ),
                        )
                    } catch (t: Throwable) {
                        Result.failure(t)
                    } finally {
                        image.close()
                    }
                    onResult(result)
                }

                override fun onError(exception: ImageCaptureException) {
                    onResult(Result.failure(exception))
                }
            },
        )
    }

    /** Decodes previously-[acquire]d bytes into a pipeline [Frame], on the calling thread - callers post this to their own decode executor. */
    fun decode(jpeg: AcquiredJpeg, maxBurstPixels: Int = BurstImageGeometry.MAX_BURST_PIXELS): Frame =
        JpegBurstFrameDecoder.decode(jpeg.bytes, jpeg.rotationDegrees, maxBurstPixels)

    private fun ByteBuffer.toByteArray(): ByteArray {
        val duplicate = duplicate().apply { rewind() }
        val bytes = ByteArray(duplicate.remaining())
        duplicate.get(bytes)
        return bytes
    }
}
