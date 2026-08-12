package com.meshcraft.app

import android.opengl.GLES20
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer

/**
 * Funciones de bajo nivel para OpenGL (buffers y compilacion de shaders) compartidas por todas
 * las geometrias de la app (ver MeshGeometry.kt, usado por Cube y futuras primitivas). Antes cada
 * clase de geometria (Cube, Grid) tenia su propia copia de estas mismas funciones - se centralizan
 * aca para no repetir el boilerplate cada vez que se agrega una primitiva nueva, y para que el
 * chequeo de errores de compilacion/linkeo (que antes no existia en ningun lado) se haga una sola
 * vez, en un solo sitio.
 */
object GLUtils {

    fun makeFloatBuffer(data: FloatArray): FloatBuffer {
        return ByteBuffer.allocateDirect(data.size * 4).run {
            order(ByteOrder.nativeOrder())
            asFloatBuffer().apply {
                put(data)
                position(0)
            }
        }
    }

    fun makeShortBuffer(data: ShortArray): ShortBuffer {
        return ByteBuffer.allocateDirect(data.size * 2).run {
            order(ByteOrder.nativeOrder())
            asShortBuffer().apply {
                put(data)
                position(0)
            }
        }
    }

    /**
     * Compila un shader y chequea GL_COMPILE_STATUS - antes este chequeo no existia (ver Cube.kt /
     * Grid.kt originales), asi que un error de sintaxis en el shader quedaba en silencio (el shader
     * simplemente no compilaba y el programa fallaba mas adelante, sin pista de por que). Ahora tira
     * una excepcion con el log real de GL, apuntando directo al problema.
     */
    fun loadShader(type: Int, shaderCode: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, shaderCode)
        GLES20.glCompileShader(shader)

        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES20.glGetShaderInfoLog(shader)
            GLES20.glDeleteShader(shader)
            val typeName = if (type == GLES20.GL_VERTEX_SHADER) "vertex" else "fragment"
            Log.e("GLUtils", "Error compilando shader $typeName: $log")
            throw RuntimeException("Error compilando shader $typeName: $log")
        }
        return shader
    }

    /**
     * Compila vertex+fragment shader y linkea el programa, chequeando GL_LINK_STATUS (tampoco
     * existia antes). Mismo criterio que loadShader: mejor una excepcion clara ahora que un cubo
     * gris/negro sin explicacion mas adelante si alguien toca el shader y comete un error.
     */
    fun buildProgram(vertexCode: String, fragmentCode: String): Int {
        val vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, vertexCode)
        val fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentCode)

        val program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vertexShader)
        GLES20.glAttachShader(program, fragmentShader)
        GLES20.glLinkProgram(program)

        val status = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES20.glGetProgramInfoLog(program)
            GLES20.glDeleteProgram(program)
            Log.e("GLUtils", "Error linkeando programa: $log")
            throw RuntimeException("Error linkeando programa: $log")
        }
        return program
    }
}
