package com.meshcraft.app

import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.abs
import kotlin.math.sqrt

class MyGLRenderer : GLSurfaceView.Renderer {

    private lateinit var cubeGeometry: Cube
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
     * Que gizmo de transformacion por eje se dibuja sobre el objeto seleccionado - null si ninguno.
     * MainActivity lo setea segun la herramienta activa (ver setLayoutTool): Move -> GizmoMode.MOVE,
     * Rotate -> GizmoMode.ROTATE. Scale todavia no tiene su version restringida a eje (ver charla
     * con el usuario).
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
     * (hitTestGizmoAxis, hitTestGizmoRotateAxis) - si diverge, el gizmo se ve en un lugar y se toca
     * en otro.
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
        for (obj in sceneObjects) obj.selected = false
        val newObject = SceneObject(id = nextObjectId++, selected = true)
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

        val axisDir = axisDirection(axis)
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
     * Rota el objeto seleccionado libre (sin eje restringido), con la misma convencion que la
     * orbita de camara: dx horizontal gira alrededor del eje Z del mundo, dy vertical gira
     * alrededor del eje X del mundo. Ambos deltas se aplican sobre la matriz de rotacion
     * acumulada del objeto (ver applyWorldRotationDelta) en vez de sumarse a angulos sueltos -
     * asi cada rotacion nueva se compone sobre el estado real actual, sin el bug de orden fijo que
     * tenia el esquema anterior de Euler (rotX/rotY/rotZ, ver charla con el usuario). Para rotacion
     * restringida a un solo eje (gizmo de anillos) ver rotateSelectedObjectOnAxis.
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
     * matriz de rotacion acumulada del objeto (ver SceneObject.rotationMatrix), pre-multiplicando
     * la matriz delta sobre la actual (delta * actual, no actual * delta). Ese orden es lo que
     * garantiza que el eje sea siempre el eje MUNDO real (X/Y/Z absolutos), sin importar como este
     * orientado el objeto en este momento - mismo criterio "Global" que ya usa el gizmo de rotacion
     * (ver comentario de gizmoMode en onDrawFrame). Reemplaza el viejo esquema de 3 angulos de
     * Euler sueltos recombinados cada frame en un orden fijo (Rz*Rx*Ry) - ese orden fijo era el bug
     * reportado y confirmado con el usuario: rotar en un eje "pisaba" visualmente lo ya rotado en
     * otro, porque el resultado final dependia del orden de composicion y no del orden real en que
     * se toco cada eje. Se llama una vez por eje tocado, tanto desde el gesto libre
     * (rotateSelectedObject, dos ejes por frame) como desde el gizmo de anillos
     * (rotateSelectedObjectOnAxis, un eje por frame).
     */
    private fun applyWorldRotationDelta(obj: SceneObject, angleDeg: Float, axis: Char) {
        if (angleDeg == 0f) return
        val dir = axisDirection(axis)
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
     * tiene una convencion fija de antes) tambien funcione, sin tener que casear por eje. El delta
     * resultante se aplica con applyWorldRotationDelta, igual que el gesto libre.
     * Devuelve true si el arrastre fue consumido (haya rotado algo o no - p.ej. si el eje quedo de
     * canto respecto de la camara, caso degenerado sin tangente definida).
     */
    fun rotateSelectedObjectOnAxis(dxScreen: Float, dyScreen: Float, axis: Char): Boolean {
        val selected = sceneObjects.firstOrNull { it.selected } ?: return true
        val center = floatArrayOf(selected.posX, selected.posY, selected.posZ)
        val radialDir = activeRotateCurrentDir ?: activeRotateStartDir ?: return true
        val screenTangent = computeScreenTangentForRadialDir(center, axisDirection(axis), radialDir) ?: return true

        // Misma sensibilidad que el rotate libre (dx/dy * 0.5).
        val delta = (dxScreen * screenTangent[0] + dyScreen * screenTangent[1]) * 0.5f
        applyWorldRotationDelta(selected, delta, axis)
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
     * Escala el objeto seleccionado libre (sin eje restringido): arrastre vertical (dyScreen)
     * cambia la escala uniforme - arriba (dy negativo) agranda, abajo achica. Clampeada entre
     * 0.1 y 10 para que no desaparezca ni crezca sin limite.
     * Devuelve false (y no hace nada) si no hay ningun objeto seleccionado.
     */
    fun scaleSelectedObject(dyScreen: Float): Boolean {
        val selected = sceneObjects.firstOrNull { it.selected } ?: return false
        val scaleFactor = 1f - dyScreen * 0.005f
        selected.scale = (selected.scale * scaleFactor).coerceIn(0.1f, 10f)
        return true
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
        // traslacion, rotacion acumulada (obj.rotationMatrix, ver SceneObject) y escala uniforme -
        // en ese orden, escala primero para que quede local al objeto antes de rotar/trasladar).
        val translateMatrix = FloatArray(16)
        val modelMatrix = FloatArray(16)
        val objMvpMatrix = FloatArray(16)
        for (obj in sceneObjects) {
            Matrix.setIdentityM(translateMatrix, 0)
            Matrix.translateM(translateMatrix, 0, obj.posX, obj.posY, obj.posZ)
            Matrix.multiplyMM(modelMatrix, 0, translateMatrix, 0, obj.rotationMatrix, 0)
            Matrix.scaleM(modelMatrix, 0, obj.scale, obj.scale, obj.scale)
            Matrix.multiplyMM(objMvpMatrix, 0, mvpMatrix, 0, modelMatrix, 0)
            cubeGeometry.draw(objMvpMatrix, obj.selected)
        }

        // Gizmo de transformacion sobre el objeto seleccionado: se dibuja SIN la rotacion/escala
        // del objeto (siempre alineado a los ejes del mundo, "Global" orientation - igual que el
        // default de Blender), solo trasladado a su posicion y escalado a tamano de pantalla
        // constante. gizmoMode decide si se dibujan flechas (Move) o anillos (Rotate) - ver
        // MainActivity.setLayoutTool. activeRotateAxis/activeMoveAxis (sincronizados desde
        // MainActivity.axisLocked segun la herramienta activa) deciden, dentro de cada modo, si se
        // resalta un solo eje agarrado o los 3 en su modo normal.
        val mode = gizmoMode
        if (mode != null) {
            val selectedObj = sceneObjects.firstOrNull { it.selected }
            if (selectedObj != null) {
                val gizmoModel = FloatArray(16)
                Matrix.setIdentityM(gizmoModel, 0)
                Matrix.translateM(gizmoModel, 0, selectedObj.posX, selectedObj.posY, selectedObj.posZ)
                Matrix.scaleM(gizmoModel, 0, gizmoScreenScale, gizmoScreenScale, gizmoScreenScale)
                val gizmoMvpMatrix = FloatArray(16)
                Matrix.multiplyMM(gizmoMvpMatrix, 0, mvpMatrix, 0, gizmoModel, 0)
                val activeAxisForMode = when (mode) {
                    GizmoMode.ROTATE -> activeRotateAxis
                    GizmoMode.MOVE -> activeMoveAxis
                }
                gizmo.draw(
                    gizmoMvpMatrix,
                    mode,
                    if (mode == GizmoMode.ROTATE) computeWorldViewDirection() else null,
                    activeAxisForMode
                )

                // Anillo trackball (blanco, solo en Rotate y solo sin eje activo - con un anillo
                // agarrado el trackball se oculta, igual que Blender oculta el resto del gizmo
                // cuando estas arrastrando un eje puntual): billboard, siempre de cara a la camara
                // sin importar la orbita - se logra multiplicando por la inversa de rotationMatrix
                // (transpuesta, al ser una rotacion pura) antes de escalar. Representa el gesto de
                // rotacion libre, que ya funciona via rotateSelectedObject.
                if (mode == GizmoMode.ROTATE && activeRotateAxis == null) {
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
                    gizmo.drawInfiniteAxisLine(lineMvpMatrix, axisDirection(axisChar), axisColor)

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
                    gizmo.drawInfiniteAxisLine(lineMvpMatrix, axisDirection(axisChar), axisColor)

                    gizmo.drawCenterCrosshair(gizmoMvpMatrix)
                }
            }
        }
    }

    /**
     * Convierte un punto de pantalla (coordenadas de vista, no NDC) en un rayo 3D (origen +
     * direccion), usando la matriz camara+orbita del ultimo frame dibujado (scratch). Compartido
     * por selectObjectAt (seleccion de objetos) y los hit-test del gizmo (hitTestGizmoAxis,
     * hitTestGizmoRotateAxis).
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
     * Convierte un tap en pantalla en un rayo 3D y selecciona el objeto mas cercano que
     * intersecta. Si no hay hit, deselecciona todo (igual que tocar espacio vacio en Blender).
     */
    fun selectObjectAt(screenX: Float, screenY: Float) {
        val (rayOrigin, rayDir) = screenPointToRay(screenX, screenY) ?: return

        var hitObject: SceneObject? = null
        var closestT = Float.MAX_VALUE
        for (obj in sceneObjects) {
            val t = intersectAABB(rayOrigin, rayDir, obj.posX, obj.posY, obj.posZ, obj.scale)
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
            val dist = closestDistanceRayToSegment(rayOrigin, rayDir, segStart, axisDirection(axisChar), axisLength)
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
            val normal = axisDirection(axisChar)
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
        val normal = axisDirection(axis)
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
     * Interseccion rayo-caja axis-aligned (metodo slab), caja de medio-lado 0.5*scale centrada
     * en (objX, objY, objZ). Ignora la rotacion del objeto a proposito (bounding box sin rotar,
     * mas grande de lo justo cuando el objeto esta rotado) - suficiente para seleccionar por
     * ahora, afinar esto requeriria un OBB (oriented bounding box) o transformar el rayo al
     * espacio local del objeto.
     */
    private fun intersectAABB(rayOrigin: FloatArray, rayDir: FloatArray, objX: Float, objY: Float, objZ: Float, scale: Float): Float? {
        val halfExtent = 0.5f * scale
        val minB = floatArrayOf(objX - halfExtent, objY - halfExtent, objZ - halfExtent)
        val maxB = floatArrayOf(objX + halfExtent, objY + halfExtent, objZ + halfExtent)
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
