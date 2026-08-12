package com.meshcraft.app

/**
 * Datos de geometria de la primitiva Grid (vertices, normales, orden de dibujo) - toda la
 * infraestructura de OpenGL (shaders, buffers, draw) vive en MeshGeometry.kt, compartida con el
 * resto de las primitivas. Ver MeshGeometry.kt para el porque de este split.
 *
 * Se llama GridMesh (no Grid) porque ese nombre ya lo usa la grilla de referencia del piso/paredes
 * (ver Grid.kt / GridPlane) - son cosas distintas: esa es solo lineas de referencia sin caras ni
 * seleccion, esta es una primitiva de malla real (Add > Mesh > Grid de Blender), un Plane
 * subdividido en xSubdivisions * ySubdivisions quads.
 *
 * A diferencia de Torus/Cylinder/IcoSphere (superficies curvas, normal distinta por cara), esta
 * malla es perfectamente PLANA - mismo criterio que Plane.kt: una sola normal constante (0,0,1)
 * para todos los vertices, y vertices COMPARTIDOS entre celdas vecinas (no explotados por
 * triangulo) via faceDrawOrder, ya que no hace falta una normal propia por cara.
 *
 * Grilla sobre el plano XY (Z=0), mismo plano que Plane y que la grilla de referencia del piso
 * (GridPlane.XY). Tamano 1x1 (de -0.5 a 0.5 en X e Y), igual que Plane, para que las dos primitivas
 * partan del mismo orden de magnitud visual. xSubdivisions/ySubdivisions 10 y 10, mismo default
 * (X Subdivisions=10, Y Subdivisions=10) que usa Blender para Add > Mesh > Grid.
 */
class GridMesh(private val xSubdivisions: Int = 10, private val ySubdivisions: Int = 10, private val size: Float = 1f) {

    private val edgeScale = 1.003f

    private val faceVertices: FloatArray
    private val faceNormals: FloatArray
    private val faceDrawOrder: ShortArray
    private val edgeVertices: FloatArray
    private val edgeDrawOrder: ShortArray
    private val geometry: MeshGeometry

    /** Indice de un vertice de la grilla (ix = columna, iy = fila) en los arrays planos de abajo - misma grilla para caras y aristas. */
    private fun idx(ix: Int, iy: Int) = ix * (ySubdivisions + 1) + iy

    init {
        val half = size / 2f
        val xCount = xSubdivisions + 1
        val yCount = ySubdivisions + 1

        // Grilla de (xSubdivisions+1) x (ySubdivisions+1) vertices, de -half a +half en X e Y -
        // mismo criterio de generar por loop en el init que ya usan Circle/UvSphere/Cylinder/Cone/
        // Torus en vez de escribir vertices a mano.
        val grid = Array(xCount) { ix ->
            Array(yCount) { iy ->
                floatArrayOf(-half + (ix.toFloat() / xSubdivisions) * size, -half + (iy.toFloat() / ySubdivisions) * size, 0f)
            }
        }

        // Vertices COMPARTIDOS (no explotados por triangulo, a diferencia de Torus/Cylinder): al
        // ser plana, la normal es la misma (0,0,1) para todo el mundo, asi que no hace falta una
        // copia propia por cara - mismo criterio que Plane, generalizado a una grilla.
        val fVerts = mutableListOf<Float>()
        val fNorms = mutableListOf<Float>()
        for (ix in 0 until xCount) {
            for (iy in 0 until yCount) {
                val p = grid[ix][iy]
                fVerts.addAll(listOf(p[0], p[1], p[2]))
                fNorms.addAll(listOf(0f, 0f, 1f))
            }
        }
        faceVertices = fVerts.toFloatArray()
        faceNormals = fNorms.toFloatArray()

        // Por cada celda (ix,iy), un quad entre las 4 esquinas (ix,iy)-(ix+1,iy)-(ix+1,iy+1)-
        // (ix,iy+1), partido en 2 triangulos - mismo winding que el quad unico de Plane
        // (0,1,2,0,2,3), que da normal +Z via regla de la mano derecha (verificado a mano).
        val fOrder = mutableListOf<Short>()
        for (ix in 0 until xSubdivisions) {
            for (iy in 0 until ySubdivisions) {
                val v00 = idx(ix, iy).toShort()
                val v10 = idx(ix + 1, iy).toShort()
                val v11 = idx(ix + 1, iy + 1).toShort()
                val v01 = idx(ix, iy + 1).toShort()
                fOrder.addAll(listOf(v00, v10, v11, v00, v11, v01))
            }
        }
        faceDrawOrder = fOrder.toShortArray()

        // Contorno de seleccion: wireframe completo de la grilla (todas las lineas de subdivision,
        // en las dos direcciones) - mismo nivel de detalle que UvSphere/Cylinder/Torus (no solo la
        // silueta exterior), escalado levemente hacia afuera (edgeScale) igual que el resto de las
        // primitivas. Reusa la misma grilla de vertices para no duplicar la logica de generacion.
        val eVerts = mutableListOf<Float>()
        for (ix in 0 until xCount) {
            for (iy in 0 until yCount) {
                val p = grid[ix][iy]
                eVerts.addAll(listOf(p[0] * edgeScale, p[1] * edgeScale, p[2] * edgeScale))
            }
        }
        edgeVertices = eVerts.toFloatArray()

        val eOrder = mutableListOf<Short>()
        // Lineas horizontales (una por cada fila iy, uniendo columnas consecutivas).
        for (iy in 0 until yCount) {
            for (ix in 0 until xSubdivisions) {
                eOrder.add(idx(ix, iy).toShort())
                eOrder.add(idx(ix + 1, iy).toShort())
            }
        }
        // Lineas verticales (una por cada columna ix, uniendo filas consecutivas).
        for (ix in 0 until xCount) {
            for (iy in 0 until ySubdivisions) {
                eOrder.add(idx(ix, iy).toShort())
                eOrder.add(idx(ix, iy + 1).toShort())
            }
        }
        edgeDrawOrder = eOrder.toShortArray()

        geometry = MeshGeometry(faceVertices, faceNormals, faceDrawOrder, edgeVertices, edgeDrawOrder)
    }

    fun draw(mvpMatrix: FloatArray, selected: Boolean) {
        geometry.draw(mvpMatrix, selected)
    }
}
