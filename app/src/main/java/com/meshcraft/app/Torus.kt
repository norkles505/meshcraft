package com.meshcraft.app

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Datos de geometria del torus (vertices, normales, orden de dibujo) - toda la infraestructura de
 * OpenGL (shaders, buffers, draw) vive en MeshGeometry.kt, compartida con el resto de las
 * primitivas. Ver MeshGeometry.kt para el porque de este split.
 *
 * A diferencia de Cylinder/Cone (un aro que se cierra con modulo, mas tapas), el torus es una
 * grilla DOBLEMENTE periodica: majorSegments vueltas alrededor del eje Z (el "aro grande") y,
 * en cada una, minorSegments vueltas alrededor de un tubo circular (el "aro chico") - mismo
 * criterio de generar por loop en el init que ya usan Circle/UvSphere/Cylinder/Cone en vez de
 * escribir vertices a mano. No hay polos ni tapas que armar por separado (a diferencia de
 * UvSphere/Cylinder): la superficie ya cierra sola en las dos direcciones via modulo, igual que
 * el aro de Cylinder/Cone pero aplicado dos veces.
 *
 * Para el indice mayor i (angulo theta, vuelta grande) la direccion radial en el plano XY es
 * radDir = (cos theta, sin theta, 0) y el centro del tubo en ese punto es majorRadius * radDir.
 * Para el indice menor j (angulo phi, vuelta chica alrededor del tubo) cada vertice se desplaza
 * desde ese centro minorRadius*cos(phi) en la direccion radDir (hacia adentro/afuera del aro
 * grande) y minorRadius*sin(phi) en +Z (hacia arriba/abajo) - phi=0 da el punto mas lejano al eje
 * Z (borde exterior del torus), phi=PI el mas cercano (borde interior/agujero).
 *
 * Sombreado PLANO en toda la superficie (mismo criterio que Cube/IcoSphere/Cylinder/Cone, no el
 * suave de UvSphere): cada triangulo "explota" en 3 vertices propios con la normal de su cara
 * (cross product de sus 2 aristas) - da las facetas rectas tipicas de un torus de pocos segmentos
 * en Blender. El winding de cada triangulo (ver comentario en el loop de caras) se verifico a
 * mano contra la normal analitica esperada (cos(phi)*radDir + sin(phi)*Z, que en cada vertice
 * apunta derecho hacia afuera del tubo) para que el cross product de outward, sin necesidad de
 * invertir nada en tiempo de dibujo.
 *
 * majorRadius 0.4 + minorRadius 0.1 = radio exterior 0.5, mismo "unit size 1" que el resto de las
 * primitivas (Cylinder/Cone/Circle ya usan radius 0.5 para su borde exterior). Proporcion 4:1
 * entre mayor y menor, misma familia que el 1:0.25 que usa Blender por defecto (1 y 0.25),
 * escalada abajo para respetar el tamano base comun de esta app. majorSegments 32, mismo default
 * que ya usan Circle/UvSphere/Cylinder/Cone para el aro grande. minorSegments 16, mismo default
 * que ya usa UvSphere.rings para el aro chico.
 */
