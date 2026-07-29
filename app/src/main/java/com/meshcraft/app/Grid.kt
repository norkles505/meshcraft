package com.meshcraft.app

import android.opengl.GLES20
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

enum class GridPlane { XY, XZ, YZ }

// Blender-style reference grid.
// XY = ground plane (used for Top view and free orbiting).
// XZ = a "wall" facing you in Front/Back (Y-axis) view.
// YZ = a "wall" facing you in Right/Left (X-axis) view.
class Grid(private val plane: GridPlane, private val size: Int = 10, private val spacing: Float = 1f) {

    private val vertexShaderCode = """
        uniform mat4 uMVPMatrix;
        attribute vec4 vPosition;
        attribute vec4 vColor;
        varying vec4 fColor;
        void main() {
            gl_Position = uMVPMatrix * vPosition;
            fColor = vColor;
        }
    """.trimIndent()

    private val fragmentShaderCode = """
        precision mediump float;
        varying vec4 fColor;
        void main() {
            gl_FragColor = fColor;
        }
    """.trimIndent()

    private val vertexBuffer: FloatBuffer
    private val colorBuffer: FloatBuffer
    private val vertexCount: Int
    private val program: Int

    init {
        val positions = mutableListOf<Float>()
        val colors = mutableListOf<Float>()

        val gray = floatArrayOf(0.32f, 0.32f, 0.32f, 1f)
        val red = floatArrayOf(0.85f, 0.25f, 0.25f, 1f)
        val green = floatArrayOf(0.25f, 0.75f, 0.3f, 1f)
        val blue = floatArrayOf(0.35f, 0.55f, 0.85f, 1f)

        val limit = size * spacing

        fun addLine(p1: FloatArray, p2: FloatArray, color: FloatArray) {
            positions.addAll(listOf(p1[0], p1[1], p1[2], p2[0], p2[1], p2[2]))
            repeat(2) { colors.addAll(color.toList()) }
        }

        when (plane) {
            GridPlane.XY -> {
                for (i in -size..size) {
                    val v = i * spacing
                    addLine(floatArrayOf(-limit, v, 0f), floatArrayOf(limit, v, 0f), if (i == 0) red else gray)
                }
                for (i in -size..size) {
                    val v = i * spacing
                    addLine(floatArrayOf(v, -limit, 0f), floatArrayOf(v, limit, 0f), if (i == 0) green else gray)
                }
            }
            GridPlane.XZ -> {
                for (i in -size..size) {
                    val v = i * spacing
                    addLine(floatArrayOf(-limit, 0f, v), floatArrayOf(limit, 0f, v), if (i == 0) red else gray)
                }
                for (i in -size..size) {
                    val v = i * spacing
                    addLine(floatArrayOf(v, 0f, -limit), floatArrayOf(v, 0f, limit), if (i == 0) blue else gray)
                }
            }
            GridPlane.YZ -> {
                for (i in -size..size) {
                    val v = i * spacing
                    addLine(floatArrayOf(0f, -limit, v), floatArrayOf(0f, limit, v), if (i == 0) green else gray)
                }
                for (i in -size..size) {
                    val v = i * spacing
                    addLine(floatArrayOf(0f, v, -limit), floatArrayOf(0f, v, limit), if (i == 0) blue else gray)
                }
            }
        }

        vertexCount = positions.size / 3

        vertexBuffer = makeFloatBuffer(positions.toFloatArray())
        colorBuffer = makeFloatBuffer(colors.toFloatArray())

        val vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, vertexShaderCode)
        val fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentShaderCode)
        program = GLES20.glCreateProgram().also {
            GLES20.glAttachShader(it, vertexShader)
            GLES20.glAttachShader(it, fragmentShader)
            GLES20.glLinkProgram(it)
        }
    }

    fun draw(mvpMatrix: FloatArray) {
        GLES20.glUseProgram(program)

        val posHandle = GLES20.glGetAttribLocation(program, "vPosition")
        GLES20.glEnableVertexAttribArray(posHandle)
        GLES20.glVertexAttribPointer(posHandle, 3, GLES20.GL_FLOAT, false, 0, vertexBuffer)

        val colorHandle = GLES20.glGetAttribLocation(program, "vColor")
        GLES20.glEnableVertexAttribArray(colorHandle)
        GLES20.glVertexAttribPointer(colorHandle, 4, GLES20.GL_FLOAT, false, 0, colorBuffer)

        val mvpHandle = GLES20.glGetUniformLocation(program, "uMVPMatrix")
        GLES20.glUniformMatrix4fv(mvpHandle, 1, false, mvpMatrix, 0)

        GLES20.glLineWidth(1f)
        GLES20.glDrawArrays(GLES20.GL_LINES, 0, vertexCount)

        GLES20.glDisableVertexAttribArray(posHandle)
        GLES20.glDisableVertexAttribArray(colorHandle)
    }

    private fun makeFloatBuffer(data: FloatArray): FloatBuffer {
        return ByteBuffer.allocateDirect(data.size * 4).run {
            order(ByteOrder.nativeOrder())
            asFloatBuffer().apply {
                put(data)
                position(0)
            }
        }
    }

    private fun loadShader(type: Int, shaderCode: String): Int {
        return GLES20.glCreateShader(type).also { shader ->
            GLES20.glShaderSource(shader, shaderCode)
            GLES20.glCompileShader(shader)
        }
    }
}
