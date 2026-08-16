package com.meshcraft.app

import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Orientacion del gizmo de transformacion activo (Move/Rotate/Scale) - selector tipo Blender
 * (ahi existen 6: Global/Local/Normal/Gimbal/View/Cursor; aca solo las 2 que tienen sentido con
 * lo que la app soporta hoy, ver charla con el usuario). Afecta a los 3 gizmos por igual (antes
 * Scale estaba SIEMPRE forzado a Local, sin opcion - ver comentario viejo en onDrawFrame,
 * reemplazado por este selector).
 *
 * GLOBAL: los 3 ejes quedan siempre alineados al mundo, sin importar como este rotado el objeto -
 * el gizmo se ve "prolijo" (Z siempre arriba), pero escalar por eje con el objeto rotado deforma
 * en diagonal (shear) - la razon de fondo (y por que Blender tiene el mismo comportamiento, no es
 * un bug de esta app) esta charlada con el usuario: con un solo float de escala por eje, un
 * escalado no-uniforme en espacio mundo sobre un objeto rotado no es representable sin deformar.
 * Esta app SI reproduce ese shear correctamente (ver SceneObject.shapeMatrix y
 * MyGLRenderer.applyLocalDirScale) - lo que antes fallaba era que el resultado se escribia
 * siempre en el eje LOCAL del objeto en vez de deformarlo, ya corregido.
 *
 * LOCAL: los 3 ejes rotan junto con el objeto - el gizmo se ve "girado" si el objeto esta rotado,
 * pero mover/rotar/escalar por eje siempre da el resultado esperado, sin deformar. Es el
 * comportamiento que ya tenia Scale a secas antes de este selector (ver fix documentado en
 * rotatedAxisDirection) - ahora tambien disponible (y default) para Move y Rotate.
 */
enum class TransformOrientation { GLOBAL, LOCAL }

class MyGLRenderer : GLSurfaceView.Renderer {

    private lateinit var cubeGeometry: Cube
    private lateinit var planeGeometry: Plane
    private lateinit var circleGeometry: Circle
    private lateinit var uvSphereGeometry: UvSphere
    private lateinit var icoSphereGeometry: IcoSphere
    private lateinit var cylinderGeometry: Cylinder
    private lateinit var coneGeometry: Cone
    private lateinit var torusGeometry: Torus
    private lateinit var gridMeshGeometry: GridMesh
    private lateinit var monkeyGeometry: Monkey
    private lateinit var gridXY: Grid
    private lateinit var gridXZ: Grid
    private lateinit var gridYZ: Grid
    private lateinit var gizmo: Gizmo3D

    /**
     * Objetos en la escena. Arranca con un solo cubo en el origen, ya seleccionado
     * (mismo comportamiento que Blender al abrir un archivo nuevo).
     */
    val sceneObjects = mutableListOf<SceneObject>()
    private var nextObjectId = 0

    /**
     * Geometria de dibujo dinamica por objeto (ver DynamicMeshGeometry.kt), solo para los objetos
     * que ya tienen editableMesh (ver SceneObject.editableMesh) - el resto sigue usando la
     * geometria estatica compartida por tipo (cubeGeometry, etc.), ver onDrawFrame. Clave: id del
     * SceneObject, no el objeto en si (sceneObjects.map/copy generan instancias nuevas en cada
     * snapshot de Undo, el id es el unico dato estable). Se llena/actualiza en
     * refreshDynamicGeometry, NUNCA dentro de onDrawFrame - el render corre en
     * RENDERMODE_CONTINUOUSLY (ver MyGLSurfaceView), asi que reconstruir buffers de OpenGL ahi
     * los recrearia 60 veces por segundo (ver comentario de DynamicMeshGeometry.update).
     */
    private val dynamicGeometries = mutableMapOf<Int, DynamicMeshGeometry>()
    // Ids de SceneObject cuya geometria dinamica quedo desactualizada y falta reconstruir de verdad
    // (ver refreshDynamicGeometry / processPendingDynamicGeometryRefreshes). Existe porque
    // enterEditModeForSelected/undo/redo se llaman desde listeners de botones de MainActivity (hilo
    // de UI), pero DynamicMeshGeometry.update() termina compilando shaders y creando buffers de
    // OpenGL de verdad (GLUtils.buildProgram), y esas llamadas SOLO son validas en el hilo de render
    // de GLSurfaceView (bug real, confirmado: la app crasheaba al tocar el tab Modeling - ver charla
    // con el usuario). Solucion: refreshDynamicGeometry ya NO reconstruye nada en el momento, solo
    // anota el id aca (seguro desde cualquier hilo); recien onDrawFrame (que si corre en el hilo de
    // GL correcto) vacia la cola una vez por frame, antes de dibujar. ConcurrentHashMap.newKeySet:
    // set thread-safe sin lock a mano, porque se escribe desde UI y se lee/limpia desde el render.
    private val pendingDynamicGeometryRefresh = java.util.concurrent.ConcurrentHashMap.newKeySet<Int>()

    /**
     * Entra a Edit Mode para el objeto seleccionado: crea su EditableMesh si todavia no lo tiene
     * (on-demand, a partir de su MeshType - ver SceneObject.editableMesh / MeshType.toEditableMesh)
     * y encola su geometria de dibujo dinamica para reconstruirse en el proximo frame (ver
     * refreshDynamicGeometry/pendingDynamicGeometryRefresh - la reconstruccion real de OpenGL no
     * puede pasar aca, esto corre en el hilo de UI). Devuelve false sin hacer nada si no hay
     * objeto seleccionado, o si esa primitiva todavia no tiene conversion a editable implementada
     * (toEditableMesh() devuelve null - hoy solo Cube, ver EditableMesh.kt) - el llamador
     * (MainActivity.setMode) usa esto para avisar con un Toast, mismo criterio que el resto de las
     * acciones de la app que pueden "no hacer nada" (Delete/Duplicate/Clear sin seleccion).
     */
    fun enterEditModeForSelected(): Boolean {
        val selected = sceneObjects.firstOrNull { it.selected } ?: return false
        if (selected.editableMesh == null) {
            selected.editableMesh = selected.type.toEditableMesh() ?: return false
        }
        refreshDynamicGeometry(selected)
        return true
    }

    /**
     * Marca la geometria de dibujo de UN objeto puntual como pendiente de reconstruir (ver
     * pendingDynamicGeometryRefresh/processPendingDynamicGeometryRefreshes - NO toca OpenGL aca,
     * solo encola el id, ver esa funcion para el trabajo real) -
     * no hace nada si el objeto no tiene editableMesh (nunca entro a Edit Mode). Se llama desde
     * enterEditModeForSelected (primera vez que se ve el objeto en modo edicion, o al volver a
     * entrar) y desde undo()/redo() (ver ahi) - un snapshot restaurado trae su propio editableMesh,
     * ya deep-copied, que puede tener vertices en posiciones distintas a las que estaban dibujadas
     * antes de deshacer, asi que la geometria dinamica vieja quedaria obsoleta si no se refresca.
     */
    private fun refreshDynamicGeometry(obj: SceneObject) {
        if (obj.editableMesh == null) return
        pendingDynamicGeometryRefresh.add(obj.id)
    }

    // Vacia pendingDynamicGeometryRefresh reconstruyendo de verdad la geometria de dibujo de cada id
    // encolado (DynamicMeshGeometry.update, que si compila shaders/buffers de OpenGL de verdad) -
    // SOLO se llama desde onDrawFrame, que corre en el hilo de render de GLSurfaceView (unico hilo
    // donde esas llamadas son validas). Copia la cola a una lista y la limpia antes de procesar, por
    // si algun id se re-encola mientras tanto. Ids de objetos ya borrados (Undo/Redo o Delete) se
    // descartan sin hacer nada.
    private fun processPendingDynamicGeometryRefreshes() {
        if (pendingDynamicGeometryRefresh.isEmpty()) return
        val ids = pendingDynamicGeometryRefresh.toList()
        pendingDynamicGeometryRefresh.clear()
        for (id in ids) {
            val obj = sceneObjects.firstOrNull { it.id == id } ?: continue
            val mesh = obj.editableMesh ?: continue
            val geo = dynamicGeometries.getOrPut(obj.id) { DynamicMeshGeometry() }
            geo.update(mesh)
        }
    }
    /**
     * Pilas de Undo/Redo: cada entrada es una foto completa de sceneObjects (deep copy, ver
     * snapshotSceneObjects) tomada ANTES de que la accion correspondiente modifique el estado real -
     * mismo criterio que dejo planteado el companero (snapshot del estado completo, mas simple y
     * confiable que deshacer cada operacion individualmente al reves). Limitada a MAX_UNDO_STEPS
     * para no crecer sin limite en sesiones largas - al llegar al tope se descarta la mas vieja
     * (FIFO), igual que el historial de Undo de Blender.
     */
    private val undoStack = ArrayDeque<List<SceneObject>>()
    private val redoStack = ArrayDeque<List<SceneObject>>()
    private val MAX_UNDO_STEPS = 50

    /** Limites del factor de escala libre por frame en scaleSelectedMeshElements - evita que la seleccion colapse a un punto (factor muy chico) o explote de tamano (factor muy grande) en un solo frame de arrastre rapido. */
    private val SCALE_FACTOR_MIN = 0.01f
    private val SCALE_FACTOR_MAX = 100f

    /**
     * Copia profunda de sceneObjects: SceneObject es un data class, pero rotationMatrix y
     * shapeMatrix son FloatArray (tipo referencia) - copy() por si solo comparte el mismo array de
     * fondo entre el original y la copia (mismo motivo por el que duplicateSelectedObject ya
     * clonaba estos dos campos a mano con copyOf()). Sin esto, restaurar un snapshot viejo
     * mutaria en vivo tambien el estado "actual" que se guardo antes, arruinando el historial.
     */
    private fun snapshotSceneObjects(): List<SceneObject> =
        sceneObjects.map { it.copy(rotationMatrix = it.rotationMatrix.copyOf(), shapeMatrix = it.shapeMatrix.copyOf(), editableMesh = it.editableMesh?.deepCopy()) }

    /**
     * Guarda el estado actual de la escena en la pila de Undo - se llama SIEMPRE antes de que una
     * accion modifique sceneObjects (Add/Delete/Duplicate: ver cada funcion; Move/Rotate/Scale: ver
     * MainActivity.onViewportDragStart, una vez por gesto de arrastre, no por frame). Cualquier
     * accion nueva despues de esto invalida el Redo pendiente (mismo comportamiento que Blender/
     * cualquier editor: no tiene sentido "rehacer" algo que ya quedo pisado por una accion distinta).
     */
    fun pushUndoSnapshot() {
        undoStack.addLast(snapshotSceneObjects())
        if (undoStack.size > MAX_UNDO_STEPS) undoStack.removeFirst()
        redoStack.clear()
    }

    /**
     * Deshace la ultima accion: guarda el estado actual en Redo (para poder rehacer despues) y
     * restaura el snapshot mas reciente de Undo. Devuelve false (sin hacer nada) si no hay nada
     * para deshacer - el llamador (MainActivity) usa esto para avisar con un Toast, mismo criterio
     * que deleteSelectedObject/duplicateSelectedObject.
     */
    fun undo(): Boolean {
        val previous = undoStack.removeLastOrNull() ?: return false
        redoStack.addLast(snapshotSceneObjects())
        sceneObjects.clear()
        sceneObjects.addAll(previous)
        for (obj in sceneObjects) refreshDynamicGeometry(obj)
        return true
    }

    /** Igual que undo() pero al reves: mueve el snapshot actual a Undo y restaura el tope de Redo. */
    fun redo(): Boolean {
        val next = redoStack.removeLastOrNull() ?: return false
        undoStack.addLast(snapshotSceneObjects())
        sceneObjects.clear()
        sceneObjects.addAll(next)
        for (obj in sceneObjects) refreshDynamicGeometry(obj)
        return true
    }

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

    /**
     * true mientras la app esta en el modo Modeling (Edit Mode) - lo setea MainActivity.setMode()
     * cada vez que se cambia de AppMode (ver esa funcion). Unica fuente de verdad usada en
     * onDrawFrame para decidir, POR OBJETO, si dynGeo.draw() debe dibujarse con el criterio visual
     * de Edit Mode (wireframe negro + resaltado naranja de sub-elementos, ver
     * DynamicMeshGeometry.draw/drawBaseWireframe/drawSelectionHighlights) en vez del contorno
     * naranja de "objeto completo" que se usa en Layout - ver charla con el usuario y su captura
     * de referencia de Blender.
     *
     * A proposito NO alcanza con este flag solo: en onDrawFrame se combina con obj.selected (ver
     * ahi) porque solo el objeto seleccionado es el que esta realmente "en edicion" (mismo
     * criterio que editingObject() - la app solo permite editar un objeto a la vez). Sin ese AND,
     * CUALQUIER objeto con editableMesh (aunque no sea el que se esta editando ahora) se
     * dibujaria con wireframe negro apenas se entrara a Modeling, incluso si esta viendose de
     * lejos sin estar seleccionado - no es el comportamiento de Blender (ahi Edit Mode es
     * exclusivo del objeto activo).
     */
    @Volatile var isEditMode = false

    /**
     * Que gizmo de transformacion por eje se dibuja sobre el objeto seleccionado - null si ninguno.
     * MainActivity lo setea segun la herramienta activa (ver setLayoutTool): Move -> GizmoMode.MOVE,
     * Rotate -> GizmoMode.ROTATE, Scale -> GizmoMode.SCALE.
     */
    @Volatile var gizmoMode: GizmoMode? = null

    /**
     * Eje (X/Y/Z) del anillo de rotacion que se esta arrastrando ahora mismo - null si no hay
     * arrastre en curso o si el arrastre es libre (no empezo tocando un anillo). MainActivity lo
     * sincroniza en onViewportDragStart/onViewportDragEnd (mismo valor que axisLocked alla, solo
     * que ahi es privado). Solo tiene efecto visual con gizmoMode == GizmoMode.ROTATE - ver
     * onDrawFrame y Gizmo3D.draw.
     */
    @Volatile var activeRotateAxis: Char? = null

    /**
     * Eje (X/Y/Z) de la flecha de Move que se esta arrastrando ahora mismo - null si no hay
     * arrastre en curso o si el arrastre es libre (no empezo tocando una flecha). Mismo criterio
     * que activeRotateAxis pero para GizmoMode.MOVE: MainActivity lo sincroniza en
     * onViewportDragStart/onViewportDragEnd (mismo valor que axisLocked alla). Solo tiene efecto
     * visual con gizmoMode == GizmoMode.MOVE - ver onDrawFrame y Gizmo3D.draw.
     */
    @Volatile var activeMoveAxis: Char? = null

    /**
     * Eje (X/Y/Z) del cubito de Scale que se esta arrastrando ahora mismo - null si no hay
     * arrastre en curso o si el arrastre es libre (no empezo tocando un cubito, ver
     * hitTestGizmoScaleAxis). Mismo criterio que activeMoveAxis/activeRotateAxis pero para
     * GizmoMode.SCALE: MainActivity lo sincroniza en onViewportDragStart/onViewportDragEnd. Solo
     * tiene efecto visual con gizmoMode == GizmoMode.SCALE - ver onDrawFrame y Gizmo3D.draw.
     */
    @Volatile var activeScaleAxis: Char? = null

