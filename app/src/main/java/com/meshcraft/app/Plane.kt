package com.meshcraft.app

/**
 * Datos de geometria del plano (vertices, normales, orden de dibujo) - toda la infraestructura de
 * OpenGL (shaders, buffers, draw) vive en MeshGeometry.kt, compartida con Cube. Ver MeshGeometry.kt
 * para el porque de este split.
 *
 * Un solo quad chato sobre el plano XY (Z=0), mismo plano que usa la grilla del piso (ver
 * GridPlane.XY en Grid.kt) - igual que el Plane por defecto de Blender, que queda apoyado en el
 * suelo. Tamano 1x1 (de -0.5 a 0.5 en X e Y), mismo tamano base que Cube (unit cube 1x1x1) para
 * que los dos partan del mismo orden de magnitud visual al agregarse.
 */
class Plane {

    // 4 vertices (un solo quad) - a diferencia del Cube no hace falta duplicar vertices por cara,
    // solo hay una cara. Sin GL_CULL_FACE habilitado en la app (ver onSurfaceCreated), el quad se
    // ve por igual desde arriba y desde abajo con esta unica normal +Z.
    private val faceVertices = floatArrayOf(
        -0.5f, -0.5f, 0f,
        0.5f, -0.5f, 0f,
        0.5f, 0.5f, 0f,
        -0.5f, 0.5f, 0f
    )

    private val faceNormals = floatArrayOf(
        0f, 0f, 1f,
        0f, 0f, 1f,
        0f, 0f, 1f,
        0f, 0f, 1f
    )

    private val faceDrawOrder = shortArrayOf(
        0, 1, 2, 0, 2, 3
    )

    private val edgeScale = 1.003f
    private val edgeVertices = floatArrayOf(
        -0.5f, -0.5f, 0f,
        0.5f, -0.5f, 0f,
        0.5f, 0.5f, 0f,
        -0.5f, 0.5f, 0f
    ).map { it * edgeScale }.toFloatArray()

    private val edgeDrawOrder = shortArrayOf(
        0, 1, 1, 2, 2, 3, 3, 0
    )

    private val geometry = MeshGeometry(faceVertices, faceNormals, faceDrawOrder, edgeVertices, edgeDrawOrder)

    fun draw(mvpMatrix: FloatArray, selected: Boolean) {
        geometry.draw(mvpMatrix, selected)
    }
}
