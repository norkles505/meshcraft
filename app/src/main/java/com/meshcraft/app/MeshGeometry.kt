package com.meshcraft.app

import android.opengl.GLES20
import java.nio.FloatBuffer
import java.nio.ShortBuffer

/**
 * Geometria solida generica (caras sombreadas + contorno naranja de seleccion), reusable por
 * cualquier primitiva (Cube, y las que se agreguen despues - Plane, UvSphere, Cylinder, etc.).
 *
 * Antes (ver Cube.kt original) cada primitiva tenia su propia copia de shaders, buffers y logica
 * de draw() - mismo codigo de ~80 lineas repetido por cada geometria nueva. Ahora una primitiva
 * nueva solo aporta sus datos (vertices/normales/indices de caras y de aristas) via el
 * constructor; toda la infraestructura de OpenGL (shaders, buffers, handles, draw) vive aca, en
 * un solo lugar.
 *
 * Los shaders (iluminación difusa simple para las caras, color solido naranja para el contorno)
 * son los mismos que ya usaba Cube - no cambia nada visualmente.
 *
 * A diferencia del Cube.kt original, los handles de atributos/uniforms se buscan (glGetAttribLocation/
 * glGetUniformLocation) UNA sola vez en el init, no en cada frame dentro de draw() - antes se
 * repetia esa busqueda en cada llamada a draw(), trabajo redundante ya que el handle no cambia
 * mientras el program siga vivo.
 */
class MeshGeometry(
    faceVertices: FloatArray,
    faceNormals: FloatArray,
    faceDrawOrder: ShortArray,
    edgeVertices: FloatArray,
    edgeDrawOrder: ShortArray
) {

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

    private val faceVertexBuffer: FloatBuffer = GLUtils.makeFloatBuffer(faceVertices)
    private val faceNormalBuffer: FloatBuffer = GLUtils.makeFloatBuffer(faceNormals)
    private val faceIndexBuffer: ShortBuffer = GLUtils.makeShortBuffer(faceDrawOrder)
    private val faceIndexCount: Int = faceDrawOrder.size
    private val faceProgram: Int = GLUtils.buildProgram(faceVertexShaderCode, faceFragmentShaderCode)

    private val edgeVertexBuffer: FloatBuffer = GLUtils.makeFloatBuffer(edgeVertices)
    private val edgeIndexBuffer: ShortBuffer = GLUtils.makeShortBuffer(edgeDrawOrder)
    private val edgeIndexCount: Int = edgeDrawOrder.size
    private val lineProgram: Int = GLUtils.buildProgram(lineVertexShaderCode, lineFragmentShaderCode)

    // Handles cacheados una sola vez (ver comentario de la clase) - antes se buscaban en cada draw().
    private val facePosHandle = GLES20.glGetAttribLocation(faceProgram, "vPosition")
    private val faceNormalHandle = GLES20.glGetAttribLocation(faceProgram, "vNormal")
    private val faceMvpHandle = GLES20.glGetUniformLocation(faceProgram, "uMVPMatrix")

    private val linePosHandle = GLES20.glGetAttribLocation(lineProgram, "vPosition")
    private val lineMvpHandle = GLES20.glGetUniformLocation(lineProgram, "uMVPMatrix")

    /**
     * selected: si es false, se dibujan las caras solidas pero se salta el contorno naranja -
     * mismo comportamiento que tenia Cube.draw() originalmente.
     */
    fun draw(mvpMatrix: FloatArray, selected: Boolean) {
        // Caras solidas sombreadas
        GLES20.glUseProgram(faceProgram)

        GLES20.glEnableVertexAttribArray(facePosHandle)
        GLES20.glVertexAttribPointer(facePosHandle, 3, GLES20.GL_FLOAT, false, 0, faceVertexBuffer)

        GLES20.glEnableVertexAttribArray(faceNormalHandle)
        GLES20.glVertexAttribPointer(faceNormalHandle, 3, GLES20.GL_FLOAT, false, 0, faceNormalBuffer)

        GLES20.glUniformMatrix4fv(faceMvpHandle, 1, false, mvpMatrix, 0)

        GLES20.glDrawElements(GLES20.GL_TRIANGLES, faceIndexCount, GLES20.GL_UNSIGNED_SHORT, faceIndexBuffer)

        GLES20.glDisableVertexAttribArray(facePosHandle)
        GLES20.glDisableVertexAttribArray(faceNormalHandle)

        // Contorno naranja de seleccion - solo si el objeto esta seleccionado
        if (!selected) return

        GLES20.glUseProgram(lineProgram)

        GLES20.glEnableVertexAttribArray(linePosHandle)
        GLES20.glVertexAttribPointer(linePosHandle, 3, GLES20.GL_FLOAT, false, 0, edgeVertexBuffer)

        GLES20.glUniformMatrix4fv(lineMvpHandle, 1, false, mvpMatrix, 0)

        GLES20.glLineWidth(4f)
        GLES20.glDrawElements(GLES20.GL_LINES, edgeIndexCount, GLES20.GL_UNSIGNED_SHORT, edgeIndexBuffer)

        GLES20.glDisableVertexAttribArray(linePosHandle)
    }
}
