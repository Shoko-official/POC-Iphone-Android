package com.poc.camera.camera

import android.util.Log
import androidx.camera.core.CameraEffect
import com.poc.camera.pipeline.Lut3d
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * [CameraEffect] that grades preview and recorded video through a [Lut3d] using
 * [LutSurfaceProcessor]. Targeting PREVIEW | VIDEO_CAPTURE means the same look
 * is shown live and baked into the file.
 *
 * The effect owns a single-thread executor for CameraEffect callbacks; the GL
 * work itself happens on the processor's own thread. CameraX never shuts down an
 * app-supplied effect executor, so it is held here and shut down in [release]
 * alongside the processor.
 */
class LookCameraEffect private constructor(
    private val processor: LutSurfaceProcessor,
    private val effectExecutor: ExecutorService,
) : CameraEffect(
    PREVIEW or VIDEO_CAPTURE,
    effectExecutor,
    processor,
    { throwable -> Log.e(TAG, "LUT effect error", throwable) },
) {

    /** Releases the underlying GL resources and shuts down the callback executor. */
    fun release() {
        processor.release()
        // Graceful shutdown: lets any in-flight/queued CameraX callback finish and
        // rejects new ones. CameraX submits no more work once the effect is unbound.
        effectExecutor.shutdown()
    }

    companion object {
        private const val TAG = "LookCameraEffect"

        /** Default 3D LUT resolution: 17^3 lattice points, a common size. */
        const val LUT_SIZE = 17

        /**
         * Builds an effect for [lut]. GL/EGL initialisation runs synchronously in
         * the processor constructor; if it throws (e.g. no GL context available),
         * the executor created here is shut down and the exception is rethrown so
         * the caller can fall back to binding without an effect.
         */
        fun create(lut: Lut3d): LookCameraEffect {
            val executor = Executors.newSingleThreadExecutor()
            val processor = try {
                LutSurfaceProcessor(lut)
            } catch (t: Throwable) {
                executor.shutdown()
                throw t
            }
            return LookCameraEffect(processor, executor)
        }
    }
}
