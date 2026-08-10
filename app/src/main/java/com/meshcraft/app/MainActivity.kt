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
    private lateinit var gizmoLabelView: GizmoLabelView

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

    private lateinit var modelingToolWrapper: View
    private lateinit var modelingToolColumnInner: LinearLayout
    private lateinit var modelingSelectBtn: ImageView
    private lateinit var modelingMoveBtn: ImageView
    private lateinit var modelingRotateBtn: ImageView
    private lateinit var modelingScaleBtn: ImageView
    private var currentModelingTool: LayoutTool = LayoutTool.SELECT
    /** Herramienta "extra" activa (Extrude Region, Bevel, etc.) - null si esta activa una de las 4 basicas. */
    private var currentModelingExtraTool: String? = null
    private val modelingExtraToolButtons = mutableMapOf<String, ImageView>()

    private var currentMode: AppMode = AppMode.LAYOUT
    private var currentLayoutTool: LayoutTool = LayoutTool.SELECT

    /**
     * Eje al que quedo restringido el arrastre actual (X/Y/Z), si empezo tocando el gizmo (ver
     * onViewportDragStart) - null si el arrastre es libre. Aplica con Move (flechas, ver
     * hitTestGizmoAxis) y Rotate (anillos, ver hitTestGizmoRotateAxis) activos; Scale por eje
     * queda para despues (ver charla con el usuario).
     */
    private var axisLocked: Char? = null

    private var modeMenuPopup: PopupWindow? = null

    // Categorias del menu de cada modo (estilo Blender: View / Select / Add / Object).
    // Por ahora solo Layout tiene contenido definido; Modeling y UV Editing quedan pendientes.
    private val layoutMenuCategories = listOf("View", "Select", "Add", "Object")

    /**
     * Categorias de Modeling (Edit Mode), ya cerradas con el supervisor. View se reutiliza tal cual
     * de Layout. Select/Add/Mesh/Vertex/Edge/Face/UV son contenido nuevo, todavia sin cargar (placeholder).
     */
    private val modelingMenuCategories = listOf("View", "Select", "Add", "Mesh", "Vertex", "Edge", "Face", "UV")

    /** Categorias de UV Editing, ya cerradas con el supervisor. Contenido nuevo, no reutiliza nada de Layout/Modeling. */
    private val uvEditingMenuCategories = listOf("View", "Select", "Image", "UV")


    /**
     * Barra de herramientas izquierda de Modeling (Edit Mode), 16 items confirmados con el supervisor.
     * Select Box/Move/Rotate/Scale reusan los iconos que ya existen en Layout. Los otros 12 todavia no
     * tienen icono diseñado, asi que van como fila de solo texto por ahora (buildSimpleMenuRow) - el dia
     * que existan los iconos, cambiar esas filas por createIconButton es un cambio quirurgico, sin tocar
     * el resto de la logica.
     */
    private val modelingToolEntries = listOf(
        AddMenuEntry("Extrude Region", R.drawable.ic_modeling_extrude_region),
        AddMenuEntry("Inset Faces", R.drawable.ic_modeling_inset_faces),
        AddMenuEntry("Bevel", R.drawable.ic_modeling_bevel),
        AddMenuEntry("Loop Cut", R.drawable.ic_modeling_loop_cut),
        AddMenuEntry("Knife", R.drawable.ic_modeling_knife),
        AddMenuEntry("Poly Build", R.drawable.ic_modeling_poly_build),
        AddMenuEntry("Spin", R.drawable.ic_modeling_spin),
        AddMenuEntry("Smooth", R.drawable.ic_modeling_smooth),
        AddMenuEntry("Edge Slide", R.drawable.ic_modeling_edge_slide),
        AddMenuEntry("Shrink/Fatten", R.drawable.ic_modeling_shrink_fatten),
        AddMenuEntry("Shear", R.drawable.ic_modeling_shear),
        AddMenuEntry("Rip Region", R.drawable.ic_modeling_rip_region)
    )


    private val selectModeSubmenuItems = listOf("Set", "Extend", "Subtract", "Difference", "Intersect")
    private val selectMoreLessSubmenuItems = listOf("More", "Less", "Parent", "Child")
    /** Sin Parent/Child (jerarquia de objetos) - no aplica seleccionando geometria en Edit Mode. */
    private val modelingMoreLessSubmenuItems = listOf("More", "Less")
    /** Mismos iconos que las categorias de Add > Mesh/Curve/Surface/etc, ya que representan los mismos tipos de objeto. */
    private val selectAllByTypeEntries = listOf(
        AddMenuEntry("Mesh", R.drawable.ic_add_mesh),
        AddMenuEntry("Curve", R.drawable.ic_add_curve),
        AddMenuEntry("Surface", R.drawable.ic_add_surface),
        AddMenuEntry("Metaball", R.drawable.ic_add_metaball),
        AddMenuEntry("Text", R.drawable.ic_add_text),
        AddMenuEntry("Grease Pencil", R.drawable.ic_add_grease_pencil),
        AddMenuEntry("Armature", R.drawable.ic_add_armature),
        AddMenuEntry("Lattice", R.drawable.ic_add_lattice),
        AddMenuEntry("Empty", R.drawable.ic_add_empty)
    )

    // Items simples de Layout > View (placeholder por ahora, no dependen del modelo de escena).
    // Nota: Asset Shelf, Cameras y View Regions se sacaron a proposito - dependen de sistemas
    // que la app todavia no tiene (biblioteca de assets, objetos Camara, layout configurable).
    private val viewSimpleActionItems = listOf(
        "Toolbar", "Sidebar", "Tool Settings", "Adjust Last Operation",
        "Frame Selected", "Frame All", "Perspective/Orthographic", "Local View"
    )
    private val viewTrailingActionItems = listOf("Area")

    /**
     * Navigation: 15 items acordados con el supervisor. Se sacaron "Center View to Cursor"
     * (pertenece a Align View) y "Center View to Selected" (no existe en el menu real de Blender).
     * Fly/Walk Navigation quedan afuera: no dependen de un sistema faltante sino de definir como
     * se controlarian por touch (son modos pensados para mouse+teclado/WASD). Zoom Camera 1:1 no
     * entra porque depende de Camera, fuera de alcance.
     */
    private val viewNavigationSubmenuItems = listOf(
        "Orbit Left", "Orbit Right", "Orbit Up", "Orbit Down", "Orbit Opposite",
        "Roll Left", "Roll Right",
        "Pan Left", "Pan Right", "Pan Up", "Pan Down",
        "Zoom In", "Zoom Out", "Zoom Region",
        "Dolly View"
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

    private val meshPrimitiveEntries = listOf(
        AddMenuEntry("Plane", R.drawable.ic_mesh_plane),
        AddMenuEntry("Cube", R.drawable.ic_mesh_cube),
        AddMenuEntry("Circle", R.drawable.ic_mesh_circle),
        AddMenuEntry("UV Sphere", R.drawable.ic_mesh_uv_sphere),
        AddMenuEntry("Ico Sphere", R.drawable.ic_mesh_ico_sphere),
        AddMenuEntry("Cylinder", R.drawable.ic_mesh_cylinder),
        AddMenuEntry("Cone", R.drawable.ic_mesh_cone),
        AddMenuEntry("Torus", R.drawable.ic_mesh_torus),
        AddMenuEntry("Grid", R.drawable.ic_mesh_grid),
        AddMenuEntry("Monkey", R.drawable.ic_mesh_monkey)
    )

    private val curvePrimitiveEntries = listOf(
        AddMenuEntry("Bézier", R.drawable.ic_curve_bezier),
        AddMenuEntry("Circle", R.drawable.ic_curve_circle),
        AddMenuEntry("Nurbs Curve", R.drawable.ic_curve_nurbs_curve),
        AddMenuEntry("Nurbs Circle", R.drawable.ic_curve_nurbs_circle),
        AddMenuEntry("Path", R.drawable.ic_curve_path)
    )

    /**
     * Nurbs Curve / Nurbs Circle aca son objetos distintos a los del menu Curve (mismo nombre,
     * pero flavor Surface) - por eso usan sus propios recursos ic_surface_nurbs_*.
     */
    private val surfacePrimitiveEntries = listOf(
        AddMenuEntry("Nurbs Curve", R.drawable.ic_surface_nurbs_curve),
        AddMenuEntry("Nurbs Circle", R.drawable.ic_surface_nurbs_circle),
        AddMenuEntry("Nurbs Surface", R.drawable.ic_surface_nurbs_surface),
        AddMenuEntry("Nurbs Cylinder", R.drawable.ic_surface_nurbs_cylinder),
        AddMenuEntry("Nurbs Sphere", R.drawable.ic_surface_nurbs_sphere),
        AddMenuEntry("Nurbs Torus", R.drawable.ic_surface_nurbs_torus)
    )

    private val metaballPrimitiveEntries = listOf(
        AddMenuEntry("Ball", R.drawable.ic_metaball_ball),
        AddMenuEntry("Capsule", R.drawable.ic_metaball_capsule),
        AddMenuEntry("Plane", R.drawable.ic_metaball_plane),
        AddMenuEntry("Ellipsoid", R.drawable.ic_metaball_ellipsoid),
        AddMenuEntry("Cube", R.drawable.ic_metaball_cube)
    )

    private val greasePencilPrimitiveEntries = listOf(
        AddMenuEntry("Blank", R.drawable.ic_grease_pencil_blank),
        AddMenuEntry("Stroke", R.drawable.ic_grease_pencil_stroke),
        AddMenuEntry("Monkey", R.drawable.ic_grease_pencil_monkey)
    )

    private val emptyPrimitiveEntries = listOf(
        AddMenuEntry("Plain Axes", R.drawable.ic_empty_plain_axes),
        AddMenuEntry("Arrows", R.drawable.ic_empty_arrows),
        AddMenuEntry("Single Arrow", R.drawable.ic_empty_single_arrow),
        AddMenuEntry("Circle", R.drawable.ic_empty_circle),
        AddMenuEntry("Cube", R.drawable.ic_empty_cube),
        AddMenuEntry("Sphere", R.drawable.ic_empty_sphere),
        AddMenuEntry("Cone", R.drawable.ic_empty_cone)
    )

    private val imagePrimitiveEntries = listOf(
        AddMenuEntry("Reference", R.drawable.ic_image_reference),
        AddMenuEntry("Background", R.drawable.ic_image_background),
        AddMenuEntry("Mesh Plane", R.drawable.ic_image_mesh_plane),
        AddMenuEntry("Empty Image", R.drawable.ic_image_empty_image)
    )

    /**
     * Contenido de Layout > Object: todo como filas simples, sin submenu (decisión del usuario).
     * Se sacó "Quick Effects" (dependia de Particles, fuera de alcance) y ademas Asset, Constraints,
     * Clean Up y Delete Global - todos dependen de sistemas que la app no tiene todavia (biblioteca
     * de assets, constraints pensados para animacion, historial de datos para limpiar, objetos
     * enlazados entre escenas). Mismo criterio que se aplico en Layout > View.
     */
    private val objectMenuItems = listOf(
        "Transform", "Set Origin", "Mirror", "Clear", "Apply", "Snap",
        "Duplicate Objects", "Duplicate Linked", "Join", "Copy Objects", "Paste Objects",
        "Collection", "Relations", "Parent", "Modifiers",
        "Link/Transfer Data", "Shade Smooth", "Shade Auto Smooth", "Shade Flat",
        "Convert", "Show/Hide", "Delete"
    )

    /**
     * Contenido de Modeling > Mesh/Vertex/Edge/Face/UV: 5 listas cerradas con el supervisor.
     * Todo como filas planas por ahora (estructura primero); Snap y Face Data entran como categoria
     * pero su submenu interno queda pendiente de detallar mas adelante (items dependientes del
     * Cursor 3D en el caso de Snap). Se excluyen a proposito: Set Attribute (Mesh, deshabilitado en
     * Blender - depende de Geometry Nodes), Extrude to Cursor or Add y Hooks (Vertex, dependen de
     * Cursor 3D y del modificador Hook respectivamente), Mark/Clear Freestyle Edge (Edge, depende de
     * Freestyle). Pendientes sin decidir todavia, tambien afuera: Weights (Mesh), Vertex Groups,
     * Blend from Shape y Propagate to Shapes (Vertex) - se resuelven junto con Armature/Shape Keys.
     */
    private val modelingMeshMenuItems = listOf(
        "Transform", "Mirror", "Snap", "Duplicate", "Extrude", "Merge", "Split", "Separate",
        "Bisect", "Knife Project", "Knife Topology Tool", "Convex Hull",
        "Symmetrize", "Snap to Symmetry", "Normals", "Shading", "Sort Elements",
        "Show/Hide", "Clean Up", "Delete"
    )
    private val modelingVertexMenuItems = listOf(
        "Extrude Vertices", "Bevel Vertices", "New Edge/Face from Vertices",
        "Connect Vertex Path", "Connect Vertex Pairs",
        "Rip Vertices", "Rip Vertices and Fill", "Rip Vertices and Extend",
        "Slide Vertices", "Smooth Vertices", "Smooth Vertices (Laplacian)",
        "Vertex Crease", "Make Vertex Parent"
    )
    private val modelingEdgeMenuItems = listOf(
        "Extrude Edges", "Bevel Edges", "Bridge Edge Loops", "Screw",
        "Subdivide", "Subdivide Edge-Ring", "Un-Subdivide",
        "Rotate Edge CW", "Rotate Edge CCW",
        "Edge Slide", "Loop Cut and Slide", "Offset Edge Slide",
        "Edge Bevel Weight", "Edge Crease",
        "Mark Seam", "Clear Seam",
        "Mark Sharp", "Clear Sharp", "Mark Sharp from Vertices", "Clear Sharp from Vertices",
        "Set Sharpness by Angle"
    )
    private val modelingFaceMenuItems = listOf(
        "Extrude Faces", "Extrude Faces Along Normals", "Extrude Individual Faces",
        "Inset Faces", "Poke Faces",
        "Triangulate Faces", "Triangles to Quads", "Solidify Faces", "Wireframe",
        "Fill", "Grid Fill", "Beautify Faces",
        "Intersect (Knife)", "Intersect (Boolean)", "Weld Edges into Faces",
        "Shade Smooth", "Shade Flat",
        "Face Data"
    )
    /** UV Editing ya esta 100% confirmado en el alcance - los 14 entran sin excepcion. */
    private val modelingUvMenuItems = listOf(
        "Unwrap Angle Based", "Unwrap Conformal", "Unwrap Minimum Stretch",
        "Smart UV Project", "Lightmap Pack", "Follow Active Quads",
        "Cube Projection", "Cylinder Projection", "Sphere Projection",
        "Project from View", "Project from View (Bounds)",
        "Mark Seam", "Clear Seam", "Reset"
    )

    /**
     * Contenido de UV Editing > View/Select/Image/UV: 4 listas cerradas con el supervisor. Se saca
     * "Asset Shelf" (deshabilitado en Blender, depende de biblioteca de assets), "Center View to
     * Cursor" (depende de un Cursor 2D propio del editor UV, mismo criterio que el Cursor 3D: se
     * pospone) y "Open Cached Render" (depende de Render, fuera de alcance). Zoom, Area, Select All
     * by Trait y Snap entran como categoria pero su contenido interno queda pendiente de detallar.
     */
    private val uvViewSimpleItems = listOf("Toolbar", "Sidebar", "Tool Settings", "Adjust Last Operation", "Update Automatically", "Show Metadata", "Frame Selected", "Frame All")
    /**
     * Ojo al implementar: a diferencia de Layout/Modeling, aca solo Lasso Select tiene submenu propio
     * (reusa selectModeSubmenuItems) - Box Select y Circle Select son acciones directas. Box Select
     * Pinned depende de que exista "pin" de vertices UV (ver Pin/Unpin/Invert Pins en el menu UV).
     */
    private val uvSelectSimpleItems = listOf("All", "None", "Invert", "Box Select", "Box Select Pinned", "Circle Select")
    private val uvSelectTrailingItems = listOf("More", "Less", "Select Similar", "Select Linked", "Select Split")
    private val uvImageMenuItems = listOf("New...", "Open...", "Copy", "Paste", "Save All Images")
    private val uvUvMenuItems = listOf("Transform", "Mirror", "Snap", "Round to Pixels", "Constrain to Image Bounds", "Merge", "Split", "Rip Move UVs", "Live Unwrap", "Unwrap", "Pin", "Unpin", "Invert Pins", "Mark Seam", "Clear Seam", "Seams from Islands", "Pack Islands", "Average Islands Scale", "Arrange/Align Islands", "Set User Region", "Custom Region", "Minimize Stretch", "Stitch", "Align", "Align Rotation", "Move on Axis", "Copy UVs", "Paste UVs", "Show/Hide Faces", "Reset")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        glView = MyGLSurfaceView(this)
        gizmoView = GizmoView(this)
        gizmoLabelView = GizmoLabelView(this)

        gizmoView.angleXProvider = { glView.renderer.angleX }
        gizmoView.angleYProvider = { glView.renderer.angleY }
        glView.onRotationChanged = { gizmoView.invalidate() }
        gizmoView.onAxisSelected = { targetX, targetY, axisChar -> animateCameraTo(targetX, targetY, axisChar) }
        glView.onTap = { x, y -> onViewportTap(x, y) }
        glView.onDragMove = { dx, dy, x, y -> onViewportDragMove(dx, dy, x, y) }
        glView.onDragStart = { x, y -> onViewportDragStart(x, y) }
        glView.onDragEnd = { onViewportDragEnd() }

        val root = FrameLayout(this)
        root.addView(
            glView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        val density = resources.displayMetrics.density
        root.addView(gizmoLabelView, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
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

        modelingToolWrapper = maxHeightScrollView(500)
        (modelingToolWrapper as ScrollView).isVerticalScrollBarEnabled = false
        modelingToolColumnInner = buildModelingToolColumn()
        (modelingToolWrapper as ScrollView).addView(modelingToolColumnInner, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        modelingToolWrapper.visibility = View.GONE
        root.addView(modelingToolWrapper, FrameLayout.LayoutParams(
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
        modelingToolWrapper.visibility = View.GONE
        popup.setOnDismissListener {
            modeMenuPopup = null
            if (currentMode == AppMode.LAYOUT) {
                leftToolColumn.visibility = View.VISIBLE
            }
            if (currentMode == AppMode.MODELING) {
                modelingToolWrapper.visibility = View.VISIBLE
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
            AppMode.MODELING -> modelingMenuCategories
            AppMode.UV_EDITING -> uvEditingMenuCategories
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

    /**
     * Dispatcher central: decide que render function usar segun modo+categoria. El chequeo de
     * "View" para UV_EDITING va ANTES del generico "category == View" (que sirve a Layout/Modeling),
     * porque si no, el generico lo intercepta primero y el contenido especifico de UV_EDITING > View
     * (renderUvEditingViewMenu) nunca se alcanza.
     */
    private fun fillModeMenuWithCategoryContent(menuColumn: LinearLayout, mode: AppMode, category: String, popup: PopupWindow) {
        if (mode == AppMode.MODELING && category == "Select") {
            renderModelingSelectMenu(menuColumn, popup)
            return
        }
        if (mode == AppMode.LAYOUT && category == "Select") {
            renderLayoutSelectMenu(menuColumn, popup)
            return
        }
        if (mode == AppMode.UV_EDITING && category == "View") {
            renderUvEditingViewMenu(menuColumn, popup)
            return
        }
        if (category == "View") {
            renderViewMenu(menuColumn, mode, popup)
            return
        }
        if (mode == AppMode.MODELING && category == "Add") {
            renderModelingAddMenu(menuColumn, popup)
            return
        }
        if (mode == AppMode.LAYOUT && category == "Add") {
            renderLayoutAddMenu(menuColumn, popup)
            return
        }
        if (mode == AppMode.MODELING && category == "Mesh") {
            renderModelingFlatMenu(menuColumn, popup, modelingMeshMenuItems)
            return
        }
        if (mode == AppMode.MODELING && category == "Vertex") {
            renderModelingFlatMenu(menuColumn, popup, modelingVertexMenuItems)
            return
        }
        if (mode == AppMode.MODELING && category == "Edge") {
            renderModelingFlatMenu(menuColumn, popup, modelingEdgeMenuItems)
            return
        }
        if (mode == AppMode.MODELING && category == "Face") {
            renderModelingFlatMenu(menuColumn, popup, modelingFaceMenuItems)
            return
        }
        if (mode == AppMode.MODELING && category == "UV") {
            renderModelingFlatMenu(menuColumn, popup, modelingUvMenuItems)
            return
        }
        if (mode == AppMode.UV_EDITING && category == "Select") {
            renderUvEditingSelectMenu(menuColumn, popup)
            return
        }
        if (mode == AppMode.UV_EDITING && category == "Image") {
            renderUvEditingFlatMenu(menuColumn, popup, uvImageMenuItems)
            return
        }
        if (mode == AppMode.UV_EDITING && category == "UV") {
            renderUvEditingUvMenu(menuColumn, popup)
            return
        }
        if (mode == AppMode.LAYOUT && category == "Object") {
            renderLayoutObjectMenu(menuColumn, popup)
            return
        }
        menuColumn.removeAllViews()
        menuColumn.addView(buildSimpleMenuRow("← Volver") {
            fillModeMenuWithCategories(menuColumn, mode, popup)
        })
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
            renderSelectAllByTypeSubmenu(menuColumn, popup)
        })
        addSelectActionRow(menuColumn, popup, "Select Grouped")
        addSelectActionRow(menuColumn, popup, "Select Linked")
        addSelectActionRow(menuColumn, popup, "Select Pattern")

        if (popup.isShowing) {
            popup.update(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
    }

    /** Submenu generico dentro de Select (Box/Circle/Lasso, More/Less), solo texto. */
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

    /** Submenu de Select All by Type, con icono por tipo (mismos recursos que Add > Mesh/Curve/etc). */
    private fun renderSelectAllByTypeSubmenu(menuColumn: LinearLayout, popup: PopupWindow) {
        menuColumn.removeAllViews()
        menuColumn.addView(buildSimpleMenuRow("← Volver") {
            renderLayoutSelectMenu(menuColumn, popup)
        })
        for (entry in selectAllByTypeEntries) {
            menuColumn.addView(buildAddMenuItem(entry.iconRes, entry.label) {
                popup.dismiss()
                onSelectMenuAction(entry.label)
            })
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

    /**
     * Contenido de Modeling > Select: lista nueva confirmada por el supervisor (no reutiliza Layout,
     * en Edit Mode se selecciona geometria, no objetos). Se saca "By Attribute" (aparece deshabilitado
     * en Blender mismo, depende de Geometry Nodes). Box/Circle/Lasso Select reusan el mismo submenu
     * de modo de seleccion (Set/Extend/Subtract/Difference/Intersect) que ya usa Layout > Select.
     */
    private fun renderModelingSelectMenu(menuColumn: LinearLayout, popup: PopupWindow) {
        menuColumn.removeAllViews()
        menuColumn.addView(buildSimpleMenuRow("← Volver") {
            fillModeMenuWithCategories(menuColumn, AppMode.MODELING, popup)
        })

        addModelingSelectActionRow(menuColumn, popup, "All")
        addModelingSelectActionRow(menuColumn, popup, "None")
        addModelingSelectActionRow(menuColumn, popup, "Invert")
        menuColumn.addView(buildSimpleMenuRow("Box Select") {
            renderModelingSelectSubmenu(menuColumn, popup, selectModeSubmenuItems)
        })
        menuColumn.addView(buildSimpleMenuRow("Circle Select") {
            renderModelingSelectSubmenu(menuColumn, popup, selectModeSubmenuItems)
        })
        menuColumn.addView(buildSimpleMenuRow("Lasso Select") {
            renderModelingSelectSubmenu(menuColumn, popup, selectModeSubmenuItems)
        })
        addModelingSelectActionRow(menuColumn, popup, "Select Mirror")
        addModelingSelectActionRow(menuColumn, popup, "Select Random")
        addModelingSelectActionRow(menuColumn, popup, "Checker Deselect")
        menuColumn.addView(buildSimpleMenuRow("More/Less") {
            renderModelingSelectSubmenu(menuColumn, popup, modelingMoreLessSubmenuItems)
        })
        addModelingSelectActionRow(menuColumn, popup, "Select Similar")
        addModelingSelectActionRow(menuColumn, popup, "Select All by Trait")
        addModelingSelectActionRow(menuColumn, popup, "Select Linked")
        addModelingSelectActionRow(menuColumn, popup, "Select Loops")
        addModelingSelectActionRow(menuColumn, popup, "Sharp Edges")
        addModelingSelectActionRow(menuColumn, popup, "Side of Active")

        if (popup.isShowing) {
            popup.update(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
    }

    /** Submenu generico dentro de Modeling > Select (Box/Circle/Lasso, More/Less), solo texto. */
    private fun renderModelingSelectSubmenu(menuColumn: LinearLayout, popup: PopupWindow, items: List<String>) {
        menuColumn.removeAllViews()
        menuColumn.addView(buildSimpleMenuRow("← Volver") {
            renderModelingSelectMenu(menuColumn, popup)
        })
        for (item in items) {
            addModelingSelectActionRow(menuColumn, popup, item)
        }
        if (popup.isShowing) {
            popup.update(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
    }

    private fun addModelingSelectActionRow(menuColumn: LinearLayout, popup: PopupWindow, label: String) {
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
    private fun renderViewMenu(menuColumn: LinearLayout, mode: AppMode, popup: PopupWindow) {
        menuColumn.removeAllViews()
        menuColumn.addView(buildSimpleMenuRow("← Volver") {
            fillModeMenuWithCategories(menuColumn, mode, popup)
        })

        for (item in viewSimpleActionItems) {
            addViewActionRow(menuColumn, popup, item)
        }

        menuColumn.addView(buildSimpleMenuRow("Viewpoint") {
            renderViewpointSubmenu(menuColumn, mode, popup)
        })
        menuColumn.addView(buildSimpleMenuRow("Navigation") {
            renderViewSubmenu(menuColumn, mode, popup, viewNavigationSubmenuItems)
        })
        addViewActionRow(menuColumn, popup, "Align View to Active")

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
    private fun renderViewpointSubmenu(menuColumn: LinearLayout, mode: AppMode, popup: PopupWindow) {
        menuColumn.removeAllViews()
        menuColumn.addView(buildSimpleMenuRow("← Volver") {
            renderViewMenu(menuColumn, mode, popup)
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
    private fun renderViewSubmenu(menuColumn: LinearLayout, mode: AppMode, popup: PopupWindow, items: List<String>) {
        menuColumn.removeAllViews()
        menuColumn.addView(buildSimpleMenuRow("← Volver") {
            renderViewMenu(menuColumn, mode, popup)
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
            if (entry.label == "Mesh") {
                menuColumn.addView(buildAddMenuItem(entry.iconRes, entry.label) {
                    renderMeshPrimitivesSubmenu(menuColumn, popup)
                })
                continue
            }
            if (entry.label == "Curve") {
                menuColumn.addView(buildAddMenuItem(entry.iconRes, entry.label) {
                    renderCurvePrimitivesSubmenu(menuColumn, popup)
                })
                continue
            }
            if (entry.label == "Surface") {
                menuColumn.addView(buildAddMenuItem(entry.iconRes, entry.label) {
                    renderSurfacePrimitivesSubmenu(menuColumn, popup)
                })
                continue
            }
            if (entry.label == "Metaball") {
                menuColumn.addView(buildAddMenuItem(entry.iconRes, entry.label) {
                    renderMetaballPrimitivesSubmenu(menuColumn, popup)
                })
                continue
            }
            if (entry.label == "Grease Pencil") {
                menuColumn.addView(buildAddMenuItem(entry.iconRes, entry.label) {
                    renderGreasePencilPrimitivesSubmenu(menuColumn, popup)
                })
                continue
            }
            if (entry.label == "Empty") {
                menuColumn.addView(buildAddMenuItem(entry.iconRes, entry.label) {
                    renderEmptyPrimitivesSubmenu(menuColumn, popup)
                })
                continue
            }
            if (entry.label == "Image") {
                menuColumn.addView(buildAddMenuItem(entry.iconRes, entry.label) {
                    renderImagePrimitivesSubmenu(menuColumn, popup)
                })
                continue
            }
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

    /**
     * Submenu de primitivas dentro de Add > Mesh. "Cube" ya crea geometria real (unica primitiva
     * que existe hoy, ver Cube.kt) - el resto sigue como placeholder (onAddMenuAction) hasta que
     * tengan su propia geometria.
     */
    private fun renderMeshPrimitivesSubmenu(menuColumn: LinearLayout, popup: PopupWindow) {
        menuColumn.removeAllViews()
        menuColumn.addView(buildSimpleMenuRow("← Volver") {
            renderLayoutAddMenu(menuColumn, popup)
        })
        for (entry in meshPrimitiveEntries) {
            menuColumn.addView(buildAddMenuItem(entry.iconRes, entry.label) {
                popup.dismiss()
                if (entry.label == "Cube") {
                    addCubeObject()
                } else {
                    onAddMenuAction(entry.label)
                }
            })
        }
        if (popup.isShowing) {
            popup.update(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
    }

    /**
     * Add > Mesh > Cube real: agrega un SceneObject nuevo via MyGLRenderer.addCube() (que ya
     * deja todo lo demas deseleccionado) y pide un redraw. La seleccion visual (contorno naranja)
     * ya reacciona sola porque Cube.kt lee SceneObject.selected en cada draw().
     */
    private fun addCubeObject() {
        glView.renderer.addCube()
        glView.requestRender()
    }

    /** Submenu de primitivas dentro de Add > Curve, mismo patron que renderMeshPrimitivesSubmenu. */
    private fun renderCurvePrimitivesSubmenu(menuColumn: LinearLayout, popup: PopupWindow) {
        menuColumn.removeAllViews()
        menuColumn.addView(buildSimpleMenuRow("← Volver") {
            renderLayoutAddMenu(menuColumn, popup)
        })
        for (entry in curvePrimitiveEntries) {
            menuColumn.addView(buildAddMenuItem(entry.iconRes, entry.label) {
                popup.dismiss()
                onAddMenuAction(entry.label)
            })
        }
        if (popup.isShowing) {
            popup.update(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
    }

    /** Submenu de primitivas dentro de Add > Surface, mismo patron que renderMeshPrimitivesSubmenu. */
    private fun renderSurfacePrimitivesSubmenu(menuColumn: LinearLayout, popup: PopupWindow) {
        menuColumn.removeAllViews()
        menuColumn.addView(buildSimpleMenuRow("← Volver") {
            renderLayoutAddMenu(menuColumn, popup)
        })
        for (entry in surfacePrimitiveEntries) {
            menuColumn.addView(buildAddMenuItem(entry.iconRes, entry.label) {
                popup.dismiss()
                onAddMenuAction(entry.label)
            })
        }
        if (popup.isShowing) {
            popup.update(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
    }

    /** Submenu de primitivas dentro de Add > Metaball, mismo patron que renderMeshPrimitivesSubmenu. */
    private fun renderMetaballPrimitivesSubmenu(menuColumn: LinearLayout, popup: PopupWindow) {
        menuColumn.removeAllViews()
        menuColumn.addView(buildSimpleMenuRow("← Volver") {
            renderLayoutAddMenu(menuColumn, popup)
        })
        for (entry in metaballPrimitiveEntries) {
            menuColumn.addView(buildAddMenuItem(entry.iconRes, entry.label) {
                popup.dismiss()
                onAddMenuAction(entry.label)
            })
        }
        if (popup.isShowing) {
            popup.update(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
    }

    /** Submenu de primitivas dentro de Add > Grease Pencil, mismo patron que renderMeshPrimitivesSubmenu. */
    private fun renderGreasePencilPrimitivesSubmenu(menuColumn: LinearLayout, popup: PopupWindow) {
        menuColumn.removeAllViews()
        menuColumn.addView(buildSimpleMenuRow("← Volver") {
            renderLayoutAddMenu(menuColumn, popup)
        })
        for (entry in greasePencilPrimitiveEntries) {
            menuColumn.addView(buildAddMenuItem(entry.iconRes, entry.label) {
                popup.dismiss()
                onAddMenuAction(entry.label)
            })
        }
        if (popup.isShowing) {
            popup.update(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
    }

    /** Submenu de primitivas dentro de Add > Empty, mismo patron que renderMeshPrimitivesSubmenu. */
    private fun renderEmptyPrimitivesSubmenu(menuColumn: LinearLayout, popup: PopupWindow) {
        menuColumn.removeAllViews()
        menuColumn.addView(buildSimpleMenuRow("← Volver") {
            renderLayoutAddMenu(menuColumn, popup)
        })
        for (entry in emptyPrimitiveEntries) {
            menuColumn.addView(buildAddMenuItem(entry.iconRes, entry.label) {
                popup.dismiss()
                onAddMenuAction(entry.label)
            })
        }
        if (popup.isShowing) {
            popup.update(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
    }

    /** Submenu de primitivas dentro de Add > Image, mismo patron que renderMeshPrimitivesSubmenu. */
    private fun renderImagePrimitivesSubmenu(menuColumn: LinearLayout, popup: PopupWindow) {
        menuColumn.removeAllViews()
        menuColumn.addView(buildSimpleMenuRow("← Volver") {
            renderLayoutAddMenu(menuColumn, popup)
        })
        for (entry in imagePrimitiveEntries) {
            menuColumn.addView(buildAddMenuItem(entry.iconRes, entry.label) {
                popup.dismiss()
                onAddMenuAction(entry.label)
            })
        }
        if (popup.isShowing) {
            popup.update(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
    }

    /**
     * Contenido de Modeling > Add: va directo a las primitivas de malla (mismas 10 que Layout > Add >
     * Mesh, mismos iconos), sin categoria intermedia - en Edit Mode "Add" siempre crea geometria.
     */
    private fun renderModelingAddMenu(menuColumn: LinearLayout, popup: PopupWindow) {
        menuColumn.removeAllViews()
        menuColumn.addView(buildSimpleMenuRow("← Volver") {
            fillModeMenuWithCategories(menuColumn, AppMode.MODELING, popup)
        })
        for (entry in meshPrimitiveEntries) {
            menuColumn.addView(buildAddMenuItem(entry.iconRes, entry.label) {
                popup.dismiss()
                onAddMenuAction(entry.label)
            })
        }
        if (popup.isShowing) {
            popup.update(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
    }

    private fun onAddMenuAction(action: String) {
        // TODO: conectar a la logica real de creacion de objetos una vez que exista el modelo de escena editable.
        Toast.makeText(this, action, Toast.LENGTH_SHORT).show()
    }

    /**
     * Render generico y compartido para Modeling > Mesh/Vertex/Edge/Face/UV: todos son listas planas
     * sin submenu por ahora, mismo patron que renderLayoutObjectMenu pero apuntando a Modeling.
     */
    private fun renderModelingFlatMenu(menuColumn: LinearLayout, popup: PopupWindow, items: List<String>) {
        menuColumn.removeAllViews()
        menuColumn.addView(buildSimpleMenuRow("← Volver") {
            fillModeMenuWithCategories(menuColumn, AppMode.MODELING, popup)
        })
        for (item in items) {
            menuColumn.addView(buildSimpleMenuRow(item) {
                popup.dismiss()
                onModelingMenuAction(item)
            })
        }
        if (popup.isShowing) {
            popup.update(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
    }

    /** Placeholder generico para categorias marcadas "pendiente de detallar" (Zoom, Area, Select All by Trait, Snap). */
    private fun renderPendingSubmenu(menuColumn: LinearLayout, popup: PopupWindow, onBack: () -> Unit) {
        menuColumn.removeAllViews()
        menuColumn.addView(buildSimpleMenuRow("← Volver") { onBack() })
        menuColumn.addView(buildSimpleMenuRow("Próximamente") { })
        if (popup.isShowing) {
            popup.update(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
    }

    private fun renderUvEditingViewMenu(menuColumn: LinearLayout, popup: PopupWindow) {
        menuColumn.removeAllViews()
        menuColumn.addView(buildSimpleMenuRow("← Volver") {
            fillModeMenuWithCategories(menuColumn, AppMode.UV_EDITING, popup)
        })
        for (item in uvViewSimpleItems) {
            addUvEditingActionRow(menuColumn, popup, item)
        }
        menuColumn.addView(buildSimpleMenuRow("Zoom") {
            renderPendingSubmenu(menuColumn, popup) { renderUvEditingViewMenu(menuColumn, popup) }
        })
        menuColumn.addView(buildSimpleMenuRow("Area") {
            renderPendingSubmenu(menuColumn, popup) { renderUvEditingViewMenu(menuColumn, popup) }
        })
        if (popup.isShowing) {
            popup.update(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
    }

    /** Ojo: aca solo Lasso Select abre submenu (reusa selectModeSubmenuItems) - Box/Circle Select son directas. */
    private fun renderUvEditingSelectMenu(menuColumn: LinearLayout, popup: PopupWindow) {
        menuColumn.removeAllViews()
        menuColumn.addView(buildSimpleMenuRow("← Volver") {
            fillModeMenuWithCategories(menuColumn, AppMode.UV_EDITING, popup)
        })
        for (item in uvSelectSimpleItems) {
            addUvEditingActionRow(menuColumn, popup, item)
        }
        menuColumn.addView(buildSimpleMenuRow("Lasso Select") {
            renderUvEditingSelectSubmenu(menuColumn, popup, selectModeSubmenuItems)
        })
        for (item in uvSelectTrailingItems) {
            addUvEditingActionRow(menuColumn, popup, item)
        }
        menuColumn.addView(buildSimpleMenuRow("Select All by Trait") {
            renderPendingSubmenu(menuColumn, popup) { renderUvEditingSelectMenu(menuColumn, popup) }
        })
        if (popup.isShowing) {
            popup.update(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
    }

    private fun renderUvEditingSelectSubmenu(menuColumn: LinearLayout, popup: PopupWindow, items: List<String>) {
        menuColumn.removeAllViews()
        menuColumn.addView(buildSimpleMenuRow("← Volver") {
            renderUvEditingSelectMenu(menuColumn, popup)
        })
        for (item in items) {
            addUvEditingActionRow(menuColumn, popup, item)
        }
        if (popup.isShowing) {
            popup.update(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
    }

    /** Contenido de UV Editing > UV: lista plana, salvo "Snap" que abre submenu pendiente de detallar. */
    private fun renderUvEditingUvMenu(menuColumn: LinearLayout, popup: PopupWindow) {
        menuColumn.removeAllViews()
        menuColumn.addView(buildSimpleMenuRow("← Volver") {
            fillModeMenuWithCategories(menuColumn, AppMode.UV_EDITING, popup)
        })
        for (item in uvUvMenuItems) {
            if (item == "Snap") {
                menuColumn.addView(buildSimpleMenuRow(item) {
                    renderPendingSubmenu(menuColumn, popup) { renderUvEditingUvMenu(menuColumn, popup) }
                })
            } else {
                addUvEditingActionRow(menuColumn, popup, item)
            }
        }
        if (popup.isShowing) {
            popup.update(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
    }

    /** Render generico para categorias de UV Editing sin excepciones (por ahora, Image). */
    private fun renderUvEditingFlatMenu(menuColumn: LinearLayout, popup: PopupWindow, items: List<String>) {
        menuColumn.removeAllViews()
        menuColumn.addView(buildSimpleMenuRow("← Volver") {
            fillModeMenuWithCategories(menuColumn, AppMode.UV_EDITING, popup)
        })
        for (item in items) {
            addUvEditingActionRow(menuColumn, popup, item)
        }
        if (popup.isShowing) {
            popup.update(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
    }

    private fun addUvEditingActionRow(menuColumn: LinearLayout, popup: PopupWindow, label: String) {
        menuColumn.addView(buildSimpleMenuRow(label) {
            popup.dismiss()
            onUvEditingMenuAction(label)
        })
    }

    private fun onUvEditingMenuAction(action: String) {
        // TODO: conectar a la logica real de UV Editing una vez que exista el modelo editable.
        Toast.makeText(this, action, Toast.LENGTH_SHORT).show()
    }

    private fun onModelingMenuAction(action: String) {
        // TODO: conectar a la logica real de edicion de malla una vez que exista el modelo editable (Edit Mode).
        Toast.makeText(this, action, Toast.LENGTH_SHORT).show()
    }

    private fun renderLayoutObjectMenu(menuColumn: LinearLayout, popup: PopupWindow) {
        menuColumn.removeAllViews()
        menuColumn.addView(buildSimpleMenuRow("← Volver") {
            fillModeMenuWithCategories(menuColumn, AppMode.LAYOUT, popup)
        })

        for (item in objectMenuItems) {
            menuColumn.addView(buildSimpleMenuRow(item) {
                popup.dismiss()
                onObjectMenuAction(item)
            })
        }

        if (popup.isShowing) {
            popup.update(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
    }

    private fun onObjectMenuAction(action: String) {
        // TODO: conectar a la logica real de cada accion una vez que exista el modelo de escena editable.
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

    /**
     * Columna izquierda de Modeling (Edit Mode): 4 botones con icono (reusa los de Layout) + 12 filas
     * de solo texto (todavia sin icono diseñado). Se envuelve en un ScrollView con altura maxima
     * (ver modelingToolWrapper en onCreate) porque 16 items no entran completos en pantallas chicas.
     */
    private fun buildModelingToolColumn(): LinearLayout {
        val density = resources.displayMetrics.density
        val column = LinearLayout(this)
        column.orientation = LinearLayout.VERTICAL

        modelingSelectBtn = createIconButton(R.drawable.ic_select_box)
        modelingMoveBtn = createIconButton(R.drawable.ic_move)
        modelingRotateBtn = createIconButton(R.drawable.ic_rotate)
        modelingScaleBtn = createIconButton(R.drawable.ic_scale)

        modelingSelectBtn.setOnClickListener { setModelingTool(LayoutTool.SELECT) }
        modelingMoveBtn.setOnClickListener { setModelingTool(LayoutTool.MOVE) }
        modelingRotateBtn.setOnClickListener { setModelingTool(LayoutTool.ROTATE) }
        modelingScaleBtn.setOnClickListener { setModelingTool(LayoutTool.SCALE) }

        val spacing = (8 * density).toInt()
        for (btn in listOf(modelingSelectBtn, modelingMoveBtn, modelingRotateBtn, modelingScaleBtn)) {
            (btn.layoutParams as LinearLayout.LayoutParams).topMargin = spacing
            column.addView(btn)
        }
        (modelingSelectBtn.layoutParams as LinearLayout.LayoutParams).topMargin = 0

        for (entry in modelingToolEntries) {
            val btn = createIconButton(entry.iconRes)
            btn.setOnClickListener { setModelingExtraTool(entry.label) }
            (btn.layoutParams as LinearLayout.LayoutParams).topMargin = spacing
            modelingExtraToolButtons[entry.label] = btn
            column.addView(btn)
        }


        updateModelingToolHighlight()
        return column
    }

    private fun setModelingTool(tool: LayoutTool) {
        currentModelingTool = tool
        currentModelingExtraTool = null
        updateModelingToolHighlight()
        // TODO: conectar cada herramienta a su logica real (seleccionar/mover/rotar/escalar geometria)
        // una vez que exista el modelo de escena editable (Edit Mode).
    }

    /** Herramienta "de un solo toque" (Extrude Region, Bevel, etc.) - queda resaltada hasta elegir otra. */
    private fun setModelingExtraTool(label: String) {
        currentModelingExtraTool = label
        updateModelingToolHighlight()
        onModelingMenuAction(label)
    }

    /**
     * Unifica el resaltado de los 16 botones de la barra de Modeling: solo uno activo a la vez
     * (basico o "extra"), igual que en Blender real - no son dos grupos independientes.
     */
    private fun updateModelingToolHighlight() {
        modelingSelectBtn.background = circleBackground(currentModelingExtraTool == null && currentModelingTool == LayoutTool.SELECT)
        modelingMoveBtn.background = circleBackground(currentModelingExtraTool == null && currentModelingTool == LayoutTool.MOVE)
        modelingRotateBtn.background = circleBackground(currentModelingExtraTool == null && currentModelingTool == LayoutTool.ROTATE)
        modelingScaleBtn.background = circleBackground(currentModelingExtraTool == null && currentModelingTool == LayoutTool.SCALE)
        for ((label, btn) in modelingExtraToolButtons) {
            btn.background = circleBackground(currentModelingExtraTool == label)
        }
    }

    private fun setLayoutTool(tool: LayoutTool) {
        // El gizmo se muestra con Move (flechas) y Rotate (anillos) - las dos herramientas que ya
        // tienen arrastre restringido a eje implementado. Scale por eje queda para despues, requiere
        // cambios al modelo de datos (scale float -> scaleX/Y/Z) - ver charla con el usuario.
        glView.renderer.gizmoMode = when (tool) {
            LayoutTool.MOVE -> GizmoMode.MOVE
            LayoutTool.ROTATE -> GizmoMode.ROTATE
            else -> null
        }
        currentLayoutTool = tool
        updateLayoutToolHighlight()
        // Move/Rotate/Scale ya funcionan via onViewportDragMove/glView.onDragMove (libre, o
        // restringido a eje si el gizmo esta activo - ver onViewportDragStart/axisLocked);
        // Select via onViewportTap.
    }

    private fun updateLayoutToolHighlight() {
        selectToolBtn.background = circleBackground(currentLayoutTool == LayoutTool.SELECT)
        moveToolBtn.background = circleBackground(currentLayoutTool == LayoutTool.MOVE)
        rotateToolBtn.background = circleBackground(currentLayoutTool == LayoutTool.ROTATE)
        scaleToolBtn.background = circleBackground(currentLayoutTool == LayoutTool.SCALE)
    }

    /**
     * ACTION_DOWN en el viewport: si estamos en Layout con Move o Rotate activos y hay un objeto
     * seleccionado, intenta el hit-test contra el gizmo correspondiente (flechas para Move via
     * hitTestGizmoAxis, anillos para Rotate via hitTestGizmoRotateAxis). Si el dedo toco el gizmo,
     * el arrastre que sigue queda restringido a ese eje (ver onViewportDragMove); si no, cae al
     * gesto libre de siempre.
     */
    private fun onViewportDragStart(x: Float, y: Float) {
        axisLocked = null
        if (currentMode != AppMode.LAYOUT) return
        axisLocked = when (currentLayoutTool) {
            LayoutTool.MOVE -> glView.renderer.hitTestGizmoAxis(x, y)
            LayoutTool.ROTATE -> glView.renderer.hitTestGizmoRotateAxis(x, y)
            else -> null
        }
        glView.renderer.activeRotateAxis = null
        glView.renderer.activeMoveAxis = if (currentLayoutTool == LayoutTool.MOVE) axisLocked else null
        gizmoLabelView.labelText = null
        if (currentLayoutTool == LayoutTool.ROTATE && axisLocked != null) {
            val axisNow = axisLocked!!
            glView.renderer.activeRotateAxis = axisNow
            val anchor = glView.renderer.computeRotateLabelAnchor()
            if (anchor != null) {
                gizmoLabelView.labelText = axisNow.toString()
                gizmoLabelView.labelX = anchor[0]
                gizmoLabelView.labelY = anchor[1]
            }
        }
        gizmoLabelView.invalidate()
    }
    /** ACTION_UP en el viewport: suelta el eje bloqueado, sea cual sea la herramienta activa - tambien limpia el resaltado del eje agarrado (activeRotateAxis/activeMoveAxis) y la etiqueta de texto, si habia una transformacion restringida en curso. */
    private fun onViewportDragEnd() {
        axisLocked = null
        glView.renderer.activeRotateAxis = null
        glView.renderer.activeMoveAxis = null
        gizmoLabelView.labelText = null
        gizmoLabelView.invalidate()
    }

    /**
     * Tap en el viewport 3D: si estamos en Layout con la herramienta Select activa, intenta
     * seleccionar el objeto tocado (o deselecciona todo si el tap cae en espacio vacio, igual
     * que en Blender). Move/Rotate/Scale ya tienen su propio gesto de arrastre (ver
     * onViewportDragMove).
     */
    private fun onViewportTap(x: Float, y: Float) {
        if (currentMode != AppMode.LAYOUT) return
        if (currentLayoutTool != LayoutTool.SELECT) return
        glView.renderer.selectObjectAt(x, y)
        glView.requestRender()
    }

    /**
     * Arrastre en el viewport 3D: si estamos en Layout con Move, Rotate o Scale activos, aplica
     * esa transformacion al objeto seleccionado en vez de rotar la camara (que es el
     * comportamiento por defecto del gesto, ver MyGLSurfaceView.onDragMove). Move y Rotate quedan
     * restringidos a un eje si el arrastre empezo tocando el gizmo (ver onViewportDragStart /
     * axisLocked); si no, caen al gesto libre de siempre. Scale sigue siendo siempre libre (su
     * gizmo por eje queda para despues). Si no hay ningun objeto seleccionado, el arrastre no hace
     * nada - a proposito no cae a rotar la camara, para que quede claro que estas en una de estas
     * herramientas sin nada para transformar. Devuelve true (arrastre consumido) siempre que una
     * de las tres este activa, se haya transformado algo o no.
     */
    private fun onViewportDragMove(dx: Float, dy: Float, x: Float, y: Float): Boolean {
        if (currentMode != AppMode.LAYOUT) return false
        return when (currentLayoutTool) {
            LayoutTool.MOVE -> {
                val axis = axisLocked
                if (axis != null) {
                    glView.renderer.moveSelectedObjectOnAxis(dx, dy, axis)
                } else {
                    glView.renderer.moveSelectedObject(dx, dy)
                }
                true
            }
            LayoutTool.ROTATE -> {
                val axis = axisLocked
                if (axis != null) {
                    glView.renderer.updateActiveRotateCurrentDir(x, y, axis)
                    glView.renderer.rotateSelectedObjectOnAxis(dx, dy, axis)
                } else {
                    glView.renderer.rotateSelectedObject(dx, dy)
                }
                true
            }
            LayoutTool.SCALE -> {
                glView.renderer.scaleSelectedObject(dy)
                true
            }
            else -> false
        }
    }

    private fun setMode(mode: AppMode) {
        currentMode = mode
        updateModeHighlight()
        leftToolColumn.visibility = if (mode == AppMode.LAYOUT) View.VISIBLE else View.GONE
        modelingToolWrapper.visibility = if (mode == AppMode.MODELING) View.VISIBLE else View.GONE
        // TODO: cambiar el resto de la interfaz/herramientas segun el modo (UV Editing).
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
