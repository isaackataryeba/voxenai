package com.voxenai.cinecam.gl

import android.opengl.GLES20
import android.opengl.GLES11Ext
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Single fullscreen-quad GL program that reads the camera's OES external texture and, in one
 * pass, applies (in order): a flat/LOG-style tone curve, an optional 3D LUT, focus-peaking edge
 * highlighting, and exposure zebras. Used for both the on-screen preview and the encoder input
 * surface so what you see is exactly what gets recorded.
 */
class CameraFilterProgram {

    private val program = GlUtil.createProgram(VERTEX_SHADER, FRAGMENT_SHADER)

    private val positionHandle = GLES20.glGetAttribLocation(program, "aPosition")
    private val texCoordHandle = GLES20.glGetAttribLocation(program, "aTextureCoord")
    private val texMatrixHandle = GLES20.glGetUniformLocation(program, "uTexMatrix")
    private val textureHandle = GLES20.glGetUniformLocation(program, "sTexture")
    private val lutTextureHandle = GLES20.glGetUniformLocation(program, "uLutTexture")
    private val lutSizeHandle = GLES20.glGetUniformLocation(program, "uLutSize")
    private val lutStrengthHandle = GLES20.glGetUniformLocation(program, "uLutStrength")
    private val logEnabledHandle = GLES20.glGetUniformLocation(program, "uLogEnabled")
    private val zebraEnabledHandle = GLES20.glGetUniformLocation(program, "uZebraEnabled")
    private val zebraThresholdHandle = GLES20.glGetUniformLocation(program, "uZebraThreshold")
    private val timeHandle = GLES20.glGetUniformLocation(program, "uTime")
    private val peakingEnabledHandle = GLES20.glGetUniformLocation(program, "uFocusPeakingEnabled")
    private val texelSizeHandle = GLES20.glGetUniformLocation(program, "uTexelSize")
    private val peakingColorHandle = GLES20.glGetUniformLocation(program, "uPeakingColor")

    private val vertexBuffer = floatBufferOf(FULLSCREEN_QUAD)
    private val texCoordBuffer = floatBufferOf(TEX_COORDS)

    data class DrawParams(
        val cameraTextureId: Int,
        val texMatrix: FloatArray,
        val lutTextureId: Int,
        val lutSize: Int,
        val lutStrength: Float,
        val logEnabled: Boolean,
        val zebraEnabled: Boolean,
        val zebraThreshold: Float,
        val focusPeakingEnabled: Boolean,
        val sourceWidth: Int,
        val sourceHeight: Int,
        val timeSeconds: Float,
    )

