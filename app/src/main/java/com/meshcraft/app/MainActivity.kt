package com.meshcraft.app

import android.animation.ValueAnimator
import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.View.MeasureSpec
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

enum class AppMode { LAYOUT, MODELING, UV_EDITING }
enum class LayoutTool { SELECT, MOVE, ROTATE, SCALE }

class MainActivity : Activity() {

    private lateinit var glView: MyGLSurfaceView
    private lateinit var gizmoView: GizmoView

    private lateinit var handButton: ImageView
    private lateinit var lockButton: ImageView

    private lateinit var fileButton: ImageView
    private lateinit var layoutTab: ImageView
    private lateinit var modelingTab: ImageView
    private lateinit var uvEditingTab: ImageView

    private lateinit var leftToolColumn: LinearLayout
    private lateinit var selectToolBtn: ImageView
    private lateinit var moveToolBtn: ImageView
    private lateinit var rotateToolBtn: ImageView
    private lateinit var scaleToolBtn: ImageView

    private var currentMode: AppMode = AppMode.LAYOUT
    private var currentLayoutTool: LayoutTool = LayoutTool.SELECT

    private var modeMenuPopup: PopupWindow? = null

    // Categorias del menu de cada modo (estilo Blender: View / Select / Add / Object).
    // Por ahora solo Layout tiene contenido definido; Modeling y UV Editing quedan pendientes.
    private val layoutMenuCategories = listOf("View", "Select", "Add", "Object")

    private val selectModeSubmenuItems = listOf("Set", "Extend", "Subtract", "Difference", "Intersect")
    private val selectMoreLessSubmenuItems = listOf("More", "Less", "Parent", "Child")
    private val selectAllByTypeSubmenuItems = listOf(
        "Mesh", "Curve", "Surface", "Metaball", "Text", "Grease Pencil", "Armature", "Lattice", "Empty"
    )

    // Items simples de Layout > View (placeholder por ahora, no dependen del modelo de escena).
    private val viewSimpleActionItems = listOf(
        "Toolbar", "Sidebar", "Tool Settings", "Asset Shelf", "Adjust Last Operation",
        "Frame Selected", "Frame All", "Perspective/Orthographic", "Local View", "Cameras"
    )
    private val viewTrailingActionItems = listOf("View Regions", "Area")

    private val viewNavigationSubmenuItems = listOf("Center View to Cursor", "Center View to Selected", "Zoom Region")
    private val viewAlignViewSubmenuItems = listOf(
        "Center Cursor and View All", "Center View to Cursor", "Align View to Active", "View Lock"
    )

    /** Viewpoint reutiliza los mismos angulos que ya usa el gizmo de ejes (ver GizmoView / animateCameraTo). */
    private data class ViewpointOption(val label: String, val angleX: Float, val angleY: Float, val planeAxis: Char)
    private val viewpointOptions = listOf(
        ViewpointOption("Top", 90f, 0f, 'Z'),
        ViewpointOption("Bottom", -90f, 0f, 'Z'),
        ViewpointOption("Front", 0f, 0f, 'Y'),
        ViewpointOption("Back", 0f, 180f, 'Y'),
        ViewpointOption("Right", 0f, -90f, 'X'),
        ViewpointOption("Left", 0f, 90f, 'X')
    )

    /**
     * Categorias de Layout > Add, con su icono propio (a diferencia de Select/View que son solo texto).
     * Todas placeholder por ahora, incluida Mesh (su submenu de primitivas queda pendiente hasta
     * que existan los iconos de cada primitiva). Camera / Collection Instance / Monkey quedan afuera
     * por ahora, sin icono todavia.
     */
    private data class AddMenuEntry(val label: String, val iconRes: Int)
    private val addMenuEntries = listOf(
        AddMenuEntry("Mesh", R.drawable.ic_add_mesh),
        AddMenuEntry("Curve", R.drawable.ic_add_curve),
        AddMenuEntry("Surface", R.drawable.ic_add_surface),
        AddMenuEntry("Text", R.drawable.ic_add_text),
        AddMenuEntry("Metaball", R.drawable.ic_add_metaball),
        AddMenuEntry("Grease Pencil", R.drawable.ic_add_grease_pencil),
        AddMenuEntry("Armature", R.drawable.ic_add_armature),
        AddMenuEntry("Lattice", R.drawable.ic_add_lattice),
        AddMenuEntry("Empty", R.drawable.ic_add_empty),
        AddMenuEntry("Image", R.drawable.ic_add_image)
    )

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

