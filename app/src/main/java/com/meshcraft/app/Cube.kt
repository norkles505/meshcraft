package com.meshcraft.app

/**
 * Datos de geometria del cubo (vertices, normales, orden de dibujo) - toda la infraestructura de
 * OpenGL (shaders, buffers, draw) vive en MeshGeometry.kt, compartida con cualquier primitiva
 * futura. Ver MeshGeometry.kt para el porque de este split.
 */
class Cube {

    // 24 vertices (4 por cara) para que cada cara tenga su propia normal plana (flat shading).
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

    private val geometry = MeshGeometry(faceVertices, faceNormals, faceDrawOrder, edgeVertices, edgeDrawOrder)

    fun draw(mvpMatrix: FloatArray, selected: Boolean) {
        geometry.draw(mvpMatrix, selected)
    }
}
