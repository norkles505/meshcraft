package com.meshcraft.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import kotlin.math.cos
import kotlin.math.sin

class GizmoView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var angleXProvider: () -> Float = { 0f }
    var angleYProvider: () -> Float = { 0f }

    private data class Axis(val dir: FloatArray, val color: Int, val label: String?)

    private val axes = listOf(
        Axis(floatArrayOf(1f, 0f, 0f), Color.rgb(226, 61, 61), "X"),
        Axis(floatArrayOf(-1f, 0f, 0f), Color.rgb(226, 61, 61), null),
        Axis(floatArrayOf(0f, 1f, 0f), Color.rgb(120, 210, 90), "Y"),
        Axis(floatArrayOf(0f, -1f, 0f), Color.rgb(120, 210, 90), null),
        Axis(floatArrayOf(0f, 0f, 1f), Color.rgb(80, 150, 235), "Z"),
        Axis(floatArrayOf(0f, 0f, -1f), Color.rgb(80, 150, 235), null)
    )

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(140, 200, 200, 200)
        strokeWidth = 2.5f
    }

    private val circleFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }

    private data class Projected(val x: Float, val y: Float, val z: Float, val axis: Axis)

    private val fullOpacity = 255
    private val dimOpacity = 178 // ~70%

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val cx = width / 2f
        val cy = height / 2f
        val radius = (minOf(width, height) / 2f) * 0.72f
        val circleRadius = width * 0.13f

        val angleXRad = Math.toRadians(angleXProvider().toDouble())
        val angleYRad = Math.toRadians(angleYProvider().toDouble())

        val projected = axes.map { axis ->
            val (x, y, z) = rotate(axis.dir[0], axis.dir[1], axis.dir[2], angleXRad, angleYRad)
            // Z is "up" (Blender-style), so it drives the vertical screen position.
            // "z" here (from rotate's y-output) is depth toward/away from the camera.
            Projected(cx + x * radius, cy - z * radius, y, axis)
        }

        val frontMostDepth = projected.minOf { it.z }
        // Draw farthest first, nearest last, so the nearest circle ends up on top.
        val drawOrder = projected.sortedByDescending { it.z }

        for (p in drawOrder) {
            if (p.axis.label != null) {
                canvas.drawLine(cx, cy, p.x, p.y, linePaint)
            }
        }

        for (p in drawOrder) {
            val alpha = if (p.z == frontMostDepth) fullOpacity else dimOpacity
            val r = if (p.axis.label != null) circleRadius else circleRadius * 0.8f

            circleFillPaint.color = p.axis.color
            circleFillPaint.alpha = alpha
            canvas.drawCircle(p.x, p.y, r, circleFillPaint)

            if (p.axis.label != null) {
                textPaint.alpha = alpha
                textPaint.textSize = circleRadius * 1.15f
                val fm = textPaint.fontMetrics
                val textY = p.y - (fm.ascent + fm.descent) / 2f
                canvas.drawText(p.axis.label, p.x, textY, textPaint)
            }
        }
    }

    // Applies the same rotation as the 3D scene: yaw around Z (angleY), then pitch around X (angleX).
    private fun rotate(x: Float, y: Float, z: Float, angleX: Double, angleY: Double): Triple<Float, Float, Float> {
        val cosZ = cos(angleY); val sinZ = sin(angleY)
        val x1 = x * cosZ - y * sinZ
        val y1 = x * sinZ + y * cosZ
        val z1 = z.toDouble()

        val cosX = cos(angleX); val sinX = sin(angleX)
        val x2 = x1
        val y2 = y1 * cosX - z1 * sinX
        val z2 = y1 * sinX + z1 * cosX

        return Triple(x2.toFloat(), y2.toFloat(), z2.toFloat())
    }
}