    /**
     * Orientacion actual del gizmo activo (Move/Rotate/Scale) - ver enum TransformOrientation.
     * MainActivity la togglea desde el boton nuevo (ver toggleTransformOrientation) y la usa para
     * decidir el texto/resaltado de ese boton. Default LOCAL: es el comportamiento sin sorpresas
     * (mismo que ya tenia Scale antes de este selector) - el usuario puede pasar a GLOBAL cuando
     * quiera el gizmo siempre alineado al mundo, sabiendo el trade-off (ver comentario del enum).
     */
    @Volatile var transformOrientation: TransformOrientation = TransformOrientation.LOCAL

    /**
     * Direccion (mundo, normalizada) desde el centro del objeto hacia el punto donde el dedo tocO
     * el anillo al empezar el arrastre - se calcula una sola vez en hitTestGizmoRotateAxis cuando
     * el hit-test encuentra un eje, y se queda fija durante todo el gesto (no se actualiza en cada
     * frame). Ya NO la usa el dibujo de la marca de angulo (ver activeRotateCurrentDir, que la
     * reemplazo para que esa marca siga al dedo en vivo, como en Blender real - ver charla con el
     * usuario y su video de referencia) - queda solo como valor inicial de activeRotateCurrentDir
     * y para computeRotateLabelAnchor, que ubica la etiqueta de texto del eje una unica vez al
     * empezar el arrastre (no hace falta que se mueva en vivo, es secundario a la marca).
     */
    @Volatile var activeRotateStartDir: FloatArray? = null
    /** Igual que activeRotateStartDir pero recalculada en cada ACTION_MOVE (ver updateActiveRotateCurrentDir) para que la marca de angulo siga al dedo en vivo, como en Blender real - ver video de referencia del usuario. Arranca igual a activeRotateStartDir (ver onViewportDragStart) para no quedar en null antes del primer move. */
    @Volatile var activeRotateCurrentDir: FloatArray? = null

    // Camera distance from the origin (zoom).
    @Volatile var cameraDistance = 7.47f
        set(value) {
            field = value.coerceIn(2f, 20f)
        }

    // Pan offset: shifts the camera + its look-at target together, sideways on screen (world X / world Z).
    @Volatile var panX = 0.05f
    @Volatile var panZ = 0.24f

    /**
     * Tamano del gizmo en unidades de mundo, recalculado por distancia de camara para que se vea
     * de tamano constante en pantalla sin importar el zoom (mismo criterio que orthoSize en
     * onDrawFrame). Unica fuente de verdad para el dibujo (onDrawFrame) y los hit-test
     * (hitTestGizmoAxis, hitTestGizmoRotateAxis, hitTestGizmoScaleAxis) - si diverge, el gizmo se
     * ve en un lugar y se toca en otro.
     */
    private val gizmoScreenScale: Float
        get() = cameraDistance * 0.15f

    /**
     * Agrega un cubo nuevo a la escena y lo deja seleccionado (mismo criterio que selectObjectAt:
     * un solo objeto seleccionado a la vez). Por ahora es la unica primitiva con geometria real
     * (ver Cube.kt), asi que es la unica que Add > Mesh puede crear de verdad todavia.
     * Sin Cursor 3D (fuera de alcance por ahora), se agrega siempre en el origen - igual que
     * Blender agregaria en un 3D cursor que nunca se movio de (0,0,0).
     */
    fun addCube(): SceneObject {
        pushUndoSnapshot()
        for (obj in sceneObjects) obj.selected = false
        val newObject = SceneObject(id = nextObjectId++, selected = true)
        sceneObjects.add(newObject)
        return newObject
    }

    fun addPlane(): SceneObject {
        pushUndoSnapshot()
        for (obj in sceneObjects) obj.selected = false
        val newObject = SceneObject(id = nextObjectId++, type = MeshType.PLANE, selected = true)
        sceneObjects.add(newObject)
        return newObject
    }

    /** Igual que addPlane, pero para Circle (ver Circle.kt / MeshType.CIRCLE). */
    fun addCircle(): SceneObject {
        pushUndoSnapshot()
        for (obj in sceneObjects) obj.selected = false
        val newObject = SceneObject(id = nextObjectId++, type = MeshType.CIRCLE, selected = true)
        sceneObjects.add(newObject)
        return newObject
    }

    /** Igual que addCircle, pero para UV Sphere (ver UvSphere.kt / MeshType.UV_SPHERE). */
    fun addUvSphere(): SceneObject {
        pushUndoSnapshot()
        for (obj in sceneObjects) obj.selected = false
        val newObject = SceneObject(id = nextObjectId++, type = MeshType.UV_SPHERE, selected = true)
        sceneObjects.add(newObject)
        return newObject
    }

    /** Igual que addUvSphere, pero para Ico Sphere (ver IcoSphere.kt / MeshType.ICO_SPHERE). */
    fun addIcoSphere(): SceneObject {
        pushUndoSnapshot()
        for (obj in sceneObjects) obj.selected = false
        val newObject = SceneObject(id = nextObjectId++, type = MeshType.ICO_SPHERE, selected = true)
        sceneObjects.add(newObject)
        return newObject
    }

    /** Igual que addIcoSphere, pero para Cylinder (ver Cylinder.kt / MeshType.CYLINDER). */
    fun addCylinder(): SceneObject {
        pushUndoSnapshot()
        for (obj in sceneObjects) obj.selected = false
        val newObject = SceneObject(id = nextObjectId++, type = MeshType.CYLINDER, selected = true)
        sceneObjects.add(newObject)
        return newObject
    }

    /** Igual que addCylinder, pero para Cone (ver Cone.kt / MeshType.CONE). */
    fun addCone(): SceneObject {
        pushUndoSnapshot()
        for (obj in sceneObjects) obj.selected = false
        val newObject = SceneObject(id = nextObjectId++, type = MeshType.CONE, selected = true)
        sceneObjects.add(newObject)
        return newObject
    }

    /** Igual que addCone, pero para Torus (ver Torus.kt / MeshType.TORUS). */
    fun addTorus(): SceneObject {
        pushUndoSnapshot()
        for (obj in sceneObjects) obj.selected = false
        val newObject = SceneObject(id = nextObjectId++, type = MeshType.TORUS, selected = true)
        sceneObjects.add(newObject)
        return newObject
    }

    /** Igual que addTorus, pero para Grid (ver GridMesh.kt / MeshType.GRID). */
    fun addGrid(): SceneObject {
        pushUndoSnapshot()
        for (obj in sceneObjects) obj.selected = false
        val newObject = SceneObject(id = nextObjectId++, type = MeshType.GRID, selected = true)
        sceneObjects.add(newObject)
        return newObject
    }

    /**
     * Object > Clear (Layout): resetea posicion, rotacion y escala del objeto seleccionado a su
     * estado original - vuelve al origen (0,0,0), sin rotar, sin escalar/deformar. En Blender
     * "Clear" es un submenu con Location/Rotation/Scale/Origin/All Transforms por separado; como
     * el menu Object de esta app quedo definido como lista plana sin submenus (decision ya
     * charlada con el usuario), esta funcion implementa el equivalente a "All Transforms" (el
     * unico de esos que tiene sentido como accion unica sin pedir mas contexto). Pasa por Undo
     * como el resto de las acciones que modifican la escena (Delete/Duplicate/Add).
     * Devuelve false (y no hace nada) si no hay ningun objeto seleccionado.
     */
    fun clearSelectedObjectTransform(): Boolean {
        val selected = sceneObjects.firstOrNull { it.selected } ?: return false
        pushUndoSnapshot()
        selected.posX = 0f
        selected.posY = 0f
        selected.posZ = 0f
        Matrix.setIdentityM(selected.rotationMatrix, 0)
        Matrix.setIdentityM(selected.shapeMatrix, 0)
        return true
    }

    /**
     * Borra el objeto seleccionado (Object > Delete). Si no hay ninguno seleccionado, no hace
     * nada y devuelve false - el llamador (MainActivity.onObjectMenuAction) usa esto para avisar
     * si hizo falta. No hay confirmacion (a diferencia de Blender, que tampoco pide confirmacion
     * para Delete via el menu Object).
     */
    fun deleteSelectedObject(): Boolean {
        val index = sceneObjects.indexOfFirst { it.selected }
        if (index == -1) return false
        pushUndoSnapshot()
        sceneObjects.removeAt(index)
        return true
    }

    /**
     * Cuanto se corre en X el duplicado respecto del original (ver duplicateSelectedObject) -
     * unidades de mundo, mismo orden de magnitud que el tamano tipico de un objeto (0.5 de medio
     * lado en las primitivas base) para que se note claramente que hay dos objetos separados.
     */
    private val DUPLICATE_OFFSET_X = 0.6f

    /**
     * Duplica el objeto seleccionado (Object > Duplicate Objects): mismo tipo, misma rotacion y
     * forma (rotationMatrix/shapeMatrix CLONADAS con copyOf - si se copiara solo la referencia del
     * FloatArray, mover o escalar una copia moveria/escalaria la otra tambien, ya que
     * compartirian el mismo array por debajo). El duplicado nace con un offset chico en X para que
     * se vea que hay dos objetos separados (Blender en cambio deja el duplicado exactamente
     * encima y lo manda directo a modo Grab con el mouse - no tenemos ese gesto encadenado todavia,
     * asi que el offset fijo es la forma mas simple de que el resultado sea utilizable de una).
     * Deja el original deseleccionado y el duplicado seleccionado (igual que Blender). Devuelve
     * null (y no hace nada) si no hay ningun objeto seleccionado.
     */
    fun duplicateSelectedObject(): SceneObject? {
        val selected = sceneObjects.firstOrNull { it.selected } ?: return null
        pushUndoSnapshot()
        for (obj in sceneObjects) obj.selected = false
        val duplicate = selected.copy(
            id = nextObjectId++,
            posX = selected.posX + DUPLICATE_OFFSET_X,
            rotationMatrix = selected.rotationMatrix.copyOf(),
            shapeMatrix = selected.shapeMatrix.copyOf(),
            editableMesh = selected.editableMesh?.deepCopy(),
            selected = true
        )
        sceneObjects.add(duplicate)
        refreshDynamicGeometry(duplicate)
        return duplicate
    }

    /** Igual que addGrid, pero para Monkey (ver Monkey.kt / MeshType.MONKEY). */
    fun addMonkey(): SceneObject {
        pushUndoSnapshot()
        for (obj in sceneObjects) obj.selected = false
        val newObject = SceneObject(id = nextObjectId++, type = MeshType.MONKEY, selected = true)
        sceneObjects.add(newObject)
        return newObject
    }

    /**
     * Calcula, para un delta de arrastre en pantalla, el delta equivalente en espacio mundo.
     * Extraido de moveSelectedObject para reusarlo tambien en moveSelectedObjectOnAxis (arrastre
     * restringido a un eje del gizmo) - misma logica en los dos casos, cambia solo que se hace
     * despues con el resultado.
     */
    private fun computeWorldDragDelta(dxScreen: Float, dyScreen: Float): FloatArray {
        // Misma escala que el pan de camara (ver MyGLSurfaceView), para que el movimiento se
        // sienta igual de "rapido" en pantalla sin importar el nivel de zoom actual.
        val moveScale = 0.01f * (cameraDistance / 6.5f)
        val rightAmount = dxScreen * moveScale
        val upAmount = -dyScreen * moveScale

        val rotation = FloatArray(16)
        Matrix.setIdentityM(rotation, 0)
        Matrix.rotateM(rotation, 0, angleX, 1f, 0f, 0f)
        Matrix.rotateM(rotation, 0, angleY, 0f, 0f, 1f)

        val inverseRotation = FloatArray(16)
        Matrix.transposeM(inverseRotation, 0, rotation, 0)

        val screenDelta = floatArrayOf(rightAmount, 0f, upAmount, 1f)
        val worldDelta = FloatArray(4)
        Matrix.multiplyMV(worldDelta, 0, inverseRotation, 0, screenDelta, 0)
        return worldDelta
    }

    /**
     * Direccion (en espacio mundo) hacia donde mira la camara, calculada de la misma forma que
     * computeWorldDragDelta: la camara nunca rota de verdad (lo que rota es el contenido via
     * rotationMatrix), asi que su forward local (+Y, ver setLookAtM en onDrawFrame) se lleva a
     * espacio mundo con la inversa de esa rotacion. Compartido por computeScreenTangentForAxis
     * para saber "desde donde se esta mirando" el gizmo de rotacion.
     */
    private fun computeWorldViewDirection(): FloatArray {
        val rotation = FloatArray(16)
        Matrix.setIdentityM(rotation, 0)
        Matrix.rotateM(rotation, 0, angleX, 1f, 0f, 0f)
        Matrix.rotateM(rotation, 0, angleY, 0f, 0f, 1f)
        val inverseRotation = FloatArray(16)
        Matrix.transposeM(inverseRotation, 0, rotation, 0)
        val localForward = floatArrayOf(0f, 1f, 0f, 0f)
        val worldForward = FloatArray(4)
        Matrix.multiplyMV(worldForward, 0, inverseRotation, 0, localForward, 0)
        return floatArrayOf(worldForward[0], worldForward[1], worldForward[2])
    }

    /**
     * Mueve el objeto seleccionado en el plano de camara (como arrastras en pantalla, igual que
     * G libre en Blender): dxScreen/dyScreen son deltas de pantalla en pixeles. Como la camara
     * nunca rota (lo que rota es el contenido via rotationMatrix, ver onDrawFrame), el eje derecha
     * de camara es siempre el eje X del mundo y el eje arriba de camara siempre el eje Z del mundo;
     * por eso el delta se arma directamente en esos ejes y se lo pasa por la rotacion inversa
     * (transpuesta, al ser una matriz de rotacion pura) para que el objeto - que si esta rotado -
     * termine moviendose en la direccion correcta sobre la pantalla.
     * Devuelve false (y no hace nada) si no hay ningun objeto seleccionado.
     */
    fun moveSelectedObject(dxScreen: Float, dyScreen: Float): Boolean {
        val selected = sceneObjects.firstOrNull { it.selected } ?: return false
        val worldDelta = computeWorldDragDelta(dxScreen, dyScreen)
        selected.posX += worldDelta[0]
        selected.posY += worldDelta[1]
        selected.posZ += worldDelta[2]
        return true
    }

    /**
     * Igual que moveSelectedObject, pero proyecta el delta de mundo sobre un solo eje (X/Y/Z)
     * antes de aplicarlo - se usa cuando el arrastre empezo tocando una flecha del gizmo (ver
     * MainActivity.onViewportDragStart, que llama a hitTestGizmoAxis en ACTION_DOWN).
     * Devuelve false (y no hace nada) si no hay ningun objeto seleccionado.
     */
    fun moveSelectedObjectOnAxis(dxScreen: Float, dyScreen: Float, axis: Char): Boolean {
        val selected = sceneObjects.firstOrNull { it.selected } ?: return false
        val worldDelta = computeWorldDragDelta(dxScreen, dyScreen)

        val axisDir = effectiveAxisDirection(axis, selected)
        val projected = worldDelta[0] * axisDir[0] + worldDelta[1] * axisDir[1] + worldDelta[2] * axisDir[2]

        selected.posX += axisDir[0] * projected
        selected.posY += axisDir[1] * projected
        selected.posZ += axisDir[2] * projected
        return true
    }

    private fun axisDirection(axis: Char): FloatArray = when (axis) {
        'X' -> floatArrayOf(1f, 0f, 0f)
        'Y' -> floatArrayOf(0f, 1f, 0f)
        else -> floatArrayOf(0f, 0f, 1f)
    }

