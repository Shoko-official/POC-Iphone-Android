package com.poc.camera.camera

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import androidx.camera.core.SurfaceOutput
import androidx.camera.core.SurfaceProcessor
import androidx.camera.core.SurfaceRequest
import com.poc.camera.pipeline.Lut3d
import com.poc.camera.pipeline.LutShaderSource
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor

/**
 * [SurfaceProcessor] that grades every camera frame through a 3D [Lut3d] on the
 * GPU. It runs a dedicated EGL/GLES2 context on its own thread, receives frames
 * as external OES textures, and renders them to the CameraX output surface(s)
 * through the LUT shader from [LutShaderSource]. Because the same LUT drives both
 * the PREVIEW and VIDEO_CAPTURE targets of the effect, the look is visible live
 * and baked into the recording.
 *
 * ## Multi-target / multi-Surface model
 *
 * A single [SurfaceProcessor] instance targeting `PREVIEW | VIDEO_CAPTURE` can be
 * handed **more than one** input [SurfaceRequest] and **more than one** output
 * [SurfaceOutput] over its lifetime, possibly overlapping: the CameraX contract
 * states that it "may request a new input Surface before releasing the existing
 * one" ([SurfaceProcessor.onInputSurface]) and "may provide a new output Surface
 * before requesting to close the existing one" ([SurfaceProcessor.onOutputSurface]),
 * and an output edge can be re-invalidated, delivering a fresh [SurfaceOutput] for
 * the same target. Holding single fields and overwriting them on each call (the
 * previous implementation) leaks the replaced Surface/texture and lets one stream
 * stall while the other renders.
 *
 * This mirrors CameraX's own reference `DefaultSurfaceProcessor`: state is kept
 * per pipe. Every input gets its own OES texture id, [SurfaceTexture] and texture
 * transform scratch matrix; every output gets its own [EGLSurface], tracked in an
 * insertion-ordered [Map]. On each frame from a given input the frame is drawn to
 * **every** current output, applying that output's own transform — the exact
 * rendering model `DefaultSurfaceProcessor.onFrameAvailable` uses. The GL program,
 * LUT texture and EGL context are shared across all pipes (one context, one
 * thread).
 *
 * ## Lifecycle
 *
 * CameraX never releases an app-supplied [SurfaceProcessor] (its internal
 * `SurfaceProcessorWithExecutor.release()` is a no-op), so teardown is driven
 * entirely by [release], invoked from `CameraPreview`'s `onDispose`. Per-input and
 * per-output resources are also freed eagerly as CameraX signals end-of-life
 * (input `provideSurface` result callback, output `EVENT_REQUEST_CLOSE`), so a
 * pipeline rebuild mid-session does not leak or tear down the shared context.
 *
 * EGL/GL setup runs synchronously in the constructor; if it fails (throws), the
 * EGL context is torn down before the exception propagates and the caller is
 * expected to fall back to binding without an effect. Standard EGL boilerplate —
 * untested in the JVM, exercised only on a device GPU.
 */
class LutSurfaceProcessor(lut: Lut3d) : SurfaceProcessor {

    private val thread = HandlerThread("LutGlThread").apply { start() }
    private val handler = Handler(thread.looper)
    private val glExecutor = Executor { command -> handler.post(command) }

    private val lutSize = lut.size
    private val fragmentShaderSource = LutShaderSource.fragmentShader(lutSize)
    private val atlasBytes = toRgbBytes(lut.toAtlasRgb())
    private val atlasWidth = LutAtlasTexture.width(lutSize)
    private val atlasHeight = LutAtlasTexture.height(lutSize)

    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglConfig: EGLConfig? = null
    private var pbufferSurface: EGLSurface = EGL14.EGL_NO_SURFACE

    // Shared across all pipes: one program, one LUT texture, one geometry.
    private var program = 0
    private var positionLoc = 0
    private var texCoordLoc = 0
    private var texMatrixLoc = 0
    private var inputTextureLoc = 0
    private var lutTextureLoc = 0
    private var lutTextureId = 0

    private val vertexBuffer: FloatBuffer = floatBuffer(FULL_SCREEN_QUAD)
    private val texCoordBuffer: FloatBuffer = floatBuffer(QUAD_TEX_COORDS)
    // Scratch for the per-output transform; reused per draw. Only touched on the
    // GL thread, one draw at a time, so a single instance is safe.
    private val outMatrix = FloatArray(16)

