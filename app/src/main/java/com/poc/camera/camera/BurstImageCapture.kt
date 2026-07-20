package com.poc.camera.camera

import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import com.poc.camera.pipeline.Frame

/**
 * Captures one full-resolution frame in memory via
 * `ImageCapture.takePicture(Executor, OnImageCapturedCallback)` and decodes it to a
 * pipeline [Frame] on the supplied [executor]. Decoding runs on that executor thread
 * (not the CameraX callback thread), which the sequential burst loop drives one capture
 * at a time, so heavy JPEG decode never overlaps.
 *
 * Thin Android adapter (device-only); the decode geometry it delegates to is unit tested
 * in [BurstImageGeometry]. The returned [ImageProxy] is always closed here.
 */
object BurstImageCapture {

    fun capture(
        imageCapture: ImageCapture,
        executor: java.util.concurrent.Executor,
        onResult: (Result<Frame>) -> Unit,
    ) {
        imageCapture.takePicture(
            executor,
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    val result = try {
                        Result.success(JpegBurstFrameDecoder.decode(image))
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
}
