package com.meshcraft.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View

/**
 * Overlay 2D transparente que dibuja la etiqueta de texto (X/Y/Z) del anillo de rotacion activo,
 * anclada al extremo de la linea punteada de angulo de arranque (ver
 * MyGLRenderer.computeRotateLabelAnchor) - el gizmo 3D (Gizmo3D.kt) no puede dibujar texto
 * (OpenGL ES puro, sin fuentes), asi que este overlay Canvas resuelve solo esa parte puntual.
 * No es clickable, para que los gestos del viewport 3D de abajo (glView) sigan llegando normal -
 * ver MainActivity.onCreate, se agrega justo encima de glView.
 */
class GizmoLabelView(context: Context) : View(context) {

    var labelText: String? = null
    var labelX: Float = 0f
    var labelY: Float = 0f

    private val density = context.resources.displayMetrics.density
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 16 * density
        isFakeBoldText = true
        textAlign = Paint.Align.CENTER
    }

    init {
        isClickable = false
        isFocusable = false
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val text = labelText ?: return
        canvas.drawText(text, labelX, labelY, textPaint)
    }
}
