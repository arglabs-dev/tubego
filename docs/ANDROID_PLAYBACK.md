# Reproducción local Android (PLA-241)

La pantalla **Biblioteca local / Reproducir** enumera exclusivamente las fichas del directorio persistente `filesDir/tubego-media/<origen>/<usuario>/<dispositivo>`. No consulta el backend. Una copia local sigue disponible aunque el servidor ya no tenga su archivo. Las fichas pendientes o sin archivo muestran su estado real y no abren un reproductor ni solicitan una descarga.

Al reproducir se comprueba tamaño y SHA-256 del archivo completo. Se comparte un URI `content://dev.arglabs.tubego.private-media/media/<capacidad-aleatoria>` con permiso temporal de lectura mediante ACTION_VIEW y ClipData. El selector incluye VLC como opción destacada cuando está instalado; también permite cualquier reproductor compatible. Si no hay ninguno se explica que debe instalar uno; el archivo permanece guardado. Video y audio usan respectivamente MP4 y MP3 según el contrato del backend.

El proveedor no está exportado. El permiso corresponde a un único archivo y nunca admite rutas, parámetros de consulta, archivos parciales, enlaces simbólicos ni escritura. Revalida la sesión aprobada, usuario, dispositivo, identidad del archivo y marcador de borrado en cada lectura del descriptor proxy, que admite seek. Cerrar sesión, cambiar de cuenta, conocer una revocación o borrar el recurso impide seguir leyendo incluso un descriptor previamente abierto. Ningún proceso puede recuperar datos que otro reproductor ya haya almacenado en su propio buffer o copiado. Sin conexión se conserva la reproducción hasta que Tubego conozca una revocación. Los permisos se conservan solo durante la vida del proceso; tras terminar el proceso se abre de nuevo desde la biblioteca.

PiP, gestos y audio con pantalla bloqueada se delegan al reproductor elegido. Tubego no implementa reproductor integrado ni garantiza esas funciones para todas las aplicaciones. En VLC hay que ajustar **Background/PiP mode**; PiP requiere autorización del sistema. Fuentes oficiales: [configuración VLC Android](https://docs.videolan.me/vlc-user/android/3.5/en/more/settings/general_settings.html) y [reproductor de video](https://docs.videolan.me/vlc-user/android/3.X/en/video/video_player.html). La compatibilidad práctica depende de la versión y configuración del reproductor.

## Verificación

`./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest` (o el Gradle instalado en el entorno). Las pruebas puras cubren daño de igual tamaño, ausencia, borrado intencional, fichas incompatibles, traversal, enlaces, parciales y MIME. En emulador/dispositivo:

```
adb install -r android/app/build/outputs/apk/debug/app-debug.apk
adb install -r android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w -e class dev.arglabs.tubego.PrivateMediaInstrumentedTest dev.arglabs.tubego.test/android.test.InstrumentationTestRunner
```

La instrumentación utiliza archivos locales de prueba y Keystore real, sin servidor ni conectividad: lectura seekable, revocación sobre descriptor abierto, aislamiento de cuenta y sesión, modos de escritura y URI maliciosos, marcador de borrado y checksum. La prueba manual de VLC debe usar un video/audio válido, abrirlo sin red, probar PiP y bloquear la pantalla; la fixture de bytes del test de descargas no es un video reproducible. No confundir aprobación de las pruebas de proveedor con validación real de VLC.

## VLC probado en emulador

El 9 de octubre de 2026 se probó VLC Android 3.7.1 x86_64 oficial en emulador API 35 con un clip MP4/AAC sintético de 45 segundos, privado y verificado por SHA-256. APK SHA-256: `4646633ede40c4784c5584087cffc5c60cc45b16966ae0b36cedd784d7c4a11e`. Se confirmó reproducción real mediante MediaSession, PiP al ir a Home y reproducción de audio con pantalla apagada. Las cuatro pruebas de aislamiento del proveedor también pasaron. No es una prueba de dispositivo físico ni de retorno preciso de posición.

Primero hay que completar los tutoriales iniciales de VLC. Para PiP, escoger **Background/PiP mode → Play videos in Picture-in-picture mode**; para audio con pantalla apagada, escoger **Play videos in background**. Son dos configuraciones externas distintas. La prueba detectó que la configuración PiP por sí sola no conserva el audio al apagar la pantalla. Tubego no altera las preferencias de VLC.

La instrumentación opcional requiere VLC instalado y configurado antes de cada caso:

```sh
adb shell am instrument -w -e class dev.arglabs.tubego.VlcIntegrationInstrumentedTest#testVlcReadsVerifiedLocalClipWithoutBackend dev.arglabs.tubego.test/android.test.InstrumentationTestRunner
# Cambiar en VLC al modo de segundo plano antes del siguiente caso.
adb shell am instrument -w -e class dev.arglabs.tubego.VlcIntegrationInstrumentedTest#testVlcVideoAudioWithScreenOff dev.arglabs.tubego.test/android.test.InstrumentationTestRunner
```