    /**
     * Igual que axisDirection, pero rotada por la orientacion actual del objeto - se usa cuando
     * transformOrientation es LOCAL (ver effectiveAxisDirection) para que Move/Rotate/Scale por
     * eje sigan la orientacion propia del objeto en vez del mundo puro.
     */
    private fun rotatedAxisDirection(axis: Char, obj: SceneObject): FloatArray {
        val local = axisDirection(axis)
        val worldDir = FloatArray(4)
        Matrix.multiplyMV(worldDir, 0, obj.rotationMatrix, 0, floatArrayOf(local[0], local[1], local[2], 0f), 0)
        return floatArrayOf(worldDir[0], worldDir[1], worldDir[2])
    }

    /**
     * Unica fuente de verdad para "que direccion usa el eje X/Y/Z ahora mismo": world pura
     * (axisDirection) si transformOrientation es GLOBAL, rotada con el objeto (rotatedAxisDirection)
     * si es LOCAL. Reemplaza los usos sueltos de axisDirection/rotatedAxisDirection en hit-test,
     * dibujo y logica de arrastre de los 3 gizmos (Move/Rotate/Scale) - antes Move/Rotate estaban
     * hardcodeados a world y Scale a rotada; ahora los 3 respetan el mismo selector (ver enum
     * TransformOrientation). Si los usos no coincidieran en la misma direccion, el gizmo se veria
     * en un lugar, se tocaria en otro, o el arrastre se sentiria invertido - mismo criterio de
     * "unica fuente de verdad" que ya se aplica en gizmoScreenScale.
     */
    private fun effectiveAxisDirection(axis: Char, obj: SceneObject): FloatArray {
        return if (transformOrientation == TransformOrientation.LOCAL) rotatedAxisDirection(axis, obj) else axisDirection(axis)
    }

    /**
     * Rota el objeto seleccionado libre (sin eje restringido), con la misma convencion que la
     * orbita de camara: dx horizontal gira alrededor del eje Z del mundo, dy vertical gira
     * alrededor del eje X del mundo. Ambos deltas se aplican sobre la matriz de rotacion
     * acumulada del objeto (ver applyWorldRotationDelta) en vez de sumarse a angulos sueltos -
     * asi cada rotacion nueva se compone sobre el estado real actual, sin el bug de orden fijo que
     * tenia el esquema anterior de Euler (rotX/rotY/rotZ, ver charla con el usuario). Para rotacion
     * restringida a un solo eje (gizmo de anillos) ver rotateSelectedObjectOnAxis. Este gesto libre
     * es deliberadamente siempre mundo (no depende del selector de orientacion) - mismo criterio
     * que la orbita de camara, a la que imita.
     * Devuelve false (y no hace nada) si no hay ningun objeto seleccionado.
     */
    fun rotateSelectedObject(dxScreen: Float, dyScreen: Float): Boolean {
        val selected = sceneObjects.firstOrNull { it.selected } ?: return false
        applyWorldRotationDelta(selected, dxScreen * 0.5f, 'Z')
        applyWorldRotationDelta(selected, dyScreen * 0.5f, 'X')
        return true
    }

    /**
     * Aplica una rotacion incremental (angleDeg grados alrededor del eje MUNDO dado) sobre la
     * matriz de rotacion acumulada del objeto - atajo de applyRotationDeltaAroundDir para cuando
     * el eje ya se conoce como char de mundo (rotateSelectedObject, gesto libre). Ver
     * applyRotationDeltaAroundDir para la logica real (pre-multiplicacion delta * actual, que
     * evita el bug de orden fijo de Euler ya resuelto - ver charla con el usuario).
     */
    private fun applyWorldRotationDelta(obj: SceneObject, angleDeg: Float, axis: Char) {
        applyRotationDeltaAroundDir(obj, angleDeg, axisDirection(axis))
    }

    /**
     * Aplica una rotacion incremental (angleDeg grados alrededor de la direccion dir dada, ya
     * resuelta) sobre la matriz de rotacion acumulada del objeto (ver SceneObject.rotationMatrix),
     * pre-multiplicando la matriz delta sobre la actual (delta * actual, no actual * delta). Ese
     * orden es lo que garantiza que la rotacion se acumule sobre el estado real actual del objeto,
     * sin el bug de orden fijo que tenia el esquema anterior de Euler (rotX/rotY/rotZ, ver charla
     * con el usuario): rotar en un eje "pisaba" visualmente lo ya rotado en otro, porque el
     * resultado final dependia del orden de composicion y no del orden real en que se toco cada eje.
     *
     * Recibe la direccion ya resuelta (no un char) para poder rotar alrededor del eje LOCAL cuando
     * transformOrientation es LOCAL (ver effectiveAxisDirection, usada por rotateSelectedObjectOnAxis)
     * o del eje MUNDO cuando es GLOBAL o para el gesto libre (ver applyWorldRotationDelta, que
     * siempre pasa axisDirection sin importar el selector - la rotacion libre imita la orbita de
     * camara y no depende de la orientacion elegida).
     */
    private fun applyRotationDeltaAroundDir(obj: SceneObject, angleDeg: Float, dir: FloatArray) {
        if (angleDeg == 0f) return
        val delta = FloatArray(16)
        Matrix.setIdentityM(delta, 0)
        Matrix.rotateM(delta, 0, angleDeg, dir[0], dir[1], dir[2])
        val result = FloatArray(16)
        Matrix.multiplyMM(result, 0, delta, 0, obj.rotationMatrix, 0)
        obj.rotationMatrix = result
    }

    /**
     * Rota el objeto seleccionado restringido a un solo eje (X/Y/Z) - se usa cuando el arrastre
     * empezo tocando un anillo del gizmo (ver MainActivity.onViewportDragStart, que llama a
     * hitTestGizmoRotateAxis en ACTION_DOWN). A diferencia de rotateSelectedObject (que reparte el
     * delta en dos ejes de forma fija), aca el delta de arrastre se proyecta sobre la
     * tangente en pantalla del circulo de rotacion de ese eje, evaluada en la direccion radial
     * actual del dedo (ver computeScreenTangentForRadialDir) en vez de una direccion fija -
     * mismo resultado visual para X/Z que el gesto libre, pero generalizado para que Y (que no
     * tiene una convencion fija de antes) tambien funcione, sin tener que casear por eje.
     *
     * axisDir se resuelve UNA vez via effectiveAxisDirection y se reusa tanto para la tangente
     * como para aplicar la rotacion (applyRotationDeltaAroundDir) - asi el anillo que ves (mundo o
     * local, segun transformOrientation) es exactamente el eje alrededor del cual se rota.
     * Devuelve true si el arrastre fue consumido (haya rotado algo o no - p.ej. si el eje quedo de
     * canto respecto de la camara, caso degenerado sin tangente definida).
     */
    fun rotateSelectedObjectOnAxis(dxScreen: Float, dyScreen: Float, axis: Char): Boolean {
        val selected = sceneObjects.firstOrNull { it.selected } ?: return true
        val center = floatArrayOf(selected.posX, selected.posY, selected.posZ)
        val radialDir = activeRotateCurrentDir ?: activeRotateStartDir ?: return true
        val axisDir = effectiveAxisDirection(axis, selected)
        val screenTangent = computeScreenTangentForRadialDir(center, axisDir, radialDir) ?: return true

        // Misma sensibilidad que el rotate libre (dx/dy * 0.5).
        val delta = (dxScreen * screenTangent[0] + dyScreen * screenTangent[1]) * 0.5f
        applyRotationDeltaAroundDir(selected, delta, axisDir)
        return true
    }

    /**
     * Direccion (normalizada, en pixeles de pantalla) en la que hay que arrastrar para rotar el
     * objeto alrededor de axisDir, evaluada en el punto radial actual bajo el dedo (ver
     * rotacion es perpendicular tanto a la direccion de vista como al eje (cross product), y se
     * proyecta a pantalla comparando dos puntos cercanos en esa direccion (ver
     * projectWorldToScreen). Null en el caso degenerado de que el eje quede de canto respecto de
     * la camara (viewDir paralelo a axisDir - el circulo se ve como una linea, sin tangente
     * definida) o si la proyeccion a pantalla no esta disponible todavia (primer frame).
     */
    private fun computeScreenTangentForRadialDir(center: FloatArray, axisDir: FloatArray, radialDir: FloatArray): FloatArray? {

        val tangent = cross(axisDir, radialDir)
        val tangentLen = sqrt(tangent[0] * tangent[0] + tangent[1] * tangent[1] + tangent[2] * tangent[2])
        if (tangentLen < 1e-4f) return null
        val tangentUnit = floatArrayOf(tangent[0] / tangentLen, tangent[1] / tangentLen, tangent[2] / tangentLen)

        val p0 = projectWorldToScreen(center[0], center[1], center[2]) ?: return null
        val p1 = projectWorldToScreen(
            center[0] + tangentUnit[0] * 0.05f,
            center[1] + tangentUnit[1] * 0.05f,
            center[2] + tangentUnit[2] * 0.05f
        ) ?: return null

        val screenDx = p1[0] - p0[0]
        val screenDy = p1[1] - p0[1]
        val screenLen = sqrt(screenDx * screenDx + screenDy * screenDy)
        if (screenLen < 1e-4f) return null
        return floatArrayOf(screenDx / screenLen, screenDy / screenLen)
    }

    private fun cross(a: FloatArray, b: FloatArray): FloatArray {
        return floatArrayOf(
            a[1] * b[2] - a[2] * b[1],
            a[2] * b[0] - a[0] * b[2],
            a[0] * b[1] - a[1] * b[0]
        )
    }

    /**
     * Proyecta un punto de mundo a coordenadas de pantalla (pixeles, mismo sistema y-hacia-abajo
     * que usan los eventos de touch) - inverso de screenPointToRay. Usa la matriz camara+orbita
     * del ultimo frame dibujado (scratch), igual que screenPointToRay/hitTestGizmoAxis.
     */
    private fun projectWorldToScreen(worldX: Float, worldY: Float, worldZ: Float): FloatArray? {
        if (viewportWidth <= 0 || viewportHeight <= 0) return null
        val vpMatrix = FloatArray(16)
        Matrix.multiplyMM(vpMatrix, 0, projectionMatrix, 0, scratch, 0)
        val clip = FloatArray(4)
        Matrix.multiplyMV(clip, 0, vpMatrix, 0, floatArrayOf(worldX, worldY, worldZ, 1f), 0)
        if (abs(clip[3]) < 1e-6f) return null
        val ndcX = clip[0] / clip[3]
        val ndcY = clip[1] / clip[3]
        val screenX = (ndcX + 1f) * 0.5f * viewportWidth
        val screenY = (1f - ndcY) * 0.5f * viewportHeight
        return floatArrayOf(screenX, screenY)
    }

    /**
     * Punto de pantalla donde debe anclarse la etiqueta de texto (X/Y/Z) del anillo de rotacion
     * activo (ver GizmoLabelView) - el extremo de la linea punteada de angulo de arranque (centro
     * del objeto + direccion de arranque * radio del anillo, un poco mas afuera para no pisarse
     * con la linea). Se llama una sola vez al empezar el arrastre (ver
     * MainActivity.onViewportDragStart) porque esa direccion (activeRotateStartDir) queda fija
     * durante todo el gesto - no hace falta recalcular cada frame. Null si no hay objeto
     * seleccionado, no hay direccion de arranque guardada, o la proyeccion a pantalla no esta
     * disponible todavia.
     */
    fun computeRotateLabelAnchor(): FloatArray? {
        val selected = sceneObjects.firstOrNull { it.selected } ?: return null
        val dir = activeRotateStartDir ?: return null
        val ringWorldRadius = gizmoScreenScale * Gizmo3D.RING_RADIUS * 1.15f
        return projectWorldToScreen(
            selected.posX + dir[0] * ringWorldRadius,
            selected.posY + dir[1] * ringWorldRadius,
            selected.posZ + dir[2] * ringWorldRadius
        )
    }

    /**
     * Escala el objeto seleccionado libre (sin eje restringido, ver GizmoMode.SCALE con
     * activeScaleAxis == null - se usa tanto desde el trackball blanco como desde el gesto de
     * Layout sin gizmo): arrastre vertical (dyScreen) cambia la escala uniforme - arriba (dy
     * negativo) agranda, abajo achica. Al ser uniforme, multiplica TODA la matriz de forma del
     * objeto (shapeMatrix) por el mismo factor - mantiene la proporcion (y cualquier shear que ya
     * tuviera) del objeto.
     *
     * El factor se clampea UNA sola vez, antes de aplicarlo (ver clampUniformScaleFactor), en vez
     * de clampear cada eje por separado despues de multiplicar (bug arreglado: si el objeto ya
     * habia quedado no-uniforme por un escalado previo con el gizmo de un solo eje, y un eje
     * estaba mas cerca del limite que los otros, clampear cada eje por separado hacia que ESE eje
     * se "congelara" en el limite mientras los otros seguian cambiando - rompiendo la uniformidad
     * que se supone que este gesto preserva). Clampeando el factor comun antes, o los 3 ejes
     * cambian igual (mientras haya margen) o ninguno cambia (si alguno ya esta en el limite) -
     * nunca una mezcla de las dos cosas.
     * Devuelve false (y no hace nada) si no hay ningun objeto seleccionado.
     */
    fun scaleSelectedObject(dyScreen: Float): Boolean {
        val selected = sceneObjects.firstOrNull { it.selected } ?: return false
        val rawFactor = 1f - dyScreen * 0.005f
        val scaleFactor = clampUniformScaleFactor(rawFactor, selected)
        scaleMatrixLinearPart(selected.shapeMatrix, scaleFactor)
        return true
    }

    /**
     * Multiplica la parte lineal (3x3) de una matriz 4x4 por un escalar comun - deja la fila/
     * columna homogenea sin tocar (shapeMatrix nunca lleva traslacion propia, ver SceneObject).
     * Escalado uniforme: multiplicar TODA la matriz por igual conmuta con cualquier shear que ya
     * hubiera (a diferencia del escalado por eje, que si depende de la direccion - ver
     * applyLocalDirScale), asi que no hace falta descomponer nada.
     */
    private fun scaleMatrixLinearPart(mat: FloatArray, factor: Float) {
        for (col in 0 until 3) {
            for (row in 0 until 3) {
                mat[col * 4 + row] *= factor
            }
        }
    }

    /**
     * Recorta rawFactor (el multiplicador que se aplicaria por igual a toda la matriz de forma)
     * para que, aplicado, ningun eje termine fuera de [0.1, 10] - sin importar que tan desparejos
     * esten los 3 ejes entre si ya de antes (columnLength generaliza lo que antes eran
     * scaleX/Y/Z sueltos). Agrandando (rawFactor > 1): el eje mas grande es el que primero
     * llegaria a 10, asi que el factor maximo permitido es el minimo de 10/largoEje entre los 3.
     * Achicando (rawFactor < 1): el eje mas chico es el que primero llegaria a 0.1, asi que el
     * factor minimo permitido es el maximo de 0.1/largoEje entre los 3.
     * Ver scaleSelectedObject para por que esto reemplaza el clamp por eje de antes.
     */
    private fun clampUniformScaleFactor(rawFactor: Float, obj: SceneObject): Float {
        val lx = columnLength(obj.shapeMatrix, 0)
        val ly = columnLength(obj.shapeMatrix, 1)
        val lz = columnLength(obj.shapeMatrix, 2)
        return when {
            rawFactor > 1f -> minOf(rawFactor, 10f / lx, 10f / ly, 10f / lz)
            rawFactor < 1f -> maxOf(rawFactor, 0.1f / lx, 0.1f / ly, 0.1f / lz)
            else -> rawFactor
        }
    }