        leftToolColumn = buildLeftToolColumn()
        root.addView(leftToolColumn, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            leftMargin = margin
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

        layoutTab.setOnClickListener { onModeTabClicked(AppMode.LAYOUT, layoutTab) }
        modelingTab.setOnClickListener { onModeTabClicked(AppMode.MODELING, modelingTab) }
        uvEditingTab.setOnClickListener { onModeTabClicked(AppMode.UV_EDITING, uvEditingTab) }

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

    /**
     * Tocar el tab del modo en el que ya estas parado abre/cierra su menu (View/Select/Add/Object).
     * Tocar un tab distinto cambia de modo y cierra cualquier menu abierto.
     */
    private fun onModeTabClicked(mode: AppMode, anchor: View) {
        if (mode == currentMode) {
            toggleModeMenu(mode, anchor)
        } else {
            modeMenuPopup?.dismiss()
            setMode(mode)
        }
    }

    private fun toggleModeMenu(mode: AppMode, anchor: View) {
        val existing = modeMenuPopup
        if (existing != null && existing.isShowing) {
            existing.dismiss()
            return
        }
        showModeMenu(mode, anchor)
    }

    private fun showModeMenu(mode: AppMode, anchor: View) {
        val density = resources.displayMetrics.density
        val menuColumn = LinearLayout(this)
        menuColumn.orientation = LinearLayout.VERTICAL

        val scrollContainer = maxHeightScrollView(360)
        scrollContainer.background = menuBackground()
        val vPad = (6 * density).toInt()
        scrollContainer.setPadding(vPad, vPad, vPad, vPad)
        scrollContainer.addView(menuColumn, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        val popup = PopupWindow(
            scrollContainer,
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
            true
        )
        popup.isOutsideTouchable = true
        popup.elevation = 12 * density

        leftToolColumn.visibility = View.GONE
        popup.setOnDismissListener {
            modeMenuPopup = null
            if (currentMode == AppMode.LAYOUT) {
                leftToolColumn.visibility = View.VISIBLE
            }
        }

        fillModeMenuWithCategories(menuColumn, mode, popup)

        modeMenuPopup = popup
        popup.showAsDropDown(anchor, 0, (8 * density).toInt())
    }

    /** ScrollView que nunca crece mas alla de maxHeightDp, para que menus largos no se salgan de la pantalla. */
    private fun maxHeightScrollView(maxHeightDp: Int): ScrollView {
        val maxHeightPx = (maxHeightDp * resources.displayMetrics.density).toInt()
        return object : ScrollView(this) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val mode = MeasureSpec.getMode(heightMeasureSpec)
                val newHeightSpec = if (mode == MeasureSpec.UNSPECIFIED) {
                    MeasureSpec.makeMeasureSpec(maxHeightPx, MeasureSpec.AT_MOST)
                } else {
                    val capped = minOf(MeasureSpec.getSize(heightMeasureSpec), maxHeightPx)
                    MeasureSpec.makeMeasureSpec(capped, MeasureSpec.AT_MOST)
                }
                super.onMeasure(widthMeasureSpec, newHeightSpec)
            }
        }
    }

