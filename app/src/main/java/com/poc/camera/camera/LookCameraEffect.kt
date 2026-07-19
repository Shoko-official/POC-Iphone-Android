package com.poc.camera.camera

import android.util.Log
import androidx.camera.core.CameraEffect
import com.poc.camera.pipeline.Lut3d
import java.util.concurrent.Executors

/**
 * [CameraEffect] that grades preview and recorded video through a [Lut3d] using
 * [LutSurfaceProcessor]. Targeting PREVIEW | VIDEO_CAPTURE means the same look
 * is shown live and baked into the file.
 *
 * The effect owns a single-thread executor for CameraEffect callbacks; the GL
 * work itself happens on the processor's own thread.
 */
class LookCameraEffect private constructor(
    private val processor: LutSurfaceProcessor,
) : CameraEffect(
    PREVIEW or VIDEO_CAPTURE,
    Executors.newSingleThreadExecutor(),
    processor,
    { throwable -> Log.e(TAG, "LUT effect error", throwable) },
) {

    /** Releases the underlying GL resources. */
    fun release() {
        processor.release()
    }

    companion object {
        private const val TAG = "LookCameraEffect"

        /** Default 3D LUT resolution: 17^3 lattice points, a common size. */
        const val LUT_SIZE = 17

        /**
         * Builds an effect for [lut]. GL/EGL initialisation runs synchronously in
         * the processor constructor; if it throws (e.g. no GL context available),
         * this rethrows so the caller can fall back to binding without an effect.
         */
        fun create(lut: Lut3d): LookCameraEffect =
            LookCameraEffect(LutSurfaceProcessor(lut))
    }
}
