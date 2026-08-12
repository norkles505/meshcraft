package com.meshcraft.app

import kotlin.math.sqrt

/**
 * Datos de geometria del Ico Sphere (vertices, normales, orden de dibujo) - toda la
 * infraestructura de OpenGL (shaders, buffers, draw) vive en MeshGeometry.kt, compartida con
 * Cube/Plane/Circle/UvSphere. Ver MeshGeometry.kt para el porque de este split.
 *
 * Se genera subdividiendo un icosaedro (12 vertices, 20 caras triangulares, proporcion aurea) -
 * cada ronda de subdivision reemplaza cada triangulo por 4 mas chicos (usando el punto medio de
 * cada arista, normalizado de nuevo al radio para que quede sobre la esfera). subdivisions=2 es
 * el default de Blender para su Ico Sphere (mismo criterio que los defaults ya usados en Circle/
 * UvSphere). radius 0.5, igual que el resto de las primitivas (unit size 1).
 *
 * A diferencia de UvSphere (normal = posicion normalizada de cada vertice, sombreado suave/
 * Gouraud), aca el sombreado es PLANO: cada triangulo "explota" en 3 vertices propios (mismo
 * criterio que Cube.kt) con la normal de su cara (cross product de sus 2 aristas) - asi se ven
 * las facetas triangulares tipicas de un Ico Sphere de pocas subdivisiones en Blender, en vez de
 * una esfera perfectamente redonda como UvSphere. Visualmente son las 2 formas de "esfera" que
 * ofrece Blender y ahora tambien esta app, cada una con su sombreado caracteristico.
 */
class IcoSphere(private val subdivisions: Int = 2, private val radius: Float = 0.5f) {

    private val edgeScale = 1.003f

    private val faceVertices: FloatArray
    private val faceNormals: FloatArray
    private val faceDrawOrder: ShortArray
    private val edgeVertices: FloatArray
    private val edgeDrawOrder: ShortArray
    private val geometry: MeshGeometry

