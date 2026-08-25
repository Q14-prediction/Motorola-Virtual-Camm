package com.motorola.virtualcam.studio.ui

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.motorola.virtualcam.studio.service.FloatingOverlayService
import com.motorola.virtualcam.studio.service.VirtualCameraService

class MainActivity : ComponentActivity() {

    private var virtualService: VirtualCameraService? = null
    private var isBound = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as VirtualCameraService.LocalBinder
            virtualService = binder.getService()
            isBound = true
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            virtualService = null
            isBound = false
        }
    }

    override fun onStart() {
        super.onStart()
        val intent = Intent(this, VirtualCameraService::class.java)
        startService(intent)
        bindService(intent, connection, Context.BIND_AUTO_CREATE)
    }

    override fun onStop() {
        super.onStop()
        if (isBound) {
            unbindService(connection)
            isBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Color(0xFF00E5FF),
                    secondary = Color(0xFF7C4DFF),
                    background = Color(0xFF0F172A),
                    surface = Color(0xFF1E293B)
                )
            ) {
                VirtualCamMainScreen(
                    virtualService = virtualService,
                    onStartFloatingWidget = { checkOverlayPermissionAndStart() }
                )
            }
        }
    }

    private fun checkOverlayPermissionAndStart() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
            Toast.makeText(this, "Veuillez autoriser l'overlay pour le bouton flottant", Toast.LENGTH_LONG).show()
        } else {
            startService(Intent(this, FloatingOverlayService::class.java))
        }
    }
}

@Composable
fun VirtualCamMainScreen(
    virtualService: VirtualCameraService?,
    onStartFloatingWidget: () -> Unit
) {
    val context = LocalContext.current
    var selectedMediaUri by remember { mutableStateOf<Uri?>(null) }
    var isPaused by remember { mutableStateOf(false) }
    var currentFps by remember { mutableStateOf(30f) }

    // 1. Gestion dynamique des autorisations (Camera + Media Android 13/14)
    val permissionsToRequest = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(
                Manifest.permission.CAMERA,
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_MEDIA_IMAGES
            )
        } else {
            arrayOf(
                Manifest.permission.CAMERA,
                Manifest.permission.READ_EXTERNAL_STORAGE
            )
        }
    }

    var permissionsGranted by remember {
        mutableStateOf(
            permissionsToRequest.all {
                ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
            }
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        permissionsGranted = results.values.all { it }
        if (!permissionsGranted) {
            Toast.makeText(context, "Toutes les autorisations sont requises !", Toast.LENGTH_SHORT).show()
        }
    }

    // 2. Sélecteur de fichiers multimédias de la galerie
    val mediaPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            selectedMediaUri = it
            virtualService?.loadMedia(it, isVideo = true)
            isPaused = false
            Toast.makeText(context, "Vidéo chargée avec succès !", Toast.LENGTH_SHORT).show()
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            Text(
                text = "Motorola Virtual Camera Studio",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.primary
            )

            // Carte d'état des autorisations
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = if (permissionsGranted) "✅ Autorisations Système : Accordées" else "⚠️ Autorisations Requises",
                        style = MaterialTheme.typography.titleMedium,
                        color = if (permissionsGranted) Color(0xFF10B981) else Color(0xFFF59E0B)
                    )
                    if (!permissionsGranted) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(
                            onClick = { permissionLauncher.launch(permissionsToRequest) },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                        ) {
                            Text("Autoriser Caméra & Galerie", color = Color.Black)
                        }
                    }
                }
            }

            // Bouton d'importation de vidéo/photo
            Button(
                onClick = { mediaPickerLauncher.launch("video/*") },
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = RoundedCornerShape(12.dp),
                enabled = permissionsGranted
            ) {
                Text("📁 Importer une Vidéo MP4 / Photo (Galerie)")
            }

            // Commandes de Lecture & Freeze Frame Crucial
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "Contrôleur de Flux (Freeze Frame Engine)",
                        style = MaterialTheme.typography.titleMedium
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        Button(
                            onClick = {
                                val newState = virtualService?.togglePlayPause() ?: false
                                isPaused = newState
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isPaused) Color(0xFFEF4444) else Color(0xFF10B981)
                            ),
                            modifier = Modifier.weight(1f).padding(end = 8.dp)
                        ) {
                            Text(if (isPaused) "▶ Reprendre (Play)" else "⏸ Figer (Pause Keep-Alive)")
                        }

                        Button(
                            onClick = onStartFloatingWidget,
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary),
                            modifier = Modifier.weight(1f).padding(start = 8.dp)
                        ) {
                            Text("🫧 Widget Flottant")
                        }
                    }

                    Text(
                        text = if (isPaused)
                            "❄️ ÉTAT FIGÉ : La dernière image est maintenue à 30 FPS dans le pipeline sans coupure"
                            else "🟢 DIFFUSION ACTIVE : Vidéo en boucle fluide",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (isPaused) Color(0xFF60A5FA) else Color(0xFF34D399)
                    )
                }
            }
        }
    }
}