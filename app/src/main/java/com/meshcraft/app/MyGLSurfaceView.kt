package com.meshcraft.app

import android.content.Context
import android.opengl.GLSurfaceView
import android.view.MotionEvent
import kotlin.math.hypot

enum class TouchMode { ROTATE, PAN }

class MyGLSurfaceView(context: Context) : GLSurfaceView(context) {

    val renderer: MyGLRenderer
    private var previousX = 0f
    private var previousY = 0f

    private var downX = 0f
    private var downY = 0f
    private val tapMoveThreshold = 20f // px de tolerancia: por debajo de esto, ACTION_UP cuenta como tap (seleccion), no arrastre

    var onRotationChanged: (() -> Unit)? = null
    /** Se dispara en ACTION_UP si el dedo no se movio mas que tapMoveThreshold - usado para seleccion de objetos. */
    var onTap: ((Float, Float) -> Unit)? = null
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

        when (e.action) {
            MotionEvent.ACTION_DOWN -> {
                downX = x
                downY = y
            }
            MotionEvent.ACTION_MOVE -> {
                if (!isLocked) {
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
            }
            MotionEvent.ACTION_UP -> {
                val moved = hypot((x - downX).toDouble(), (y - downY).toDouble())
                if (moved < tapMoveThreshold) {
                    onTap?.invoke(x, y)
                }
            }
        }

        previousX = x
        previousY = y
        return true
    }
}
