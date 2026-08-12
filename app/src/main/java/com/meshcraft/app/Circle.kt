package com.meshcraft.app

import kotlin.math.cos
import kotlin.math.sin

/**
 * Datos de geometria del circulo (vertices, normales, orden de dibujo) - toda la infraestructura
 * de OpenGL (shaders, buffers, draw) vive en MeshGeometry.kt, compartida con Cube y Plane. Ver
 * MeshGeometry.kt para el porque de este split.
 *
 * A diferencia de Cube/Plane (geometria fija, hardcodeada), la cantidad de vertices depende de
 * `segments` - se genera con un loop en el init en vez de escribir cada vertice a mano.
 *
 * Chato sobre el plano XY (Z=0), igual que Plane - mismo plano que la grilla del piso (ver
 * GridPlane.XY en Grid.kt), apoyado en el suelo como en Blender. Relleno como un abanico de
 * triangulos desde el centro (fan), no solo el contorno - mismo criterio que Plane: un objeto
 * solido y visible, ya que la app todavia no tiene Edit Mode ni un "fill type" configurable como
 * Blender (que por defecto en realidad NO rellena el circulo).
 *
 * radius 0.5 y segments 32: mismo radio base (0.5) que el medio-lado de Cube/Plane (unit size 1),
 * y mismo segment count que usa Blender por defecto para su Circle.
 */
class Circle(private val segments: Int = 32, private val radius: Float = 0.5f) {

    private val edgeScale = 1.003f

    private val faceVertices: FloatArray
    private val faceNormals: FloatArray
    private val faceDrawOrder: ShortArray
    private val edgeVertices: FloatArray
    private val edgeDrawOrder: ShortArray
    private val geometry: MeshGeometry

    init {
        // Vertice 0 = centro; vertices 1..segments = perimetro, en orden de angulo creciente.
        val fVerts = mutableListOf<Float>()
        val fNorms = mutableListOf<Float>()
        fVerts.addAll(listOf(0f, 0f, 0f))
        fNorms.addAll(listOf(0f, 0f, 1f))
        for (i in 0 until segments) {
            val angle = (i.toFloat() / segments) * (2f * Math.PI.toFloat())
            fVerts.addAll(listOf(radius * cos(angle), radius * sin(angle), 0f))
            fNorms.addAll(listOf(0f, 0f, 1f))
        }
        faceVertices = fVerts.toFloatArray()
        faceNormals = fNorms.toFloatArray()

        // Triangulo (centro, v_i, v_i+1) por cada segmento - angulo creciente da normal +Z
        // (regla de la mano derecha), mismo criterio de winding que Plane.
        val fOrder = mutableListOf<Short>()
        for (i in 0 until segments) {
            val curr = (i + 1).toShort()
            val next = (((i + 1) % segments) + 1).toShort()
            fOrder.addAll(listOf(0.toShort(), curr, next))
        }
        faceDrawOrder = fOrder.toShortArray()

        // Contorno de seleccion: solo el perimetro (sin los radios internos del abanico),
        // escalado levemente hacia afuera - mismo criterio que Cube/Plane.
        val eVerts = mutableListOf<Float>()
        for (i in 0 until segments) {
            val angle = (i.toFloat() / segments) * (2f * Math.PI.toFloat())
            eVerts.addAll(listOf(radius * edgeScale * cos(angle), radius * edgeScale * sin(angle), 0f))
        }
        edgeVertices = eVerts.toFloatArray()

        val eOrder = mutableListOf<Short>()
        for (i in 0 until segments) {
            eOrder.add(i.toShort())
            eOrder.add(((i + 1) % segments).toShort())
        }
        edgeDrawOrder = eOrder.toShortArray()

        geometry = MeshGeometry(faceVertices, faceNormals, faceDrawOrder, edgeVertices, edgeDrawOrder)
    }

    fun draw(mvpMatrix: FloatArray, selected: Boolean) {
        geometry.draw(mvpMatrix, selected)
    }
}
