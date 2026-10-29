# Revisión visual de TubeGo 0.5.0

La prueba visual usa únicamente una API HTTPS con datos sintéticos. El servicio de prueba rechaza escrituras y no se conecta a ningún servidor real. El certificado se añade al proceso de instrumentación; la app normal conserva la verificación TLS de Android.

1. Iniciar un emulador Android y la API local:

   ```bash
   python3 ui-tests/visual_fixture.py --directory /tmp/tubego-visual-fixture --port 9445
   ```

2. Copiar `/tmp/tubego-visual-fixture/cert.pem` a `app/src/androidTest/res/raw/visual_cert.pem` y compilar:

   ```bash
   gradle assembleDebug lintDebug testDebugUnitTest assembleDebugAndroidTest
   python3 localization/verify.py
   ```

3. Instalar los APK de app y pruebas en el emulador y ejecutar:

   ```bash
   adb -s emulator-5580 install -r app/build/outputs/apk/debug/app-debug.apk
   adb -s emulator-5580 install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
   adb -s emulator-5580 shell am instrument -w \
     -e class dev.arglabs.tubego.VisualRedesignInstrumentedTest,dev.arglabs.tubego.AdminCenterInstrumentedTest \
     dev.arglabs.tubego.test/android.test.InstrumentationTestRunner
   ```

Las pruebas comprueban las pantallas declaradas, el estilo compartido, la navegación de los ajustes personales, el cambio de rol al regresar, la separación de administración, los controles de almacenamiento y la cancelación de la confirmación de cierre de sesión. Las capturas usan datos de ejemplo y se guardan en los archivos externos de la app bajo `ui-previews-v021`.

Usar únicamente un emulador desechable. Las sesiones de ejemplo sustituyen el estado de la app de pruebas. Para revisar textos ampliados, repetir la prueba de pantallas con `settings put system font_scale 1.3` en ese emulador. La cobertura del manifiesto y los textos nuevos se comprueban también en `localization/verify.py`.

La regresión `VisualRedesignInstrumentedTest#testLibraryCategoryAndSearchSurviveRotation`
comprueba la categoría recibida de la API, su copia sin conexión, el selector y la
conservación de búsqueda y categoría al girar de vertical a horizontal y de vuelta.
Guarda capturas `Library-categories-*.png` en la misma carpeta de vistas previas.
Ejecutarla también con `font_scale=1.3` y en una pantalla estrecha. Conceder el
permiso de notificaciones al APK de ejemplo antes de capturar para evitar diálogos
del sistema durante la prueba.
