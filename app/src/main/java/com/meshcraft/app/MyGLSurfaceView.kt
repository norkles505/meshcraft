package com.meshcraft.app

import android.content.Context
import android.opengl.GLSurfaceView
import android.view.MotionEvent

enum class TouchMode { ROTATE, PAN }

class MyGLSurfaceView(context: Context) : GLSurfaceView(context) {

    val renderer: MyGLRenderer
    private var previousX = 0f
    private var previousY = 0f

    var onRotationChanged: (() -> Unit)? = null
    var touchMode: TouchMode = TouchMode.ROTATE
    var isLocked: Boolean = false

    init {
        setEGLContextClientVersion(2)
        renderer = MyGLRenderer()
        setRenderer(renderer)
        renderMode = RENDERMODE_CONTINUOUSLY
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val x = e.x
        val y = e.y

        if (e.action == MotionEvent.ACTION_MOVE && !isLocked) {
            val dx = x - previousX
            val dy = y - previousY

            if (touchMode == TouchMode.ROTATE) {
                renderer.angleY += dx * 0.5f
                renderer.angleX += dy * 0.5f
                renderer.isOrthographic = false
                renderer.gridPlaneAxis = 'Z'
            } else {
                val panScale = 0.01f * (renderer.cameraDistance / 6.5f)
                renderer.panX -= dx * panScale
                renderer.panZ += dy * panScale
            }

            requestRender()
            onRotationChanged?.invoke()
        }

        previousX = x
        previousY = y
        return true
    }
}
