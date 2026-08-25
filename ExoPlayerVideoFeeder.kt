package com.motorola.virtualcam.studio.engine

import android.content.Context
import android.net.Uri
import android.view.Surface
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer

/**
 * ExoPlayerVideoFeeder : Décodeur multimédia optimisé pour Motorola.
 *
 * Utilise l'accélération matérielle MediaCodec du processeur (Snapdragon / MediaTek)
 * pour décoder les fichiers MP4 à haut débit sans surchauffe.
 * 
 * - Configure le bouclage transparent (REPEAT_MODE_ALL).
 * - Envoie directement les trames décodées à la SurfaceTexture OpenGL.
 */
class ExoPlayerVideoFeeder(
    private val context: Context,
    private val onVideoEnded: () -> Unit
) {

    private var player: ExoPlayer? = null
    private var outputSurface: Surface? = null
    private var isPausedInternal = false

    init {
        player = ExoPlayer.Builder(context).build().apply {
            repeatMode = Player.REPEAT_MODE_ALL
            playWhenReady = true
            addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_ENDED) {
                        onVideoEnded()
                    }
                }
            })
        }
    }

    fun setOutputSurface(surface: Surface) {
        this.outputSurface = surface
        player?.setVideoSurface(surface)
    }

    fun loadMedia(uri: Uri, isVideo: Boolean = true) {
        val mediaItem = MediaItem.fromUri(uri)
        player?.setMediaItem(mediaItem)
        player?.prepare()
        player?.play()
        isPausedInternal = false
    }

    fun pause() {
        isPausedInternal = true
        // Met en pause le décodeur : l'image courante reste ancrée dans le buffer de sortie
        player?.pause()
    }

    fun play() {
        isPausedInternal = false
        player?.play()
    }

    fun isPaused(): Boolean = isPausedInternal

    fun release() {
        player?.release()
        player = null
        outputSurface = null
    }
}