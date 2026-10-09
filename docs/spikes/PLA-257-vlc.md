# PLA-257 — posición y continuación con VLC externo

Estado: investigación de código y prototipo aislado. **Validación Android/VLC pendiente; no cerrar la tarjeta como Done.** Fecha: 2026-10-08.

## Decisión para el MVP

Mantener reproductor externo e historial de apertura. No prometer posición exacta, sincronización del punto de reproducción ni reanudación automática hasta probar la matriz de abajo. No añadir LibVLC ni reproductor embebido. Este spike no bloquea el MVP.

## Evidencia oficial, reproducible

Código VideoLAN Android fijado al commit **91c78014517bfbc769df4634d06cbd40ebce77ec**, fechado 2026-10-07. Es una revisión de desarrollo, **no una versión APK validada**.

* [VideoPlayerActivity, recepción de posición](https://github.com/videolan/vlc-android/blob/91c78014517bfbc769df4634d06cbd40ebce77ec/application/vlc-android/src/org/videolan/vlc/gui/video/VideoPlayerActivity.kt#L2215): acepta `position` como Long en milisegundos (compatibilidad Int), salvo `from_start=true`.
* [Salida de actividad](https://github.com/videolan/vlc-android/blob/91c78014517bfbc769df4634d06cbd40ebce77ec/application/vlc-android/src/org/videolan/vlc/gui/video/VideoPlayerActivity.kt#L1203): construye resultado con `extra_position` y `extra_duration`; puede omitirlos sin servicio/URI. No es un flujo periódico de progreso.
* [Constantes](https://github.com/videolan/vlc-android/blob/91c78014517bfbc769df4634d06cbd40ebce77ec/application/vlc-android/src/org/videolan/vlc/gui/video/VideoPlayerActivity.kt#L2575): acción `org.videolan.vlc.player.result` para paquete estable; resultados de error específicos dependen de versión. No interpretar números históricos como contrato estable.
* [Cambio a audio](https://github.com/videolan/vlc-android/blob/91c78014517bfbc769df4634d06cbd40ebce77ec/application/vlc-android/src/org/videolan/vlc/gui/video/VideoPlayerActivity.kt#L1778) y [onStop](https://github.com/videolan/vlc-android/blob/91c78014517bfbc769df4634d06cbd40ebce77ec/application/vlc-android/src/org/videolan/vlc/gui/video/VideoPlayerActivity.kt#L966): pasar a audio puede terminar la actividad de video mientras continúa audio. El checkpoint puede quedar desactualizado.
* [Manifest](https://github.com/videolan/vlc-android/blob/91c78014517bfbc769df4634d06cbd40ebce77ec/application/vlc-android/AndroidManifest.xml#L1027): actividad exportada, PiP declarado y `singleTask`. La entrega de Activity Result requiere comprobarse con VLC abierto y cerrado; no asumir callback final.
* [Documentación oficial de controles](https://docs.videolan.me/vlc-user/android/3.X/en/video/video_player.html): describe gestos y ventana PiP; requiere permiso del sistema. [Opciones de audio](https://docs.videolan.me/vlc-user/android/3.X/en/more/settings/extra_audio.html): el comportamiento al quitar VLC de recientes depende de su configuración. Tubego no controla estas preferencias.

Conclusión: el código ofrece una vía de integración, pero no demuestra fiabilidad de extremo a extremo. Un cierre forzado no garantiza resultado y el modo audio no garantiza un checkpoint al finalizar. El alcance aprobado admite degradar a historial de apertura.

## Prototipo

`pla257/android/.../VlcProbe.java` abre un `content://` privado con permiso temporal de lectura y componente explícito de VLC. No expone rutas `file://`, no agrega permiso de escritura, no usa red ni instala reproductores. El proveedor debe ser FileProvider con `exported=false`, `grantUriPermissions=true` y una ruta estrecha al directorio de medios; no compartir la raíz de archivos. El llamador obtiene URI con `FileProvider.getUriForFile` y conoce su MIME real.

Integración en una Activity de prueba:

```java
// fileUri proviene de FileProvider y apunta a un archivo local completo.
VlcProbe.Launch launch = VlcProbe.open(this, fileUri, "video/mp4", savedMillis, 10);
// Mostrar error recuperable para NOT_INSTALLED o ACCESS_DENIED.
// En onActivityResult, con requestCode/resultCode/data recibidos:
VlcProbe.Checkpoint point = VlcProbe.result(requestCode, resultCode, data);
// Guardar point != null junto al resourceId de la solicitud original.
// No usar URI devuelta como autorización ni identidad del recurso.
```

Persistir antes de abrir `{resourceId, accountId, sessionId, openedAt}` para sobrevivir recreación de Activity. Admitir una reproducción pendiente por sesión, y rechazar resultados que ya no correspondan a la cuenta/sesión activa. Nunca registrar contraseñas/tokens, ni sobrescribir un checkpoint conocido con resultado cancelado, nulo o malformado. La validación del prototipo descarta valores negativos y posiciones mayores que la duración. Un resultado válido es un checkpoint observado, no prueba de fin ni de reproducción completa.

El helper `ResumePosition` usa el retroceso por usuario (10 segundos predeterminados), milisegundos y mínimo cero. No habilita sincronización en producción. Si el spike supera las pruebas, crear seguimiento que almacene posición/duración/fecha/dispositivo y sincronice checkpoints válidos; sin resultado fiable, conservar solo apertura y permitir al usuario usar el historial propio de VLC.

## Compilación y pruebas reproducibles

Sin cambiar la app ni agregar dependencias productivas:

```bash
mkdir -p /tmp/pla257-classes
javac -d /tmp/pla257-classes docs/spikes/pla257/src/dev/arglabs/tubego/spike/*.java
java -cp /tmp/pla257-classes dev.arglabs.tubego.spike.ResumePositionTest
# ANDROID_JAR apunta al android.jar instalado del SDK.
javac -cp "$ANDROID_JAR:/tmp/pla257-classes" -d /tmp/pla257-classes docs/spikes/pla257/android/dev/arglabs/tubego/spike/VlcProbe.java
```

Verificación ejecutada: helper compilado con Eclipse ECJ 3.43.0 y Java 21; **13 comprobaciones pasan**. Adaptador y helper compilados para Java 8 contra `android.jar` API 35 oficial, sin errores ni advertencias. La primera compilación del adaptador con Java 17 falló por paquetes Java duplicados en los stubs Android; usar bootclasspath Android y target Java 8 resolvió el conflicto. No se cambió código productivo.

Reproducción con el compilador usado (ECJ desde `https://repo.maven.apache.org/maven2/org/eclipse/jdt/ecj/3.43.0/ecj-3.43.0.jar`, SHA-256 `c786468c65e906498e7e36ece4e0d04c6d3dd34c9a61b34a3a5b512801911a82`):

```bash
java -jar /tmp/pla257-ecj.jar -17 -d /tmp/pla257-classes docs/spikes/pla257/src/dev/arglabs/tubego/spike/*.java
java -cp /tmp/pla257-classes dev.arglabs.tubego.spike.ResumePositionTest
java -jar /tmp/pla257-ecj.jar -1.8 -bootclasspath "$ANDROID_JAR" -d /tmp/pla257-android-classes docs/spikes/pla257/src/dev/arglabs/tubego/spike/ResumePosition.java docs/spikes/pla257/android/dev/arglabs/tubego/spike/VlcProbe.java
```

SDK de compilación: `https://dl.google.com/android/repository/platform-35_r02.zip`, SHA-256 `0988cacad01b38a18a47bac14a0695f246bc76c1b06c0eeb8eb0dc825ab0c8e0`.

Estos tests verifican retroceso, límites, ausencia de datos y resultados inválidos; no prueban Android ni VLC. No se ejecutaron pruebas de dispositivo o emulador en esta sesión. No hay evidencia de APK instalada, retorno real de posición, PiP ni pantalla bloqueada.

## Matriz pendiente para cerrar PLA-257

Probar al menos un dispositivo físico y un emulador Android soportados por el MVP, con APK estable VLC oficial. Registrar modelo/API, versión exacta de VLC, origen/hash de APK, configuración de fondo/PiP y versión de Tubego. Usar un MP4 local de duración conocida sin contenido privado y repetir cada caso tres veces.

| Caso | Evidencia requerida | Estado |
|---|---|---|
| VLC cerrado y ya abierto | URI privada legible; no resultado cancelado inmediato por singleTask | Pendiente |
| Pausar en 15:32 y salir normal | Callback, tipos Long, duración correcta, posición ±2 s | Pendiente |
| Reabrir en 15:22 y retroceso cerca de cero | Seek aplicado, sin diálogo/conflicto de resume de VLC | Pendiente |
| Home/Maps y PiP | Video continúa, permisos explicados, callback y tiempo registrados | Pendiente |
| Pantalla bloqueada/modo audio | Audio continúa; verificar checkpoint al volver y al terminar | Pendiente |
| Quitar de recientes y force-stop VLC | Sin promesa de callback; historial previo conservado | Pendiente |
| Matar/recrear Tubego durante VLC | Asociación correcta de recurso, sin cruce de cuenta | Pendiente |
| VLC ausente, permiso revocado, archivo borrado | Error recuperable, sin borrar historial ni usar streaming | Pendiente |
| Solo audio y fin natural | Comportamiento documentado; no asumir contrato del video | Pendiente |

Criterio de salida: anexar evidencia y límites por versión. Si posición/retorno no son fiables en los escenarios soportados, documentar resultado negativo y mantener solo historial de apertura. No requerir reproductor integrado para cerrar la investigación.
