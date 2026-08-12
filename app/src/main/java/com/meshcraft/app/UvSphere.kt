package com.meshcraft.app

import kotlin.math.cos
import kotlin.math.sin

/**
 * Datos de geometria de la UV Sphere (vertices, normales, orden de dibujo) - toda la
 * infraestructura de OpenGL (shaders, buffers, draw) vive en MeshGeometry.kt, compartida con
 * Cube/Plane/Circle. Ver MeshGeometry.kt para el porque de este split.
 *
 * Igual que Circle, la cantidad de vertices depende de parametros (segments = anillos de
 * longitud, rings = anillos de latitud) - se genera con un loop en el init en vez de escribir
 * cada vertice a mano. Defaults (32 segments, 16 rings) son los mismos que usa Blender por
 * defecto para su UV Sphere (mismo criterio que el default de 32 segments ya usado en Circle).
 *
 * Polos alineados al eje Z (mundo Z-up, ver comentario "Blender-style Z-up" en MyGLRenderer) -
 * mismo eje "arriba" que ya usa Circle (normal +Z, chato sobre XY). radius 0.5, igual que el
 * medio-lado de Cube/Plane/Circle (unit size 1).
 *
 * A diferencia de Cube (normales planas por cara, un vertice duplicado por cada cara), aca cada
 * vertice tiene una unica normal = su propia posicion normalizada (direccion radial desde el
 * centro) - al ser MeshGeometry quien interpola la normal por pixel (varying fNormal, ver
 * MeshGeometry.faceVertexShaderCode), esto da sombreado suave (Gouraud) en vez de caras planas,
 * que es lo esperado visualmente para una esfera.
 */
class UvSphere(private val segments: Int = 32, private val rings: Int = 16, private val radius: Float = 0.5f) {

    private val edgeScale = 1.003f

    private val faceVertices: FloatArray
    private val faceNormals: FloatArray
    private val faceDrawOrder: ShortArray
    private val edgeVertices: FloatArray
    private val edgeDrawOrder: ShortArray
    private val geometry: MeshGeometry

    init {
        // Grilla de (rings+1) x (segments+1) vertices: theta (latitud, 0=polo norte..PI=polo
        // sur) recorre los anillos, phi (longitud, 0..2PI) recorre cada anillo. La columna
        // segments+1 duplica la primera (mismo criterio que un seam de textura) para no tener
        // que envolver el indice con modulo al armar los triangulos.
        val fVerts = mutableListOf<Float>()
        val fNorms = mutableListOf<Float>()
        for (i in 0..rings) {
            val theta = (i.toFloat() / rings) * Math.PI.toFloat()
            val sinTheta = sin(theta)
            val cosTheta = cos(theta)
            for (j in 0..segments) {
                val phi = (j.toFloat() / segments) * (2f * Math.PI.toFloat())
                val nx = sinTheta * cos(phi)
                val ny = sinTheta * sin(phi)
                val nz = cosTheta
                fVerts.addAll(listOf(radius * nx, radius * ny, radius * nz))
                fNorms.addAll(listOf(nx, ny, nz))
            }
        }
        faceVertices = fVerts.toFloatArray()
        faceNormals = fNorms.toFloatArray()

        // Cada celda de la grilla (i,j)-(i,j+1)-(i+1,j)-(i+1,j+1) son 2 triangulos. Sin backface
        // culling en esta app (ver MeshGeometry/MyGLRenderer.onSurfaceCreated, solo GL_DEPTH_TEST)
        // el orden de winding no afecta visibilidad ni sombreado (las normales ya vienen dadas
        // por vertice, no se calculan por cross product) - solo importa la conectividad. En los
        // polos (i=0 e i=rings) toda la fila comparte la misma posicion (sinTheta=0), asi que
        // esos triangulos quedan degenerados (area cero) - no rompen nada, simplemente no dibujan.
        val fOrder = mutableListOf<Short>()
        val cols = segments + 1
        for (i in 0 until rings) {
            for (j in 0 until segments) {
                val v0 = (i * cols + j).toShort()
                val v1 = (i * cols + j + 1).toShort()
                val v2 = ((i + 1) * cols + j).toShort()
                val v3 = ((i + 1) * cols + j + 1).toShort()
                fOrder.addAll(listOf(v0, v2, v1))
                fOrder.addAll(listOf(v1, v2, v3))
            }
        }
        faceDrawOrder = fOrder.toShortArray()

        // Contorno de seleccion: grilla completa de aristas (anillos de latitud + meridianos de
        // longitud), escalada levemente hacia afuera - mismo criterio que Cube/Circle. Reusa la
        // misma grilla de vertices (radio * edgeScale) para no duplicar la logica de generacion.
        val eVerts = mutableListOf<Float>()
        for (i in 0..rings) {
            val theta = (i.toFloat() / rings) * Math.PI.toFloat()
            val sinTheta = sin(theta)
            val cosTheta = cos(theta)
            for (j in 0..segments) {
                val phi = (j.toFloat() / segments) * (2f * Math.PI.toFloat())
                eVerts.addAll(listOf(
                    radius * edgeScale * sinTheta * cos(phi),
                    radius * edgeScale * sinTheta * sin(phi),
                    radius * edgeScale * cosTheta
                ))
            }
        }
        edgeVertices = eVerts.toFloatArray()

        val eOrder = mutableListOf<Short>()
        // Anillos de latitud (horizontal): conecta cada vertice con el siguiente en el mismo anillo.
        for (i in 0..rings) {
            for (j in 0 until segments) {
                eOrder.add((i * cols + j).toShort())
                eOrder.add((i * cols + j + 1).toShort())
            }
        }
        // Meridianos de longitud (vertical): conecta cada vertice con el correspondiente del anillo siguiente.
        for (i in 0 until rings) {
            for (j in 0 until segments) {
                eOrder.add((i * cols + j).toShort())
                eOrder.add(((i + 1) * cols + j).toShort())
            }
        }
        edgeDrawOrder = eOrder.toShortArray()

        geometry = MeshGeometry(faceVertices, faceNormals, faceDrawOrder, edgeVertices, edgeDrawOrder)
    }

    fun draw(mvpMatrix: FloatArray, selected: Boolean) {
        geometry.draw(mvpMatrix, selected)
    }
}
