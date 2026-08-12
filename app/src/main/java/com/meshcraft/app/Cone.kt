package com.meshcraft.app

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Datos de geometria del cono (vertices, normales, orden de dibujo) - toda la infraestructura de
 * OpenGL (shaders, buffers, draw) vive en MeshGeometry.kt, compartida con el resto de las
 * primitivas. Ver MeshGeometry.kt para el porque de este split.
 *
 * Mismo patron que Cylinder.kt (loop por segmento en vez de vertices a mano), pero con dos partes
 * en vez de tres: tapa de abajo (fan, normal -Z, identico a la tapa de abajo de Cylinder) y el
 * costado, que en vez de una franja de quads entre dos aros es un fan de triangulos entre el aro
 * de la base y un unico vertice punta arriba - no hay tapa de arriba, el cono termina en punta.
 *
 * Sombreado PLANO en el costado (mismo criterio que Cube/IcoSphere/Cylinder): cada triangulo
 * "explota" en 3 vertices propios con la normal de su cara (cross product de sus 2 aristas) - da
 * las facetas rectas tipicas de un cono de pocos segmentos en Blender, en vez de un cono
 * perfectamente liso. La tapa tambien es plana (normal fija -Z), igual que Circle/Plane/Cylinder.
 *
 * radius 0.5, igual que el resto de las primitivas (unit size 1). height 1 (de Z=-0.5 la base a
 * Z=0.5 la punta), mismo criterio de tamano base que Cylinder. segments 32, mismo default que ya
 * usan Circle/UvSphere/Cylinder.
 */
class Cone(private val segments: Int = 32, private val radius: Float = 0.5f, private val height: Float = 1f) {

    private val edgeScale = 1.003f

    private val faceVertices: FloatArray
    private val faceNormals: FloatArray
    private val faceDrawOrder: ShortArray
    private val edgeVertices: FloatArray
    private val edgeDrawOrder: ShortArray
    private val geometry: MeshGeometry

    init {
        val halfHeight = height / 2f

        // Aro de la base en espacio de mundo (sin explotar todavia) - se reusa tanto para armar la
        // tapa como el costado, y tambien (escalado por edgeScale) para el contorno.
        val baseRim = Array(segments) { i ->
            val angle = (i.toFloat() / segments) * (2f * Math.PI.toFloat())
            floatArrayOf(radius * cos(angle), radius * sin(angle), -halfHeight)
        }
        val tip = floatArrayOf(0f, 0f, halfHeight)

        val fVerts = mutableListOf<Float>()
        val fNorms = mutableListOf<Float>()

        // Tapa de abajo: fan desde el centro, mismo winding que la tapa de abajo de Cylinder
        // (centro, siguiente, actual) para que la normal quede -Z.
        val baseCenter = floatArrayOf(0f, 0f, -halfHeight)
        for (i in 0 until segments) {
            val next = (i + 1) % segments
            for (p in listOf(baseCenter, baseRim[next], baseRim[i])) {
                fVerts.addAll(listOf(p[0], p[1], p[2]))
                fNorms.addAll(listOf(0f, 0f, -1f))
            }
        }

        // Costado: por cada segmento, un triangulo (baseRim[i], baseRim[next], tip) - mismo orden
        // de vertices (a, b, c) que el primer triangulo del costado de Cylinder (botRim[i],
        // botRim[next], topRim[next]), asi que da la misma convencion de normal hacia afuera sin
        // necesidad de invertir nada.
        for (i in 0 until segments) {
            val next = (i + 1) % segments
            val a = baseRim[i]; val b = baseRim[next]; val c = tip
            val ux = b[0] - a[0]; val uy = b[1] - a[1]; val uz = b[2] - a[2]
            val vx = c[0] - a[0]; val vy = c[1] - a[1]; val vz = c[2] - a[2]
            var nx = uy * vz - uz * vy
            var ny = uz * vx - ux * vz
            var nz = ux * vy - uy * vx
            val nLen = sqrt(nx * nx + ny * ny + nz * nz)
            if (nLen > 1e-8f) { nx /= nLen; ny /= nLen; nz /= nLen }
            for (p in listOf(a, b, c)) {
                fVerts.addAll(listOf(p[0], p[1], p[2]))
                fNorms.addAll(listOf(nx, ny, nz))
            }
        }

        faceVertices = fVerts.toFloatArray()
        faceNormals = fNorms.toFloatArray()
        faceDrawOrder = ShortArray(faceVertices.size / 3) { it.toShort() }

        // Contorno de seleccion: wireframe completo (aro de la base + lineas desde cada punto del
        // aro hasta la punta) - mismo nivel de detalle que Cylinder, escalado levemente hacia
        // afuera (edgeScale) igual que el resto de las primitivas.
        val eVerts = mutableListOf<Float>()
        for (p in baseRim) eVerts.addAll(listOf(p[0] * edgeScale, p[1] * edgeScale, p[2] * edgeScale))
        // Ultimo vertice del buffer de contorno: la punta (indice `segments`), sin escalar por
        // edgeScale (es un unico punto, no tiene "hacia afuera" que valga la pena exagerar).
        eVerts.addAll(listOf(tip[0], tip[1], tip[2]))
        edgeVertices = eVerts.toFloatArray()

        val eOrder = mutableListOf<Short>()
        // Aro de la base (indices 0..segments-1).
        for (i in 0 until segments) {
            eOrder.add(i.toShort())
            eOrder.add(((i + 1) % segments).toShort())
        }
        // Lineas desde cada punto del aro hasta la punta (indice `segments`).
        val tipIndex = segments.toShort()
        for (i in 0 until segments) {
            eOrder.add(i.toShort())
            eOrder.add(tipIndex)
        }
        edgeDrawOrder = eOrder.toShortArray()

        geometry = MeshGeometry(faceVertices, faceNormals, faceDrawOrder, edgeVertices, edgeDrawOrder)
    }

    fun draw(mvpMatrix: FloatArray, selected: Boolean) {
        geometry.draw(mvpMatrix, selected)
    }
}
