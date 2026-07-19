package com.poc.camera.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LutShaderSourceTest {

    private fun assertBalancedBraces(source: String) {
        var depth = 0
        for (ch in source) {
            when (ch) {
                '{' -> depth++
                '}' -> depth--
            }
            assertTrue("closing brace without matching opener", depth >= 0)
        }
        assertEquals("unbalanced braces", 0, depth)
    }

    @Test
    fun fragmentShaderDeclaresExpectedSamplersAndUniforms() {
        val source = LutShaderSource.fragmentShader(17)
        assertTrue(source.contains("#extension GL_OES_EGL_image_external : require"))
        assertTrue(source.contains("samplerExternalOES"))
        assertTrue(source.contains(LutShaderSource.UNIFORM_INPUT_TEXTURE))
        assertTrue(source.contains(LutShaderSource.UNIFORM_LUT_TEXTURE))
        assertTrue(source.contains("sampler2D"))
        assertTrue(source.contains("gl_FragColor"))
    }

    @Test
    fun fragmentShaderEmbedsSizeConstants() {
        val source = LutShaderSource.fragmentShader(17)
        assertTrue("expected LUT_SIZE 17.0", source.contains("17.0"))
        assertTrue("expected LUT_MAX 16.0", source.contains("16.0"))
        assertTrue("expected ATLAS_WIDTH 289.0", source.contains("289.0"))
    }

    @Test
    fun fragmentShaderHasBalancedBraces() {
        assertBalancedBraces(LutShaderSource.fragmentShader(17))
        assertBalancedBraces(LutShaderSource.fragmentShader(9))
    }

    @Test
    fun vertexShaderDeclaresAttributesAndBalancedBraces() {
        val source = LutShaderSource.vertexShader()
        assertTrue(source.contains(LutShaderSource.ATTRIB_POSITION))
        assertTrue(source.contains(LutShaderSource.ATTRIB_TEXTURE_COORD))
        assertTrue(source.contains(LutShaderSource.UNIFORM_TEX_MATRIX))
        assertTrue(source.contains("gl_Position"))
        assertBalancedBraces(source)
    }

    @Test
    fun fragmentShaderRejectsTooSmallSize() {
        try {
            LutShaderSource.fragmentShader(1)
            throw AssertionError("expected IllegalArgumentException for size 1")
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }
}
