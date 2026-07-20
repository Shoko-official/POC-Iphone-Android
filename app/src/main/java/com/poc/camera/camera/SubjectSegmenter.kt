package com.poc.camera.camera

import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.Segmentation
import com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions
import com.poc.camera.imaging.BitmapFrameConverter
import com.poc.camera.pipeline.Frame
import com.poc.camera.pipeline.MaskResize
import com.poc.camera.pipeline.NoSubjectDecision
import com.poc.camera.pipeline.SegmentationMaskDecoder
import java.util.concurrent.TimeUnit

private const val TAG = "SubjectSegmenter"

/**
 * On-device subject segmentation for Portrait mode (issue #80), wrapping MLKit's standalone
 * (bundled-model) selfie segmenter - `com.google.mlkit:segmentation-selfie:16.0.0-beta6`, the
 * only "com.google.mlkit:segmentation-selfie" artifact Google currently publishes (verified
 * against Google's Maven index; there is no non-beta release of this standalone artifact).
 * Bundled model, not the Play-Services-dynamic variant, so segmentation works fully offline
 * and deterministically per device rather than depending on a module Play Services may or may
 * not have downloaded yet.
 *
 * ## Verified API (javap against the resolved 16.0.0-beta6 jar; no guessed names)
 *
 * - `SelfieSegmenterOptions.Builder().setDetectorMode(SelfieSegmenterOptions.SINGLE_IMAGE_MODE).build()`
 *   - [SelfieSegmenterOptions.SINGLE_IMAGE_MODE], not `STREAM_MODE`: each Portrait capture is
 *     one independent still, not a video stream, so there is no temporal smoothing state to
 *     carry (and none of `STREAM_MODE`'s smoothing-ratio/executor knobs are relevant here).
 *   - `enableRawSizeMask()` is deliberately NOT called: per MLKit's docs, leaving it unset
 *     means the returned [com.google.mlkit.vision.segmentation.SegmentationMask] is already
 *     sized to match the input image (`getWidth()`/`getHeight()` equal the [InputImage]'s),
 *     rather than the model's native (smaller) working resolution - the mask therefore lines
 *     up with [Frame] pixel-for-pixel in the common case; [MaskResize] is only a defensive
 *     fallback.
 * - `Segmentation.getClient(options): Segmenter`
 * - `segmenter.process(InputImage): Task<SegmentationMask>`
 * - `SegmentationMask.getBuffer(): ByteBuffer` - one 4-byte float per pixel, row-major, native
 *   byte order, each value in [0, 1] = foreground confidence (per Google's public API
 *   reference for `SegmentationMask.getBuffer()`; not observable from the jar's bytecode
 *   alone, so this specific claim is doc-verified rather than javap-verified).
 * - `InputImage.fromBitmap(Bitmap, rotationDegrees: Int)` - rotationDegrees is always 0 here:
 *   the merged [Frame] this segments is already decoded upright (see [ExifMetadata]'s KDoc).
 *
 * ## Device-only
 *
 * MLKit's bundled model cannot run in a JVM unit test (no Robolectric/instrumentation in this
 * project), so this thin adapter is untested; the pure decision/resize helpers it composes -
 * [SegmentationMaskDecoder], [NoSubjectDecision], [MaskResize] - carry the actual test
 * coverage. Mask quality is device-dependent (model accuracy, NNAPI/GPU delegate
 * availability): this is disclosed, not hidden, in the issue's scope.
 */
object SubjectSegmenter {

    /** Upper bound on how long [segment] blocks waiting for MLKit before giving up. */
    private const val TIMEOUT_SECONDS = 3L

    /**
     * Segments [frame]'s subject, returning a soft mask (`FloatArray`, length
     * `frame.width * frame.height`, row-major, values in [0, 1], 1 = subject - the exact
     * contract [com.poc.camera.pipeline.BlurMap]/[com.poc.camera.pipeline.BokehRenderer]
     * expect), or `null` when segmentation times out, fails outright, or
     * [NoSubjectDecision.hasNoSubject] finds no confident subject in the result.
     *
     * BLOCKS the calling thread on [Tasks.await] with a ~[TIMEOUT_SECONDS] timeout - callers
     * must invoke this off the main thread (Portrait's capture flow already runs the whole
     * merge -> segment -> bokeh -> finish chain on `Dispatchers.Default`).
     */
    fun segment(frame: Frame): FloatArray? {
        val options = SelfieSegmenterOptions.Builder()
            .setDetectorMode(SelfieSegmenterOptions.SINGLE_IMAGE_MODE)
            .build()
        val segmenter = Segmentation.getClient(options)
        val bitmap = BitmapFrameConverter.fromFrame(frame)
        return try {
            val inputImage = InputImage.fromBitmap(bitmap, 0)
            val mlkitMask = Tasks.await(segmenter.process(inputImage), TIMEOUT_SECONDS, TimeUnit.SECONDS)
            val decoded = SegmentationMaskDecoder.toFloatArray(mlkitMask.buffer, mlkitMask.width, mlkitMask.height)
            val mask = if (mlkitMask.width == frame.width && mlkitMask.height == frame.height) {
                decoded
            } else {
                MaskResize.bilinear(decoded, mlkitMask.width, mlkitMask.height, frame.width, frame.height)
            }
            if (NoSubjectDecision.hasNoSubject(mask)) null else mask
        } catch (e: Exception) {
            // Timeout, no-face/no-subject failure, or any MLKit runtime error: Portrait's
            // caller treats a null mask as "fall back to a normal finished photo" (issue #80's
            // graceful-failure requirement), so this is a logged, swallowed miss rather than a
            // capture failure.
            Log.w(TAG, "Subject segmentation failed or timed out; falling back to no mask", e)
            null
        } finally {
            segmenter.close()
            bitmap.recycle()
        }
    }
}
