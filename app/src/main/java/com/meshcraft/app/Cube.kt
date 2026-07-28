package com.meshcraft.app

import android.opengl.GLES20
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer

class Cube {

    // ---------- Solid shaded faces (Blender-like flat gray shading) ----------
    private val faceVertexShaderCode = """
        uniform mat4 uMVPMatrix;
        attribute vec4 vPosition;
        attribute vec3 vNormal;
        varying vec3 fNormal;
        void main() {
            gl_Position = uMVPMatrix * vPosition;
            fNormal = vNormal;
        }
    """.trimIndent()

    private val faceFragmentShaderCode = """
        precision mediump float;
        varying vec3 fNormal;
        void main() {
            vec3 lightDir = normalize(vec3(0.4, 0.9, 0.6));
            float diff = max(dot(normalize(fNormal), lightDir), 0.0);
            float shade = 0.45 + 0.55 * diff;
            vec3 baseColor = vec3(0.62, 0.62, 0.62);
            gl_FragColor = vec4(baseColor * shade, 1.0);
        }
    """.trimIndent()

    // 24 vertices (4 per face) so each face gets its own flat normal
    private val faceVertices = floatArrayOf(
        // Front (+Z)
        -0.5f, -0.5f, 0.5f, 0.5f, -0.5f, 0.5f, 0.5f, 0.5f, 0.5f, -0.5f, 0.5f, 0.5f,
        // Back (-Z)
        0.5f, -0.5f, -0.5f, -0.5f, -0.5f, -0.5f, -0.5f, 0.5f, -0.5f, 0.5f, 0.5f, -0.5f,
        // Right (+X)
        0.5f, -0.5f, 0.5f, 0.5f, -0.5f, -0.5f, 0.5f, 0.5f, -0.5f, 0.5f, 0.5f, 0.5f,
        // Left (-X)
        -0.5f, -0.5f, -0.5f, -0.5f, -0.5f, 0.5f, -0.5f, 0.5f, 0.5f, -0.5f, 0.5f, -0.5f,
        // Top (+Y)
        -0.5f, 0.5f, 0.5f, 0.5f, 0.5f, 0.5f, 0.5f, 0.5f, -0.5f, -0.5f, 0.5f, -0.5f,
        // Bottom (-Y)
        -0.5f, -0.5f, -0.5f, 0.5f, -0.5f, -0.5f, 0.5f, -0.5f, 0.5f, -0.5f, -0.5f, 0.5f
    )

    private val faceNormals = floatArrayOf(
        0f, 0f, 1f, 0f, 0f, 1f, 0f, 0f, 1f, 0f, 0f, 1f,
        0f, 0f, -1f, 0f, 0f, -1f, 0f, 0f, -1f, 0f, 0f, -1f,
        1f, 0f, 0f, 1f, 0f, 0f, 1f, 0f, 0f, 1f, 0f, 0f,
        -1f, 0f, 0f, -1f, 0f, 0f, -1f, 0f, 0f, -1f, 0f, 0f,
        0f, 1f, 0f, 0f, 1f, 0f, 0f, 1f, 0f, 0f, 1f, 0f,
        0f, -1f, 0f, 0f, -1f, 0f, 0f, -1f, 0f, 0f, -1f, 0f
    )

    private val faceDrawOrder = shortArrayOf(
        0, 1, 2, 0, 2, 3,
        4, 5, 6, 4, 6, 7,
        8, 9, 10, 8, 10, 11,
        12, 13, 14, 12, 14, 15,
        16, 17, 18, 16, 18, 19,
        20, 21, 22, 20, 22, 23
    )

    // ---------- Orange selection outline ----------
    private val lineVertexShaderCode = """
        uniform mat4 uMVPMatrix;
        attribute vec4 vPosition;
        void main() {
            gl_Position = uMVPMatrix * vPosition;
        }
    """.trimIndent()

    private val lineFragmentShaderCode = """
        precision mediump float;
        void main() {
            gl_FragColor = vec4(0.95, 0.5, 0.1, 1.0);
        }
    """.trimIndent()

    private val edgeScale = 1.003f
    private val edgeVertices = floatArrayOf(
        -0.5f, -0.5f, 0.5f, 0.5f, -0.5f, 0.5f, 0.5f, 0.5f, 0.5f, -0.5f, 0.5f, 0.5f,
        -0.5f, -0.5f, -0.5f, 0.5f, -0.5f, -0.5f, 0.5f, 0.5f, -0.5f, -0.5f, 0.5f, -0.5f
    ).map { it * edgeScale }.toFloatArray()

