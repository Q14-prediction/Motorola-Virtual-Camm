# Motorola Virtual Camera Native Studio 📱

Application Android native en Kotlin conçue pour remplacer le flux de la caméra physique par une vidéo MP4 ou une photo importée depuis la galerie, optimisée pour les smartphones **Motorola (Edge 30/40/50, Moto G, Razr, ThinkPhone)** et Android 12/13/14/15.

---

## 🎯 Fonctionnalités Clés

1. **Gestion Dynamique des Permissions** :
   - Caméra (`CAMERA`)
   - Médias Android 13/14/15 (`READ_MEDIA_VIDEO`, `READ_MEDIA_IMAGES`)
   - Fallback stockage antérieur (`READ_EXTERNAL_STORAGE`)
   - Fenêtre flottante (`SYSTEM_ALERT_WINDOW`)

2. **Moteur Freeze-Frame Anti-Coupure (Crucial)** :
   - Lorsque vous appuyez sur **Pause**, le décodeur vidéo s'arrête mais la **boucle de rendu OpenGL ES continue de cadencer à 30 FPS constants**.
   - Le Presentation Time Stamp (PTS) continue d'augmenter de manière monotone.
   - Les applications consommatrices (WhatsApp, Zoom, Teams, KYC) ne détectent **aucun écran noir ni déconnexion de capteur**.

3. **Intégration Camera2 & CameraX** :
   - Compatible injection locale ou injection globale via module LSPosed / Xposed.

---

## 🚀 Compilation & Installation

1. Ouvrez le projet dans **Android Studio** (Hedgehog, Iguana ou Ladybug).
2. Connectez votre appareil Motorola via USB avec le Débogage USB activé (`adb devices`).
3. Cliquez sur **Run 'app'** ou lancez :
   ```bash
   ./gradlew assembleDebug
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```