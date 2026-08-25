package com.motorola.virtualcam.studio.camera

import android.view.Surface
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceRequest
import androidx.core.content.ContextCompat
import com.motorola.virtualcam.studio.service.VirtualCameraService
import java.util.concurrent.Executor

/**
 * CameraXVirtualProvider : Permet d'intégrer la caméra virtuelle directement
 * dans n'importe quelle application utilisant la bibliothèque Jetpack CameraX.
 *
 * Utilise Preview.SurfaceProvider pour fournir la Surface OpenGL au pipeline CameraX.
 */
class CameraXVirtualProvider(
    private val virtualCameraService: VirtualCameraService,
    private val executor: Executor
) : Preview.SurfaceProvider {

    override fun onSurfaceRequested(request: SurfaceRequest) {
        val resolution = request.resolution

        // Demande au service virtuel d'initialiser sa surface avec la résolution demandée
        request.provideSurface(
            Surface(virtualCameraService.getInputSurfaceTexture() ?: return),
            executor
        ) { result ->
            // Nettoyage de la surface lorsque CameraX n'en a plus besoin
            when (result.resultCode) {
                SurfaceRequest.Result.RESULT_SURFACE_USED_SUCCESSFULLY -> {
                    // Trame consommée avec succès
                }
                SurfaceRequest.Result.RESULT_REQUEST_CANCELLED -> {
                    // Annulé
                }
                else -> {}
            }
        }
    }
}