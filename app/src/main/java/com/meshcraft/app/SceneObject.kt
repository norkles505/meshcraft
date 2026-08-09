package com.meshcraft.app

/**
 * Objeto en la escena. Por ahora todos son cubos (es la unica geometria que existe),
 * pero id/posicion ya estan pensados para cuando existan mas tipos via Add > Mesh.
 *
 * rotZ/rotX: rotacion libre (sin eje restringido), misma convencion que la orbita de camara
 * (angleY/angleX en MyGLRenderer) - grados. rotY: solo se toca con el gizmo de rotacion por eje
 * (ver MyGLRenderer.rotateSelectedObjectOnAxis) - no tiene gesto libre asociado, igual que rotZ/rotX
 * no lo tenian antes del gizmo. scale: escala uniforme libre, 1f = tamano original.
 */
data class SceneObject(
    val id: Int,
    var posX: Float = 0f,
    var posY: Float = 0f,
    var posZ: Float = 0f,
    var rotX: Float = 0f,
    var rotY: Float = 0f,
    var rotZ: Float = 0f,
    var scale: Float = 1f,
    var selected: Boolean = false
)
