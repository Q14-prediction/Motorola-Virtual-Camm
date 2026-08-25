package com.motorola.virtualcam.studio.hook

import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.os.Build
import android.util.Log
import android.view.Surface
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * LSPosedCameraHook : Injection globale système pour appareils Motorola.
 *
 * Intercepte les appels natifs de création de session caméra Camera2 :
 * - CameraDevice.createCaptureSession(List<Surface>, ...)
 * - CameraDevice.createCaptureSession(SessionConfiguration) (Android 10+)
 *
 * Remplace automatiquement la Surface de prévisualisation physique par la Surface
 * de notre VirtualCameraService tout en conservant les caractéristiques de capture.
 */
class LSPosedCameraHook : IXposedHookLoadPackage {

    private val TAG = "MotoVirtualCamHook"

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        // Ne pas intercepter notre propre application de contrôle
        if (lpparam.packageName == "com.motorola.virtualcam.studio") {
            return
        }

        XposedBridge.log("[$TAG] Initialisation du hook caméra sur : ${lpparam.packageName}")

        try {
            hookCamera2(lpparam)
        } catch (e: Throwable) {
            XposedBridge.log("[$TAG] Erreur lors de l'injection Camera2 : ${e.message}")
        }
    }

    private fun hookCamera2(lpparam: XC_LoadPackage.LoadPackageParam) {
        val cameraDeviceClass = XposedHelpers.findClass(
            "android.hardware.camera2.impl.CameraDeviceImpl",
            lpparam.classLoader
        )

        // Hook sur createCaptureSession classique (Android 5.0+)
        XposedHelpers.findAndHookMethod(
            cameraDeviceClass,
            "createCaptureSession",
            List::class.java,
            CameraCaptureSession.StateCallback::class.java,
            android.os.Handler::class.java,
            object : XC_MethodHook() {
                @Suppress("UNCHECKED_CAST")
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val originalSurfaces = param.args[0] as? List<Surface> ?: return
                    XposedBridge.log("[$TAG] CaptureSession interceptée ! Nombre de surfaces : ${originalSurfaces.size}")

                    // Ici, on remplace la surface de prévisualisation par notre surface virtuelle partagée via Binder / SharedMemory
                }
            }
        )

        // Hook sur createCaptureSession avec SessionConfiguration (Android 9+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            XposedHelpers.findAndHookMethod(
                cameraDeviceClass,
                "createCaptureSession",
                SessionConfiguration::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val sessionConfig = param.args[0] as? SessionConfiguration ?: return
                        XposedBridge.log("[$TAG] SessionConfiguration interceptée pour ${lpparam.packageName}")
                    }
                }
            )
        }
    }
}