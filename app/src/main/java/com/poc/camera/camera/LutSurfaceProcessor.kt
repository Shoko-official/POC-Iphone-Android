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
 * as an external OES texture, and renders them to the CameraX output surface
 * through the LUT shader from [LutShaderSource]. Because the same LUT drives
 * both the PREVIEW and VIDEO_CAPTURE targets of the effect, the look is visible
 * live and baked into the recording.
 *
 * EGL/GL setup runs synchronously in the constructor; if it fails (throws), the
 * caller is expected to fall back to binding without an effect. Standard EGL
 * boilerplate — untested in JVM, exercised only on a device GPU.
 */
class LutSurfaceProcessor(lut: Lut3d) : SurfaceProcessor {

    private val thread = HandlerThread("LutGlThread").apply { start() }
    private val handler = Handler(thread.looper)
    private val glExecutor = Executor { command -> handler.post(command) }

    private val lutSize = lut.size
    private val fragmentShaderSource = LutShaderSource.fragmentShader(lutSize)
    private val atlasBytes = toRgbBytes(lut.toAtlasRgb())
    private val atlasWidth = lutSize * lutSize
    private val atlasHeight = lutSize

    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglConfig: EGLConfig? = null
    private var pbufferSurface: EGLSurface = EGL14.EGL_NO_SURFACE

    private var program = 0
    private var positionLoc = 0
    private var texCoordLoc = 0
    private var texMatrixLoc = 0
    private var inputTextureLoc = 0
    private var lutTextureLoc = 0
    private var oesTextureId = 0
    private var lutTextureId = 0

    private val vertexBuffer: FloatBuffer = floatBuffer(FULL_SCREEN_QUAD)
    private val texCoordBuffer: FloatBuffer = floatBuffer(QUAD_TEX_COORDS)
    private val stMatrix = FloatArray(16)
    private val outMatrix = FloatArray(16)

    private var inputSurfaceTexture: SurfaceTexture? = null
    private var outputSurface: SurfaceOutput? = null
    private var eglOutputSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var released = false

    init {
        runOnGlThreadBlocking { setupGl() }
    }

    override fun onInputSurface(request: SurfaceRequest) {
        handler.post {
            if (released) {
                request.willNotProvideSurface()
                return@post
            }
            val surfaceTexture = SurfaceTexture(oesTextureId).apply {
                setDefaultBufferSize(request.resolution.width, request.resolution.height)
                setOnFrameAvailableListener({ drawFrame() }, handler)
            }
            inputSurfaceTexture = surfaceTexture
            val surface = Surface(surfaceTexture)
            request.provideSurface(surface, glExecutor) { result ->
                surface.release()
                if (inputSurfaceTexture === surfaceTexture) {
                    surfaceTexture.release()
                    inputSurfaceTexture = null
                }
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
            outputSurface = surfaceOutput
            val surface = surfaceOutput.getSurface(glExecutor) { event ->
                if (event.eventCode == SurfaceOutput.Event.EVENT_REQUEST_CLOSE) {
                    releaseOutput(surfaceOutput)
                    // The output closing is CameraX's terminal signal for this
                    // processor session, so tear the whole context down.
                    release()
                }
            }
            eglOutputSurface = EGL14.eglCreateWindowSurface(
                eglDisplay,
                eglConfig,
                surface,
                intArrayOf(EGL14.EGL_NONE),
                0,
            )
            checkEglError("eglCreateWindowSurface")
        }
    }

    /** Releases GL/EGL resources and stops the thread. Idempotent. */
    fun release() {
        handler.post {
            if (released) return@post
            released = true
            inputSurfaceTexture?.release()
            inputSurfaceTexture = null
            outputSurface?.close()
            outputSurface = null
            destroyEgl()
            thread.quitSafely()
        }
    }

    private fun releaseOutput(surfaceOutput: SurfaceOutput) {
        if (outputSurface === surfaceOutput) {
            if (eglOutputSurface != EGL14.EGL_NO_SURFACE) {
                EGL14.eglDestroySurface(eglDisplay, eglOutputSurface)
                eglOutputSurface = EGL14.EGL_NO_SURFACE
            }
            outputSurface = null
        }
        surfaceOutput.close()
    }

    private fun drawFrame() {
        val surfaceTexture = inputSurfaceTexture ?: return
        surfaceTexture.updateTexImage()
        val output = outputSurface ?: return
        if (eglOutputSurface == EGL14.EGL_NO_SURFACE) return

        surfaceTexture.getTransformMatrix(stMatrix)
        output.updateTransformMatrix(outMatrix, stMatrix)

        EGL14.eglMakeCurrent(eglDisplay, eglOutputSurface, eglOutputSurface, eglContext)
        val size = output.size
        GLES20.glViewport(0, 0, size.width, size.height)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        GLES20.glUseProgram(program)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId)
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

        EGL14.eglSwapBuffers(eglDisplay, eglOutputSurface)
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

        oesTextureId = createOesTexture()
        lutTextureId = createLutTexture()
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
        if (eglOutputSurface != EGL14.EGL_NO_SURFACE) {
            EGL14.eglDestroySurface(eglDisplay, eglOutputSurface)
            eglOutputSurface = EGL14.EGL_NO_SURFACE
        }
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
