package com.meshcraft.app

import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.abs

class MyGLRenderer : GLSurfaceView.Renderer {

    private lateinit var cubeGeometry: Cube
    private lateinit var gridXY: Grid
    private lateinit var gridXZ: Grid
    private lateinit var gridYZ: Grid

    /**
     * Objetos en la escena. Arranca con un solo cubo en el origen, ya seleccionado
     * (mismo comportamiento que Blender al abrir un archivo nuevo).
     */
    val sceneObjects = mutableListOf<SceneObject>()
    private var nextObjectId = 0

    private val mvpMatrix = FloatArray(16)
    private val projectionMatrix = FloatArray(16)
    private val viewMatrix = FloatArray(16)
    private val rotationMatrix = FloatArray(16)
    private val scratch = FloatArray(16)

    private var viewportWidth = 1
    private var viewportHeight = 1

    // angleX = pitch (rotates around world X). angleY = yaw (rotates around world Z, since Z is "up" here, like Blender).
    // Default fijado por el usuario (capturado con el long-press de debug en el gizmo).
    @Volatile var angleX = 19.8f
    @Volatile var angleY = -137.0f

    // Like Blender: axis-aligned gizmo views snap to orthographic; free orbiting uses perspective.
    @Volatile var isOrthographic = false

    // Which axis the camera is currently looking down: 'Z' -> ground (XY) grid, 'Y' -> XZ wall, 'X' -> YZ wall.
    @Volatile var gridPlaneAxis = 'Z'

    // Camera distance from the origin (zoom).
    @Volatile var cameraDistance = 7.47f
        set(value) {
            field = value.coerceIn(2f, 20f)
        }

    // Pan offset: shifts the camera + its look-at target together, sideways on screen (world X / world Z).
    @Volatile var panX = 0.05f
    @Volatile var panZ = 0.24f

    /**
     * Agrega un cubo nuevo a la escena y lo deja seleccionado (mismo criterio que selectObjectAt:
     * un solo objeto seleccionado a la vez). Por ahora es la unica primitiva con geometria real
     * (ver Cube.kt), asi que es la unica que Add > Mesh puede crear de verdad todavia.
     * Sin Cursor 3D (fuera de alcance por ahora), se agrega siempre en el origen - igual que
     * Blender agregaria en un 3D cursor que nunca se movio de (0,0,0).
     */
    fun addCube(): SceneObject {
        for (obj in sceneObjects) obj.selected = false
        val newObject = SceneObject(id = nextObjectId++, selected = true)
        sceneObjects.add(newObject)
        return newObject
    }

    fun zoomIn() {
        cameraDistance -= cameraDistance * 0.15f
    }

    fun zoomOut() {
        cameraDistance += cameraDistance * 0.15f
    }

    override fun onSurfaceCreated(unused: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0.11f, 0.11f, 0.11f, 1f)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        cubeGeometry = Cube()
        gridXY = Grid(GridPlane.XY)
        gridXZ = Grid(GridPlane.XZ)
        gridYZ = Grid(GridPlane.YZ)

