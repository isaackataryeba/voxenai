package com.voxenai.cinecam.gl

import android.opengl.GLES20
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * A parsed 3D LUT ready to be uploaded as a single 2D "tile strip" texture: width = size*size,
 * height = size, tile (blue slice) laid out left-to-right, with red across a tile's x axis and
 * green across its y axis. This lets the fragment shader do a 3D LUT lookup with two 2D texture
 * fetches + a manual blend between the two nearest blue slices, which works on plain GLES2
 * hardware without requiring GL_TEXTURE_3D / GLES30.
 */
class CubeLut(val size: Int, val rgbaTileStrip: ByteBuffer) {
    val textureWidth: Int get() = size * size
    val textureHeight: Int get() = size

    fun uploadTexture(): Int {
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        val texId = textures[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        rgbaTileStrip.rewind()
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, textureWidth, textureHeight, 0,
            GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, rgbaTileStrip
        )
        GlUtil.checkGlError("upload LUT texture")
        return texId
    }

    companion object {
        /** Identity LUT (no-op passthrough) used as the default / "None" selection. */
        fun identity(size: Int = 2): CubeLut {
            val buffer = ByteBuffer.allocateDirect(size * size * size * 4).order(ByteOrder.nativeOrder())
            for (b in 0 until size) {
                for (g in 0 until size) {
                    for (r in 0 until size) {
                        buffer.put(scale(r, size))
                        buffer.put(scale(g, size))
                        buffer.put(scale(b, size))
                        buffer.put(255.toByte())
                    }
                }
            }
            buffer.rewind()
            return CubeLut(size, buffer)
        }

        private fun scale(index: Int, size: Int): Byte =
            (255.0 * index / (size - 1).coerceAtLeast(1)).toInt().coerceIn(0, 255).toByte()

        /** Parses an Adobe/Resolve-style ASCII `.cube` 3D LUT file. */
        fun parse(input: InputStream): CubeLut {
            var lutSize = 0
            val values = ArrayList<FloatArray>()

            BufferedReader(InputStreamReader(input)).use { reader ->
                reader.forEachLine { rawLine ->
                    val line = rawLine.trim()
                    if (line.isEmpty() || line.startsWith("#")) return@forEachLine
                    when {
                        line.startsWith("LUT_3D_SIZE") -> {
                            lutSize = line.substringAfter("LUT_3D_SIZE").trim().toInt()
                        }
                        line[0].isDigit() || line[0] == '-' || line[0] == '+' -> {
                            val parts = line.split(Regex("\\s+"))
                            if (parts.size >= 3) {
                                values.add(
                                    floatArrayOf(parts[0].toFloat(), parts[1].toFloat(), parts[2].toFloat())
                                )
                            }
                        }
                        // TITLE / DOMAIN_MIN / DOMAIN_MAX / LUT_1D_SIZE and anything else: ignored.
                    }
                }
            }

            require(lutSize > 0) { "Missing LUT_3D_SIZE in .cube file" }
            require(values.size == lutSize * lutSize * lutSize) {
                "Expected ${lutSize * lutSize * lutSize} LUT entries, found ${values.size}"
            }

            val buffer = ByteBuffer.allocateDirect(lutSize * lutSize * lutSize * 4).order(ByteOrder.nativeOrder())
            val flat = ByteArray(lutSize * lutSize * lutSize * 4)

            // .cube raster order: red fastest, then green, then blue slowest.
            for (idx in values.indices) {
                val r = idx % lutSize
                val g = (idx / lutSize) % lutSize
                val b = idx / (lutSize * lutSize)
                val column = b * lutSize + r
                val row = g
                val pixelOffset = (row * (lutSize * lutSize) + column) * 4
                val rgb = values[idx]
                flat[pixelOffset] = to255(rgb[0])
                flat[pixelOffset + 1] = to255(rgb[1])
                flat[pixelOffset + 2] = to255(rgb[2])
                flat[pixelOffset + 3] = 255.toByte()
            }

            buffer.put(flat)
            buffer.rewind()
            return CubeLut(lutSize, buffer)
        }

        private fun to255(v: Float): Byte = (v.coerceIn(0f, 1f) * 255f).toInt().coerceIn(0, 255).toByte()
    }
}
