package com.meshcraft.app

import android.opengl.GLES20
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.cos
import kotlin.math.sin

/** Que geometria dibuja Gizmo3D.draw en este frame - una por herramienta con arrastre restringido a eje. */
enum class GizmoMode { MOVE, ROTATE }

/**
 * Gizmo de transformacion por eje, dibujado en espacio mundo centrado en el objeto seleccionado -
 * ver charla con el usuario. Move usa flechas (shaft + tip), Rotate usa anillos - ambos comparten
 * shaders, colores por eje y el criterio de tamano constante en pantalla (gizmoScreenScale en
 * MyGLRenderer). Scale todavia no tiene su geometria (queda para cuando se implemente su gizmo).
 *
 * Geometria por eje, en unidades locales (se escala y traslada en MyGLRenderer.onDrawFrame):
 * - Move: shaft (linea) + tip (piramide de base cuadrada) a lo largo de +X para el eje X; los
 *   ejes Y/Z se generan permutando que componente es "along" en pointOnAxis, en vez de repetir la
 *   definicion 3 veces. Con un eje agarrado (activeAxis != null en draw, ver
 *   MyGLRenderer.activeMoveAxis), se dibuja SOLO esa flecha (mas gruesa) y las otras 2
 *   desaparecen - mismo criterio visual que Rotate con un anillo agarrado (ver mas abajo), y
 *   MyGLRenderer de paso agrega la linea infinita + crucecita del pivote (drawInfiniteAxisLine /
 *   drawCenterCrosshair, mismas piezas que usa Rotate). Se logra sin geometria dinamica: como
 *   moveLineVertices/moveTriVertices ya se arman por eje en orden fijo (X, Y, Z - ver init),
 *   alcanza con dibujar la sub-region del buffer que le corresponde a ese eje via el offset
 *   "first" de glDrawArrays (MOVE_LINE_VERTS_PER_AXIS / MOVE_TRI_VERTS_PER_AXIS, companion object).
 * - Rotate: 3 anillos de color (uno por eje) + un 4to anillo blanco "trackball" mas grande,
 *   siempre de cara a la camara (billboard, armado en MyGLRenderer.onDrawFrame). De cada anillo
 *   de color solo se dibuja la mitad que queda de frente a la camara (ver
 *   updateRotateVisibleSegments) - la mitad de atras directamente NO se dibuja (no se oscurece,
 *   se omite), quedan como arcos abiertos - es lo que hace que se lea como una esfera en 3D en
 *   vez de 3 ovalos planos cruzados, igual que el gizmo real de Blender. Se probo primero
 *   oscureciendo esa mitad en vez de omitirla, pero contra el fondo oscuro de la app se veia
 *   directamente negra - omitirla del todo se ve mejor y es mas fiel a la referencia.
 *   Cuando se esta arrastrando un anillo (activeAxis != null en draw), ese comportamiento se
 *   reemplaza por updateRotateActiveAxisOnly: se ve solo ese anillo, completo y en SU PROPIO
 *   color de eje (no blanco - ver charla con el usuario, referencia visual real de Blender) -
 *   ver comentario de draw() mas abajo. Ademas, mientras se arrastra, MyGLRenderer.onDrawFrame
 *   pide tambien drawInfiniteAxisLine (linea que cruza toda la pantalla), drawStartAngleMarker
 *   (linea punteada del angulo de arranque) y drawCenterCrosshair (marca del pivote) - las 4
 *   piezas juntas replican el gizmo de rotacion agarrado de Blender.
 */
class Gizmo3D {

    /**
     * Unica fuente de verdad para las medidas del gizmo. MyGLRenderer (hitTestGizmoAxis,
     * hitTestGizmoRotateAxis) las referencia directamente en vez de repetir los numeros - antes
     * estaban duplicadas a mano entre dibujo y hit-test, con riesgo de desincronizarse en
     * silencio si alguien cambiaba una sin la otra.
     */
    companion object {
        const val SHAFT_LENGTH = 0.9f
        const val TIP_LENGTH = 0.25f
        const val TIP_HALF_WIDTH = 0.05f
        const val RING_RADIUS = 0.75f
        const val RING_SEGMENTS = 48
        /** Radio del anillo blanco (trackball), relativo a RING_RADIUS - un poco mas grande para que quede por fuera de los 3 de color, igual que en Blender. */
        const val TRACKBALL_RADIUS_SCALE = 1.15f
        /** Media longitud (unidades de mundo reales, NO escaladas por gizmoScreenScale) de la linea infinita del eje activo - ver drawInfiniteAxisLine. Elegido para cruzar la pantalla en cualquier zoom razonable sin salirse del far plane de la camara (30, ver MyGLRenderer.onDrawFrame). */
        const val AXIS_LINE_HALF_LENGTH = 15f
        /** Vertices (GL_LINES) que aporta cada eje al shaft de Move en moveLineVertices - ver addMoveAxisGeometry (1 segmento = 2 vertices). Junto con MOVE_TRI_VERTS_PER_AXIS, permite dibujar solo la flecha de un eje via el offset "first" de glDrawArrays, sin geometria dinamica. */
        const val MOVE_LINE_VERTS_PER_AXIS = 2
        /** Vertices (GL_TRIANGLES) que aporta cada eje al tip de Move en moveTriVertices - ver addMoveAxisGeometry (piramide de base cuadrada = 4 triangulos * 3 vertices). */
        const val MOVE_TRI_VERTS_PER_AXIS = 12
    }