class Torus(
    private val majorSegments: Int = 32,
    private val minorSegments: Int = 16,
    private val majorRadius: Float = 0.4f,
    private val minorRadius: Float = 0.1f
) {

    private val edgeScale = 1.003f

    private val faceVertices: FloatArray
    private val faceNormals: FloatArray
    private val faceDrawOrder: ShortArray
    private val edgeVertices: FloatArray
    private val edgeDrawOrder: ShortArray
    private val geometry: MeshGeometry

    /** Posicion de un vertice de la grilla (i = vuelta grande, j = vuelta chica), sin escalar por edgeScale. */
    private fun ringVertex(i: Int, j: Int): FloatArray {
        val theta = (i.toFloat() / majorSegments) * (2f * Math.PI.toFloat())
        val phi = (j.toFloat() / minorSegments) * (2f * Math.PI.toFloat())
        val radDirX = cos(theta)
        val radDirY = sin(theta)
        val tubeOffset = minorRadius * cos(phi)
        val zOffset = minorRadius * sin(phi)
        return floatArrayOf(
            majorRadius * radDirX + tubeOffset * radDirX,
            majorRadius * radDirY + tubeOffset * radDirY,
            zOffset
        )
    }

    init {
        // Grilla de majorSegments x minorSegments vertices (sin fila/columna extra de cierre: a
        // diferencia de UvSphere - que necesita una columna duplicada porque sus polos rompen la
        // periodicidad - aca las dos direcciones cierran limpio con modulo, mismo criterio que ya
        // usan Cylinder/Cone para su unico aro).
        val grid = Array(majorSegments) { i -> Array(minorSegments) { j -> ringVertex(i, j) } }

        val fVerts = mutableListOf<Float>()
        val fNorms = mutableListOf<Float>()

        // Por cada celda (i,j) de la grilla, un quad entre las 4 esquinas (i,j)-(i,j+1)-(i+1,j+1)-
        // (i+1,j), partido en 2 triangulos - mismo criterio que el costado de Cylinder. Winding
        // (v00, v11, v01) y (v00, v10, v11) verificado a mano (ver comentario de la clase) para
        // que el cross product de outward, sin tener que invertir nada.
        for (i in 0 until majorSegments) {
            val ni = (i + 1) % majorSegments
            for (j in 0 until minorSegments) {
                val nj = (j + 1) % minorSegments
                val v00 = grid[i][j]
                val v01 = grid[i][nj]
                val v10 = grid[ni][j]
                val v11 = grid[ni][nj]
                val tri1 = listOf(v00, v11, v01)
                val tri2 = listOf(v00, v10, v11)
                for (tri in listOf(tri1, tri2)) {
                    val a = tri[0]; val b = tri[1]; val c = tri[2]
                    val ux = b[0] - a[0]; val uy = b[1] - a[1]; val uz = b[2] - a[2]
                    val vx = c[0] - a[0]; val vy = c[1] - a[1]; val vz = c[2] - a[2]
                    var nx = uy * vz - uz * vy
                    var ny = uz * vx - ux * vz
                    var nz = ux * vy - uy * vx
                    val nLen = sqrt(nx * nx + ny * ny + nz * nz)
                    if (nLen > 1e-8f) { nx /= nLen; ny /= nLen; nz /= nLen }
                    for (p in tri) {
                        fVerts.addAll(listOf(p[0], p[1], p[2]))
                        fNorms.addAll(listOf(nx, ny, nz))
                    }
                }
            }
        }

        faceVertices = fVerts.toFloatArray()
        faceNormals = fNorms.toFloatArray()
        faceDrawOrder = ShortArray(faceVertices.size / 3) { it.toShort() }

        // Contorno de seleccion: grilla completa de aristas (aros chicos, uno por cada vuelta
        // mayor, mas aros grandes, uno por cada vuelta menor) - mismo criterio que las latitudes/
        // meridianos de UvSphere, pero las dos familias de aros cierran solas via modulo (sin
        // poles que las corten). Escalado levemente hacia afuera (edgeScale) igual que el resto
        // de las primitivas. Reusa la misma grilla de vertices (ringVertex) para no duplicar la
        // logica de generacion, solo aplicando edgeScale a cada punto.
        val eVerts = mutableListOf<Float>()
        for (i in 0 until majorSegments) {
            for (j in 0 until minorSegments) {
                val p = grid[i][j]
                eVerts.addAll(listOf(p[0] * edgeScale, p[1] * edgeScale, p[2] * edgeScale))
            }
        }
        edgeVertices = eVerts.toFloatArray()

        val eOrder = mutableListOf<Short>()
        fun idx(i: Int, j: Int) = (i * minorSegments + j).toShort()
        // Aros chicos: por cada vuelta mayor i, conecta los minorSegments puntos de su tubo.
        for (i in 0 until majorSegments) {
            for (j in 0 until minorSegments) {
                eOrder.add(idx(i, j))
                eOrder.add(idx(i, (j + 1) % minorSegments))
            }
        }
        // Aros grandes: por cada vuelta menor j, conecta los majorSegments puntos de esa "latitud" del tubo.
        for (j in 0 until minorSegments) {
            for (i in 0 until majorSegments) {
                eOrder.add(idx(i, j))
                eOrder.add(idx((i + 1) % majorSegments, j))
            }
        }
        edgeDrawOrder = eOrder.toShortArray()

        geometry = MeshGeometry(faceVertices, faceNormals, faceDrawOrder, edgeVertices, edgeDrawOrder)
    }

    fun draw(mvpMatrix: FloatArray, selected: Boolean) {
        geometry.draw(mvpMatrix, selected)
    }
}
