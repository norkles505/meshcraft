package com.meshcraft.app

import android.opengl.Matrix

/**
 * Objeto en la escena. Por ahora todos son cubos (es la unica geometria que existe),
 * pero id/posicion ya estan pensados para cuando existan mas tipos via Add > Mesh.
 *
 * rotationMatrix: rotacion acumulada del objeto, como matriz 4x4 (identidad = sin rotar).
 * Reemplaza los 3 angulos sueltos que tenia antes (rotX/rotY/rotZ): guardarlos por separado y
 * recombinarlos cada frame en un orden fijo (Rz*Rx*Ry) es el clasico problema de angulos de Euler
 * - el resultado depende del orden de composicion, no del orden real en que el usuario roto cada
 * eje, asi que una segunda rotacion podia "cancelar" visualmente el efecto de la primera (bug
 * reportado y confirmado - ver charla con el usuario). Guardando una sola matriz acumulada y
 * multiplicando cada rotacion nueva (delta) sobre el estado actual real (ver
 * MyGLRenderer.applyWorldRotationDelta) el bug desaparece, sin importar que eje se toque en que
 * orden. scale: escala uniforme libre, 1f = tamano original.
 */
data class SceneObject(
    val id: Int,
    var posX: Float = 0f,
    var posY: Float = 0f,
    var posZ: Float = 0f,
    var rotationMatrix: FloatArray = FloatArray(16).apply { Matrix.setIdentityM(this, 0) },
    var scale: Float = 1f,
    var selected: Boolean = false
)
