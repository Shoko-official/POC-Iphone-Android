package com.poc.camera.pipeline

/**
 * Builds the GLSL shader sources used by the video LUT processor. Pure string
 * construction with no Android/GL dependencies, so the invariants (sampler and
 * uniform names, embedded LUT constants, balanced braces) are unit-tested on
 * the JVM even though the shaders only ever run on a device GPU.
 *
 * The fragment shader reads the camera frame from an external OES texture and
 * grades it through a 3D LUT stored as a 2D atlas of N blue slices laid out
 * horizontally (width = N*N, height = N), matching [Lut3d.toAtlasRgb]. Trilinear
 * interpolation is done as two hardware-bilinear fetches (one per adjacent blue
 * slice, red/green filtered by the sampler) mixed by the blue fraction.
 */
object LutShaderSource {

    const val ATTRIB_POSITION = "aPosition"
    const val ATTRIB_TEXTURE_COORD = "aTextureCoord"
    const val UNIFORM_TEX_MATRIX = "uTexMatrix"
    const val UNIFORM_INPUT_TEXTURE = "uInputTexture"
    const val UNIFORM_LUT_TEXTURE = "uLutTexture"

    /** Passthrough vertex shader that applies the OES transform to the UVs. */
    fun vertexShader(): String =
        """
        uniform mat4 $UNIFORM_TEX_MATRIX;
        attribute vec4 $ATTRIB_POSITION;
        attribute vec4 $ATTRIB_TEXTURE_COORD;
        varying vec2 vTextureCoord;
        void main() {
            gl_Position = $ATTRIB_POSITION;
            vTextureCoord = ($UNIFORM_TEX_MATRIX * $ATTRIB_TEXTURE_COORD).xy;
        }
        """.trimIndent()

    /**
     * Fragment shader for a LUT of the given [size]. Embeds the size-dependent
     * constants as float literals so the atlas addressing has no runtime
     * uniforms to set.
     */
    fun fragmentShader(size: Int): String {
        require(size >= 2) { "LUT size must be at least 2, was $size" }
        val n = "${size}.0"
        val nMinusOne = "${size - 1}.0"
        val atlasWidth = "${size * size}.0"
        return """
            #extension GL_OES_EGL_image_external : require
            // Atlas addressing reaches ~N*N (288.5 at N=17); at the GLES2 mediump minimum
            // (fp16) that intermediate lands in [256,512) where the ULP is 0.25, quantising
            // the red interpolation position and banding smooth gradients. Use highp where the
            // GPU supports it in the fragment stage; fall back to mediump verbatim otherwise.
            #ifdef GL_FRAGMENT_PRECISION_HIGH
            precision highp float;
            #else
            precision mediump float;
            #endif
            varying vec2 vTextureCoord;
            uniform samplerExternalOES $UNIFORM_INPUT_TEXTURE;
            uniform sampler2D $UNIFORM_LUT_TEXTURE;

            const float LUT_SIZE = $n;
            const float LUT_MAX = $nMinusOne;
            const float ATLAS_WIDTH = $atlasWidth;

            vec3 sampleLut(vec3 color) {
                vec3 c = clamp(color, 0.0, 1.0);
                float blue = c.b * LUT_MAX;
                float bLo = floor(blue);
                float bHi = min(bLo + 1.0, LUT_MAX);
                float bFrac = blue - bLo;

                float red = c.r * LUT_MAX;
                float green = c.g * LUT_MAX;
                float v = (green + 0.5) / LUT_SIZE;

                float uLo = (bLo * LUT_SIZE + red + 0.5) / ATLAS_WIDTH;
                float uHi = (bHi * LUT_SIZE + red + 0.5) / ATLAS_WIDTH;

                vec3 loSlice = texture2D($UNIFORM_LUT_TEXTURE, vec2(uLo, v)).rgb;
                vec3 hiSlice = texture2D($UNIFORM_LUT_TEXTURE, vec2(uHi, v)).rgb;
                return mix(loSlice, hiSlice, bFrac);
            }

            void main() {
                vec4 src = texture2D($UNIFORM_INPUT_TEXTURE, vTextureCoord);
                vec3 graded = sampleLut(src.rgb);
                gl_FragColor = vec4(graded, src.a);
            }
        """.trimIndent()
    }
}