    // All mutated and read only on the GL thread (handler), so no synchronisation
    // is needed. Inputs render to every output in [outputSurfaces].
    private val inputs = mutableListOf<InputPipe>()
    private val outputSurfaces = linkedMapOf<SurfaceOutput, EGLSurface>()
    private var released = false

    /** Per-input GL state: its own OES texture, [SurfaceTexture] and transform. */
    private class InputPipe(
        val oesTextureId: Int,
        val surfaceTexture: SurfaceTexture,
        val surface: Surface,
    ) {
        val stMatrix = FloatArray(16)
    }

    init {
        runOnGlThreadBlocking { setupGl() }
    }

    override fun onInputSurface(request: SurfaceRequest) {
        handler.post {
            if (released) {
                request.willNotProvideSurface()
                return@post
            }
            // Ensure a valid draw surface is current before allocating the texture,
            // in case a prior output was released while current.
            makeCurrentPbuffer()
            val oesTextureId = createOesTexture()
            val surfaceTexture = SurfaceTexture(oesTextureId).apply {
                setDefaultBufferSize(request.resolution.width, request.resolution.height)
            }
            val surface = Surface(surfaceTexture)
            val pipe = InputPipe(oesTextureId, surfaceTexture, surface)
            // The listener knows which pipe fired via the captured [pipe], so it
            // updates that pipe's own SurfaceTexture/OES texture and draws it.
            surfaceTexture.setOnFrameAvailableListener({ onFrameAvailable(pipe) }, handler)
            inputs.add(pipe)
            request.provideSurface(surface, glExecutor) { result ->
                releaseInput(pipe)
                Log.d(TAG, "input surface released, result=${result.resultCode}")
            }
        }
    }

    override fun onOutputSurface(surfaceOutput: SurfaceOutput) {
        handler.post {
            if (released) {
                surfaceOutput.close()
                return@post
            }
            val surface = surfaceOutput.getSurface(glExecutor) { event ->
                if (event.eventCode == SurfaceOutput.Event.EVENT_REQUEST_CLOSE) {
                    // Only this output is going away; other outputs (and the shared
                    // context) keep running. Full teardown is [release] via onDispose.
                    releaseOutput(surfaceOutput)
                }
            }
            val eglSurface = EGL14.eglCreateWindowSurface(
                eglDisplay,
                eglConfig,
                surface,
                intArrayOf(EGL14.EGL_NONE),
                0,
            )
            checkEglError("eglCreateWindowSurface")
            outputSurfaces[surfaceOutput] = eglSurface
        }
    }

    /** Releases all GL/EGL resources and stops the thread. Idempotent. */
    fun release() {
        handler.post {
            if (released) return@post
            released = true
            // Keep a context current so the texture/program deletes below take.
            makeCurrentPbuffer()
            for (pipe in inputs.toList()) {
                releaseInput(pipe)
            }
            for (surfaceOutput in outputSurfaces.keys.toList()) {
                releaseOutput(surfaceOutput)
            }
            if (lutTextureId != 0) {
                GLES20.glDeleteTextures(1, intArrayOf(lutTextureId), 0)
                lutTextureId = 0
            }
            if (program != 0) {
                GLES20.glDeleteProgram(program)
                program = 0
            }
            destroyEgl()
            thread.quitSafely()
        }
    }

    /** Frees one input pipe. Safe to call twice; the membership check gates it. */
    private fun releaseInput(pipe: InputPipe) {
        if (!inputs.remove(pipe)) return
        pipe.surfaceTexture.setOnFrameAvailableListener(null)
        pipe.surfaceTexture.release()
        pipe.surface.release()
        makeCurrentPbuffer()
        GLES20.glDeleteTextures(1, intArrayOf(pipe.oesTextureId), 0)
    }

    /** Frees one output pipe and closes its [SurfaceOutput]. Safe to call twice. */
    private fun releaseOutput(surfaceOutput: SurfaceOutput) {
        val eglSurface = outputSurfaces.remove(surfaceOutput)
        if (eglSurface != null && eglSurface != EGL14.EGL_NO_SURFACE) {
            EGL14.eglDestroySurface(eglDisplay, eglSurface)
        }
        surfaceOutput.close()
    }

    private fun onFrameAvailable(pipe: InputPipe) {
        // A frame callback may already have been queued when the pipe was released.
        if (released || pipe !in inputs) return
        pipe.surfaceTexture.updateTexImage()
        pipe.surfaceTexture.getTransformMatrix(pipe.stMatrix)
        if (outputSurfaces.isEmpty()) return

        for ((surfaceOutput, eglSurface) in outputSurfaces) {
            if (eglSurface == EGL14.EGL_NO_SURFACE) continue
            surfaceOutput.updateTransformMatrix(outMatrix, pipe.stMatrix)

            EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)
            val size = surfaceOutput.size
            GLES20.glViewport(0, 0, size.width, size.height)
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

            GLES20.glUseProgram(program)

            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, pipe.oesTextureId)
            GLES20.glUniform1i(inputTextureLoc, 0)

            GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, lutTextureId)
            GLES20.glUniform1i(lutTextureLoc, 1)

            GLES20.glUniformMatrix4fv(texMatrixLoc, 1, false, outMatrix, 0)

            GLES20.glEnableVertexAttribArray(positionLoc)
            GLES20.glVertexAttribPointer(positionLoc, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer)
            GLES20.glEnableVertexAttribArray(texCoordLoc)
            GLES20.glVertexAttribPointer(texCoordLoc, 2, GLES20.GL_FLOAT, false, 0, texCoordBuffer)

            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

            GLES20.glDisableVertexAttribArray(positionLoc)
            GLES20.glDisableVertexAttribArray(texCoordLoc)

            EGL14.eglSwapBuffers(eglDisplay, eglSurface)
        }
    }

    private fun setupGl() {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(eglDisplay != EGL14.EGL_NO_DISPLAY) { "no EGL display" }
        val version = IntArray(2)
        check(EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) { "eglInitialize failed" }

        val configAttribs = intArrayOf(
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT or EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        check(
            EGL14.eglChooseConfig(eglDisplay, configAttribs, 0, configs, 0, 1, numConfigs, 0) &&
                numConfigs[0] > 0,
        ) { "eglChooseConfig failed" }
        val config = configs[0] ?: error("no EGL config")
        eglConfig = config

        eglContext = EGL14.eglCreateContext(
            eglDisplay,
            config,
            EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE),
            0,
        )
        check(eglContext != EGL14.EGL_NO_CONTEXT) { "eglCreateContext failed" }

        pbufferSurface = EGL14.eglCreatePbufferSurface(
            eglDisplay,
            config,
            intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE),
            0,
        )
        check(pbufferSurface != EGL14.EGL_NO_SURFACE) { "eglCreatePbufferSurface failed" }
        check(EGL14.eglMakeCurrent(eglDisplay, pbufferSurface, pbufferSurface, eglContext)) {
            "eglMakeCurrent failed"
        }

        program = buildProgram(LutShaderSource.vertexShader(), fragmentShaderSource)
        positionLoc = GLES20.glGetAttribLocation(program, LutShaderSource.ATTRIB_POSITION)
        texCoordLoc = GLES20.glGetAttribLocation(program, LutShaderSource.ATTRIB_TEXTURE_COORD)
        texMatrixLoc = GLES20.glGetUniformLocation(program, LutShaderSource.UNIFORM_TEX_MATRIX)
        inputTextureLoc = GLES20.glGetUniformLocation(program, LutShaderSource.UNIFORM_INPUT_TEXTURE)
        lutTextureLoc = GLES20.glGetUniformLocation(program, LutShaderSource.UNIFORM_LUT_TEXTURE)

        lutTextureId = createLutTexture()
    }

    /** Keeps the pbuffer/context current so GL object deletes have a live context. */
    private fun makeCurrentPbuffer() {
        if (eglDisplay != EGL14.EGL_NO_DISPLAY &&
            eglContext != EGL14.EGL_NO_CONTEXT &&
            pbufferSurface != EGL14.EGL_NO_SURFACE
        ) {
            EGL14.eglMakeCurrent(eglDisplay, pbufferSurface, pbufferSurface, eglContext)
        }
    }

    private fun createOesTexture(): Int {
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        val id = ids[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, id)
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR,
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR,
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE,
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE,
        )
        return id
    }

    private fun createLutTexture(): Int {
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        val id = ids[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, id)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        // The atlas rows are tightly packed RGB (3 bytes/texel). GL's default
        // GL_UNPACK_ALIGNMENT of 4 would misread every row after the first when
        // the row stride is not a multiple of 4 (e.g. 289*3 = 867 bytes at
        // size 17 - see LutAtlasTexture). Set alignment to 1 for this upload.
        // This is the only glTexImage2D/glTexSubImage2D call in the class, so the
        // default is never relied on elsewhere and does not need restoring.
        GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, LutAtlasTexture.REQUIRED_UNPACK_ALIGNMENT)
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D,
            0,
            GLES20.GL_RGB,
            atlasWidth,
            atlasHeight,
            0,
            GLES20.GL_RGB,
            GLES20.GL_UNSIGNED_BYTE,
            ByteBuffer.wrap(atlasBytes),
        )
        return id
    }

    private fun destroyEgl() {
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) return
        EGL14.eglMakeCurrent(
            eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT,
        )
        // Defensive: any output EGL surfaces not yet released (e.g. teardown on a
        // setup failure) are destroyed here so nothing leaks with the display.
        for (eglSurface in outputSurfaces.values) {
            if (eglSurface != EGL14.EGL_NO_SURFACE) {
                EGL14.eglDestroySurface(eglDisplay, eglSurface)
            }
        }
        outputSurfaces.clear()
        if (pbufferSurface != EGL14.EGL_NO_SURFACE) {
            EGL14.eglDestroySurface(eglDisplay, pbufferSurface)
            pbufferSurface = EGL14.EGL_NO_SURFACE
        }
        if (eglContext != EGL14.EGL_NO_CONTEXT) {
            EGL14.eglDestroyContext(eglDisplay, eglContext)
            eglContext = EGL14.EGL_NO_CONTEXT
        }
        EGL14.eglTerminate(eglDisplay)
        eglDisplay = EGL14.EGL_NO_DISPLAY
    }

    private fun runOnGlThreadBlocking(action: () -> Unit) {
        val latch = CountDownLatch(1)
        var failure: Throwable? = null
        handler.post {
            try {
                action()
            } catch (t: Throwable) {
                failure = t
                // Setup may have created the EGL context before failing (e.g. a
                // shader compile/link throw). Tear it down on the GL thread (EGL
                // is thread-affine) before the thread quits, otherwise the
                // display/context/pbuffer leak. destroyEgl is idempotent.
                destroyEgl()
            } finally {
                latch.countDown()
            }
        }
        latch.await()
        failure?.let {
            thread.quitSafely()
            throw it
        }
    }

    private fun checkEglError(op: String) {
        val error = EGL14.eglGetError()
        if (error != EGL14.EGL_SUCCESS) {
            Log.e(TAG, "$op: EGL error 0x${Integer.toHexString(error)}")
        }
    }

    companion object {
        private const val TAG = "LutSurfaceProcessor"

        // Full-screen quad as a triangle strip, clip-space XY. aPosition is a
        // vec4 so the missing Z/W default to (0, 1).
        private val FULL_SCREEN_QUAD = floatArrayOf(
            -1f, -1f,
            1f, -1f,
            -1f, 1f,
            1f, 1f,
        )

        // Matching texture coordinates; the OES transform matrix is applied in
        // the vertex shader.
        private val QUAD_TEX_COORDS = floatArrayOf(
            0f, 0f,
            1f, 0f,
            0f, 1f,
            1f, 1f,
        )

        private fun floatBuffer(data: FloatArray): FloatBuffer =
            ByteBuffer.allocateDirect(data.size * Float.SIZE_BYTES)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
                .apply {
                    put(data)
                    position(0)
                }

        private fun toRgbBytes(atlas: FloatArray): ByteArray {
            val out = ByteArray(atlas.size)
            for (i in atlas.indices) {
                out[i] = (atlas[i].coerceIn(0f, 1f) * 255f + 0.5f).toInt().toByte()
            }
            return out
        }

        private fun buildProgram(vertexSource: String, fragmentSource: String): Int {
            val vertexShader = compileShader(GLES20.GL_VERTEX_SHADER, vertexSource)
            val fragmentShader = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
            val program = GLES20.glCreateProgram()
            check(program != 0) { "glCreateProgram failed" }
            GLES20.glAttachShader(program, vertexShader)
            GLES20.glAttachShader(program, fragmentShader)
            GLES20.glLinkProgram(program)
            val linked = IntArray(1)
            GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linked, 0)
            if (linked[0] == 0) {
                val log = GLES20.glGetProgramInfoLog(program)
                GLES20.glDeleteProgram(program)
                error("program link failed: $log")
            }
            GLES20.glDeleteShader(vertexShader)
            GLES20.glDeleteShader(fragmentShader)
            return program
        }

        private fun compileShader(type: Int, source: String): Int {
            val shader = GLES20.glCreateShader(type)
            check(shader != 0) { "glCreateShader failed" }
            GLES20.glShaderSource(shader, source)
            GLES20.glCompileShader(shader)
            val compiled = IntArray(1)
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
            if (compiled[0] == 0) {
                val log = GLES20.glGetShaderInfoLog(shader)
                GLES20.glDeleteShader(shader)
                error("shader compile failed: $log")
            }
            return shader
        }
    }
}