        sceneObjects.clear()
        sceneObjects.add(SceneObject(id = nextObjectId++, selected = true))
    }

    override fun onSurfaceChanged(unused: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        viewportWidth = width
        viewportHeight = height
    }

    override fun onDrawFrame(unused: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)

        val ratio = viewportWidth.toFloat() / viewportHeight.toFloat()
        if (isOrthographic) {
            // Matches the apparent size of the perspective view at the current camera distance
            // (distance * tan(halfFOV) = distance * 0.5), so switching views doesn't feel like a zoom.
            val orthoSize = cameraDistance * 0.5f
            Matrix.orthoM(projectionMatrix, 0, -orthoSize * ratio, orthoSize * ratio, -orthoSize, orthoSize, 0.1f, 30f)
        } else {
            Matrix.frustumM(projectionMatrix, 0, -ratio, ratio, -1f, 1f, 2f, 30f)
        }

        // Camera looks along +Y with Z as the up direction (Blender-style Z-up).
        // panX/panZ shift both eye and target together, so it pans without changing the viewing angle.
        Matrix.setLookAtM(
            viewMatrix, 0,
            panX, -cameraDistance, panZ,
            panX, 0f, panZ,
            0f, 0f, 1f
        )

        Matrix.setIdentityM(rotationMatrix, 0)
        Matrix.rotateM(rotationMatrix, 0, angleX, 1f, 0f, 0f)
        Matrix.rotateM(rotationMatrix, 0, angleY, 0f, 0f, 1f)

        Matrix.multiplyMM(scratch, 0, viewMatrix, 0, rotationMatrix, 0)
        Matrix.multiplyMM(mvpMatrix, 0, projectionMatrix, 0, scratch, 0)

        val grid = when (gridPlaneAxis) {
            'X' -> gridYZ
            'Y' -> gridXZ
            else -> gridXY
        }
        grid.draw(mvpMatrix)

        // Cada objeto se dibuja con su propia matriz (mvpMatrix comun de camara + traslacion propia).
        val modelMatrix = FloatArray(16)
        val objMvpMatrix = FloatArray(16)
        for (obj in sceneObjects) {
            Matrix.setIdentityM(modelMatrix, 0)
            Matrix.translateM(modelMatrix, 0, obj.posX, obj.posY, obj.posZ)
            Matrix.multiplyMM(objMvpMatrix, 0, mvpMatrix, 0, modelMatrix, 0)
            cubeGeometry.draw(objMvpMatrix, obj.selected)
        }
    }

    /**
     * Convierte un tap en pantalla (coordenadas de vista, no NDC) en un rayo 3D y selecciona
     * el objeto mas cercano que intersecta. Si no hay hit, deselecciona todo (igual que tocar
     * espacio vacio en Blender). Usa la matriz camara+orbita del ultimo frame dibujado (scratch);
     * no incluye la traslacion de cada objeto, que se resta adentro de intersectAABB.
     */
    fun selectObjectAt(screenX: Float, screenY: Float) {
        if (viewportWidth <= 0 || viewportHeight <= 0) return

        val ndcX = (2f * screenX / viewportWidth) - 1f
        val ndcY = 1f - (2f * screenY / viewportHeight)

        val vpMatrix = FloatArray(16)
        Matrix.multiplyMM(vpMatrix, 0, projectionMatrix, 0, scratch, 0)
        val invMatrix = FloatArray(16)
        if (!Matrix.invertM(invMatrix, 0, vpMatrix, 0)) return

        val nearPoint = floatArrayOf(ndcX, ndcY, -1f, 1f)
        val farPoint = floatArrayOf(ndcX, ndcY, 1f, 1f)
        val nearWorld = FloatArray(4)
        val farWorld = FloatArray(4)
        Matrix.multiplyMV(nearWorld, 0, invMatrix, 0, nearPoint, 0)
        Matrix.multiplyMV(farWorld, 0, invMatrix, 0, farPoint, 0)
        if (nearWorld[3] != 0f) for (i in 0..2) nearWorld[i] /= nearWorld[3]
        if (farWorld[3] != 0f) for (i in 0..2) farWorld[i] /= farWorld[3]

        val rayOrigin = floatArrayOf(nearWorld[0], nearWorld[1], nearWorld[2])
        val rayDir = floatArrayOf(
            farWorld[0] - nearWorld[0],
            farWorld[1] - nearWorld[1],
            farWorld[2] - nearWorld[2]
        )

        var hitObject: SceneObject? = null
        var closestT = Float.MAX_VALUE
        for (obj in sceneObjects) {
            val t = intersectAABB(rayOrigin, rayDir, obj.posX, obj.posY, obj.posZ)
            if (t != null && t < closestT) {
                closestT = t
                hitObject = obj
            }
        }

        for (obj in sceneObjects) {
            obj.selected = (obj === hitObject)
        }
    }

    /** Interseccion rayo-caja axis-aligned (metodo slab), caja de medio-lado 0.5 centrada en (objX, objY, objZ). */
    private fun intersectAABB(rayOrigin: FloatArray, rayDir: FloatArray, objX: Float, objY: Float, objZ: Float): Float? {
        val halfExtent = 0.5f
        val minB = floatArrayOf(objX - halfExtent, objY - halfExtent, objZ - halfExtent)
        val maxB = floatArrayOf(objX + halfExtent, objY + halfExtent, objZ + halfExtent)
        var tMin = -Float.MAX_VALUE
        var tMax = Float.MAX_VALUE

        for (i in 0..2) {
            if (abs(rayDir[i]) < 1e-6f) {
                if (rayOrigin[i] < minB[i] || rayOrigin[i] > maxB[i]) return null
            } else {
                var t1 = (minB[i] - rayOrigin[i]) / rayDir[i]
                var t2 = (maxB[i] - rayOrigin[i]) / rayDir[i]
                if (t1 > t2) {
                    val tmp = t1
                    t1 = t2
                    t2 = tmp
                }
                tMin = maxOf(tMin, t1)
                tMax = minOf(tMax, t2)
                if (tMin > tMax) return null
            }
        }

        return if (tMax >= 0f) maxOf(tMin, 0f) else null
    }
}