    fun draw(params: DrawParams) {
        GLES20.glUseProgram(program)
        GlUtil.checkGlError("glUseProgram")

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, params.cameraTextureId)
        GLES20.glUniform1i(textureHandle, 0)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, params.lutTextureId)
        GLES20.glUniform1i(lutTextureHandle, 1)

        GLES20.glUniformMatrix4fv(texMatrixHandle, 1, false, params.texMatrix, 0)
        GLES20.glUniform1f(lutSizeHandle, params.lutSize.toFloat())
        GLES20.glUniform1f(lutStrengthHandle, params.lutStrength)
        GLES20.glUniform1i(logEnabledHandle, if (params.logEnabled) 1 else 0)
        GLES20.glUniform1i(zebraEnabledHandle, if (params.zebraEnabled) 1 else 0)
        GLES20.glUniform1f(zebraThresholdHandle, params.zebraThreshold)
        GLES20.glUniform1f(timeHandle, params.timeSeconds)
        GLES20.glUniform1i(peakingEnabledHandle, if (params.focusPeakingEnabled) 1 else 0)
        GLES20.glUniform2f(
            texelSizeHandle,
            1f / params.sourceWidth.coerceAtLeast(1),
            1f / params.sourceHeight.coerceAtLeast(1)
        )
        GLES20.glUniform3f(peakingColorHandle, 1f, 0.15f, 0.85f)

        vertexBuffer.position(0)
        GLES20.glEnableVertexAttribArray(positionHandle)
        GLES20.glVertexAttribPointer(positionHandle, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer)

        texCoordBuffer.position(0)
        GLES20.glEnableVertexAttribArray(texCoordHandle)
        GLES20.glVertexAttribPointer(texCoordHandle, 2, GLES20.GL_FLOAT, false, 0, texCoordBuffer)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GlUtil.checkGlError("glDrawArrays")

        GLES20.glDisableVertexAttribArray(positionHandle)
        GLES20.glDisableVertexAttribArray(texCoordHandle)
    }

    fun release() {
        GLES20.glDeleteProgram(program)
    }

    private fun floatBufferOf(data: FloatArray): FloatBuffer =
        ByteBuffer.allocateDirect(data.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            put(data)
            position(0)
        }

    companion object {
        private val FULLSCREEN_QUAD = floatArrayOf(
            -1f, -1f,
            1f, -1f,
            -1f, 1f,
            1f, 1f,
        )
        private val TEX_COORDS = floatArrayOf(
            0f, 0f,
            1f, 0f,
            0f, 1f,
            1f, 1f,
        )

        private const val VERTEX_SHADER = """
            attribute vec4 aPosition;
            attribute vec4 aTextureCoord;
            uniform mat4 uTexMatrix;
            varying vec2 vTextureCoord;
            void main() {
                gl_Position = aPosition;
                vTextureCoord = (uTexMatrix * aTextureCoord).xy;
            }
        """

        private const val FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTextureCoord;
            uniform samplerExternalOES sTexture;
            uniform sampler2D uLutTexture;
            uniform float uLutSize;
            uniform float uLutStrength;
            uniform bool uLogEnabled;
            uniform bool uZebraEnabled;
            uniform float uZebraThreshold;
            uniform float uTime;
            uniform bool uFocusPeakingEnabled;
            uniform vec2 uTexelSize;
            uniform vec3 uPeakingColor;

            vec3 applyLogCurve(vec3 c) {
                vec3 lifted = c * 0.65 + 0.09;
                return pow(clamp(lifted, 0.0, 1.0), vec3(0.92));
            }

            vec4 sampleLut3d(vec3 color) {
                float size = uLutSize;
                float blue = color.b * (size - 1.0);
                float bSlice1 = floor(blue);
                float bSlice2 = min(bSlice1 + 1.0, size - 1.0);
                float rNorm = color.r * (size - 1.0);
                float gNorm = color.g * (size - 1.0);

                vec2 texPos1;
                texPos1.x = (bSlice1 * size + rNorm + 0.5) / (size * size);
                texPos1.y = (gNorm + 0.5) / size;

                vec2 texPos2;
                texPos2.x = (bSlice2 * size + rNorm + 0.5) / (size * size);
                texPos2.y = (gNorm + 0.5) / size;

                vec4 c1 = texture2D(uLutTexture, texPos1);
                vec4 c2 = texture2D(uLutTexture, texPos2);
                return mix(c1, c2, fract(blue));
            }

            float luma(vec3 c) { return dot(c, vec3(0.299, 0.587, 0.114)); }

            void main() {
                vec4 base = texture2D(sTexture, vTextureCoord);
                vec3 color = base.rgb;

                if (uLogEnabled) {
                    color = applyLogCurve(color);
                }

                if (uLutSize > 1.5) {
                    vec3 lutColor = sampleLut3d(clamp(color, 0.0, 1.0)).rgb;
                    color = mix(color, lutColor, uLutStrength);
                }

                if (uFocusPeakingEnabled) {
                    float lC = luma(color);
                    float lL = luma(texture2D(sTexture, vTextureCoord - vec2(uTexelSize.x, 0.0)).rgb);
                    float lR = luma(texture2D(sTexture, vTextureCoord + vec2(uTexelSize.x, 0.0)).rgb);
                    float lU = luma(texture2D(sTexture, vTextureCoord - vec2(0.0, uTexelSize.y)).rgb);
                    float lD = luma(texture2D(sTexture, vTextureCoord + vec2(0.0, uTexelSize.y)).rgb);
                    float edge = abs(lC * 4.0 - lL - lR - lU - lD);
                    if (edge > 0.25) {
                        color = mix(color, uPeakingColor, min(edge * 1.5, 1.0));
                    }
                }

                if (uZebraEnabled) {
                    float l = luma(base.rgb);
                    if (l >= uZebraThreshold) {
                        float stripe = fract((gl_FragCoord.x + gl_FragCoord.y + uTime * 60.0) / 10.0);
                        if (stripe < 0.5) {
                            color = vec3(1.0, 1.0, 1.0) - color * 0.2;
                        }
                    }
                }

                gl_FragColor = vec4(color, base.a);
            }
        """
    }
}