    init {
        // Vertices UNICOS (compartidos entre triangulos, sin duplicar todavia) - se usan como
        // base antes de "explotar" cada triangulo en 3 vertices propios para el sombreado plano
        // (ver mas abajo) y para calcular las aristas del contorno de seleccion sin duplicados.
        val uniqueVerts = mutableListOf<FloatArray>()
        val faces = mutableListOf<IntArray>()

        fun addVertex(x: Float, y: Float, z: Float): Int {
            val len = sqrt(x * x + y * y + z * z)
            uniqueVerts.add(floatArrayOf(x / len * radius, y / len * radius, z / len * radius))
            return uniqueVerts.size - 1
        }

        // Icosaedro base: 12 vertices (proporcion aurea t) y 20 caras - indices de caras
        // estandar para esta disposicion de vertices (misma que cualquier implementacion clasica
        // de icosaedro/geodesic sphere).
        val t = (1f + sqrt(5f)) / 2f
        val v0 = addVertex(-1f, t, 0f)
        val v1 = addVertex(1f, t, 0f)
        val v2 = addVertex(-1f, -t, 0f)
        val v3 = addVertex(1f, -t, 0f)
        val v4 = addVertex(0f, -1f, t)
        val v5 = addVertex(0f, 1f, t)
        val v6 = addVertex(0f, -1f, -t)
        val v7 = addVertex(0f, 1f, -t)
        val v8 = addVertex(t, 0f, -1f)
        val v9 = addVertex(t, 0f, 1f)
        val v10 = addVertex(-t, 0f, -1f)
        val v11 = addVertex(-t, 0f, 1f)

        faces.addAll(listOf(
            intArrayOf(v0, v11, v5), intArrayOf(v0, v5, v1), intArrayOf(v0, v1, v7),
            intArrayOf(v0, v7, v10), intArrayOf(v0, v10, v11),
            intArrayOf(v1, v5, v9), intArrayOf(v5, v11, v4), intArrayOf(v11, v10, v2),
            intArrayOf(v10, v7, v6), intArrayOf(v7, v1, v8),
            intArrayOf(v3, v9, v4), intArrayOf(v3, v4, v2), intArrayOf(v3, v2, v6),
            intArrayOf(v3, v6, v8), intArrayOf(v3, v8, v9),
            intArrayOf(v4, v9, v5), intArrayOf(v2, v4, v11), intArrayOf(v6, v2, v10),
            intArrayOf(v8, v6, v7), intArrayOf(v9, v8, v1)
        ))

        // Subdivision: cada ronda reemplaza cada triangulo (a,b,c) por 4 mas chicos, usando el
        // punto medio de cada arista (normalizado de nuevo al radio - ver addVertex). midpointCache
        // evita crear el mismo vertice de arista compartida 2 veces (una por cada triangulo que
        // la toca) - clave codificada como par ordenado (indice menor, indice mayor).
        repeat(subdivisions) {
            val midpointCache = HashMap<Long, Int>()

            fun midpoint(iA: Int, iB: Int): Int {
                val key = if (iA < iB) iA.toLong() * 100000L + iB else iB.toLong() * 100000L + iA
                midpointCache[key]?.let { return it }
                val a = uniqueVerts[iA]
                val b = uniqueVerts[iB]
                val mid = addVertex((a[0] + b[0]) / 2f, (a[1] + b[1]) / 2f, (a[2] + b[2]) / 2f)
                midpointCache[key] = mid
                return mid
            }

            val newFaces = mutableListOf<IntArray>()
            for (face in faces) {
                val a = face[0]; val b = face[1]; val c = face[2]
                val ab = midpoint(a, b)
                val bc = midpoint(b, c)
                val ca = midpoint(c, a)
                newFaces.add(intArrayOf(a, ab, ca))
                newFaces.add(intArrayOf(b, bc, ab))
                newFaces.add(intArrayOf(c, ca, bc))
                newFaces.add(intArrayOf(ab, bc, ca))
            }
            faces.clear()
            faces.addAll(newFaces)
        }

        // Caras: sombreado PLANO (ver comentario de la clase) - cada triangulo "explota" en 3
        // vertices propios (mismo criterio que Cube.kt) con la normal de su cara (cross product
        // de sus 2 aristas, normalizado). Sin reuso de vertices entre triangulos, asi que el
        // orden de dibujo es simplemente secuencial (0,1,2, 3,4,5, ...) - cada terna consecutiva
        // ya es un triangulo propio.
        val fVerts = mutableListOf<Float>()
        val fNorms = mutableListOf<Float>()
        for (face in faces) {
            val a = uniqueVerts[face[0]]
            val b = uniqueVerts[face[1]]
            val c = uniqueVerts[face[2]]
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
        faceDrawOrder = ShortArray(faces.size * 3) { it.toShort() }

        // Contorno de seleccion: aristas UNICAS del mesh compartido (uniqueVerts/faces, antes de
        // "explotar" en vertices por cara) - evita dibujar cada arista interna 2 veces (una por
        // cada triangulo que la comparte, ya que en un icosaedro subdividido cada arista interna
        // es compartida por exactamente 2 caras). Mismo criterio de edgeScale que Cube/Circle/UvSphere.
        val edgeSet = HashSet<Long>()
        val eOrder = mutableListOf<Short>()
        fun addEdge(iA: Int, iB: Int) {
            val key = if (iA < iB) iA.toLong() * 100000L + iB else iB.toLong() * 100000L + iA
            if (edgeSet.add(key)) {
                eOrder.add(iA.toShort())
                eOrder.add(iB.toShort())
            }
        }
        for (face in faces) {
            addEdge(face[0], face[1])
            addEdge(face[1], face[2])
            addEdge(face[2], face[0])
        }
        edgeDrawOrder = eOrder.toShortArray()

        val eVerts = mutableListOf<Float>()
        for (v in uniqueVerts) {
            eVerts.addAll(listOf(v[0] * edgeScale, v[1] * edgeScale, v[2] * edgeScale))
        }
        edgeVertices = eVerts.toFloatArray()

        geometry = MeshGeometry(faceVertices, faceNormals, faceDrawOrder, edgeVertices, edgeDrawOrder)
    }

    fun draw(mvpMatrix: FloatArray, selected: Boolean) {
        geometry.draw(mvpMatrix, selected)
    }
}
