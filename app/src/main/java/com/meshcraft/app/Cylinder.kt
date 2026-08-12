package com.meshcraft.app

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Datos de geometria del cilindro (vertices, normales, orden de dibujo) - toda la infraestructura
 * de OpenGL (shaders, buffers, draw) vive en MeshGeometry.kt, compartida con el resto de las
 * primitivas. Ver MeshGeometry.kt para el porque de este split.
 *
 * Tres partes generadas por loop (mismo criterio que Circle/UvSphere/IcoSphere en vez de escribir
 * vertices a mano): tapa de arriba (fan, normal +Z), tapa de abajo (fan, normal -Z, winding
 * invertido respecto de la de arriba para que quede mirando hacia afuera) y el costado (una franja
 * de quads entre ambos aros, cada uno partido en 2 triangulos).
 *
 * A diferencia de UvSphere (normales suaves radiales), el costado usa sombreado PLANO (mismo
 * criterio que Cube/IcoSphere): cada triangulo "explota" en 3 vertices propios con la normal de su
 * cara (cross product de sus 2 aristas) - da las facetas rectas tipicas de un cilindro de pocos
 * segmentos en Blender, en vez de un tubo perfectamente liso. Las tapas tambien son planas (normal
 * fija +Z/-Z), igual que ya hacian Circle/Plane.
 *
 * radius 0.5, igual que el resto de las primitivas (unit size 1). height 1 (de Z=-0.5 a Z=0.5) -
 * mismo criterio de tamano base que Cube, para que las primitivas nuevas partan del mismo orden de
 * magnitud visual. segments 32, mismo default que ya usan Circle/UvSphere.
 */
class Cylinder(private val segments: Int = 32, private val radius: Float = 0.5f, private val height: Float = 1f) {

    private val edgeScale = 1.003f

    private val faceVertices: FloatArray
    private val faceNormals: FloatArray
    private val faceDrawOrder: ShortArray
    private val edgeVertices: FloatArray
    private val edgeDrawOrder: ShortArray
    private val geometry: MeshGeometry

    init {
        val halfHeight = height / 2f

        // Aros de arriba/abajo en espacio de mundo (sin explotar todavia) - se reusan tanto para
        // armar las tapas como el costado, y tambien (escalados por edgeScale) para el contorno.
        val topRim = Array(segments) { i ->
            val angle = (i.toFloat() / segments) * (2f * Math.PI.toFloat())
            floatArrayOf(radius * cos(angle), radius * sin(angle), halfHeight)
        }
        val botRim = Array(segments) { i ->
            val angle = (i.toFloat() / segments) * (2f * Math.PI.toFloat())
            floatArrayOf(radius * cos(angle), radius * sin(angle), -halfHeight)
        }

        val fVerts = mutableListOf<Float>()
        val fNorms = mutableListOf<Float>()

        // Tapa de arriba: fan desde el centro, angulo creciente da normal +Z (misma convencion
        // de winding que Circle).
        val topCenter = floatArrayOf(0f, 0f, halfHeight)
        for (i in 0 until segments) {
            val next = (i + 1) % segments
            for (p in listOf(topCenter, topRim[i], topRim[next])) {
                fVerts.addAll(listOf(p[0], p[1], p[2]))
                fNorms.addAll(listOf(0f, 0f, 1f))
            }
        }

        // Tapa de abajo: mismo fan, winding invertido (centro, siguiente, actual) para que la
        // normal quede -Z en vez de +Z - misma logica que Circle pero mirando para el otro lado.
        val botCenter = floatArrayOf(0f, 0f, -halfHeight)
        for (i in 0 until segments) {
            val next = (i + 1) % segments
            for (p in listOf(botCenter, botRim[next], botRim[i])) {
                fVerts.addAll(listOf(p[0], p[1], p[2]))
                fNorms.addAll(listOf(0f, 0f, -1f))
            }
        }

        // Costado: por cada segmento, un quad (botRim[i], botRim[next], topRim[next], topRim[i])
        // partido en 2 triangulos - los 4 puntos son coplanares (top esta siempre directamente
        // arriba del bot correspondiente), asi que ambos triangulos dan la misma normal via cross
        // product, sin necesidad de promediar nada (verificado a mano, mismo criterio que
        // IcoSphere para caras planas).
        for (i in 0 until segments) {
            val next = (i + 1) % segments
            val tri1 = listOf(botRim[i], botRim[next], topRim[next])
            val tri2 = listOf(botRim[i], topRim[next], topRim[i])
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

        faceVertices = fVerts.toFloatArray()
        faceNormals = fNorms.toFloatArray()
        faceDrawOrder = ShortArray(faceVertices.size / 3) { it.toShort() }

        // Contorno de seleccion: wireframe completo (aro de arriba, aro de abajo, verticales entre
        // cada par correspondiente) - mismo nivel de detalle que UvSphere/IcoSphere, escalado
        // levemente hacia afuera (edgeScale) igual que el resto de las primitivas.
        val eVerts = mutableListOf<Float>()
        for (p in topRim) eVerts.addAll(listOf(p[0] * edgeScale, p[1] * edgeScale, p[2] * edgeScale))
        for (p in botRim) eVerts.addAll(listOf(p[0] * edgeScale, p[1] * edgeScale, p[2] * edgeScale))
        edgeVertices = eVerts.toFloatArray()

        val eOrder = mutableListOf<Short>()
        // Aro de arriba (indices 0..segments-1).
        for (i in 0 until segments) {
            eOrder.add(i.toShort())
            eOrder.add(((i + 1) % segments).toShort())
        }
        // Aro de abajo (indices segments..2*segments-1).
        for (i in 0 until segments) {
            eOrder.add((segments + i).toShort())
            eOrder.add((segments + (i + 1) % segments).toShort())
        }
        // Verticales, uniendo cada punto del aro de arriba con su correspondiente de abajo.
        for (i in 0 until segments) {
            eOrder.add(i.toShort())
            eOrder.add((segments + i).toShort())
        }
        edgeDrawOrder = eOrder.toShortArray()

        geometry = MeshGeometry(faceVertices, faceNormals, faceDrawOrder, edgeVertices, edgeDrawOrder)
    }

    fun draw(mvpMatrix: FloatArray, selected: Boolean) {
        geometry.draw(mvpMatrix, selected)
    }
}
