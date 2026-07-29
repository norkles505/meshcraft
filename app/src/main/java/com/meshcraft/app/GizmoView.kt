package com.meshcraft.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.cos
import kotlin.math.sin

class GizmoView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var angleXProvider: () -> Float = { 0f }
    var angleYProvider: () -> Float = { 0f }

    // Called with (targetAngleX, targetAngleY) when the user taps an axis circle.
    var onAxisSelected: ((Float, Float) -> Unit)? = null

    // dir = axis direction, viewAngleX/viewAngleY = camera angles that look straight down this axis
    // (chosen so world Z also stays pointing up on screen, no unwanted flip).
    private data class Axis(
        val dir: FloatArray,
        val color: Int,
        val label: String?,
        val viewAngleX: Float,
        val viewAngleY: Float
    )

    private val axes = listOf(
        Axis(floatArrayOf(1f, 0f, 0f), Color.rgb(226, 61, 61), "X", 0f, -90f),
        Axis(floatArrayOf(-1f, 0f, 0f), Color.rgb(226, 61, 61), null, 0f, 90f),
        Axis(floatArrayOf(0f, 1f, 0f), Color.rgb(120, 210, 90), "Y", 0f, 180f),
        Axis(floatArrayOf(0f, -1f, 0f), Color.rgb(120, 210, 90), null, 0f, 0f),
        Axis(floatArrayOf(0f, 0f, 1f), Color.rgb(80, 150, 235), "Z", 90f, 0f),
        Axis(floatArrayOf(0f, 0f, -1f), Color.rgb(80, 150, 235), null, -90f, 0f)
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
    private data class HitTarget(val x: Float, val y: Float, val radius: Float, val axis: Axis)

    private var hitTargets: List<HitTarget> = emptyList()

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
            Projected(cx + x * radius, cy - z * radius, y, axis)
        }

        val frontMostDepth = projected.minOf { it.z }
        val drawOrder = projected.sortedByDescending { it.z }

        for (p in drawOrder) {
            if (p.axis.label != null) {
                canvas.drawLine(cx, cy, p.x, p.y, linePaint)
            }
        }

        val newHitTargets = mutableListOf<HitTarget>()

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

            newHitTargets.add(HitTarget(p.x, p.y, r, p.axis))
        }

        // Nearest (topmost) circles were drawn last, so reverse for hit-priority.
        hitTargets = newHitTargets.reversed()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> return true
            MotionEvent.ACTION_UP -> {
                val hit = hitTargets.firstOrNull { t ->
                    val dx = event.x - t.x
                    val dy = event.y - t.y
                    dx * dx + dy * dy <= t.radius * t.radius * 1.4f
                }
                if (hit != null) {
                    onAxisSelected?.invoke(hit.axis.viewAngleX, hit.axis.viewAngleY)
                }
                return true
            }
        }
        return false
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
