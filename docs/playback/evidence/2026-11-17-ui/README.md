# Interfaz de reproducción 0.6.1 — DN2103, Android 13/API 33

Referencia instalada: VLC 3.7.2, versionCode 13070209. Se comparó el reproductor
pausado y su panel lateral con el clip sintético de 45 segundos. No se utilizaron
videos, sesiones ni descargas de la cuenta productiva para los ensayos.

Implementación propia de controles, iconos y gestos; no se incorporaron recursos
o código de la UI de VLC. Referencias de comportamiento:

- https://docs.videolan.me/vlc-user/android/3.X/en/video/video_player.html
- https://docs.videolan.me/vlc-android-user/more/settings/extra_video.html

Las capturas del harness verifican disposición vertical/horizontal, barra naranja,
controles blancos, panel lateral desplazable y conservación de posición al girar.
El botón de transmisión abre proyección Android; no es un renderer Chromecast.
No se implementan la biblioteca/listas de VLC ni descarga de subtítulos.

## Validación

Los archivos de resultados adjuntos registran los ensayos reales. El harness usa
`dev.arglabs.tubego.playbacktest` con sesiones y medios sintéticos, aislado de
`dev.arglabs.tubego`. La actualización productiva usa firma coincidente e
`adb install -r`; `production-update.json` conserva versiones, UID, directorio,
hash de sesión cifrada y cantidad de archivos privados antes/después.

La matriz original de callbacks externos sigue en la evidencia de PLA-257;
estas pruebas de UI no la sustituyen ni cierran la tarjeta. El backend de progreso
sigue requiriendo despliegue para sincronización entre dispositivos.

Resultado: 10/10 ensayos nativos pasan (170.081 s), 87 pruebas JVM sin fallos,
998 cadenas ES/EN verificadas y lint sin errores. Las primeras ejecuciones
ajustaron sincronización del harness, desplazamiento del panel y búsqueda del
botón ACEPTAR transformado a mayúsculas; el registro adjunto es la ejecución final.

Casos nativos: ocultación y ambas orientaciones; rotación durante reproducción;
doble/triple toque y 2× temporal/restauración; bloqueo/desbloqueo; PiP y checkpoints;
pellizco y configuración; pausa/búsqueda/reanudación; brillo/volumen/avance;
fin natural con cobertura; cambio de cuenta. Clip corto: no demuestra sesiones de
horas, todos los códecs ni todos los modelos Android.

Actualización productiva comprobada: 0.6.0 (20) → 0.6.1 (21), firma coincidente,
UID/directorio sin cambios, hash de sesión cifrada idéntico y 220 → 220 archivos
privados. Se abrió Biblioteca tras actualizar. No se desinstaló ni se borraron datos.
El ensayo visual adicional de ambas orientaciones pasa (1/1, 13.544 s); se
inspeccionaron las capturas del contenido sintético y el menú oscuro renderizado.
