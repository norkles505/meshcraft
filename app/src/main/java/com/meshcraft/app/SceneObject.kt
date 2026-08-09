package com.meshcraft.app

/**
 * Objeto en la escena. Por ahora todos son cubos (es la unica geometria que existe),
 * pero id/posicion ya estan pensados para cuando existan mas tipos via Add > Mesh.
 */
data class SceneObject(
    val id: Int,
    var posX: Float = 0f,
    var posY: Float = 0f,
    var posZ: Float = 0f,
    var selected: Boolean = false
)