    private val vertexShaderCode = """
        uniform mat4 uMVPMatrix;
        attribute vec4 vPosition;
        attribute vec4 vColor;
        varying vec4 fColor;
        void main() {
            gl_Position = uMVPMatrix * vPosition;
            fColor = vColor;
        }
    """.trimIndent()

    private val fragmentShaderCode = """
        precision mediump float;
        varying vec4 fColor;
        void main() {
            gl_FragColor = fColor;
        }
    """.trimIndent()

    // Mismos colores que ya usa GizmoView (el gizmo de ejes de camara) para X/Y/Z, asi los dos
    // gizmos de la app quedan visualmente consistentes.
    private data class AxisDef(val alongComponent: Int, val color: FloatArray)

    private val axisDefs = listOf(
        AxisDef(0, floatArrayOf(0.886f, 0.239f, 0.239f, 1f)), // X - rojo
        AxisDef(1, floatArrayOf(0.471f, 0.824f, 0.353f, 1f)), // Y - verde
        AxisDef(2, floatArrayOf(0.314f, 0.588f, 0.922f, 1f))  // Z - azul
    )

    // Color del anillo trackball (blanco/gris) - ver bloque de campos de abajo para su geometria.
    private val trackballColor = floatArrayOf(0.85f, 0.85f, 0.85f, 1f)
    // Color de la crucecita central (ver drawCenterCrosshair) - blanco puro, siempre visible
    // contra cualquier color de eje de fondo.
    private val crosshairColor = floatArrayOf(1f, 1f, 1f, 1f)

    // Geometria de Move: lineas (shaft) + triangulos (tip de la flecha). Se arman por eje en orden
    // fijo X, Y, Z (ver init, mismo orden que axisDefs) - eso es lo que permite despues dibujar
    // solo un eje via offset (ver MOVE_LINE_VERTS_PER_AXIS / MOVE_TRI_VERTS_PER_AXIS).
    private val moveLineVertices = mutableListOf<Float>()
    private val moveLineColors = mutableListOf<Float>()
    private val moveTriVertices = mutableListOf<Float>()
    private val moveTriColors = mutableListOf<Float>()

    // Geometria BASE de Rotate: los 3 anillos de color completos (48 segmentos c/u). De aca sale,
    // cada frame, el subconjunto de segmentos "de frente" (ver updateRotateVisibleSegments) - esta
    // lista completa nunca se dibuja directamente, es solo la fuente de datos.
    private val rotateLineVertices = mutableListOf<Float>()
    private val rotateLineColors = mutableListOf<Float>()
    /** alongComponent (0/1/2) de cada segmento de rotateLineVertices, mismo orden - se usa en updateRotateActiveAxisOnly para filtrar solo el eje que se esta arrastrando. */
    private val rotateSegmentAlongComponent = mutableListOf<Int>()

    /**
     * Geometria del anillo trackball: mismo radio "along=1" (plano XZ, de frente al eje Y - ver
     * comentario en el bloque de init sobre por que va en 1 y no en 2) pero un poco mas grande
     * (TRACKBALL_RADIUS_SCALE) y en su propio buffer, separado de los 3 de color - se dibuja
     * aparte (drawTrackballRing) con su propia matriz billboard armada en MyGLRenderer. Es solo un
     * indicador visual, sin su propio hit-test (la rotacion libre ya existe via
     * rotateSelectedObject cuando el arrastre no empieza sobre ninguno de los 3 anillos de color).
     * Se dibuja completo, sin recorte: al ser siempre de cara a la camara, no tiene "mitad de
     * atras" que ocultar.
     */
    private val trackballLineVertices = mutableListOf<Float>()
    private val trackballLineColors = mutableListOf<Float>()