    private val edgeDrawOrder = shortArrayOf(
        0, 1, 1, 2, 2, 3, 3, 0,
        4, 5, 5, 6, 6, 7, 7, 4,
        0, 4, 1, 5, 2, 6, 3, 7
    )

    private val faceVertexBuffer: FloatBuffer
    private val faceNormalBuffer: FloatBuffer
    private val faceIndexBuffer: ShortBuffer
    private val faceProgram: Int

    private val edgeVertexBuffer: FloatBuffer
    private val edgeIndexBuffer: ShortBuffer
    private val lineProgram: Int

    init {
        faceVertexBuffer = makeFloatBuffer(faceVertices)
        faceNormalBuffer = makeFloatBuffer(faceNormals)
        faceIndexBuffer = makeShortBuffer(faceDrawOrder)
        faceProgram = buildProgram(faceVertexShaderCode, faceFragmentShaderCode)

        edgeVertexBuffer = makeFloatBuffer(edgeVertices)
        edgeIndexBuffer = makeShortBuffer(edgeDrawOrder)
        lineProgram = buildProgram(lineVertexShaderCode, lineFragmentShaderCode)
    }

    fun draw(mvpMatrix: FloatArray) {
        // Solid shaded faces
        GLES20.glUseProgram(faceProgram)

        val posHandle = GLES20.glGetAttribLocation(faceProgram, "vPosition")
        GLES20.glEnableVertexAttribArray(posHandle)
        GLES20.glVertexAttribPointer(posHandle, 3, GLES20.GL_FLOAT, false, 0, faceVertexBuffer)

        val normalHandle = GLES20.glGetAttribLocation(faceProgram, "vNormal")
        GLES20.glEnableVertexAttribArray(normalHandle)
        GLES20.glVertexAttribPointer(normalHandle, 3, GLES20.GL_FLOAT, false, 0, faceNormalBuffer)

        val mvpHandle = GLES20.glGetUniformLocation(faceProgram, "uMVPMatrix")
        GLES20.glUniformMatrix4fv(mvpHandle, 1, false, mvpMatrix, 0)

        GLES20.glDrawElements(GLES20.GL_TRIANGLES, faceDrawOrder.size, GLES20.GL_UNSIGNED_SHORT, faceIndexBuffer)

        GLES20.glDisableVertexAttribArray(posHandle)
        GLES20.glDisableVertexAttribArray(normalHandle)

        // Orange selection outline
        GLES20.glUseProgram(lineProgram)

        val linePosHandle = GLES20.glGetAttribLocation(lineProgram, "vPosition")
        GLES20.glEnableVertexAttribArray(linePosHandle)
        GLES20.glVertexAttribPointer(linePosHandle, 3, GLES20.GL_FLOAT, false, 0, edgeVertexBuffer)

        val lineMvpHandle = GLES20.glGetUniformLocation(lineProgram, "uMVPMatrix")
        GLES20.glUniformMatrix4fv(lineMvpHandle, 1, false, mvpMatrix, 0)

        GLES20.glLineWidth(4f)
        GLES20.glDrawElements(GLES20.GL_LINES, edgeDrawOrder.size, GLES20.GL_UNSIGNED_SHORT, edgeIndexBuffer)

        GLES20.glDisableVertexAttribArray(linePosHandle)
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

    private fun makeShortBuffer(data: ShortArray): ShortBuffer {
        return ByteBuffer.allocateDirect(data.size * 2).run {
            order(ByteOrder.nativeOrder())
            asShortBuffer().apply {
                put(data)
                position(0)
            }
        }
    }

    private fun buildProgram(vertexCode: String, fragmentCode: String): Int {
        val vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, vertexCode)
        val fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentCode)
        return GLES20.glCreateProgram().also {
            GLES20.glAttachShader(it, vertexShader)
            GLES20.glAttachShader(it, fragmentShader)
            GLES20.glLinkProgram(it)
        }
    }

    private fun loadShader(type: Int, shaderCode: String): Int {
        return GLES20.glCreateShader(type).also { shader ->
            GLES20.glShaderSource(shader, shaderCode)
            GLES20.glCompileShader(shader)
        }
    }
}
