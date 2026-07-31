package com.meshcraft.app

import android.animation.ValueAnimator
import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import android.widget.Toast

enum class AppMode { LAYOUT, MODELING, UV_EDITING }

class MainActivity : Activity() {

    private lateinit var glView: MyGLSurfaceView
    private lateinit var gizmoView: GizmoView

    private lateinit var handButton: ImageView
    private lateinit var lockButton: ImageView

    private lateinit var fileButton: ImageView
    private lateinit var layoutTab: ImageView
    private lateinit var modelingTab: ImageView
    private lateinit var uvEditingTab: ImageView

    private var currentMode: AppMode = AppMode.LAYOUT

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        glView = MyGLSurfaceView(this)
        gizmoView = GizmoView(this)

        gizmoView.angleXProvider = { glView.renderer.angleX }
        gizmoView.angleYProvider = { glView.renderer.angleY }
        glView.onRotationChanged = { gizmoView.invalidate() }
        gizmoView.onAxisSelected = { targetX, targetY, axisChar -> animateCameraTo(targetX, targetY, axisChar) }

        val root = FrameLayout(this)
        root.addView(
            glView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        val density = resources.displayMetrics.density
        val gizmoSize = (68 * density).toInt()
        val margin = (16 * density).toInt()
        val gizmoParams = FrameLayout.LayoutParams(gizmoSize, gizmoSize)
        gizmoParams.gravity = Gravity.TOP or Gravity.END
        gizmoParams.topMargin = margin
        gizmoParams.rightMargin = margin
        root.addView(gizmoView, gizmoParams)

        root.addView(buildToolButtonColumn(), FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.END
            rightMargin = margin
            bottomMargin = margin
        })