    private val moveLineVertexBuffer: FloatBuffer
    private val moveLineColorBuffer: FloatBuffer
    private val moveLineVertexCount: Int
    private val moveTriVertexBuffer: FloatBuffer
    private val moveTriColorBuffer: FloatBuffer
    private val moveTriVertexCount: Int
    private val trackballLineVertexBuffer: FloatBuffer
    private val trackballLineColorBuffer: FloatBuffer
    private val trackballLineVertexCount: Int
    private val program: Int

    /**
     * Buffers DINAMICOS de los anillos de color: se reescriben cada frame en
     * updateRotateVisibleSegments (o updateRotateActiveAxisOnly, si hay eje agarrado) con solo los
     * segmentos que corresponde mostrar ese frame - por eso el tamano reservado es el maximo
     * posible (todos los segmentos de los 3 anillos) pero la cantidad realmente usada
     * (rotateLineVisibleVertexCount) varia cada frame segun cual de las dos funciones escribio.
     */
    private val rotateMaxVertexCount = RING_SEGMENTS * 2 * axisDefs.size
    private val rotateLineDynamicVertexArray = FloatArray(rotateMaxVertexCount * 3)
    private val rotateLineDynamicColorArray = FloatArray(rotateMaxVertexCount * 4)
    private val rotateLineDynamicVertexBuffer: FloatBuffer = makeFloatBuffer(rotateLineDynamicVertexArray)
    private val rotateLineDynamicColorBuffer: FloatBuffer = makeFloatBuffer(rotateLineDynamicColorArray)
    private var rotateLineVisibleVertexCount = 0