    /**
     * Largo de una columna (0=X, 1=Y, 2=Z) de una matriz 4x4 - generaliza scaleX/scaleY/scaleZ
     * (que antes eran exactamente este valor, al ser shapeMatrix diagonal pura): cada columna es
     * donde termina el eje local correspondiente despues de aplicar la matriz, asi que su largo es
     * "cuanto se estira" el objeto en esa direccion. Compartido por los clamps de escala y por
     * intersectAABB (bounding box aproximado para seleccion).
     */
    private fun columnLength(mat: FloatArray, col: Int): Float {
        val x = mat[col * 4 + 0]
        val y = mat[col * 4 + 1]
        val z = mat[col * 4 + 2]
        return sqrt(x * x + y * y + z * z)
    }

    /**
     * Igual que scaleSelectedObject, pero aplica el factor SOLO a lo largo de la direccion del eje
     * dado - se usa cuando el arrastre empezo tocando el cubito de un eje del gizmo de Scale (ver
     * MainActivity.onViewportDragStart, que llama a hitTestGizmoScaleAxis en ACTION_DOWN).
     * Se proyecta el arrastre completo (dx, dy) sobre la direccion en pantalla del eje (ver
     * projectDragOntoAxisScreenDir) - mismo criterio que moveSelectedObjectOnAxis (proyeccion en
     * espacio mundo) y rotateSelectedObjectOnAxis (proyeccion sobre la tangente en pantalla):
     * "arrastrar en la direccion en que apunta el cubito" agranda, en la direccion contraria
     * achica, sin importar si el eje se ve horizontal, vertical o en diagonal en pantalla para el
     * angulo de camara actual.
     *
     * La direccion de escalado (effectiveAxisDirection: eje mundo puro en Global, eje rotado en
     * Local) se convierte a espacio local del objeto (worldDirToLocalDir) antes de aplicarse sobre
     * shapeMatrix (ver applyLocalDirScale) - shapeMatrix es una matriz completa, no 3 floats
     * sueltos, asi que SI puede representar el resultado real: en Local (o con el objeto sin
     * rotar) da lo mismo que antes (escala pura por eje, verificado a mano); en Global con el
     * objeto rotado, el eje mundo no coincide con ningun eje propio del objeto y el resultado es
     * un shear real - mismo comportamiento que Blender (ver charla con el usuario y su video de
     * referencia, donde se confirmo el bug: antes esto se escribia siempre en el eje LOCAL,
     * como si el objeto no estuviera rotado).
     * Devuelve false (y no hace nada) si no hay ningun objeto seleccionado.
     */
    fun scaleSelectedObjectOnAxis(dxScreen: Float, dyScreen: Float, axis: Char): Boolean {
        val selected = sceneObjects.firstOrNull { it.selected } ?: return false
        val dragAmount = projectDragOntoAxisScreenDir(dxScreen, dyScreen, axis, selected)
        val rawFactor = 1f + dragAmount * 0.005f
        val localDir = worldDirToLocalDir(effectiveAxisDirection(axis, selected), selected)
        val factor = clampAxisScaleFactor(rawFactor, localDir, selected)
        applyLocalDirScale(selected, localDir, factor)
        return true
    }

    /**
     * Convierte una direccion de MUNDO a espacio LOCAL del objeto, deshaciendo su rotationMatrix
     * (rotacion pura - la inversa es la transpuesta). Usada por scaleSelectedObjectOnAxis: la
     * direccion por la que se escala (effectiveAxisDirection, ya sea eje mundo puro en Global o
     * eje rotado en Local) tiene que pasarse a espacio local antes de aplicarla sobre shapeMatrix,
     * que vive ANTES de rotationMatrix en la composicion (ver SceneObject).
     */
    private fun worldDirToLocalDir(worldDir: FloatArray, obj: SceneObject): FloatArray {
        val inverseRotation = FloatArray(16)
        Matrix.transposeM(inverseRotation, 0, obj.rotationMatrix, 0)
        val result = FloatArray(4)
        Matrix.multiplyMV(result, 0, inverseRotation, 0, floatArrayOf(worldDir[0], worldDir[1], worldDir[2], 0f), 0)
        return floatArrayOf(result[0], result[1], result[2])
    }

    /**
     * Cuanto esta estirado el objeto ahora mismo en una direccion LOCAL dada (unitaria): aplica
     * shapeMatrix a esa direccion y mide el largo del resultado - generaliza columnLength (que es
     * el caso particular de una direccion que coincide exactamente con un eje local) a cualquier
     * direccion, incluida una diagonal entre varios ejes locales (caso Global con el objeto
     * rotado). Usada por clampAxisScaleFactor para poner los mismos limites [0.1, 10] que ya
     * existian, ahora tambien cuando la direccion de escalado no es un eje local puro.
     */
    private fun directionalScaleMagnitude(shape: FloatArray, localDir: FloatArray): Float {
        var vx = 0f
        var vy = 0f
        var vz = 0f
        for (col in 0 until 3) {
            vx += shape[col * 4 + 0] * localDir[col]
            vy += shape[col * 4 + 1] * localDir[col]
            vz += shape[col * 4 + 2] * localDir[col]
        }
        return sqrt(vx * vx + vy * vy + vz * vz)
    }

    /** Mismo criterio de clamp [0.1, 10] que clampUniformScaleFactor, pero medido a lo largo de una direccion local especifica (ver directionalScaleMagnitude) en vez de por columna pura - equivalente exacto cuando localDir es un eje puro. */
    private fun clampAxisScaleFactor(rawFactor: Float, localDir: FloatArray, obj: SceneObject): Float {
        val currentMag = directionalScaleMagnitude(obj.shapeMatrix, localDir)
        if (currentMag < 1e-6f) return rawFactor
        return when {
            rawFactor > 1f -> minOf(rawFactor, 10f / currentMag)
            rawFactor < 1f -> maxOf(rawFactor, 0.1f / currentMag)
            else -> rawFactor
        }
    }

    /**
     * Aplica un escalado con factor `factor` a lo largo de una direccion LOCAL unitaria dada
     * (localDir, ya convertida via worldDirToLocalDir), modificando shapeMatrix del objeto -
     * formula estandar de escalado direccional: S' = S + (factor - 1) * localDir * (localDir^T * S).
     *
     * Si localDir coincide con un eje local puro (Local, o Global con el objeto sin rotar), esto
     * da EXACTAMENTE lo mismo que escalar ese eje a secas (equivalente matematico al viejo
     * `selected.scaleX *= factor`, verificado a mano) - cero cambio de comportamiento ahi.
     *
     * Si localDir queda repartida entre varios ejes locales (Global con el objeto rotado, ver
     * worldDirToLocalDir), la formula mete terminos fuera de la diagonal en shapeMatrix (shear) -
     * es lo que reproduce el comportamiento real de Blender al escalar en Global un objeto rotado
     * (ver charla con el usuario, video de referencia): el objeto se deforma en diagonal en vez de
     * estirarse derecho, porque el eje mundo que se esta arrastrando no coincide con ningun eje
     * propio del objeto.
     */
    private fun applyLocalDirScale(obj: SceneObject, localDir: FloatArray, factor: Float) {
        val shape = obj.shapeMatrix
        // rowProjected[col] = localDir . (columna `col` de shape) = localDir^T * S, fila 1x3.
        val rowProjected = FloatArray(3)
        for (col in 0 until 3) {
            rowProjected[col] = localDir[0] * shape[col * 4 + 0] + localDir[1] * shape[col * 4 + 1] + localDir[2] * shape[col * 4 + 2]
        }
        val delta = factor - 1f
        for (col in 0 until 3) {
            for (row in 0 until 3) {
                shape[col * 4 + row] += delta * localDir[row] * rowProjected[col]
            }
        }
    }

    /**
     * Proyecta el arrastre completo (dx, dy) sobre la direccion en pantalla en que apunta un eje
     * dado, para el objeto dado - se calcula proyectando el centro del objeto y un punto un poco
     * mas alla en la direccion del eje a pantalla (ver projectWorldToScreen), y comparando
     * esos dos puntos. Fallback a -dyScreen (comportamiento viejo) si la proyeccion no esta
     * disponible todavia (primer frame) o el eje quedo de canto respecto de la camara (proyectado
     * a un solo punto en pantalla, sin direccion definida).
     *
     * Usa effectiveAxisDirection (no axisDirection ni rotatedAxisDirection sueltos) - la direccion
     * en pantalla usada para medir el arrastre tiene que ser la misma que se ve dibujada (ver
     * onDrawFrame), o el arrastre se sentiria invertido o en un angulo que no corresponde al
     * cubito que se ve en pantalla (bug original reportado y confirmado con el usuario, corregido).
     */
    private fun projectDragOntoAxisScreenDir(dxScreen: Float, dyScreen: Float, axis: Char, obj: SceneObject): Float {
        val axisDir = effectiveAxisDirection(axis, obj)
        val p0 = projectWorldToScreen(obj.posX, obj.posY, obj.posZ)
        val p1 = projectWorldToScreen(
            obj.posX + axisDir[0] * 0.05f,
            obj.posY + axisDir[1] * 0.05f,
            obj.posZ + axisDir[2] * 0.05f
        )
        if (p0 == null || p1 == null) return -dyScreen

        val screenDx = p1[0] - p0[0]
        val screenDy = p1[1] - p0[1]
        val screenLen = sqrt(screenDx * screenDx + screenDy * screenDy)
        if (screenLen < 1e-4f) return -dyScreen

        return (dxScreen * (screenDx / screenLen)) + (dyScreen * (screenDy / screenLen))
    }

    fun zoomIn() {
        cameraDistance -= cameraDistance * 0.15f
    }

    fun zoomOut() {
        cameraDistance += cameraDistance * 0.15f
    }

    override fun onSurfaceCreated(unused: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0.11f, 0.11f, 0.11f, 1f)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        cubeGeometry = Cube()
        planeGeometry = Plane()
        circleGeometry = Circle()
        uvSphereGeometry = UvSphere()
        icoSphereGeometry = IcoSphere()
        cylinderGeometry = Cylinder()
        coneGeometry = Cone()
        torusGeometry = Torus()
        gridMeshGeometry = GridMesh()
        monkeyGeometry = Monkey()
        gridXY = Grid(GridPlane.XY)
        gridXZ = Grid(GridPlane.XZ)
        gridYZ = Grid(GridPlane.YZ)
        gizmo = Gizmo3D()

