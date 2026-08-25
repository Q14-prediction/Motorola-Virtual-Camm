package com.motorola.virtualcam.studio.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.SurfaceTexture
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.view.Surface
import androidx.core.app.NotificationCompat
import com.motorola.virtualcam.studio.R
import com.motorola.virtualcam.studio.engine.EglKeepAliveRenderer
import com.motorola.virtualcam.studio.engine.ExoPlayerVideoFeeder
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * VirtualCameraService : Coeur du système de caméra virtuelle pour appareils Motorola.
 *
 * Gère le cycle de vie du décodeur vidéo (ExoPlayer), le moteur de rendu OpenGL (EGL Surface)
 * et le mécanisme CRUCIAL de "Freeze Frame Keep-Alive" :
 * Lorsque l'utilisateur met en pause, le décodeur arrête d'avancer dans le temps,
 * mais la boucle de rendu OpenGL CONTINUE d'envoyer 30 FPS de la dernière image décodée
 * avec un timestamp de présentation monotone incrémental (PTS) vers la Surface de la caméra.
 * Cela empêche la Camera2 API ou les applications réceptrices (WhatsApp, Zoom, KYC)
 * de détecter une interruption de flux ou un timeout de trame.
 */
class VirtualCameraService : Service() {

    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())

    private var videoFeeder: ExoPlayerVideoFeeder? = null
    private var eglRenderer: EglKeepAliveRenderer? = null

    // État réactif de la caméra virtuelle
    private val _isStreaming = MutableStateFlow(false)
    val isStreaming = _isStreaming.asStateFlow()

    private val _isPaused = MutableStateFlow(false)
    val isPaused = _isPaused.asStateFlow()

    private val _fpsFlow = MutableStateFlow(30f)
    val fpsFlow = _fpsFlow.asStateFlow()

    private var currentMediaUri: Uri? = null

    inner class LocalBinder : Binder() {
        fun getService(): VirtualCameraService = this@VirtualCameraService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        startForegroundNotification()
        initializeEngines()
    }

    private fun initializeEngines() {
        // 1. Initialisation du moteur de rendu OpenGL avec boucle keep-alive
        eglRenderer = EglKeepAliveRenderer(
            onFpsUpdate = { fps ->
                _fpsFlow.value = fps
            }
        )

        // 2. Initialisation du lecteur ExoPlayer
        videoFeeder = ExoPlayerVideoFeeder(
            context = this,
            onVideoEnded = {
                // Géré par ExoPlayer REPEAT_MODE_ALL pour un bouclage sans faille
            }
        )
    }

    /**
     * Lie le flux virtuel à la Surface de prévisualisation de la caméra cible (CameraX ou Camera2)
     */
    fun attachOutputSurface(targetSurface: Surface, width: Int = 1920, height: Int = 1080) {
        eglRenderer?.setOutputSurface(targetSurface, width, height)

        // Récupère la SurfaceTexture d'entrée dans laquelle ExoPlayer va écrire
        val inputSurfaceTexture = eglRenderer?.getInputSurfaceTexture()
        if (inputSurfaceTexture != null) {
            val decoderSurface = Surface(inputSurfaceTexture)
            videoFeeder?.setOutputSurface(decoderSurface)
        }

        _isStreaming.value = true
    }

    /**
     * Charge une nouvelle vidéo ou image depuis l'URI sélectionnée
     */
    fun loadMedia(uri: Uri, isVideo: Boolean = true) {
        currentMediaUri = uri
        videoFeeder?.loadMedia(uri, isVideo = isVideo)
        _isPaused.value = false
        eglRenderer?.setFrozen(false)
    }

    /**
     * BOUTON PAUSE CRUCIAL :
     * Fige immédiatement la vidéo sur l'image courante (Freeze Frame),
     * tout en ordonnant au moteur EGL de continuer à pomper activement
     * cette trame à 30/60 FPS vers le consommateur de la caméra.
     */
    fun togglePlayPause(): Boolean {
        val currentlyPaused = _isPaused.value
        val nextPausedState = !currentlyPaused

        if (nextPausedState) {
            // Mise en Pause : On fige la progression du décodeur vidéo
            videoFeeder?.pause()
            // On active le mode Keep-Alive Freeze Frame dans OpenGL
            eglRenderer?.setFrozen(true)
        } else {
            // Reprise : On relance la lecture fluide
            eglRenderer?.setFrozen(false)
            videoFeeder?.play()
        }

        _isPaused.value = nextPausedState
        updateNotification(if (nextPausedState) "Flux Figé (Freeze Frame Actif)" else "Diffusion en cours")
        return nextPausedState
    }

    fun isCurrentlyPaused(): Boolean = _isPaused.value

    private fun startForegroundNotification() {
        val channelId = "motorola_virtual_cam_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Caméra Virtuelle Motorola",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Maintien du flux de caméra virtuelle actif"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }

        val notification = buildNotification("Service de Caméra Virtuelle Prêt")
        startForeground(1001, notification)
    }

    private fun updateNotification(status: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(1001, buildNotification(status))
    }

    private fun buildNotification(contentText: String): Notification {
        val channelId = "motorola_virtual_cam_channel"
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("Motorola Virtual Camera Active")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        videoFeeder?.release()
        eglRenderer?.release()
        _isStreaming.value = false
    }
}