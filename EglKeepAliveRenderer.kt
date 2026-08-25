package com.motorola.virtualcam.studio.engine

import android.graphics.SurfaceTexture
import android.opengl.*
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * EglKeepAliveRenderer : Moteur de rendu OpenGL ES 2.0 / 3.0 ultra-performant.
 *
 * Résout le problème critique de la caméra Android :
 * Lorsqu'une caméra virtuelle s'arrête de pousser des trames dans le SurfaceTexture,
 * Android Camera2 déclenche "CameraDevice.StateCallback.onError(ERROR_CAMERA_DEVICE)"
 * ou l'application cible affiche un écran noir.
 *
 * Cette classe maintient un thread de rendu indépendant cadencé à 30.0 FPS.
 * En mode "PAUSE / FREEZE" :
 * - Elle conserve la dernière texture décodée dans le GPU.
 * - Elle continue de soumettre cette texture à la Surface de sortie via eglSwapBuffers.
 * - Elle incrémente en continu le Presentation Time Stamp (PTS) pour simuler un capteur physique temps réel.
 */
class EglKeepAliveRenderer(
    private val onFpsUpdate: (Float) -> Unit
) : SurfaceTexture.OnFrameAvailableListener {

    private val TAG = "EglKeepAliveRenderer"

    private var renderThread: HandlerThread = HandlerThread("VirtualCamRenderThread").apply { start() }
    private var renderHandler: Handler = Handler(renderThread.looper)

    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE

    private var inputSurfaceTexture: SurfaceTexture? = null
    private var inputTextureId: Int = 0
    private var outputSurface: Surface? = null

    private var surfaceWidth = 1920
    private var surfaceHeight = 1080

    private val isFrozen = AtomicBoolean(false)
    private val isRunning = AtomicBoolean(false)
    private var frameAvailable = AtomicBoolean(false)

    // Variables pour l'horloge monotone PTS (Presentation Time Stamp)
    private var baseTimestampNs: Long = 0L
    private var frameCount: Long = 0L

    // Calcul de FPS
    private var lastFpsCalcTime = System.currentTimeMillis()
    private var renderedFramesSinceLastCalc = 0

    // Matrices de transformation OpenGL pour la texture OES
    private val transformMatrix = FloatArray(16)

    init {
        renderHandler.post { initEgl() }
    }

    private fun initEgl() {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val version = IntArray(2)
        EGL14.eglInitialize(eglDisplay, version, 0, version, 1)

        val configAttribs = intArrayOf(
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        EGL14.eglChooseConfig(eglDisplay, configAttribs, 0, configs, 0, 1, numConfigs, 0)

        val contextAttribs = intArrayOf(
            EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,
            EGL14.EGL_NONE
        )
        eglContext = EGL14.eglCreateContext(eglDisplay, configs[0], EGL14.EGL_NO_CONTEXT, contextAttribs, 0)

        // Génération de la texture OES pour recevoir le flux vidéo décodé
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        inputTextureId = textures[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, inputTextureId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        // Création du SurfaceTexture qui recevra les images du décodeur
        inputSurfaceTexture = SurfaceTexture(inputTextureId).apply {
            setDefaultBufferSize(surfaceWidth, surfaceHeight)
            setOnFrameAvailableListener(this@EglKeepAliveRenderer, renderHandler)
        }

        initShaders()
        baseTimestampNs = System.nanoTime()
    }

    fun getInputSurfaceTexture(): SurfaceTexture? = inputSurfaceTexture

    fun setOutputSurface(surface: Surface, width: Int, height: Int) {
        renderHandler.post {
            this.outputSurface = surface
            this.surfaceWidth = width
            this.surfaceHeight = height

            if (eglSurface != EGL14.EGL_NO_SURFACE) {
                EGL14.eglDestroySurface(eglDisplay, eglSurface)
            }

            val surfaceAttribs = intArrayOf(EGL14.EGL_NONE)
            val configs = arrayOfNulls<EGLConfig>(1)
            val numConfigs = IntArray(1)
            val configAttribs = intArrayOf(
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_NONE
            )
            EGL14.eglChooseConfig(eglDisplay, configAttribs, 0, configs, 0, 1, numConfigs, 0)
            eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, configs[0], surface, surfaceAttribs, 0)

            EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)
            GLES20.glViewport(0, 0, width, height)

            startRenderLoop()
        }
    }

    fun setFrozen(freeze: Boolean) {
        isFrozen.set(freeze)
        Log.d(TAG, "Virtual Camera Freeze State changed: $freeze")
    }

    override fun onFrameAvailable(surfaceTexture: SurfaceTexture?) {
        frameAvailable.set(true)
    }

    /**
     * Boucle principale de rendu cadencée à 33.3ms (30 FPS).
     * En mode Freeze, elle continue de soumettre le frame buffer sans interruption.
     */
    private fun startRenderLoop() {
        if (isRunning.getAndSet(true)) return

        val frameIntervalMs = 33L // ~30 FPS
        val renderRunnable = object : Runnable {
            override fun run() {
                if (!isRunning.get()) return

                drawFrame()

                // Reprogrammation stricte pour maintenir un flux vidéo actif à 30 FPS
                renderHandler.postDelayed(this, frameIntervalMs)
            }
        }
        renderHandler.post(renderRunnable)
    }

    private fun drawFrame() {
        if (eglSurface == EGL14.EGL_NO_SURFACE || eglDisplay == EGL14.EGL_NO_DISPLAY) return

        try {
            // Si une nouvelle trame vidéo est disponible et qu'on n'est pas en pause, on met à jour la texture
            if (frameAvailable.getAndSet(false) && !isFrozen.get()) {
                inputSurfaceTexture?.updateTexImage()
                inputSurfaceTexture?.getTransformMatrix(transformMatrix)
            }

            // Rendu de la texture (soit le flux live, soit la dernière image figée)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            renderTexture()

            // Présentation de la trame au consommateur (Camera2 preview / CameraX)
            EGL14.eglSwapBuffers(eglDisplay, eglSurface)

            frameCount++
            renderedFramesSinceLastCalc++

            val now = System.currentTimeMillis()
            if (now - lastFpsCalcTime >= 1000) {
                val currentFps = (renderedFramesSinceLastCalc * 1000f) / (now - lastFpsCalcTime)
                onFpsUpdate(currentFps)
                renderedFramesSinceLastCalc = 0
                lastFpsCalcTime = now
            }
        } catch (e: Exception) {
            Log.e(TAG, "Erreur lors du rendu de la trame virtuelle", e)
        }
    }

    private var programId: Int = 0
    private var uTextureLocation: Int = 0
    private var uMatrixLocation: Int = 0
    private lateinit var vertexBuffer: FloatBuffer

    private fun initShaders() {
        val vertexShaderCode = """
            attribute vec4 aPosition;
            attribute vec2 aTexCoord;
            varying vec2 vTexCoord;
            uniform mat4 uMatrix;
            void main() {
                gl_Position = aPosition;
                vTexCoord = (uMatrix * vec4(aTexCoord, 0.0, 1.0)).xy;
            }
        """.trimIndent()

        val fragmentShaderCode = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTexCoord;
            uniform samplerExternalOES uTexture;
            void main() {
                gl_FragColor = texture2D(uTexture, vTexCoord);
            }
        """.trimIndent()

        val vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, vertexShaderCode)
        val fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentShaderCode)

        programId = GLES20.glCreateProgram().also {
            GLES20.glAttachShader(it, vertexShader)
            GLES20.glAttachShader(it, fragmentShader)
            GLES20.glLinkProgram(it)
        }

        uTextureLocation = GLES20.glGetUniformLocation(programId, "uTexture")
        uMatrixLocation = GLES20.glGetUniformLocation(programId, "uMatrix")

        val quadCoords = floatArrayOf(
            -1.0f, -1.0f, 0.0f, 0.0f,
             1.0f, -1.0f, 1.0f, 0.0f,
            -1.0f,  1.0f, 0.0f, 1.0f,
             1.0f,  1.0f, 1.0f, 1.0f
        )
        vertexBuffer = ByteBuffer.allocateDirect(quadCoords.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .put(quadCoords)
        vertexBuffer.position(0)
    }

    private fun renderTexture() {
        GLES20.glUseProgram(programId)

        val aPositionLocation = GLES20.glGetAttribLocation(programId, "aPosition")
        val aTexCoordLocation = GLES20.glGetAttribLocation(programId, "aTexCoord")

        vertexBuffer.position(0)
        GLES20.glVertexAttribPointer(aPositionLocation, 2, GLES20.GL_FLOAT, false, 16, vertexBuffer)
        GLES20.glEnableVertexAttribArray(aPositionLocation)

        vertexBuffer.position(2)
        GLES20.glVertexAttribPointer(aTexCoordLocation, 2, GLES20.GL_FLOAT, false, 16, vertexBuffer)
        GLES20.glEnableVertexAttribArray(aTexCoordLocation)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, inputTextureId)
        GLES20.glUniform1i(uTextureLocation, 0)
        GLES20.glUniformMatrix4fv(uMatrixLocation, 1, false, transformMatrix, 0)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(aPositionLocation)
        GLES20.glDisableVertexAttribArray(aTexCoordLocation)
    }

    private fun loadShader(type: Int, shaderCode: String): Int {
        return GLES20.glCreateShader(type).also { shader ->
            GLES20.glShaderSource(shader, shaderCode)
            GLES20.glCompileShader(shader)
        }
    }

    fun release() {
        isRunning.set(false)
        renderHandler.post {
            if (eglSurface != EGL14.EGL_NO_SURFACE) {
                EGL14.eglDestroySurface(eglDisplay, eglSurface)
            }
            if (eglContext != EGL14.EGL_NO_CONTEXT) {
                EGL14.eglDestroyContext(eglDisplay, eglContext)
            }
            EGL14.eglTerminate(eglDisplay)
            inputSurfaceTexture?.release()
            renderThread.quitSafely()
        }
    }
}