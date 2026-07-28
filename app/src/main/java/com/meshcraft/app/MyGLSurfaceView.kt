package com.meshcraft.app

import android.content.Context
import android.opengl.GLSurfaceView
import android.view.MotionEvent

class MyGLSurfaceView(context: Context) : GLSurfaceView(context) {

    val renderer: MyGLRenderer
    private var previousX = 0f
    private var previousY = 0f

    var onRotationChanged: (() -> Unit)? = null

    init {
        setEGLContextClientVersion(2)
        renderer = MyGLRenderer()
        setRenderer(renderer)
        renderMode = RENDERMODE_CONTINUOUSLY
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val x = e.x
        val y = e.y

        if (e.action == MotionEvent.ACTION_MOVE) {
            val dx = x - previousX
            val dy = y - previousY
            renderer.angleY += dx * 0.5f
            renderer.angleX += dy * 0.5f
            requestRender()
            onRotationChanged?.invoke()
        }

        previousX = x
        previousY = y
        return true
    }
}