        root.addView(buildTopBar(), FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.TOP
            topMargin = margin
        })

        setContentView(root)
    }

    private fun buildTopBar(): FrameLayout {
        val density = resources.displayMetrics.density
        val margin = (16 * density).toInt()
        val bar = FrameLayout(this)

        // File: icono suelto en la esquina, abre un menu propio (New / Save / Import / Export).
        fileButton = createIconButton(R.drawable.ic_file)
        fileButton.setOnClickListener { showFileMenu(it) }
        val fileParams = FrameLayout.LayoutParams(
            fileButton.layoutParams.width,
            fileButton.layoutParams.height
        )
        fileParams.gravity = Gravity.TOP or Gravity.START
        fileParams.leftMargin = margin
        bar.addView(fileButton, fileParams)

        // Layout / Modeling / UV Editing: pestañas de modo, centradas.
        val tabsRow = LinearLayout(this)
        tabsRow.orientation = LinearLayout.HORIZONTAL

        layoutTab = createIconButton(R.drawable.ic_layout)
        modelingTab = createIconButton(R.drawable.ic_modeling)
        uvEditingTab = createIconButton(R.drawable.ic_uv_editing)

        layoutTab.setOnClickListener { setMode(AppMode.LAYOUT) }
        modelingTab.setOnClickListener { setMode(AppMode.MODELING) }
        uvEditingTab.setOnClickListener { setMode(AppMode.UV_EDITING) }

        val spacing = (8 * density).toInt()
        for (tab in listOf(layoutTab, modelingTab, uvEditingTab)) {
            (tab.layoutParams as LinearLayout.LayoutParams).leftMargin = spacing
            tabsRow.addView(tab)
        }
        // El primer tab no necesita margen izquierdo extra.
        (layoutTab.layoutParams as LinearLayout.LayoutParams).leftMargin = 0

        val tabsParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        )
        tabsParams.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        bar.addView(tabsRow, tabsParams)

        updateModeHighlight()

        return bar
    }

    private fun setMode(mode: AppMode) {
        currentMode = mode
        updateModeHighlight()
        // TODO: cambiar la interfaz/herramientas segun el modo (Layout / Modeling / UV Editing).
    }

    private fun updateModeHighlight() {
        layoutTab.background = circleBackground(currentMode == AppMode.LAYOUT)
        modelingTab.background = circleBackground(currentMode == AppMode.MODELING)
        uvEditingTab.background = circleBackground(currentMode == AppMode.UV_EDITING)
    }

    private fun showFileMenu(anchor: View) {
        val density = resources.displayMetrics.density
        val menuColumn = LinearLayout(this)
        menuColumn.orientation = LinearLayout.VERTICAL
        menuColumn.background = menuBackground()
        val vPad = (6 * density).toInt()
        menuColumn.setPadding(vPad, vPad, vPad, vPad)

        val popup = PopupWindow(
            menuColumn,
            (170 * density).toInt(),
            LinearLayout.LayoutParams.WRAP_CONTENT,
            true
        )
        popup.isOutsideTouchable = true
        popup.elevation = 12 * density

        menuColumn.addView(buildFileMenuItem(R.drawable.ic_new, "New") {
            popup.dismiss()
            onFileMenuAction("New")
        })
        menuColumn.addView(buildFileMenuItem(R.drawable.ic_save, "Save") {
            popup.dismiss()
            onFileMenuAction("Save")
        })
        menuColumn.addView(buildFileMenuItem(R.drawable.ic_import, "Import") {
            popup.dismiss()
            onFileMenuAction("Import")
        })
        menuColumn.addView(buildFileMenuItem(R.drawable.ic_export, "Export") {
            popup.dismiss()
            onFileMenuAction("Export")
        })

        popup.showAsDropDown(anchor, 0, (8 * density).toInt())
    }

    private fun buildFileMenuItem(iconRes: Int, label: String, onClick: () -> Unit): LinearLayout {
        val density = resources.displayMetrics.density
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        val hPad = (10 * density).toInt()
        val vPad = (10 * density).toInt()
        row.setPadding(hPad, vPad, hPad, vPad)
        row.isClickable = true
        row.background = menuItemRippleBackground()
        row.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )

        val icon = ImageView(this)
        icon.setImageResource(iconRes)
        val iconSize = (20 * density).toInt()
        icon.layoutParams = LinearLayout.LayoutParams(iconSize, iconSize)
        row.addView(icon)

        val text = TextView(this)
        text.text = label
        text.setTextColor(Color.WHITE)
        text.textSize = 14f
        val textParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        textParams.leftMargin = (12 * density).toInt()
        text.layoutParams = textParams
        row.addView(text)

        row.setOnClickListener { onClick() }
        return row
    }

    private fun onFileMenuAction(action: String) {
        // TODO: reemplazar por la logica real de New/Save/Import/Export una vez definido el formato de proyecto.
        Toast.makeText(this, action, Toast.LENGTH_SHORT).show()
    }

    private fun menuBackground(): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 14 * resources.displayMetrics.density
            color = ColorStateList.valueOf(Color.argb(245, 32, 32, 32))
        }
    }

    private fun menuItemRippleBackground(): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 8 * resources.displayMetrics.density
            color = ColorStateList.valueOf(Color.TRANSPARENT)
        }
    }

    private fun buildToolButtonColumn(): LinearLayout {
        val density = resources.displayMetrics.density
        val column = LinearLayout(this)
        column.orientation = LinearLayout.VERTICAL

        val zoomInBtn = createIconButton(R.drawable.ic_zoom_in)
        val zoomOutBtn = createIconButton(R.drawable.ic_zoom_out)
        handButton = createIconButton(R.drawable.ic_hand)
        lockButton = createIconButton(R.drawable.ic_lock_rotation)

        zoomInBtn.setOnClickListener { glView.renderer.zoomIn() }
        zoomOutBtn.setOnClickListener { glView.renderer.zoomOut() }

        handButton.setOnClickListener {
            glView.touchMode = if (glView.touchMode == TouchMode.ROTATE) TouchMode.PAN else TouchMode.ROTATE
            handButton.background = circleBackground(glView.touchMode == TouchMode.PAN)
        }

        lockButton.setOnClickListener {
            glView.isLocked = !glView.isLocked
            lockButton.background = circleBackground(glView.isLocked)
        }

        val spacing = (8 * density).toInt()
        for (btn in listOf(zoomInBtn, zoomOutBtn, handButton, lockButton)) {
            (btn.layoutParams as LinearLayout.LayoutParams).topMargin = spacing
            column.addView(btn)
        }

        return column
    }

    private fun createIconButton(iconRes: Int): ImageView {
        val density = resources.displayMetrics.density
        val sizePx = (40 * density).toInt()
        val paddingPx = (9 * density).toInt()

        val iv = ImageView(this)
        iv.setImageResource(iconRes)
        iv.setPadding(paddingPx, paddingPx, paddingPx, paddingPx)
        iv.background = circleBackground(false)
        iv.layoutParams = LinearLayout.LayoutParams(sizePx, sizePx)
        iv.isClickable = true
        return iv
    }

    private fun circleBackground(active: Boolean): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            color = if (active) {
                // Blender-style orange, matching the selection outline color.
                android.content.res.ColorStateList.valueOf(Color.argb(235, 242, 128, 26))
            } else {
                android.content.res.ColorStateList.valueOf(Color.argb(150, 40, 40, 40))
            }
        }
    }

    private fun animateCameraTo(targetAngleX: Float, targetAngleY: Float, axisChar: Char) {
        val renderer = glView.renderer
        renderer.isOrthographic = true
        renderer.gridPlaneAxis = axisChar
        val startX = renderer.angleX
        val startY = renderer.angleY
        val deltaX = shortestDelta(startX, targetAngleX)
        val deltaY = shortestDelta(startY, targetAngleY)

        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 220
            interpolator = DecelerateInterpolator()
            addUpdateListener { anim ->
                val t = anim.animatedValue as Float
                renderer.angleX = startX + deltaX * t
                renderer.angleY = startY + deltaY * t
                gizmoView.invalidate()
            }
            start()
        }
    }

    private fun shortestDelta(from: Float, to: Float): Float {
        var diff = (to - from) % 360f
        if (diff > 180f) diff -= 360f
        if (diff < -180f) diff += 360f
        return diff
    }

    override fun onPause() {
        super.onPause()
        glView.onPause()
    }

    override fun onResume() {
        super.onResume()
        glView.onResume()
    }
}
