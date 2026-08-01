package com.meshcraft.app

import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class MyGLRenderer : GLSurfaceView.Renderer {

    private lateinit var cube: Cube
    private lateinit var gridXY: Grid
    private lateinit var gridXZ: Grid
    private lateinit var gridYZ: Grid

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

    fun zoomIn() {
        cameraDistance -= cameraDistance * 0.15f
    }

    fun zoomOut() {
        cameraDistance += cameraDistance * 0.15f
    }

    override fun onSurfaceCreated(unused: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0.11f, 0.11f, 0.11f, 1f)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        cube = Cube()
        gridXY = Grid(GridPlane.XY)
        gridXZ = Grid(GridPlane.XZ)
        gridYZ = Grid(GridPlane.YZ)
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
        cube.draw(mvpMatrix)
    }
}