    private fun fillModeMenuWithCategories(menuColumn: LinearLayout, mode: AppMode, popup: PopupWindow) {
        menuColumn.removeAllViews()
        val categories = when (mode) {
            AppMode.LAYOUT -> layoutMenuCategories
            AppMode.MODELING -> emptyList() // TODO: definir categorias de Modeling.
            AppMode.UV_EDITING -> emptyList() // TODO: definir categorias de UV Editing.
        }
        if (categories.isEmpty()) {
            menuColumn.addView(buildSimpleMenuRow("Próximamente") { })
        } else {
            for (category in categories) {
                menuColumn.addView(buildSimpleMenuRow(category) {
                    fillModeMenuWithCategoryContent(menuColumn, mode, category, popup)
                })
            }
        }
        if (popup.isShowing) {
            popup.update(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
    }

    private fun fillModeMenuWithCategoryContent(menuColumn: LinearLayout, mode: AppMode, category: String, popup: PopupWindow) {
        if (mode == AppMode.LAYOUT && category == "Select") {
            renderLayoutSelectMenu(menuColumn, popup)
            return
        }
        if (mode == AppMode.LAYOUT && category == "View") {
            renderLayoutViewMenu(menuColumn, popup)
            return
        }
        if (mode == AppMode.LAYOUT && category == "Add") {
            renderLayoutAddMenu(menuColumn, popup)
            return
        }
        menuColumn.removeAllViews()
        menuColumn.addView(buildSimpleMenuRow("← Volver") {
            fillModeMenuWithCategories(menuColumn, mode, popup)
        })
        // TODO: reemplazar por las opciones reales de Object.
        menuColumn.addView(buildSimpleMenuRow("Próximamente") { })
        if (popup.isShowing) {
            popup.update(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
    }

    /** Contenido de Layout > Select, tal cual la estructura confirmada por el usuario. */
    private fun renderLayoutSelectMenu(menuColumn: LinearLayout, popup: PopupWindow) {
        menuColumn.removeAllViews()
        menuColumn.addView(buildSimpleMenuRow("← Volver") {
            fillModeMenuWithCategories(menuColumn, AppMode.LAYOUT, popup)
        })

        addSelectActionRow(menuColumn, popup, "All")
        addSelectActionRow(menuColumn, popup, "None")
        addSelectActionRow(menuColumn, popup, "Invert")
        menuColumn.addView(buildSimpleMenuRow("Box Select") {
            renderSelectSubmenu(menuColumn, popup, selectModeSubmenuItems)
        })
        menuColumn.addView(buildSimpleMenuRow("Circle Select") {
            renderSelectSubmenu(menuColumn, popup, selectModeSubmenuItems)
        })
        menuColumn.addView(buildSimpleMenuRow("Lasso Select") {
            renderSelectSubmenu(menuColumn, popup, selectModeSubmenuItems)
        })
        addSelectActionRow(menuColumn, popup, "Select Active Camera")
        addSelectActionRow(menuColumn, popup, "Select Mirror")
        addSelectActionRow(menuColumn, popup, "Select Random")
        menuColumn.addView(buildSimpleMenuRow("More/Less") {
            renderSelectSubmenu(menuColumn, popup, selectMoreLessSubmenuItems)
        })
        menuColumn.addView(buildSimpleMenuRow("Select All by Type") {
            renderSelectSubmenu(menuColumn, popup, selectAllByTypeSubmenuItems)
        })
        addSelectActionRow(menuColumn, popup, "Select Grouped")
        addSelectActionRow(menuColumn, popup, "Select Linked")
        addSelectActionRow(menuColumn, popup, "Select Pattern")

        if (popup.isShowing) {
            popup.update(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
    }

    /** Submenu generico dentro de Select (Box/Circle/Lasso, More/Less, Select All by Type). */
    private fun renderSelectSubmenu(menuColumn: LinearLayout, popup: PopupWindow, items: List<String>) {
        menuColumn.removeAllViews()
        menuColumn.addView(buildSimpleMenuRow("← Volver") {
            renderLayoutSelectMenu(menuColumn, popup)
        })
        for (item in items) {
            addSelectActionRow(menuColumn, popup, item)
        }
        if (popup.isShowing) {
            popup.update(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
    }

    private fun addSelectActionRow(menuColumn: LinearLayout, popup: PopupWindow, label: String) {
        menuColumn.addView(buildSimpleMenuRow(label) {
            popup.dismiss()
            onSelectMenuAction(label)
        })
    }

    private fun onSelectMenuAction(action: String) {
        // TODO: conectar a la logica real de seleccion una vez que exista el modelo de escena editable.
        Toast.makeText(this, action, Toast.LENGTH_SHORT).show()
    }

    /**
     * Contenido de Layout > View, en el orden acordado con el usuario:
     * items simples -> Viewpoint (funcional, reusa el gizmo) -> Navigation -> Align View -> items simples finales.
     */
    private fun renderLayoutViewMenu(menuColumn: LinearLayout, popup: PopupWindow) {
        menuColumn.removeAllViews()
        menuColumn.addView(buildSimpleMenuRow("← Volver") {
            fillModeMenuWithCategories(menuColumn, AppMode.LAYOUT, popup)
        })

        for (item in viewSimpleActionItems) {
            addViewActionRow(menuColumn, popup, item)
        }

        menuColumn.addView(buildSimpleMenuRow("Viewpoint") {
            renderViewpointSubmenu(menuColumn, popup)
        })
        menuColumn.addView(buildSimpleMenuRow("Navigation") {
            renderViewSubmenu(menuColumn, popup, viewNavigationSubmenuItems)
        })
        menuColumn.addView(buildSimpleMenuRow("Align View") {
            renderViewSubmenu(menuColumn, popup, viewAlignViewSubmenuItems)
        })

        for (item in viewTrailingActionItems) {
            addViewActionRow(menuColumn, popup, item)
        }

        if (popup.isShowing) {
            popup.update(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
    }

    /**
     * Submenu de Viewpoint: unico contenido de View con logica real, ya que reusa
     * animateCameraTo con los mismos angulos que el gizmo de ejes (ver viewpointOptions).
     */
    private fun renderViewpointSubmenu(menuColumn: LinearLayout, popup: PopupWindow) {
        menuColumn.removeAllViews()
        menuColumn.addView(buildSimpleMenuRow("← Volver") {
            renderLayoutViewMenu(menuColumn, popup)
        })
        for (option in viewpointOptions) {
            menuColumn.addView(buildSimpleMenuRow(option.label) {
                popup.dismiss()
                animateCameraTo(option.angleX, option.angleY, option.planeAxis)
            })
        }
        if (popup.isShowing) {
            popup.update(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
    }

    /** Submenu generico dentro de View (Navigation, Align View), todos placeholder por ahora. */
    private fun renderViewSubmenu(menuColumn: LinearLayout, popup: PopupWindow, items: List<String>) {
        menuColumn.removeAllViews()
        menuColumn.addView(buildSimpleMenuRow("← Volver") {
            renderLayoutViewMenu(menuColumn, popup)
        })
        for (item in items) {
            addViewActionRow(menuColumn, popup, item)
        }
        if (popup.isShowing) {
            popup.update(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
    }

    private fun addViewActionRow(menuColumn: LinearLayout, popup: PopupWindow, label: String) {
        menuColumn.addView(buildSimpleMenuRow(label) {
            popup.dismiss()
            onViewMenuAction(label)
        })
    }

    private fun onViewMenuAction(action: String) {
        // TODO: conectar cada accion a su logica real (toggles de UI, camara, area, etc.) mas adelante.
        Toast.makeText(this, action, Toast.LENGTH_SHORT).show()
    }

    /**
     * Contenido de Layout > Add: categorias con icono propio (Mesh/Curve/Surface/Text/Metaball/
     * Grease Pencil/Armature/Lattice/Empty/Image), todas placeholder. Mesh todavia no tiene submenu
     * de primitivas (Plane, Cube, UV Sphere, etc.) porque esos iconos quedan pendientes de diseño.
     * Camera / Collection Instance / Monkey quedan afuera por ahora, sin icono todavia.
     */
    private fun renderLayoutAddMenu(menuColumn: LinearLayout, popup: PopupWindow) {
        menuColumn.removeAllViews()
        menuColumn.addView(buildSimpleMenuRow("← Volver") {
            fillModeMenuWithCategories(menuColumn, AppMode.LAYOUT, popup)
        })

        for (entry in addMenuEntries) {
            menuColumn.addView(buildAddMenuItem(entry.iconRes, entry.label) {
                popup.dismiss()
                onAddMenuAction(entry.label)
            })
        }

        if (popup.isShowing) {
            popup.update(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
    }

    /** Fila de menu con icono + texto, mismo estilo que buildFileMenuItem, reusada para Add. */
    private fun buildAddMenuItem(iconRes: Int, label: String, onClick: () -> Unit): LinearLayout {
        val density = resources.displayMetrics.density
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        val hPad = (12 * density).toInt()
        val vPad = (9 * density).toInt()
        row.setPadding(hPad, vPad, hPad, vPad)
        row.isClickable = true
        row.background = menuItemPressBackground()
        row.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )

        val icon = ImageView(this)
        icon.setImageResource(iconRes)
        val iconSize = (18 * density).toInt()
        icon.layoutParams = LinearLayout.LayoutParams(iconSize, iconSize)
        row.addView(icon)

        val text = TextView(this)
        text.text = label
        text.setTextColor(Color.WHITE)
        text.textSize = 13f
        val textParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        textParams.leftMargin = (10 * density).toInt()
        text.layoutParams = textParams
        row.addView(text)

        row.setOnClickListener { onClick() }
        return row
    }

    private fun onAddMenuAction(action: String) {
        // TODO: conectar a la logica real de creacion de objetos una vez que exista el modelo de escena editable.
        Toast.makeText(this, action, Toast.LENGTH_SHORT).show()
    }

    private fun buildSimpleMenuRow(label: String, onClick: () -> Unit): LinearLayout {
        val density = resources.displayMetrics.density
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        val hPad = (12 * density).toInt()
        val vPad = (10 * density).toInt()
        row.setPadding(hPad, vPad, hPad, vPad)
        row.isClickable = true
        row.background = menuItemPressBackground()
        row.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )

        val text = TextView(this)
        text.text = label
        text.setTextColor(Color.WHITE)
        text.textSize = 14f
        row.addView(text)

        row.setOnClickListener { onClick() }
        return row
    }

    private fun buildLeftToolColumn(): LinearLayout {
        val density = resources.displayMetrics.density
        val column = LinearLayout(this)
        column.orientation = LinearLayout.VERTICAL

        selectToolBtn = createIconButton(R.drawable.ic_select_box)
        moveToolBtn = createIconButton(R.drawable.ic_move)
        rotateToolBtn = createIconButton(R.drawable.ic_rotate)
        scaleToolBtn = createIconButton(R.drawable.ic_scale)

        selectToolBtn.setOnClickListener { setLayoutTool(LayoutTool.SELECT) }
        moveToolBtn.setOnClickListener { setLayoutTool(LayoutTool.MOVE) }
        rotateToolBtn.setOnClickListener { setLayoutTool(LayoutTool.ROTATE) }
        scaleToolBtn.setOnClickListener { setLayoutTool(LayoutTool.SCALE) }

        val spacing = (8 * density).toInt()
        for (btn in listOf(selectToolBtn, moveToolBtn, rotateToolBtn, scaleToolBtn)) {
            (btn.layoutParams as LinearLayout.LayoutParams).topMargin = spacing
            column.addView(btn)
        }
        (selectToolBtn.layoutParams as LinearLayout.LayoutParams).topMargin = 0

        updateLayoutToolHighlight()

        return column
    }

    private fun setLayoutTool(tool: LayoutTool) {
        currentLayoutTool = tool
        updateLayoutToolHighlight()
        // TODO: conectar cada herramienta a su logica real (seleccionar/mover/rotar/escalar objeto)
        // una vez que exista el modelo de escena.
    }

    private fun updateLayoutToolHighlight() {
        selectToolBtn.background = circleBackground(currentLayoutTool == LayoutTool.SELECT)
        moveToolBtn.background = circleBackground(currentLayoutTool == LayoutTool.MOVE)
        rotateToolBtn.background = circleBackground(currentLayoutTool == LayoutTool.ROTATE)
        scaleToolBtn.background = circleBackground(currentLayoutTool == LayoutTool.SCALE)
    }

    private fun setMode(mode: AppMode) {
        currentMode = mode
        updateModeHighlight()
        leftToolColumn.visibility = if (mode == AppMode.LAYOUT) View.VISIBLE else View.GONE
        // TODO: cambiar el resto de la interfaz/herramientas segun el modo (Modeling / UV Editing).
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

        fileButton.background = circleBackground(true)

        val popup = PopupWindow(
            menuColumn,
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
            true
        )
        popup.isOutsideTouchable = true
        popup.elevation = 12 * density
        popup.setOnDismissListener {
            fileButton.background = circleBackground(false)
        }

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
        val hPad = (12 * density).toInt()
        val vPad = (9 * density).toInt()
        row.setPadding(hPad, vPad, hPad, vPad)
        row.isClickable = true
        row.background = menuItemPressBackground()
        row.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )

        val icon = ImageView(this)
        icon.setImageResource(iconRes)
        val iconSize = (18 * density).toInt()
        icon.layoutParams = LinearLayout.LayoutParams(iconSize, iconSize)
        row.addView(icon)

        val text = TextView(this)
        text.text = label
        text.setTextColor(Color.WHITE)
        text.textSize = 13f
        val textParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        textParams.leftMargin = (10 * density).toInt()
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

    private fun menuItemPressBackground(): StateListDrawable {
        val density = resources.displayMetrics.density
        val pressed = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 8 * density
            color = ColorStateList.valueOf(Color.argb(235, 242, 128, 26))
        }
        val normal = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 8 * density
            color = ColorStateList.valueOf(Color.TRANSPARENT)
        }
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), pressed)
            addState(intArrayOf(), normal)
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