        sceneObjects.clear()
        sceneObjects.add(SceneObject(id = nextObjectId++, selected = true))
    }

    override fun onSurfaceChanged(unused: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        viewportWidth = width
        viewportHeight = height
    }

    override fun onDrawFrame(unused: GL10?) {
        // Vacia la cola de refrescos de geometria dinamica pendientes (ver pendingDynamicGeometryRefresh)
        // ANTES de dibujar - este es el unico lugar seguro para tocar OpenGL de verdad para eso (hilo de render).
        processPendingDynamicGeometryRefreshes()
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

        // Cada objeto se dibuja con su propia matriz (mvpMatrix comun de camara + transform propia:
        // traslacion, rotacion acumulada (obj.rotationMatrix, ver SceneObject) y forma (obj.shapeMatrix,
        // escala y, cuando corresponde, shear) - en ese orden, la forma primero para que quede local
        // al objeto antes de rotar/trasladar.
        val translateMatrix = FloatArray(16)
        val modelMatrix = FloatArray(16)
        val shapedModelMatrix = FloatArray(16)
        val objMvpMatrix = FloatArray(16)
        for (obj in sceneObjects) {
            Matrix.setIdentityM(translateMatrix, 0)
            Matrix.translateM(translateMatrix, 0, obj.posX, obj.posY, obj.posZ)
            Matrix.multiplyMM(modelMatrix, 0, translateMatrix, 0, obj.rotationMatrix, 0)
            Matrix.multiplyMM(shapedModelMatrix, 0, modelMatrix, 0, obj.shapeMatrix, 0)
            Matrix.multiplyMM(objMvpMatrix, 0, mvpMatrix, 0, shapedModelMatrix, 0)
            // Si el objeto ya tiene editableMesh (entro a Edit Mode al menos una vez, ver
            // enterEditModeForSelected), dibuja SIEMPRE con su geometria dinamica propia (ver
            // dynamicGeometries/DynamicMeshGeometry.kt) - en Layout o en Modeling, no solo
            // mientras esta en Edit Mode: una vez editado, ya no puede volver a compartir la
            // geometria estatica del tipo (cubeGeometry, etc.), porque esa es la MISMA instancia
            // que usan todos los demas cubos sin editar de la escena. NO se llama update() aca -
            // ver comentario de dynamicGeometries sobre por que (RENDERMODE_CONTINUOUSLY).
            val dynGeo = dynamicGeometries[obj.id]
            if (obj.editableMesh != null && dynGeo != null) {
                dynGeo.draw(objMvpMatrix, obj.selected, isEditMode && obj.selected)
                continue
            }
            when (obj.type) {
                MeshType.CUBE -> cubeGeometry.draw(objMvpMatrix, obj.selected)
                MeshType.PLANE -> planeGeometry.draw(objMvpMatrix, obj.selected)
                MeshType.CIRCLE -> circleGeometry.draw(objMvpMatrix, obj.selected)
                MeshType.UV_SPHERE -> uvSphereGeometry.draw(objMvpMatrix, obj.selected)
                MeshType.ICO_SPHERE -> icoSphereGeometry.draw(objMvpMatrix, obj.selected)
                MeshType.CYLINDER -> cylinderGeometry.draw(objMvpMatrix, obj.selected)
                MeshType.CONE -> coneGeometry.draw(objMvpMatrix, obj.selected)
                MeshType.TORUS -> torusGeometry.draw(objMvpMatrix, obj.selected)
                MeshType.GRID -> gridMeshGeometry.draw(objMvpMatrix, obj.selected)
                MeshType.MONKEY -> monkeyGeometry.draw(objMvpMatrix, obj.selected)
            }
        }

        // Gizmo de transformacion sobre el objeto seleccionado: la orientacion (mundo fijo vs
        // rotado con el objeto) la decide transformOrientation (ver enum TransformOrientation y
        // el selector Global/Local en MainActivity) - aplica por igual a los 3 modos (Move/Rotate/
        // Scale), no solo a Scale como antes de agregar el selector. gizmoMode decide si se
        // dibujan flechas (Move), anillos (Rotate) o cubitos (Scale) - ver MainActivity.setLayoutTool.
        // activeRotateAxis/activeMoveAxis/activeScaleAxis (sincronizados desde MainActivity.axisLocked
        // segun la herramienta activa) deciden, dentro de cada modo, si se resalta un solo eje
        // agarrado o los 3 en su modo normal.
        val mode = gizmoMode
        if (mode != null) {
            val selectedObj = sceneObjects.firstOrNull { it.selected }
            if (selectedObj != null) {
                val gizmoModel = FloatArray(16)
                Matrix.setIdentityM(gizmoModel, 0)
                Matrix.translateM(gizmoModel, 0, selectedObj.posX, selectedObj.posY, selectedObj.posZ)
                // LOCAL: el gizmo rota junto con el objeto, para que lo que ves coincida siempre
                // con lo que va a pasar al arrastrar (ver comentario del enum TransformOrientation
                // sobre el trade-off con GLOBAL). GLOBAL: se deja el gizmo sin rotar (comportamiento
                // de mas abajo, sin este bloque) - se ve "prolijo" pero, para Scale con el objeto
                // rotado, el resultado real puede deformar en diagonal (charlado con el usuario).
                if (transformOrientation == TransformOrientation.LOCAL) {
                    val rotatedGizmoModel = FloatArray(16)
                    Matrix.multiplyMM(rotatedGizmoModel, 0, gizmoModel, 0, selectedObj.rotationMatrix, 0)
                    System.arraycopy(rotatedGizmoModel, 0, gizmoModel, 0, 16)
                }
                Matrix.scaleM(gizmoModel, 0, gizmoScreenScale, gizmoScreenScale, gizmoScreenScale)
                val gizmoMvpMatrix = FloatArray(16)
                Matrix.multiplyMM(gizmoMvpMatrix, 0, mvpMatrix, 0, gizmoModel, 0)
                val activeAxisForMode = when (mode) {
                    GizmoMode.ROTATE -> activeRotateAxis
                    GizmoMode.MOVE -> activeMoveAxis
                    GizmoMode.SCALE -> activeScaleAxis
                }
                gizmo.draw(
                    gizmoMvpMatrix,
                    mode,
                    if (mode == GizmoMode.ROTATE) computeWorldViewDirection() else null,
                    activeAxisForMode
                )

                // Anillo trackball (blanco, en Rotate y Scale, solo sin eje activo - con un eje
                // agarrado el trackball se oculta, igual que Blender oculta el resto del gizmo
                // cuando estas arrastrando un eje puntual): billboard, siempre de cara a la camara
                // sin importar la orbita - se logra multiplicando por la inversa de rotationMatrix
                // (transpuesta, al ser una rotacion pura) antes de escalar. En Rotate representa la
                // rotacion libre (rotateSelectedObject); en Scale representa el escalado uniforme
                // libre (scaleSelectedObject) - mismo anillo reusado como referencia visual para
                // los dos gestos "sin eje" (ver charla con el usuario). El trackball es rotation-
                // invariant (billboard de camara, no de objeto) porque el escalado uniforme libre
                // no tiene el problema de Scale por eje (escalar los 3 ejes por igual conmuta con
                // cualquier rotacion), asi que no necesita el mismo ajuste que el bloque de arriba.
                val showTrackball = (mode == GizmoMode.ROTATE && activeRotateAxis == null) ||
                    (mode == GizmoMode.SCALE && activeScaleAxis == null)
                if (showTrackball) {
                    val inverseOrbit = FloatArray(16)
                    Matrix.transposeM(inverseOrbit, 0, rotationMatrix, 0)

                    val translatePart = FloatArray(16)
                    Matrix.setIdentityM(translatePart, 0)
                    Matrix.translateM(translatePart, 0, selectedObj.posX, selectedObj.posY, selectedObj.posZ)

                    val trackballModel = FloatArray(16)
                    Matrix.multiplyMM(trackballModel, 0, translatePart, 0, inverseOrbit, 0)
                    Matrix.scaleM(trackballModel, 0, gizmoScreenScale, gizmoScreenScale, gizmoScreenScale)

                    val trackballMvpMatrix = FloatArray(16)
                    Matrix.multiplyMM(trackballMvpMatrix, 0, mvpMatrix, 0, trackballModel, 0)
                    gizmo.drawTrackballRing(trackballMvpMatrix)
                }

                // Con un anillo agarrado: linea infinita del eje + marca de angulo en vivo (sigue
                // al dedo) + crucecita del pivote - las 3 piezas extra que replican la referencia
                // visual de Blender (ver video de referencia del usuario). Todo en el color propio
                // del eje activo. La marca de angulo usa activeRotateCurrentDir (recalculada en
                // cada ACTION_MOVE, ver MainActivity.onViewportDragMove) en vez del viejo
                // activeRotateStartDir fijo, con fallback a este ultimo para el primer frame del
                // gesto (antes del primer move) o en el caso degenerado de que
                // updateActiveRotateCurrentDir no haya podido calcular una direccion ese frame.
                if (mode == GizmoMode.ROTATE && activeRotateAxis != null) {
                    val axisChar = activeRotateAxis!!
                    val axisColor = gizmo.colorForAxis(axisChar)

                    // Linea infinita: traslacion al objeto SIN el escalado de gizmoScreenScale
                    // (su longitud se maneja en unidades de mundo reales dentro de Gizmo3D).
                    val translateOnlyModel = FloatArray(16)
                    Matrix.setIdentityM(translateOnlyModel, 0)
                    Matrix.translateM(translateOnlyModel, 0, selectedObj.posX, selectedObj.posY, selectedObj.posZ)
                    val lineMvpMatrix = FloatArray(16)
                    Matrix.multiplyMM(lineMvpMatrix, 0, mvpMatrix, 0, translateOnlyModel, 0)
                    gizmo.drawInfiniteAxisLine(lineMvpMatrix, effectiveAxisDirection(axisChar, selectedObj), axisColor)

                    val liveDir = activeRotateCurrentDir ?: activeRotateStartDir
                    if (liveDir != null) {
                        gizmo.drawLiveAngleMarker(gizmoMvpMatrix, liveDir, axisColor)
                    }
                    gizmo.drawCenterCrosshair(gizmoMvpMatrix)
                }

                // Con una flecha de Move agarrada: linea infinita del eje + crucecita del pivote -
                // mismo criterio visual que el bloque de arriba para Rotate, pero sin marca de
                // angulo (esa es propia de una rotacion, no aplica a un movimiento). Ver charla
                // con el usuario - pidio el mismo comportamiento "solo un eje + linea infinita"
                // que ya tenia Rotate, tambien para Move.
                if (mode == GizmoMode.MOVE && activeMoveAxis != null) {
                    val axisChar = activeMoveAxis!!
                    val axisColor = gizmo.colorForAxis(axisChar)

                    val translateOnlyModel = FloatArray(16)
                    Matrix.setIdentityM(translateOnlyModel, 0)
                    Matrix.translateM(translateOnlyModel, 0, selectedObj.posX, selectedObj.posY, selectedObj.posZ)
                    val lineMvpMatrix = FloatArray(16)
                    Matrix.multiplyMM(lineMvpMatrix, 0, mvpMatrix, 0, translateOnlyModel, 0)
                    gizmo.drawInfiniteAxisLine(lineMvpMatrix, effectiveAxisDirection(axisChar, selectedObj), axisColor)

                    gizmo.drawCenterCrosshair(gizmoMvpMatrix)
                }

                // Con un cubito de Scale agarrado: mismo criterio que Move (linea infinita del eje
                // + crucecita del pivote, sin marca de angulo - eso es propio de Rotate). Usa
                // effectiveAxisDirection (no rotatedAxisDirection a secas) - la linea infinita
                // tiene que seguir la misma direccion que el resto del gizmo de Scale en este
                // frame (mundo o local, segun transformOrientation), o quedaria apuntando a un
                // lado distinto del cubito que se ve en pantalla.
                if (mode == GizmoMode.SCALE && activeScaleAxis != null) {
                    val axisChar = activeScaleAxis!!
                    val axisColor = gizmo.colorForAxis(axisChar)

                    val translateOnlyModel = FloatArray(16)
                    Matrix.setIdentityM(translateOnlyModel, 0)
                    Matrix.translateM(translateOnlyModel, 0, selectedObj.posX, selectedObj.posY, selectedObj.posZ)
                    val lineMvpMatrix = FloatArray(16)
                    Matrix.multiplyMM(lineMvpMatrix, 0, mvpMatrix, 0, translateOnlyModel, 0)
                    gizmo.drawInfiniteAxisLine(lineMvpMatrix, effectiveAxisDirection(axisChar, selectedObj), axisColor)

                    gizmo.drawCenterCrosshair(gizmoMvpMatrix)
                }
            }
        }
    }

    /**
     * Convierte un punto de pantalla (coordenadas de vista, no NDC) en un rayo 3D (origen +
     * direccion), usando la matriz camara+orbita del ultimo frame dibujado (scratch). Compartido
     * por selectObjectAt (seleccion de objetos) y los hit-test del gizmo (hitTestGizmoAxis,
     * hitTestGizmoRotateAxis, hitTestGizmoScaleAxis).
     */
    private fun screenPointToRay(screenX: Float, screenY: Float): Pair<FloatArray, FloatArray>? {
        if (viewportWidth <= 0 || viewportHeight <= 0) return null

        val ndcX = (2f * screenX / viewportWidth) - 1f
        val ndcY = 1f - (2f * screenY / viewportHeight)

        val vpMatrix = FloatArray(16)
        Matrix.multiplyMM(vpMatrix, 0, projectionMatrix, 0, scratch, 0)
        val invMatrix = FloatArray(16)
        if (!Matrix.invertM(invMatrix, 0, vpMatrix, 0)) return null

        val nearPoint = floatArrayOf(ndcX, ndcY, -1f, 1f)
        val farPoint = floatArrayOf(ndcX, ndcY, 1f, 1f)
        val nearWorld = FloatArray(4)
        val farWorld = FloatArray(4)
        Matrix.multiplyMV(nearWorld, 0, invMatrix, 0, nearPoint, 0)
        Matrix.multiplyMV(farWorld, 0, invMatrix, 0, farPoint, 0)
        if (nearWorld[3] != 0f) for (i in 0..2) nearWorld[i] /= nearWorld[3]
        if (farWorld[3] != 0f) for (i in 0..2) farWorld[i] /= farWorld[3]

        val rayOrigin = floatArrayOf(nearWorld[0], nearWorld[1], nearWorld[2])
        val rayDir = floatArrayOf(
            farWorld[0] - nearWorld[0],
            farWorld[1] - nearWorld[1],
            farWorld[2] - nearWorld[2]
        )
        return rayOrigin to rayDir
    }

    /**
     * Deselecciona todos los objetos (Layout > Select > None) - mismo criterio que selectObjectAt
     * tocando espacio vacio. No pasa por Undo (igual que selectObjectAt/hitTestGizmoRotateAxis):
     * la seleccion se trata como estado de UI, no como una edicion de la escena - Blender tampoco
     * mete los cambios de seleccion en su historial de Undo principal.
     */
    fun deselectAll() {
        for (obj in sceneObjects) obj.selected = false
    }

    /**
     * Convierte un tap en pantalla en un rayo 3D y selecciona el objeto mas cercano que
     * intersecta. Si no hay hit, deselecciona todo (igual que tocar espacio vacio en Blender).
     */
    fun selectObjectAt(screenX: Float, screenY: Float) {
        val (rayOrigin, rayDir) = screenPointToRay(screenX, screenY) ?: return

        var hitObject: SceneObject? = null
        var closestT = Float.MAX_VALUE
        for (obj in sceneObjects) {
            val t = intersectAABB(rayOrigin, rayDir, obj.posX, obj.posY, obj.posZ, obj.shapeMatrix)
            if (t != null && t < closestT) {
                closestT = t
                hitObject = obj
            }
        }

        for (obj in sceneObjects) {
            obj.selected = (obj === hitObject)
        }
    }

    /**
     * Hit-test del gizmo de Move: para el objeto seleccionado, calcula la distancia minima entre
     * el rayo del tap y cada uno de los 3 segmentos de eje (en espacio mundo, con el mismo tamano
     * de pantalla constante que usa el dibujo - ver gizmoScreenScale), y devuelve el eje mas
     * cercano si esta dentro del radio de tolerancia. Devuelve null si no hay objeto seleccionado
     * o si ningun eje esta lo bastante cerca (en ese caso el arrastre cae al gesto libre normal).
     */
    fun hitTestGizmoAxis(screenX: Float, screenY: Float): Char? {
        val selected = sceneObjects.firstOrNull { it.selected } ?: return null
        val (rayOrigin, rayDir) = screenPointToRay(screenX, screenY) ?: return null

        // Unica fuente de verdad: Gizmo3D.SHAFT_LENGTH / TIP_LENGTH (companion object alla) - ya
        // no hay numeros duplicados a mano entre dibujo y hit-test.
        val segStart = floatArrayOf(selected.posX, selected.posY, selected.posZ)
        // (ver companion object de Gizmo3D, arriba, para SHAFT_LENGTH/TIP_LENGTH)
        val axisLength = gizmoScreenScale * (Gizmo3D.SHAFT_LENGTH + Gizmo3D.TIP_LENGTH)
        val hitRadius = gizmoScreenScale * 0.18f

        var bestAxis: Char? = null
        var bestDist = Float.MAX_VALUE
        for (axisChar in listOf('X', 'Y', 'Z')) {
            val dist = closestDistanceRayToSegment(rayOrigin, rayDir, segStart, effectiveAxisDirection(axisChar, selected), axisLength)
            if (dist < hitRadius && dist < bestDist) {
                bestDist = dist
                bestAxis = axisChar
            }
        }
        return bestAxis
    }

    /**
     * Hit-test del gizmo de Scale: mismo criterio que hitTestGizmoAxis (distancia minima rayo-
     * segmento, un segmento por eje), pero con el largo y el radio de tolerancia propios de Scale
     * (shaft + cubito, ver Gizmo3D.SCALE_BOX_HALF_SIZE) en vez de shaft + punta de flecha. Usa
     * effectiveAxisDirection (no un axisDirection/rotatedAxisDirection fijo) - el cubito de cada
     * eje se dibuja en la orientacion elegida (ver onDrawFrame), asi que el segmento contra el que
     * se mide la distancia del toque tiene que estar en esa misma orientacion, o el hit-test
     * terminaria probando contra una posicion distinta de donde el cubito realmente se ve.
     * Devuelve null si no hay objeto seleccionado o si ningun eje esta lo bastante cerca (el
     * arrastre cae entonces al escalado uniforme libre, ver scaleSelectedObject).
     */
    fun hitTestGizmoScaleAxis(screenX: Float, screenY: Float): Char? {
        val selected = sceneObjects.firstOrNull { it.selected } ?: return null
        val (rayOrigin, rayDir) = screenPointToRay(screenX, screenY) ?: return null

        val segStart = floatArrayOf(selected.posX, selected.posY, selected.posZ)
        val axisLength = gizmoScreenScale * (Gizmo3D.SHAFT_LENGTH + Gizmo3D.SCALE_BOX_HALF_SIZE)
        val hitRadius = gizmoScreenScale * 0.18f

        var bestAxis: Char? = null
        var bestDist = Float.MAX_VALUE
        for (axisChar in listOf('X', 'Y', 'Z')) {
            val dist = closestDistanceRayToSegment(rayOrigin, rayDir, segStart, effectiveAxisDirection(axisChar, selected), axisLength)
            if (dist < hitRadius && dist < bestDist) {
                bestDist = dist
                bestAxis = axisChar
            }
        }
        return bestAxis
    }

    /**
     * Hit-test del gizmo de Rotate: para el objeto seleccionado, y para cada uno de los 3 anillos,
     * interseca el rayo del tap con el plano perpendicular a ese eje que pasa por el centro del
     * objeto (rayo-plano, cerrado, sin iteracion), y compara la distancia del punto de interseccion
     * al centro contra el radio del anillo (Gizmo3D.RING_RADIUS, escalado por gizmoScreenScale -
     * unica fuente de verdad, ya no hay numero duplicado a mano aca). Devuelve el anillo mas cercano dentro de la
     * tolerancia, o null si no hay objeto seleccionado, el eje quedo de canto respecto de la camara
     * (rayo paralelo al plano - interseccion indefinida) o ningun anillo esta lo bastante cerca.
     * De paso, si encuentra un hit, guarda en activeRotateStartDir la direccion (mundo, normalizada)
     * desde el centro hasta el punto tocado - la usa computeRotateLabelAnchor para la etiqueta de
     * texto del eje agarrado (ver MainActivity.onViewportDragStart, que ademas copia este valor
     * como semilla inicial de activeRotateCurrentDir).
     */
    fun hitTestGizmoRotateAxis(screenX: Float, screenY: Float): Char? {
        val selected = sceneObjects.firstOrNull { it.selected } ?: return null
        val (rayOrigin, rayDir) = screenPointToRay(screenX, screenY) ?: return null

        val center = floatArrayOf(selected.posX, selected.posY, selected.posZ)
        val ringRadius = gizmoScreenScale * Gizmo3D.RING_RADIUS
        val tolerance = gizmoScreenScale * 0.15f

        var bestAxis: Char? = null
        var bestDist = Float.MAX_VALUE
        var bestDir: FloatArray? = null
        for (axisChar in listOf('X', 'Y', 'Z')) {
            val normal = effectiveAxisDirection(axisChar, selected)
            val denom = dot(rayDir, normal)
            if (abs(denom) < 1e-6f) continue

            val diff = floatArrayOf(center[0] - rayOrigin[0], center[1] - rayOrigin[1], center[2] - rayOrigin[2])
            val t = dot(diff, normal) / denom
            if (t < 0f) continue

            val hitPoint = floatArrayOf(
                rayOrigin[0] + rayDir[0] * t,
                rayOrigin[1] + rayDir[1] * t,
                rayOrigin[2] + rayDir[2] * t
            )
            val dx = hitPoint[0] - center[0]
            val dy = hitPoint[1] - center[1]
            val dz = hitPoint[2] - center[2]
            val distFromCenter = sqrt(dx * dx + dy * dy + dz * dz)
            val distFromRing = abs(distFromCenter - ringRadius)

            if (distFromRing < tolerance && distFromRing < bestDist) {
                bestDist = distFromRing
                bestAxis = axisChar
                bestDir = if (distFromCenter > 1e-4f) floatArrayOf(dx / distFromCenter, dy / distFromCenter, dz / distFromCenter) else null
            }
        }
        if (bestAxis != null) activeRotateStartDir = bestDir
        return bestAxis
    }

    /**
     * Interseccion rayo-plano SIN tolerancia de cercania al anillo (a diferencia de
     * hitTestGizmoRotateAxis): aca el dedo puede estar lejos del circulo y la marca de angulo
     * igual tiene que apuntar hacia el, tal cual el gizmo real de Blender (ver video de referencia
     * del usuario). Actualiza activeRotateCurrentDir; no hace nada (deja el valor anterior) en el
     * caso degenerado de eje de canto respecto de la camara, interseccion detras de la camara, o
     * punto de interseccion pegado al centro (direccion indefinida).
     */
    fun updateActiveRotateCurrentDir(screenX: Float, screenY: Float, axis: Char) {
        val selected = sceneObjects.firstOrNull { it.selected } ?: return
        val (rayOrigin, rayDir) = screenPointToRay(screenX, screenY) ?: return
        val center = floatArrayOf(selected.posX, selected.posY, selected.posZ)
        val normal = effectiveAxisDirection(axis, selected)
        val denom = dot(rayDir, normal)
        if (abs(denom) < 1e-6f) return
        val diff = floatArrayOf(center[0] - rayOrigin[0], center[1] - rayOrigin[1], center[2] - rayOrigin[2])
        val t = dot(diff, normal) / denom
        if (t < 0f) return
        val hitPoint = floatArrayOf(rayOrigin[0] + rayDir[0] * t, rayOrigin[1] + rayDir[1] * t, rayOrigin[2] + rayDir[2] * t)
        val dx = hitPoint[0] - center[0]
        val dy = hitPoint[1] - center[1]
        val dz = hitPoint[2] - center[2]
        val dist = sqrt(dx * dx + dy * dy + dz * dz)
        if (dist < 1e-4f) return
        activeRotateCurrentDir = floatArrayOf(dx / dist, dy / dist, dz / dist)
    }

    /**
     * Distancia minima entre una recta (rayOrigin + t*rayDir, t libre) y un segmento
     * (segStart + s*segDir, s clampeado a [0, segLength]) - formula estandar de distancia
     * minima entre dos rectas en 3D, resuelta para el punto mas cercano del rayo una vez fijado
     * el parametro clampeado del segmento.
     */
    private fun closestDistanceRayToSegment(
        rayOrigin: FloatArray, rayDir: FloatArray,
        segStart: FloatArray, segDir: FloatArray, segLength: Float
    ): Float {
        val r = FloatArray(3) { rayOrigin[it] - segStart[it] }
        val a = dot(rayDir, rayDir)
        val b = dot(rayDir, segDir)
        val c = dot(rayDir, r)
        val e = dot(segDir, segDir)
        val f = dot(segDir, r)
        val denom = a * e - b * b

        var s = if (denom > 1e-8f) (a * f - b * c) / denom else if (e > 1e-8f) f / e else 0f
        s = s.coerceIn(0f, segLength)
        val t = if (a > 1e-8f) maxOf(0f, (b * s - c) / a) else 0f

        val closestOnRay = FloatArray(3) { rayOrigin[it] + rayDir[it] * t }
        val closestOnSeg = FloatArray(3) { segStart[it] + segDir[it] * s }
        val dx = closestOnRay[0] - closestOnSeg[0]
        val dy = closestOnRay[1] - closestOnSeg[1]
        val dz = closestOnRay[2] - closestOnSeg[2]
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    private fun dot(a: FloatArray, b: FloatArray) = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]

    /**
     * Matriz modelo completa (traslacion + rotacion + forma) de un objeto - mismo calculo que se
     * arma en linea en onDrawFrame para cada objeto (translateMatrix * rotationMatrix *
     * shapeMatrix), extraido aca para que los raycast de sub-elementos (raycastVertexAt/EdgeAt/
     * FaceAt) puedan llevar un vertice local del EditableMesh a espacio mundo sin duplicar la
     * formula ni arriesgarse a que las dos copias se desincronicen.
     */
    private fun objectModelMatrix(obj: SceneObject): FloatArray {
        val translateMatrix = FloatArray(16)
        Matrix.setIdentityM(translateMatrix, 0)
        Matrix.translateM(translateMatrix, 0, obj.posX, obj.posY, obj.posZ)
        val modelMatrix = FloatArray(16)
        Matrix.multiplyMM(modelMatrix, 0, translateMatrix, 0, obj.rotationMatrix, 0)
        val shapedModelMatrix = FloatArray(16)
        Matrix.multiplyMM(shapedModelMatrix, 0, modelMatrix, 0, obj.shapeMatrix, 0)
        return shapedModelMatrix
    }

    private fun localVertexToWorld(modelMatrix: FloatArray, local: MeshVertex): FloatArray {
        val result = FloatArray(4)
        Matrix.multiplyMV(result, 0, modelMatrix, 0, floatArrayOf(local.x, local.y, local.z, 1f), 0)
        return floatArrayOf(result[0], result[1], result[2])
    }

    /**
     * Objeto actualmente en Edit Mode: el seleccionado, siempre y cuando ya tenga su EditableMesh
     * creado (ver enterEditModeForSelected) - null si no hay objeto seleccionado o si todavia no
     * entro a Modeling. Unica fuente de verdad usada por los 3 raycast de sub-elementos y por las
     * funciones de seleccion masiva (selectAllMeshElements/deselectAllMeshElements/
     * invertMeshElementSelection), para no repetir esta misma condicion varias veces.
     */
    private fun editingObject(): SceneObject? {
        val selected = sceneObjects.firstOrNull { it.selected } ?: return null
        return if (selected.editableMesh != null) selected else null
    }

    /** Radio de tolerancia (pixeles de pantalla) para tocar un vertice - mas generoso que una arista o una cara porque un vertice es un punto, el objetivo mas dificil de acertar con el dedo. */
    private val VERTEX_PICK_RADIUS_PX = 40f
    /** Radio de tolerancia (pixeles de pantalla) para tocar una arista - distancia punto-segmento en pantalla, ver pointToSegmentDistance2D. */
    private val EDGE_PICK_RADIUS_PX = 28f

    /**
     * Raycast de Fase 1 (Vertex select mode): para el objeto en Edit Mode, proyecta cada vertice
     * a pantalla (ver projectWorldToScreen) y devuelve el mas cercano al toque dentro del radio de
     * tolerancia - distancia en PANTALLA, no en mundo, para que el tamano del objetivo no cambie
     * con el zoom (mismo criterio de "tamano constante en pantalla" que ya usa gizmoScreenScale
     * para el gizmo, aca aplicado al pick de vertices en vez de al dibujo). Null si no hay objeto
     * en Edit Mode o si ningun vertice cae dentro del radio.
     */
    fun raycastVertexAt(screenX: Float, screenY: Float): MeshVertex? {
        val obj = editingObject() ?: return null
        val mesh = obj.editableMesh ?: return null
        val model = objectModelMatrix(obj)
        var best: MeshVertex? = null
        var bestDist = Float.MAX_VALUE
        for (v in mesh.vertices) {
            val world = localVertexToWorld(model, v)
            val screen = projectWorldToScreen(world[0], world[1], world[2]) ?: continue
            val dx = screen[0] - screenX
            val dy = screen[1] - screenY
            val dist = sqrt(dx * dx + dy * dy)
            if (dist < VERTEX_PICK_RADIUS_PX && dist < bestDist) {
                bestDist = dist
                best = v
            }
        }
        return best
    }

    /** Distancia minima (pantalla, pixeles) entre un punto y un segmento - usada por raycastEdgeAt, mismo criterio 2D que closestDistanceRayToSegment usa en 3D para el gizmo. */
    private fun pointToSegmentDistance2D(px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float): Float {
        val abx = bx - ax
        val aby = by - ay
        val apx = px - ax
        val apy = py - ay
        val abLenSq = abx * abx + aby * aby
        val t = if (abLenSq > 1e-6f) ((apx * abx + apy * aby) / abLenSq).coerceIn(0f, 1f) else 0f
        val cx = ax + abx * t
        val cy = ay + aby * t
        val dx = px - cx
        val dy = py - cy
        return sqrt(dx * dx + dy * dy)
    }

    /**
     * Raycast de Fase 1 (Edge select mode): mismo criterio que raycastVertexAt pero contra los
     * segmentos de pantalla de cada arista (distancia punto-segmento 2D, ver
     * pointToSegmentDistance2D) en vez de puntos sueltos. Null si no hay objeto en Edit Mode o
     * ninguna arista cae dentro del radio.
     */
    fun raycastEdgeAt(screenX: Float, screenY: Float): MeshEdge? {
        val obj = editingObject() ?: return null
        val mesh = obj.editableMesh ?: return null
        val vertexById = mesh.vertices.associateBy { it.id }
        val model = objectModelMatrix(obj)
        var best: MeshEdge? = null
        var bestDist = Float.MAX_VALUE
        for (e in mesh.edges) {
            val v1 = vertexById[e.v1] ?: continue
            val v2 = vertexById[e.v2] ?: continue
            val w1 = localVertexToWorld(model, v1)
            val w2 = localVertexToWorld(model, v2)
            val s1 = projectWorldToScreen(w1[0], w1[1], w1[2]) ?: continue
            val s2 = projectWorldToScreen(w2[0], w2[1], w2[2]) ?: continue
            val dist = pointToSegmentDistance2D(screenX, screenY, s1[0], s1[1], s2[0], s2[1])
            if (dist < EDGE_PICK_RADIUS_PX && dist < bestDist) {
                bestDist = dist
                best = e
            }
        }
        return best
    }

    /**
     * Interseccion rayo-triangulo (Moller-Trumbore, formula estandar) - devuelve el parametro t a
     * lo largo del rayo si hay hit (mas cerca = t mas chico), o null si el rayo es paralelo al
     * triangulo o el punto de interseccion cae fuera de sus 3 lados. Usada por raycastFaceAt
     * contra la triangulacion en abanico de cada cara (mismo criterio que DynamicMeshGeometry usa
     * para dibujar - ver comentario ahi: fan simple desde el primer vertice, valido para cualquier
     * n-gon convexo).
     */
    private fun intersectRayTriangle(orig: FloatArray, dir: FloatArray, v0: FloatArray, v1: FloatArray, v2: FloatArray): Float? {
        val eps = 1e-6f
        val e1 = floatArrayOf(v1[0] - v0[0], v1[1] - v0[1], v1[2] - v0[2])
        val e2 = floatArrayOf(v2[0] - v0[0], v2[1] - v0[1], v2[2] - v0[2])
        val h = cross(dir, e2)
        val a = dot(e1, h)
        if (abs(a) < eps) return null
        val f = 1f / a
        val s = floatArrayOf(orig[0] - v0[0], orig[1] - v0[1], orig[2] - v0[2])
        val u = f * dot(s, h)
        if (u < 0f || u > 1f) return null
        val q = cross(s, e1)
        val v = f * dot(dir, q)
        if (v < 0f || u + v > 1f) return null
        val t = f * dot(e2, q)
        return if (t > eps) t else null
    }

    /**
     * Raycast de Fase 1 (Face select mode): a diferencia de vertice/arista (que comparan distancia
     * en PANTALLA), esto es un raycast 3D de verdad contra la triangulacion en abanico de cada
     * cara (ver intersectRayTriangle) - una cara es una superficie, no un punto, asi que "tocarla"
     * es literalmente que el rayo la atraviese, sin necesitar tolerancia. Devuelve la cara mas
     * cercana a la camara (menor t) entre todas las que el rayo atraviesa. Null si no hay objeto en
     * Edit Mode o el rayo no atraviesa ninguna cara.
     */
    fun raycastFaceAt(screenX: Float, screenY: Float): MeshFace? {
        val obj = editingObject() ?: return null
        val mesh = obj.editableMesh ?: return null
        val vertexById = mesh.vertices.associateBy { it.id }
        val model = objectModelMatrix(obj)
        val (rayOrigin, rayDir) = screenPointToRay(screenX, screenY) ?: return null
        var best: MeshFace? = null
        var bestT = Float.MAX_VALUE
        for (face in mesh.faces) {
            val corners = face.vertexIds.mapNotNull { vertexById[it] }
            if (corners.size < 3) continue
            val worldCorners = corners.map { localVertexToWorld(model, it) }
            for (i in 1 until worldCorners.size - 1) {
                val t = intersectRayTriangle(rayOrigin, rayDir, worldCorners[0], worldCorners[i], worldCorners[i + 1])
                if (t != null && t < bestT) {
                    bestT = t
                    best = face
                }
            }
        }
        return best
    }

    /**
     * Toque en el viewport durante Edit Mode con la herramienta Select activa (ver
     * MainActivity.onViewportTap) - deselecciona todo lo demas y selecciona SOLO el elemento
     * tocado (seleccion simple, sin Shift/Ctrl para extender todavia - mismo criterio que
     * selectObjectAt para objetos en Layout). Si el toque no acierta a nada, deselecciona todo
     * (tocar espacio vacio deselecciona, igual que en Blender).
     */
    fun selectMeshElementAt(screenX: Float, screenY: Float, mode: EditSelectMode) {
        val mesh = editingObject()?.editableMesh ?: return
        when (mode) {
            EditSelectMode.VERTEX -> {
                val hit = raycastVertexAt(screenX, screenY)
                for (v in mesh.vertices) v.selected = (v === hit)
            }
            EditSelectMode.EDGE -> {
                val hit = raycastEdgeAt(screenX, screenY)
                for (e in mesh.edges) e.selected = (e === hit)
            }
            EditSelectMode.FACE -> {
                val hit = raycastFaceAt(screenX, screenY)
                for (f in mesh.faces) f.selected = (f === hit)
            }
        }
    }

    /** Modeling > Select > All, para el tipo de sub-elemento actualmente activo (ver EditSelectMode) - no toca los otros dos tipos, mismo criterio que Blender (el modo de seleccion activo decide sobre que se opera). */
    fun selectAllMeshElements(mode: EditSelectMode) {
        val mesh = editingObject()?.editableMesh ?: return
        when (mode) {
            EditSelectMode.VERTEX -> for (v in mesh.vertices) v.selected = true
            EditSelectMode.EDGE -> for (e in mesh.edges) e.selected = true
            EditSelectMode.FACE -> for (f in mesh.faces) f.selected = true
        }
    }

    /** Modeling > Select > None, mismo criterio de mode que selectAllMeshElements. */
    fun deselectAllMeshElements(mode: EditSelectMode) {
        val mesh = editingObject()?.editableMesh ?: return
        when (mode) {
            EditSelectMode.VERTEX -> for (v in mesh.vertices) v.selected = false
            EditSelectMode.EDGE -> for (e in mesh.edges) e.selected = false
            EditSelectMode.FACE -> for (f in mesh.faces) f.selected = false
        }
    }

    /** Modeling > Select > Invert, mismo criterio de mode que selectAllMeshElements. */
    fun invertMeshElementSelection(mode: EditSelectMode) {
        val mesh = editingObject()?.editableMesh ?: return
        when (mode) {
            EditSelectMode.VERTEX -> for (v in mesh.vertices) v.selected = !v.selected
            EditSelectMode.EDGE -> for (e in mesh.edges) e.selected = !e.selected
            EditSelectMode.FACE -> for (f in mesh.faces) f.selected = !f.selected

        }
    }

    /**
     * Convierte la seleccion actual al cambiar de EditSelectMode (Vertex/Edge/Face) - mismo
     * comportamiento que Blender real: la seleccion nunca se pierde, se "traduce" al nuevo modo
     * en vez de borrarse (ver charla con el usuario, eligio esta opcion en vez de deseleccionar
     * todo). No hace nada si fromMode == toMode o si no hay objeto en Edit Mode.
     *
     * Usa el conjunto de vertices seleccionados como denominador comun entre los 3 modos (mismo
     * criterio que usa Blender internamente - "selection flushing"): primero se calcula QUE
     * vertices quedan implicados por la seleccion actual (fromMode), y despues se deriva la
     * seleccion del modo nuevo (toMode) a partir de ESE conjunto de vertices, con las mismas 2
     * reglas en los 2 sentidos:
     * - Una arista quedaria seleccionada si AMBOS extremos estan en el conjunto (flush hacia arriba).
     * - Una cara quedaria seleccionada si TODOS sus vertices estan en el conjunto (flush hacia arriba).
     * - Pasar a Vertex es directo: el conjunto ES la seleccion de vertices.
     * Este criterio simetrico evita tener que escribir 6 casos (Vertex->Edge, Vertex->Face,
     * Edge->Vertex, Edge->Face, Face->Vertex, Face->Edge) por separado - los 3 "de entrada" (que
     * arman el conjunto) y los 3 "de salida" (que lo aplican) alcanzan para cubrir las 6
     * combinaciones con la misma logica.
     */
    private fun verticesAffectedBySelection(mesh: EditableMesh): Set<Int> {
        val fromVertices = mesh.vertices.filter { it.selected }.map { it.id }
        val fromEdges = mesh.edges.filter { it.selected }.flatMap { listOf(it.v1, it.v2) }
        val fromFaces = mesh.faces.filter { it.selected }.flatMap { it.vertexIds }
        return (fromVertices + fromEdges + fromFaces).toSet()
    }

    fun hasSelectedMeshElements(): Boolean {
        val mesh = editingObject()?.editableMesh ?: return false
        return verticesAffectedBySelection(mesh).isNotEmpty()
    }

    private fun objectLinearMatrix(obj: SceneObject): FloatArray {
        val result = FloatArray(16)
        Matrix.multiplyMM(result, 0, obj.rotationMatrix, 0, obj.shapeMatrix, 0)
        return result
    }

    private fun worldDeltaToLocalDelta(worldDelta: FloatArray, obj: SceneObject): FloatArray {
        val linear = objectLinearMatrix(obj)
        val inverse = FloatArray(16)
        if (!Matrix.invertM(inverse, 0, linear, 0)) return floatArrayOf(0f, 0f, 0f)
        val result = FloatArray(4)
        Matrix.multiplyMV(result, 0, inverse, 0, floatArrayOf(worldDelta[0], worldDelta[1], worldDelta[2], 0f), 0)
        return floatArrayOf(result[0], result[1], result[2])
    }

    fun moveSelectedMeshElements(dxScreen: Float, dyScreen: Float): Boolean {
        val obj = editingObject() ?: return false
        val mesh = obj.editableMesh ?: return false
        val vertexIds = verticesAffectedBySelection(mesh)
        if (vertexIds.isEmpty()) return false

        val worldDelta = computeWorldDragDelta(dxScreen, dyScreen)
        val localDelta = worldDeltaToLocalDelta(worldDelta, obj)

        for (v in mesh.vertices) {
            if (v.id in vertexIds) {
                v.x += localDelta[0]
                v.y += localDelta[1]
                v.z += localDelta[2]
            }
        }
        refreshDynamicGeometry(obj)
        return true
    }

    /**
     * Igual que moveSelectedMeshElements, pero proyecta el delta de mundo sobre un solo eje (X/Y/Z)
     * antes de convertirlo a espacio local - se usa cuando el arrastre empezo tocando el gizmo de
     * Move (ver MainActivity.onViewportDragStart, que llama a hitTestGizmoAxis en ACTION_DOWN,
     * mismo hit-test que ya usa Object Mode).
     *
     * SIMPLIFICACION CONOCIDA (TODO): el gizmo se dibuja en el ORIGEN DEL OBJETO (mismo pivote que
     * Object Mode, ver onDrawFrame), no en el punto medio de los vertices seleccionados como hace
     * Blender real. Para primitivas simetricas centradas en su origen (caso de Cube hoy) el efecto
     * practico es minimo, pero si mas adelante se edita una malla ya desplazada del origen, el
     * gizmo va a aparecer en un lugar distinto de donde esta la seleccion. Arreglarlo requiere
     * reposicionar el gizmo dibujado y su hit-test al centroide de la seleccion en vez del origen
     * del objeto - postergado a proposito para no agrandar mas este cambio.
     *
     * Devuelve false (y no hace nada) si no hay objeto en Edit Mode o no hay nada seleccionado.
     */
    fun moveSelectedMeshElementsOnAxis(dxScreen: Float, dyScreen: Float, axis: Char): Boolean {
        val obj = editingObject() ?: return false
        val mesh = obj.editableMesh ?: return false
        val vertexIds = verticesAffectedBySelection(mesh)
        if (vertexIds.isEmpty()) return false

        val worldDelta = computeWorldDragDelta(dxScreen, dyScreen)
        val axisDir = effectiveAxisDirection(axis, obj)
        val projected = worldDelta[0] * axisDir[0] + worldDelta[1] * axisDir[1] + worldDelta[2] * axisDir[2]
        val worldAxisDelta = floatArrayOf(axisDir[0] * projected, axisDir[1] * projected, axisDir[2] * projected)
        val localDelta = worldDeltaToLocalDelta(worldAxisDelta, obj)

        for (v in mesh.vertices) {
            if (v.id in vertexIds) {
                v.x += localDelta[0]
                v.y += localDelta[1]
                v.z += localDelta[2]
            }
        }
        refreshDynamicGeometry(obj)
        return true
    }

    /**
     * Centro (mundo) de los vertices actualmente afectados por la seleccion (ver
     * verticesAffectedBySelection) - null si no hay nada seleccionado. Recalculado desde las
     * posiciones ACTUALES en cada llamada, no cacheado al empezar el arrastre: rotar (o escalar)
     * un conjunto de puntos alrededor de su propio centroide no mueve ese centroide (propiedad
     * geometrica basica), asi que recalcularlo cada frame da un pivote estable sin necesidad de
     * guardarlo aparte - a diferencia de Object Mode, donde el pivote (posicion del objeto) es
     * ajeno a la rotacion y por eso no necesita este cuidado.
     */
    private fun selectionCenterWorld(obj: SceneObject, mesh: EditableMesh, vertexIds: Set<Int>): FloatArray? {
        if (vertexIds.isEmpty()) return null
        val model = objectModelMatrix(obj)
        var sx = 0f; var sy = 0f; var sz = 0f
        var count = 0
        for (v in mesh.vertices) {
            if (v.id in vertexIds) {
                val w = localVertexToWorld(model, v)
                sx += w[0]; sy += w[1]; sz += w[2]
                count++
            }
        }
        if (count == 0) return null
        return floatArrayOf(sx / count, sy / count, sz / count)
    }

    /**
     * Rota los vertices en vertexIds alrededor de un eje MUNDO (worldAxisDir) que pasa por el
     * centro de la seleccion (ver selectionCenterWorld) - a diferencia de applyRotationDeltaAroundDir
     * (Object Mode, que rota la matriz acumulada del objeto entero), esto mueve cada vertice
     * individualmente: lo lleva a mundo, lo rota alrededor del pivote, y lo vuelve a espacio local
     * invirtiendo la matriz completa del objeto (traslacion + rotacion + forma - ver
     * objectModelMatrix) para que el resultado sea correcto sin importar como este transformado el
     * objeto.
     */
    private fun rotateMeshVerticesAroundWorldAxis(obj: SceneObject, mesh: EditableMesh, vertexIds: Set<Int>, angleDeg: Float, worldAxisDir: FloatArray) {
        if (angleDeg == 0f) return
        val center = selectionCenterWorld(obj, mesh, vertexIds) ?: return
        val model = objectModelMatrix(obj)
        val invModel = FloatArray(16)
        if (!Matrix.invertM(invModel, 0, model, 0)) return

        val delta = FloatArray(16)
        Matrix.setIdentityM(delta, 0)
        Matrix.rotateM(delta, 0, angleDeg, worldAxisDir[0], worldAxisDir[1], worldAxisDir[2])

        for (v in mesh.vertices) {
            if (v.id !in vertexIds) continue
            val world = localVertexToWorld(model, v)
            val rel = floatArrayOf(world[0] - center[0], world[1] - center[1], world[2] - center[2], 0f)
            val rotatedRel = FloatArray(4)
            Matrix.multiplyMV(rotatedRel, 0, delta, 0, rel, 0)
            val newWorld = floatArrayOf(center[0] + rotatedRel[0], center[1] + rotatedRel[1], center[2] + rotatedRel[2], 1f)
            val newLocal = FloatArray(4)
            Matrix.multiplyMV(newLocal, 0, invModel, 0, newWorld, 0)
            v.x = newLocal[0]
            v.y = newLocal[1]
            v.z = newLocal[2]
        }
    }

    /**
     * Rota libre (sin eje restringido) los vertices/aristas/caras seleccionados del objeto en
     * Edit Mode, alrededor del centro de la seleccion - mismo gesto y misma sensibilidad que
     * rotateSelectedObject (Object Mode: dx horizontal gira alrededor de Z mundo, dy vertical
     * alrededor de X mundo), pero con el pivote nuevo (ver selectionCenterWorld) en vez del
     * centro del objeto. Rotate restringido a un eje (anillos del gizmo) queda pendiente, mismo
     * criterio que Move (primero libre, despues restringido por eje).
     *
     * Devuelve false (y no hace nada) si no hay objeto en Edit Mode o no hay nada seleccionado.
     */
    fun rotateSelectedMeshElements(dxScreen: Float, dyScreen: Float): Boolean {
        val obj = editingObject() ?: return false
        val mesh = obj.editableMesh ?: return false
        val vertexIds = verticesAffectedBySelection(mesh)
        if (vertexIds.isEmpty()) return false

        rotateMeshVerticesAroundWorldAxis(obj, mesh, vertexIds, dxScreen * 0.5f, axisDirection('Z'))
        rotateMeshVerticesAroundWorldAxis(obj, mesh, vertexIds, dyScreen * 0.5f, axisDirection('X'))
        refreshDynamicGeometry(obj)
        return true
    }

    /**
     * Escala libre (sin eje restringido) los vertices/aristas/caras seleccionados del objeto en
     * Edit Mode, alrededor del centro de la seleccion (ver selectionCenterWorld) - mismo gesto y
     * misma sensibilidad que scaleSelectedObject (Object Mode: dyScreen negativo agranda, positivo
     * achica), pero escalando cada vertice individualmente respecto del pivote nuevo en vez de
     * multiplicar la matriz de forma del objeto entero.
     *
     * A diferencia de scaleSelectedObject (que clampea via clampUniformScaleFactor, pensado para
     * columnas de shapeMatrix), aca el factor se clampea con un limite simple y generoso
     * (SCALE_FACTOR_MIN/MAX) sobre la distancia de cada vertice al pivote - alcanza para evitar
     * que la seleccion colapse a un punto o escale a un tamano absurdo, sin la complejidad de medir
     * columnas de una matriz que aca no aplica (estamos moviendo puntos sueltos, no transformando
     * una forma via matriz).
     *
     * Devuelve false (y no hace nada) si no hay objeto en Edit Mode o no hay nada seleccionado.
     */
    /**
     * Rota los vertices/aristas/caras seleccionados del objeto en Edit Mode, restringido a un
     * solo eje (X/Y/Z) - se usa cuando el arrastre empezo tocando un anillo del gizmo (ver
     * MainActivity.onViewportDragStart, que llama a hitTestGizmoRotateAxis en ACTION_DOWN, mismo
     * hit-test que ya usa Object Mode). Mismo criterio que rotateSelectedObjectOnAxis para calcular
     * CUANTO rotar (proyeccion del arrastre sobre la tangente en pantalla del anillo, ver
     * computeScreenTangentForRadialDir), pero aplicando el angulo resultante con
     * rotateMeshVerticesAroundWorldAxis (vertice por vertice, alrededor del centro de la seleccion)
     * en vez de sobre la matriz acumulada del objeto entero.
     *
     * SIMPLIFICACION CONOCIDA (mismo TODO que moveSelectedMeshElementsOnAxis): el anillo se dibuja
     * y se toca en el ORIGEN DEL OBJETO, no en el centro real de la seleccion - solo afecta el
     * calculo de CUANTO se arrastro, no ALREDEDOR DE QUE se rota (eso ya lo maneja
     * rotateMeshVerticesAroundWorldAxis con selectionCenterWorld).
     *
     * Devuelve true si el arrastre fue consumido (haya rotado algo o no).
     */
    fun rotateSelectedMeshElementsOnAxis(dxScreen: Float, dyScreen: Float, axis: Char): Boolean {
        val obj = editingObject() ?: return true
        val mesh = obj.editableMesh ?: return true
        val vertexIds = verticesAffectedBySelection(mesh)
        if (vertexIds.isEmpty()) return true

        val center = floatArrayOf(obj.posX, obj.posY, obj.posZ)
        val radialDir = activeRotateCurrentDir ?: activeRotateStartDir ?: return true
        val axisDir = effectiveAxisDirection(axis, obj)
        val screenTangent = computeScreenTangentForRadialDir(center, axisDir, radialDir) ?: return true

        val delta = (dxScreen * screenTangent[0] + dyScreen * screenTangent[1]) * 0.5f
        rotateMeshVerticesAroundWorldAxis(obj, mesh, vertexIds, delta, axisDir)
        refreshDynamicGeometry(obj)
        return true
    }

    /**
     * Igual que scaleSelectedMeshElements pero restringido a un solo eje (X/Y/Z) - se usa cuando
     * el arrastre empezo tocando el cubito de un eje del gizmo de Scale en Edit Mode (ver
     * MainActivity.onViewportDragStart, que llama a hitTestGizmoScaleAxis en ACTION_DOWN, mismo
     * hit-test que ya usa Object Mode). A diferencia de scaleSelectedObjectOnAxis (que deforma
     * shapeMatrix), aca se escala cada vertice individualmente: se descompone su posicion relativa
     * al centro de la seleccion en (componente a lo largo del eje) + (resto), y solo la componente
     * a lo largo del eje se multiplica por el factor - el resto queda igual, dando un escalado
     * real de un solo eje sobre la geometria (no un shear).
     *
     * Devuelve false (y no hace nada) si no hay objeto en Edit Mode o no hay nada seleccionado.
     */
    fun scaleSelectedMeshElementsOnAxis(dxScreen: Float, dyScreen: Float, axis: Char): Boolean {
        val obj = editingObject() ?: return false
        val mesh = obj.editableMesh ?: return false
        val vertexIds = verticesAffectedBySelection(mesh)
        if (vertexIds.isEmpty()) return false
        val center = selectionCenterWorld(obj, mesh, vertexIds) ?: return false

        val dragAmount = projectDragOntoAxisScreenDir(dxScreen, dyScreen, axis, obj)
        val rawFactor = 1f + dragAmount * 0.005f
        val factor = rawFactor.coerceIn(SCALE_FACTOR_MIN, SCALE_FACTOR_MAX)
        val axisDir = effectiveAxisDirection(axis, obj)

        val model = objectModelMatrix(obj)
        val invModel = FloatArray(16)
        if (!Matrix.invertM(invModel, 0, model, 0)) return false

        for (v in mesh.vertices) {
            if (v.id !in vertexIds) continue
            val world = localVertexToWorld(model, v)
            val rel = floatArrayOf(world[0] - center[0], world[1] - center[1], world[2] - center[2])
            val proj = rel[0] * axisDir[0] + rel[1] * axisDir[1] + rel[2] * axisDir[2]
            val extra = (factor - 1f) * proj
            val newWorld = floatArrayOf(
                world[0] + axisDir[0] * extra,
                world[1] + axisDir[1] * extra,
                world[2] + axisDir[2] * extra,
                1f
            )
            val newLocal = FloatArray(4)
            Matrix.multiplyMV(newLocal, 0, invModel, 0, newWorld, 0)
            v.x = newLocal[0]
            v.y = newLocal[1]
            v.z = newLocal[2]
        }
        refreshDynamicGeometry(obj)
        return true
    }

    fun scaleSelectedMeshElements(dyScreen: Float): Boolean {
        val obj = editingObject() ?: return false
        val mesh = obj.editableMesh ?: return false
        val vertexIds = verticesAffectedBySelection(mesh)
        if (vertexIds.isEmpty()) return false
        val center = selectionCenterWorld(obj, mesh, vertexIds) ?: return false

        val rawFactor = 1f - dyScreen * 0.005f
        val factor = rawFactor.coerceIn(SCALE_FACTOR_MIN, SCALE_FACTOR_MAX)

        val model = objectModelMatrix(obj)
        val invModel = FloatArray(16)
        if (!Matrix.invertM(invModel, 0, model, 0)) return false

        for (v in mesh.vertices) {
            if (v.id !in vertexIds) continue
            val world = localVertexToWorld(model, v)
            val newWorld = floatArrayOf(
                center[0] + (world[0] - center[0]) * factor,
                center[1] + (world[1] - center[1]) * factor,
                center[2] + (world[2] - center[2]) * factor,
                1f
            )
            val newLocal = FloatArray(4)
            Matrix.multiplyMV(newLocal, 0, invModel, 0, newWorld, 0)
            v.x = newLocal[0]
            v.y = newLocal[1]
            v.z = newLocal[2]
        }
        refreshDynamicGeometry(obj)
        return true
    }

    /**
     * Modeling > Extrude Region (Fase 3 del plan de Edit Mode, ver charla con el supervisor):
     * extruye las caras actualmente seleccionadas - las duplica (nuevos vertices en la MISMA
     * posicion que los originales, arrancan superpuestos, listos para "tirar" con el Move que se
     * dispara automaticamente despues, ver MainActivity.onExtrudeRegionClicked - mismo flujo que
     * E seguido de G implicito en Blender), reasigna las caras seleccionadas para que usen los
     * vertices nuevos (son ellas las que quedan "paradas" en el aire, listas para moverse) y
     * cierra el hueco con caras laterales nuevas en cada arista de BORDE de la seleccion (arista
     * usada por exactamente UNA cara seleccionada - si la comparten dos caras seleccionadas, es
     * interna a la region y no necesita pared, ver edgeUsage).
     *
     * Al terminar, la seleccion queda SOLO sobre la geometria nueva (vertices duplicados, aristas
     * del "techo" y las caras extruidas) - vertices/aristas viejos y las paredes nuevas quedan sin
     * seleccionar, igual que en Blender. Devuelve false (sin hacer nada) si no hay objeto en Edit
     * Mode o no hay ninguna cara seleccionada - el llamador (MainActivity) usa esto para avisar
     * con un Toast, mismo criterio que deleteSelectedObject/duplicateSelectedObject.
     *
     * LIMITACION CONOCIDA (TODO): si la region seleccionada no tiene ningun borde abierto (por
     * ejemplo, seleccionar TODAS las caras de un objeto cerrado), los vertices originales de esa
     * region quedan sin ninguna cara/arista que los referencie (huerfanos, se ven como puntos
     * sueltos flotando en el wireframe) - caso raro en el uso tipico (extruir una sola cara o un
     * grupo chico con borde abierto), documentado para revisar mas adelante si hace falta.
     */
    /**
     * Modeling > Mesh > Delete (Fase 4 del plan de Edit Mode, ver charla con el supervisor):
     * borra la geometria actualmente seleccionada, segun el modo de sub-elemento activo (mode) -
     * mismo criterio que selectAllMeshElements/deselectAllMeshElements/invertMeshElementSelection
     * (el modo activo decide sobre que se opera).
     *
     * Regla base (Vertex mode): borrar un vertice se lleva puesto TODO lo que dependa de el - sus
     * aristas y sus caras, para no dejar geometria "colgada" apuntando a un vertice que ya no
     * existe (referencia rota). Edge mode sigue la misma logica un nivel mas arriba: borrar una
     * arista se lleva puesta cualquier cara que la use en su contorno (busca el par de vertices
     * consecutivo en el loop de cada cara, no solo si ambos vertices aparecen sueltos - una cara
     * puede tener los 2 vertices de una arista sin que esa arista sea parte de su borde). Face
     * mode es el mas simple: borra SOLO las caras, deja vertices y aristas como estan (mismo
     * comportamiento que "Delete Faces" en Blender - puede dejar geometria huerfana a proposito,
     * el usuario decide si tambien la quiere borrar).
     *
     * Devuelve false (sin hacer nada) si no hay objeto en Edit Mode o no hay nada seleccionado en
     * el modo activo - el llamador (MainActivity) usa esto para avisar con un Toast, mismo
     * criterio que deleteSelectedObject/duplicateSelectedObject.
     */
    fun deleteSelectedMeshElements(mode: EditSelectMode): Boolean {
        val obj = editingObject() ?: return false
        val mesh = obj.editableMesh ?: return false

        val removedVertexIds: Set<Int>
        val removedEdgeIds = mutableSetOf<Int>()
        val removedFaceIds = mutableSetOf<Int>()

        when (mode) {
            EditSelectMode.VERTEX -> {
                removedVertexIds = mesh.vertices.filter { it.selected }.map { it.id }.toSet()
                if (removedVertexIds.isEmpty()) return false
                for (e in mesh.edges) if (e.v1 in removedVertexIds || e.v2 in removedVertexIds) removedEdgeIds.add(e.id)
                for (f in mesh.faces) if (f.vertexIds.any { it in removedVertexIds }) removedFaceIds.add(f.id)
            }
            EditSelectMode.EDGE -> {
                val selectedEdges = mesh.edges.filter { it.selected }
                if (selectedEdges.isEmpty()) return false
                removedVertexIds = emptySet()
                removedEdgeIds.addAll(selectedEdges.map { it.id })
                for (f in mesh.faces) {
                    val ids = f.vertexIds
                    for (i in ids.indices) {
                        val a = ids[i]
                        val b = ids[(i + 1) % ids.size]
                        if (selectedEdges.any { (it.v1 == a && it.v2 == b) || (it.v1 == b && it.v2 == a) }) {
                            removedFaceIds.add(f.id)
                            break
                        }
                    }
                }
            }
            EditSelectMode.FACE -> {
                val selectedFaces = mesh.faces.filter { it.selected }
                if (selectedFaces.isEmpty()) return false
                removedVertexIds = emptySet()
                removedFaceIds.addAll(selectedFaces.map { it.id })
            }
        }

        pushUndoSnapshot()

        mesh.faces.removeAll { it.id in removedFaceIds }
        mesh.edges.removeAll { it.id in removedEdgeIds }
        mesh.vertices.removeAll { it.id in removedVertexIds }

        refreshDynamicGeometry(obj)
        return true
    }

    fun extrudeSelectedFaces(): Boolean {
        val obj = editingObject() ?: return false
        val mesh = obj.editableMesh ?: return false
        val selectedFaces = mesh.faces.filter { it.selected }
        if (selectedFaces.isEmpty()) return false

        pushUndoSnapshot()

        data class BoundaryEdge(val a: Int, val b: Int)
        val edgeUsage = mutableMapOf<Pair<Int, Int>, Int>()
        val firstOrder = mutableMapOf<Pair<Int, Int>, BoundaryEdge>()
        for (face in selectedFaces) {
            val ids = face.vertexIds
            for (i in ids.indices) {
                val a = ids[i]
                val b = ids[(i + 1) % ids.size]
                val key = if (a < b) a to b else b to a
                edgeUsage[key] = (edgeUsage[key] ?: 0) + 1
                firstOrder.getOrPut(key) { BoundaryEdge(a, b) }
            }
        }
        val boundaryEdges = edgeUsage.filterValues { it == 1 }.keys.map { firstOrder.getValue(it) }

        val regionVertexIds = selectedFaces.flatMap { it.vertexIds }.toSet()
        val vertexById = mesh.vertices.associateBy { it.id }
        var nextVertexId = (mesh.vertices.maxOfOrNull { it.id } ?: -1) + 1
        var nextEdgeId = (mesh.edges.maxOfOrNull { it.id } ?: -1) + 1
        var nextFaceId = (mesh.faces.maxOfOrNull { it.id } ?: -1) + 1

        for (v in mesh.vertices) v.selected = false
        for (e in mesh.edges) e.selected = false
        for (f in mesh.faces) f.selected = false

        val vertexRemap = mutableMapOf<Int, Int>()
        for (oldId in regionVertexIds) {
            val old = vertexById[oldId] ?: continue
            val newId = nextVertexId++
            mesh.vertices.add(MeshVertex(newId, old.x, old.y, old.z, selected = true))
            vertexRemap[oldId] = newId
        }

        for (boundary in boundaryEdges) {
            val aNew = vertexRemap[boundary.a] ?: continue
            val bNew = vertexRemap[boundary.b] ?: continue
            mesh.faces.add(MeshFace(nextFaceId++, listOf(boundary.a, boundary.b, bNew, aNew), selected = false))
        }

        val railCreated = mutableSetOf<Int>()
        for (boundary in boundaryEdges) {
            for (oldId in listOf(boundary.a, boundary.b)) {
                if (oldId in railCreated) continue
                val newId = vertexRemap[oldId] ?: continue
                mesh.edges.add(MeshEdge(nextEdgeId++, oldId, newId, selected = false))
                railCreated.add(oldId)
            }
        }

        val capEdgeCreated = mutableSetOf<Pair<Int, Int>>()
        for (face in selectedFaces) {
            val ids = face.vertexIds
            for (i in ids.indices) {
                val a = vertexRemap[ids[i]] ?: continue
                val b = vertexRemap[ids[(i + 1) % ids.size]] ?: continue
                val key = if (a < b) a to b else b to a
                if (key in capEdgeCreated) continue
                mesh.edges.add(MeshEdge(nextEdgeId++, a, b, selected = true))
                capEdgeCreated.add(key)
            }
        }

        for (face in selectedFaces) {
            val remappedIds = face.vertexIds.map { vertexRemap[it] ?: it }
            val index = mesh.faces.indexOfFirst { it.id == face.id }
            if (index != -1) {
                mesh.faces[index] = MeshFace(face.id, remappedIds, selected = true)
            }
        }

        refreshDynamicGeometry(obj)
        return true
    }

    fun convertSelectionOnModeChange(fromMode: EditSelectMode, toMode: EditSelectMode) {





        if (fromMode == toMode) return
        val mesh = editingObject()?.editableMesh ?: return

        val selectedVertexIds: Set<Int> = when (fromMode) {
            EditSelectMode.VERTEX -> mesh.vertices.filter { it.selected }.map { it.id }.toSet()
            EditSelectMode.EDGE -> mesh.edges.filter { it.selected }.flatMap { listOf(it.v1, it.v2) }.toSet()
            EditSelectMode.FACE -> mesh.faces.filter { it.selected }.flatMap { it.vertexIds }.toSet()
        }

        for (v in mesh.vertices) v.selected = false
        for (e in mesh.edges) e.selected = false
        for (f in mesh.faces) f.selected = false

        when (toMode) {
            EditSelectMode.VERTEX -> for (v in mesh.vertices) v.selected = v.id in selectedVertexIds
            EditSelectMode.EDGE -> for (e in mesh.edges) e.selected = e.v1 in selectedVertexIds && e.v2 in selectedVertexIds
            EditSelectMode.FACE -> for (f in mesh.faces) f.selected = f.vertexIds.isNotEmpty() && f.vertexIds.all { it in selectedVertexIds }
        }
    }

    /**
     * Interseccion rayo-caja axis-aligned (metodo slab), caja de medio-lado 0.5*largoColumna por
     * eje (columnLength generaliza los scaleX/Y/Z sueltos que tenia antes el modelo de datos - ver
     * SceneObject.shapeMatrix) centrada en (objX, objY, objZ). Ignora la rotacion del objeto (y
     * cualquier shear de shapeMatrix) a proposito (bounding box sin rotar, mas grande de lo justo
     * cuando el objeto esta rotado o deformado) - misma simplificacion deliberada que ya existia
     * con scaleX/Y/Z sueltos, suficiente para seleccionar por ahora; afinar esto requeriria un OBB
     * (oriented bounding box) o transformar el rayo al espacio local del objeto.
     */
    private fun intersectAABB(
        rayOrigin: FloatArray, rayDir: FloatArray,
        objX: Float, objY: Float, objZ: Float,
        shapeMatrix: FloatArray
    ): Float? {
        val halfExtent = floatArrayOf(
            0.5f * columnLength(shapeMatrix, 0),
            0.5f * columnLength(shapeMatrix, 1),
            0.5f * columnLength(shapeMatrix, 2)
        )
        val center = floatArrayOf(objX, objY, objZ)
        val minB = FloatArray(3) { center[it] - halfExtent[it] }
        val maxB = FloatArray(3) { center[it] + halfExtent[it] }
        var tMin = -Float.MAX_VALUE
        var tMax = Float.MAX_VALUE

        for (i in 0..2) {
            if (abs(rayDir[i]) < 1e-6f) {
                if (rayOrigin[i] < minB[i] || rayOrigin[i] > maxB[i]) return null
            } else {
                var t1 = (minB[i] - rayOrigin[i]) / rayDir[i]
                var t2 = (maxB[i] - rayOrigin[i]) / rayDir[i]
                if (t1 > t2) {
                    val tmp = t1
                    t1 = t2
                    t2 = tmp
                }
                tMin = maxOf(tMin, t1)
                tMax = minOf(tMax, t2)
                if (tMin > tMax) return null
            }
        }

        return if (tMax >= 0f) maxOf(tMin, 0f) else null
    }
}