    init {
        for (def in axisDefs) {
            addMoveAxisGeometry(def.alongComponent, def.color)
            addRingGeometry(def.alongComponent, def.color, RING_RADIUS, rotateLineVertices, rotateLineColors)
            repeat(RING_SEGMENTS) { rotateSegmentAlongComponent.add(def.alongComponent) }
        }
        // Trackball: OJO ACA - a diferencia de los anillos de color, va en alongComponent=1
        // (normal = eje Y local), NO 2 (Z). La camara de esta app siempre mira fijo a lo largo de
        // +Y (lo que "orbita" es el contenido via rotationMatrix, no la camara en si - ver
        // computeWorldViewDirection en MyGLRenderer). Este anillo cancela esa rotacion para quedar
        // billboard, asi que su forma se ve SIEMPRE tal cual esta definida aca en local, sin
        // importar el angulo de orbita - por eso tiene que nacer de frente a Y (normal=Y). Con
        // alongComponent=2 (normal=Z) quedaba de canto siempre (el eje Y esta CONTENIDO en el
        // plano XY) - se veia como una linea chata en vez de un circulo, bug ya arreglado.
        addRingGeometry(1, trackballColor, RING_RADIUS * TRACKBALL_RADIUS_SCALE, trackballLineVertices, trackballLineColors)

        moveLineVertexBuffer = makeFloatBuffer(moveLineVertices.toFloatArray())
        moveLineColorBuffer = makeFloatBuffer(moveLineColors.toFloatArray())
        moveLineVertexCount = moveLineVertices.size / 3

        moveTriVertexBuffer = makeFloatBuffer(moveTriVertices.toFloatArray())
        moveTriColorBuffer = makeFloatBuffer(moveTriColors.toFloatArray())
        moveTriVertexCount = moveTriVertices.size / 3

        trackballLineVertexBuffer = makeFloatBuffer(trackballLineVertices.toFloatArray())
        trackballLineColorBuffer = makeFloatBuffer(trackballLineColors.toFloatArray())
        trackballLineVertexCount = trackballLineVertices.size / 3

        val vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, vertexShaderCode)
        val fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentShaderCode)
        program = GLES20.glCreateProgram().also {
            GLES20.glAttachShader(it, vertexShader)
            GLES20.glAttachShader(it, fragmentShader)
            GLES20.glLinkProgram(it)
        }
    }

    /**
     * Punto generico de geometria del gizmo. alongComponent (0=X, 1=Y, 2=Z) indica que indice del
     * vertice recibe la coordenada "along" (a lo largo del eje, usada por Move); p1/p2 reciben las
     * coordenadas perpendiculares (base de la piramide en Move, seno/coseno en Rotate), en orden
     * ciclico. Compartido por addMoveAxisGeometry y addRingGeometry para no repetir el mapeo.
     */
    private fun pointOnAxis(alongComponent: Int, along: Float, p1: Float, p2: Float): FloatArray {
        return when (alongComponent) {
            0 -> floatArrayOf(along, p1, p2)
            1 -> floatArrayOf(p2, along, p1)
            else -> floatArrayOf(p1, p2, along)
        }
    }

    /** Arma shaft + tip (flecha) para un eje de Move. */
    private fun addMoveAxisGeometry(alongComponent: Int, color: FloatArray) {
        moveLineVertices.addAll(pointOnAxis(alongComponent, 0f, 0f, 0f).toList())
        moveLineVertices.addAll(pointOnAxis(alongComponent, SHAFT_LENGTH, 0f, 0f).toList())
        repeat(2) { moveLineColors.addAll(color.toList()) }

        val apex = pointOnAxis(alongComponent, SHAFT_LENGTH + TIP_LENGTH, 0f, 0f)
        val base = listOf(
            pointOnAxis(alongComponent, SHAFT_LENGTH, TIP_HALF_WIDTH, TIP_HALF_WIDTH),
            pointOnAxis(alongComponent, SHAFT_LENGTH, TIP_HALF_WIDTH, -TIP_HALF_WIDTH),
            pointOnAxis(alongComponent, SHAFT_LENGTH, -TIP_HALF_WIDTH, -TIP_HALF_WIDTH),
            pointOnAxis(alongComponent, SHAFT_LENGTH, -TIP_HALF_WIDTH, TIP_HALF_WIDTH)
        )
        for (i in base.indices) {
            val b1 = base[i]
            val b2 = base[(i + 1) % base.size]
            moveTriVertices.addAll(apex.toList())
            moveTriVertices.addAll(b1.toList())
            moveTriVertices.addAll(b2.toList())
            repeat(3) { moveTriColors.addAll(color.toList()) }
        }
    }

    /**
     * Arma un anillo (circulo aproximado por RING_SEGMENTS segmentos GL_LINES) en el plano
     * perpendicular a alongComponent - p.ej. para el eje Z (alongComponent=2), el anillo vive en
     * el plano XY (along=0 fijo, p1/p2 parametrizados por coseno/seno del radio). Recibe radius y
     * las listas destino como parametros para poder reusarla tanto en los 3 anillos de color
     * (radius=RING_RADIUS) como en el anillo trackball (radius mas grande, buffer separado).
     */
    private fun addRingGeometry(
        alongComponent: Int, color: FloatArray, radius: Float,
        targetVertices: MutableList<Float>, targetColors: MutableList<Float>
    ) {
        for (i in 0 until RING_SEGMENTS) {
            val theta0 = (i.toFloat() / RING_SEGMENTS) * (2.0 * Math.PI).toFloat()
            val theta1 = ((i + 1).toFloat() / RING_SEGMENTS) * (2.0 * Math.PI).toFloat()
            val p0 = pointOnAxis(alongComponent, 0f, radius * cos(theta0), radius * sin(theta0))
            val p1 = pointOnAxis(alongComponent, 0f, radius * cos(theta1), radius * sin(theta1))
            targetVertices.addAll(p0.toList())
            targetVertices.addAll(p1.toList())
            repeat(2) { targetColors.addAll(color.toList()) }
        }
    }

    /**
     * Recorre los segmentos base de los 3 anillos de color (rotateLineVertices, en pares
     * consecutivos: vertice 2i / 2i+1 = extremos del segmento i) y arma rotateLineDynamicVertexArray
     * / rotateLineDynamicColorArray SOLO con los segmentos que quedan de frente a la camara -
     * omite (no dibuja) los de atras, en vez de oscurecerlos, porque oscurecidos contra el fondo
     * oscuro de la app se veian directamente negros.
     *
     * localViewDir es la direccion a la que mira la camara, expresada en el espacio local SIN
     * ROTAR del gizmo (ver MyGLRenderer.computeWorldViewDirection, que ya hace exactamente esa
     * conversion deshaciendo rotationMatrix - los anillos de color se transforman por
     * rotationMatrix igual que el resto de la escena, asi que hay que comparar en ese mismo
     * espacio "pre-rotacion" para que el resultado quede prendido al angulo real). Un segmento
     * queda "de frente" (se dibuja) si el punto medio de sus dos extremos se opone a hacia donde
     * mira la camara (dot < 0, es decir, apunta hacia el espectador).
     */
    private fun updateRotateVisibleSegments(localViewDir: FloatArray) {
        var writeVertexIndex = 0 // indice de vertice (no de float) ya escrito en los arrays dinamicos
        val segmentCount = rotateLineVertices.size / 6 // 6 floats = 2 vertices * 3 componentes por segmento

        for (seg in 0 until segmentCount) {
            val base = seg * 6
            val x0 = rotateLineVertices[base]; val y0 = rotateLineVertices[base + 1]; val z0 = rotateLineVertices[base + 2]
            val x1 = rotateLineVertices[base + 3]; val y1 = rotateLineVertices[base + 4]; val z1 = rotateLineVertices[base + 5]

            val midX = (x0 + x1) * 0.5f
            val midY = (y0 + y1) * 0.5f
            val midZ = (z0 + z1) * 0.5f
            val dot = midX * localViewDir[0] + midY * localViewDir[1] + midZ * localViewDir[2]
            if (dot >= 0f) continue // de espaldas a la camara - se omite del todo, no se dibuja

            val vBase = writeVertexIndex * 3
            rotateLineDynamicVertexArray[vBase] = x0
            rotateLineDynamicVertexArray[vBase + 1] = y0
            rotateLineDynamicVertexArray[vBase + 2] = z0
            rotateLineDynamicVertexArray[vBase + 3] = x1
            rotateLineDynamicVertexArray[vBase + 4] = y1
            rotateLineDynamicVertexArray[vBase + 5] = z1

            val cBaseSrc = seg * 8 // 8 floats = 2 vertices * 4 componentes de color, en rotateLineColors
            val cBaseDst = writeVertexIndex * 4
            for (k in 0 until 8) {
                rotateLineDynamicColorArray[cBaseDst + k] = rotateLineColors[cBaseSrc + k]
            }

            writeVertexIndex += 2
        }

        rotateLineVisibleVertexCount = writeVertexIndex
        rotateLineDynamicVertexBuffer.position(0)
        rotateLineDynamicVertexBuffer.put(rotateLineDynamicVertexArray, 0, writeVertexIndex * 3)
        rotateLineDynamicVertexBuffer.position(0)
        rotateLineDynamicColorBuffer.position(0)
        rotateLineDynamicColorBuffer.put(rotateLineDynamicColorArray, 0, writeVertexIndex * 4)
        rotateLineDynamicColorBuffer.position(0)
    }

    /** 'X'/'Y'/'Z' -> alongComponent (0/1/2), mismo mapeo que pointOnAxis. Usado por draw() para activeAxis y por colorForAxis. */
    private fun charToAlongComponent(axis: Char): Int = when (axis) {
        'X' -> 0
        'Y' -> 1
        else -> 2
    }

    /** Color propio de un eje (mismo que usan su anillo/flecha) - usado por MyGLRenderer para pintar la linea infinita y la marca de angulo de arranque con el color del eje activo, en vez de blanco (ver charla con el usuario, referencia visual real de Blender). */
    fun colorForAxis(axis: Char): FloatArray = axisDefs[charToAlongComponent(axis)].color

    /**
     * Version "agarrado" del anillo de rotacion: en vez de los 3 anillos como arcos de color
     * (updateRotateVisibleSegments), dibuja SOLO el eje activo (el que se esta arrastrando - ver
     * MainActivity.onViewportDragStart/axisLocked), como circulo COMPLETO (sin recortar por
     * camara) en SU PROPIO color de eje (no blanco - ver charla con el usuario) - mismo criterio
     * visual que usa Blender: al agarrar un eje, ese anillo se vuelve circulo completo y el resto
     * del gizmo (los otros 2 anillos + el trackball, este ultimo oculto desde MyGLRenderer)
     * desaparece, para dejar clarisimo cual eje se esta rotando.
     */
    private fun updateRotateActiveAxisOnly(alongComponent: Int) {
        val color = axisDefs[alongComponent].color
        var writeVertexIndex = 0
        val segmentCount = rotateLineVertices.size / 6

        for (seg in 0 until segmentCount) {
            if (rotateSegmentAlongComponent[seg] != alongComponent) continue
            val base = seg * 6

            val vBase = writeVertexIndex * 3
            rotateLineDynamicVertexArray[vBase] = rotateLineVertices[base]
            rotateLineDynamicVertexArray[vBase + 1] = rotateLineVertices[base + 1]
            rotateLineDynamicVertexArray[vBase + 2] = rotateLineVertices[base + 2]
            rotateLineDynamicVertexArray[vBase + 3] = rotateLineVertices[base + 3]
            rotateLineDynamicVertexArray[vBase + 4] = rotateLineVertices[base + 4]
            rotateLineDynamicVertexArray[vBase + 5] = rotateLineVertices[base + 5]

            val cBase = writeVertexIndex * 4
            for (v in 0 until 2) {
                for (c in 0 until 4) {
                    rotateLineDynamicColorArray[cBase + v * 4 + c] = color[c]
                }
            }

            writeVertexIndex += 2
        }

        rotateLineVisibleVertexCount = writeVertexIndex
        rotateLineDynamicVertexBuffer.position(0)
        rotateLineDynamicVertexBuffer.put(rotateLineDynamicVertexArray, 0, writeVertexIndex * 3)
        rotateLineDynamicVertexBuffer.position(0)
        rotateLineDynamicColorBuffer.position(0)
        rotateLineDynamicColorBuffer.put(rotateLineDynamicColorArray, 0, writeVertexIndex * 4)
        rotateLineDynamicColorBuffer.position(0)
    }

    /**
     * Se dibuja sin depth test: el gizmo siempre queda visible por encima del objeto (mismo
     * criterio simple que Blender usa por defecto). Si mas adelante se quiere que el objeto lo
     * pueda tapar parcialmente, este es el lugar para volver a habilitarlo.
     *
     * localViewDir es necesario para GizmoMode.ROTATE sin eje activo (ver
     * updateRotateVisibleSegments) - viene de MyGLRenderer.computeWorldViewDirection(). Para MOVE
     * no se usa, se puede pasar null. activeAxis (X/Y/Z) es el eje que se esta arrastrando ahora
     * mismo (ver MainActivity.axisLocked, sincronizado a MyGLRenderer.activeRotateAxis /
     * activeMoveAxis segun el modo) - si no es null, en ROTATE se ignora localViewDir y se dibuja
     * solo ese anillo, completo, en su color de eje (ver updateRotateActiveAxisOnly); en MOVE se
     * dibuja solo esa flecha, mas gruesa (ver MOVE_LINE_VERTS_PER_AXIS/MOVE_TRI_VERTS_PER_AXIS).
     * Si activeAxis es null y (en ROTATE) localViewDir tambien, no se dibuja nada ese frame en vez
     * de mostrar los 3 anillos sin filtrar.
     */
    fun draw(mvpMatrix: FloatArray, mode: GizmoMode, localViewDir: FloatArray? = null, activeAxis: Char? = null) {
        GLES20.glUseProgram(program)

        val posHandle = GLES20.glGetAttribLocation(program, "vPosition")
        val colorHandle = GLES20.glGetAttribLocation(program, "vColor")
        val mvpHandle = GLES20.glGetUniformLocation(program, "uMVPMatrix")
        GLES20.glUniformMatrix4fv(mvpHandle, 1, false, mvpMatrix, 0)

        GLES20.glDisable(GLES20.GL_DEPTH_TEST)

        GLES20.glEnableVertexAttribArray(posHandle)
        GLES20.glEnableVertexAttribArray(colorHandle)

        when (mode) {
            GizmoMode.MOVE -> {
                // Con eje activo (arrastre en curso): solo esa flecha, mas gruesa para que
                // resalte - mismo criterio que Rotate con un anillo agarrado. Sin eje activo:
                // las 3 flechas completas, como siempre. No hace falta geometria dinamica: los 2
                // buffers ya estan armados por eje en orden fijo X/Y/Z (ver init), asi que alcanza
                // con dibujar la sub-region de cada uno via el offset "first" de glDrawArrays.
                val lineFirst: Int
                val lineCount: Int
                val triFirst: Int
                val triCount: Int
                if (activeAxis != null) {
                    val axisIndex = charToAlongComponent(activeAxis)
                    lineFirst = axisIndex * MOVE_LINE_VERTS_PER_AXIS
                    lineCount = MOVE_LINE_VERTS_PER_AXIS
                    triFirst = axisIndex * MOVE_TRI_VERTS_PER_AXIS
                    triCount = MOVE_TRI_VERTS_PER_AXIS
                    GLES20.glLineWidth(6f)
                } else {
                    lineFirst = 0
                    lineCount = moveLineVertexCount
                    triFirst = 0
                    triCount = moveTriVertexCount
                    GLES20.glLineWidth(4f)
                }

                GLES20.glVertexAttribPointer(posHandle, 3, GLES20.GL_FLOAT, false, 0, moveLineVertexBuffer)
                GLES20.glVertexAttribPointer(colorHandle, 4, GLES20.GL_FLOAT, false, 0, moveLineColorBuffer)
                GLES20.glDrawArrays(GLES20.GL_LINES, lineFirst, lineCount)

                GLES20.glVertexAttribPointer(posHandle, 3, GLES20.GL_FLOAT, false, 0, moveTriVertexBuffer)
                GLES20.glVertexAttribPointer(colorHandle, 4, GLES20.GL_FLOAT, false, 0, moveTriColorBuffer)
                GLES20.glDrawArrays(GLES20.GL_TRIANGLES, triFirst, triCount)
            }
            GizmoMode.ROTATE -> {
                // Con eje activo (arrastre en curso): solo ese anillo, completo y en su color de
                // eje, mas grueso para que resalte. Sin eje activo: comportamiento normal, arcos
                // de color segun de que lado de la camara queda cada segmento.
                if (activeAxis != null) {
                    updateRotateActiveAxisOnly(charToAlongComponent(activeAxis))
                    GLES20.glLineWidth(6f)
                } else if (localViewDir != null) {
                    updateRotateVisibleSegments(localViewDir)
                    GLES20.glLineWidth(4f)
                } else {
                    rotateLineVisibleVertexCount = 0
                }
                if (rotateLineVisibleVertexCount > 0) {
                    GLES20.glVertexAttribPointer(posHandle, 3, GLES20.GL_FLOAT, false, 0, rotateLineDynamicVertexBuffer)
                    GLES20.glVertexAttribPointer(colorHandle, 4, GLES20.GL_FLOAT, false, 0, rotateLineDynamicColorBuffer)
                    GLES20.glDrawArrays(GLES20.GL_LINES, 0, rotateLineVisibleVertexCount)
                }
            }
        }

        GLES20.glDisableVertexAttribArray(posHandle)
        GLES20.glDisableVertexAttribArray(colorHandle)

        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
    }

    /**
     * Dibuja el anillo trackball (blanco/gris). Se llama aparte de draw() porque necesita su
     * propia matriz "billboard" (armada en MyGLRenderer.onDrawFrame cancelando la rotacion de
     * orbita), a diferencia de los anillos de color que si tienen que rotar con la escena. Linea
     * mas fina que los anillos de color (2f) para que se lea como una referencia secundaria, no
     * como un cuarto eje - mismo criterio visual que usa Blender para su anillo de rotacion libre.
     */
    fun drawTrackballRing(mvpMatrix: FloatArray) {
        drawLines(mvpMatrix, trackballLineVertexBuffer, trackballLineColorBuffer, trackballLineVertexCount, 2f)
    }

    /**
     * Linea que cruza toda la pantalla a lo largo del eje activo, centrada en el objeto -
     * referencia visual de Blender al mover o rotar con un eje restringido, para ver la
     * orientacion del eje mas alla del tamano chico del gizmo. mvpMatrix debe traer ya la
     * traslacion al objeto aplicada pero SIN el escalado de gizmoScreenScale (ver
     * MyGLRenderer.onDrawFrame) - la longitud se maneja aca en unidades de mundo reales
     * (AXIS_LINE_HALF_LENGTH), no en las unidades locales chicas del resto del gizmo.
     */
    fun drawInfiniteAxisLine(mvpMatrix: FloatArray, axisDir: FloatArray, color: FloatArray) {
        val half = AXIS_LINE_HALF_LENGTH
        val vertices = floatArrayOf(
            -axisDir[0] * half, -axisDir[1] * half, -axisDir[2] * half,
            axisDir[0] * half, axisDir[1] * half, axisDir[2] * half
        )
        val colors = FloatArray(8)
        for (v in 0 until 2) {
            for (c in 0 until 4) colors[v * 4 + c] = color[c]
        }
        drawLines(mvpMatrix, makeFloatBuffer(vertices), makeFloatBuffer(colors), 2, 2f)
    }

    /**
     * Linea punteada desde el centro del objeto hasta donde esta el dedo AHORA - sigue al dedo en
     * vivo mientras se arrastra, igual que el gizmo real de Blender (ver video de referencia del
     * usuario; localDir es MyGLRenderer.activeRotateCurrentDir, recalculada en cada ACTION_MOVE
     * via updateActiveRotateCurrentDir). Antes usaba una direccion fija congelada al empezar el
     * gesto (activeRotateStartDir) - simplificacion respecto de Blender real, ya corregida.




     * localDir ya viene en el mismo espacio local que la geometria del anillo (ver
     * MyGLRenderer.hitTestGizmoRotateAxis - es una direccion mundo directa, sin transformar, ya
     * que el gizmo no lleva rotacion propia, ver comentario de clase).
     */
    fun drawLiveAngleMarker(mvpMatrix: FloatArray, localDir: FloatArray, color: FloatArray) {
        val dashCount = 10
        val vertices = mutableListOf<Float>()
        val colors = mutableListOf<Float>()
        var i = 0
        while (i < dashCount) {
            // Dibuja de a pares: el primero del par es un "dash" visible, el segundo es el hueco
            // (no se agrega geometria para el hueco, solo se salta ese tramo del parametro t).
            val t0 = i.toFloat() / dashCount
            val t1 = (i + 1).toFloat() / dashCount
            vertices.addAll(listOf(localDir[0] * RING_RADIUS * t0, localDir[1] * RING_RADIUS * t0, localDir[2] * RING_RADIUS * t0))
            vertices.addAll(listOf(localDir[0] * RING_RADIUS * t1, localDir[1] * RING_RADIUS * t1, localDir[2] * RING_RADIUS * t1))
            repeat(2) { colors.addAll(color.toList()) }
            i += 2
        }
        val vArr = vertices.toFloatArray()
        drawLines(mvpMatrix, makeFloatBuffer(vArr), makeFloatBuffer(colors.toFloatArray()), vArr.size / 3, 3f)
    }

    /**
     * Crucecita chica de 3 ejes en el centro del objeto (pivote de rotacion o de movimiento) - se
     * dibuja con el mismo mvpMatrix ya escalado del gizmo (gizmoScreenScale), asi que su tamano
     * queda proporcional al resto del gizmo. Se usan los 3 ejes (no solo 2, billboard) para evitar
     * el caso degenerado de verse como una linea chata si la camara queda alineada con uno de
     * ellos - mas simple que armar una matriz billboard aparte solo para esto.
     */
    fun drawCenterCrosshair(mvpMatrix: FloatArray) {
        val s = 0.08f
        val vertices = floatArrayOf(
            -s, 0f, 0f, s, 0f, 0f,
            0f, -s, 0f, 0f, s, 0f,
            0f, 0f, -s, 0f, 0f, s
        )
        val colors = FloatArray(6 * 4)
        for (v in 0 until 6) {
            for (c in 0 until 4) colors[v * 4 + c] = crosshairColor[c]
        }
        drawLines(mvpMatrix, makeFloatBuffer(vertices), makeFloatBuffer(colors), 6, 3f)
    }

    /** Helper compartido: sube un par de buffers vertex/color al shader y dibuja como GL_LINES. Usado por drawTrackballRing y las piezas extra del eje activo (linea infinita, marca de angulo, crosshair) en Move y Rotate. */
    private fun drawLines(mvpMatrix: FloatArray, vertexBuffer: FloatBuffer, colorBuffer: FloatBuffer, vertexCount: Int, lineWidth: Float) {
        GLES20.glUseProgram(program)

        val posHandle = GLES20.glGetAttribLocation(program, "vPosition")
        val colorHandle = GLES20.glGetAttribLocation(program, "vColor")
        val mvpHandle = GLES20.glGetUniformLocation(program, "uMVPMatrix")
        GLES20.glUniformMatrix4fv(mvpHandle, 1, false, mvpMatrix, 0)

        GLES20.glDisable(GLES20.GL_DEPTH_TEST)

        GLES20.glEnableVertexAttribArray(posHandle)
        GLES20.glEnableVertexAttribArray(colorHandle)
        GLES20.glLineWidth(lineWidth)

        GLES20.glVertexAttribPointer(posHandle, 3, GLES20.GL_FLOAT, false, 0, vertexBuffer)
        GLES20.glVertexAttribPointer(colorHandle, 4, GLES20.GL_FLOAT, false, 0, colorBuffer)
        GLES20.glDrawArrays(GLES20.GL_LINES, 0, vertexCount)

        GLES20.glDisableVertexAttribArray(posHandle)
        GLES20.glDisableVertexAttribArray(colorHandle)

        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
    }

    private fun makeFloatBuffer(data: FloatArray): FloatBuffer {
        return ByteBuffer.allocateDirect(data.size * 4).run {
            order(ByteOrder.nativeOrder())
            asFloatBuffer().apply {
                put(data)
                position(0)
            }
        }
    }

    private fun loadShader(type: Int, shaderCode: String): Int {
        return GLES20.glCreateShader(type).also { shader ->
            GLES20.glShaderSource(shader, shaderCode)
            GLES20.glCompileShader(shader)
        }
    }
}
