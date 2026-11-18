# PLA-257 — posición y continuación con VLC externo

Estado: investigación de código y prototipo aislado. **Validación Android/VLC pendiente; no cerrar la tarjeta como Done.** Fecha: 2026-10-08.

## Sesión 2026-11-17

Git comenzó limpio en `main`. Se consultó PLA-257 en Linear: sigue **In Progress**. No se modificó la tarjeta. `adb devices -l` volvió a fallar antes de enumerar dispositivos: `could not install *smartsocket* listener: Operation not permitted`. Por tanto, las versiones TubeGo 0.5.13 (19), VLC 3.7.2 (13070209), modelo DN2103 y serial R4HAEIVC9DQKXCDI son datos proporcionados por el usuario, **no verificados por esta sesión**. No hubo instalación, desinstalación ni acceso a datos del teléfono. La matriz continúa pendiente.

Se añadió un [APK de diagnóstico aislado y su procedimiento](pla257/app/README.md), que usa el helper existente, una Activity real con `startActivityForResult`, proveedor privado y el clip sintético existente. Persiste ensayos y resultados crudos. Compilarlo no demuestra callbacks ni compatibilidad del teléfono. No usa los archivos, sesiones ni proveedor productivo de TubeGo.

La preferencia de reproductor externo se implementó en ambos flujos de la app: Ajustes → Preferencias de reproducción y descarga → Reproductor externo predeterminado. Guarda componente por audio/video en este teléfono, permite Preguntar cada vez, destaca VLC y vuelve al selector si el componente no resuelve para el MIME actual. Sigue registrando solo aperturas; no habilita seguimiento no validado.

Verificaciones de esta sesión: fuentes Java productivas compiladas contra API 35 y dependencias locales; catálogo ES/EN validado; las 13 comprobaciones `ResumePositionTest` pasan; fuentes del probe compiladas, empaquetadas con AAPT2/D8 y firma del APK comprobada. Hubo advertencias de anotaciones Kotlin ausentes al compilar contra las dependencias cacheadas. El build Gradle completo (incluidos lint/JUnit) no se ejecutó: el entorno impide escribir el archivo de bloqueo de la biblioteca nativa en `~/.gradle/native`. Estas comprobaciones parciales no equivalen a un build Gradle ni a pruebas instrumentadas.

### Recomendación provisional y trabajo siguiente

| Alternativa | Seguimiento | Experiencia y coste |
|---|---|---|
| VLC externo | Posible checkpoint al salir, pendiente de matriz; sin canal periódico en este prototipo | Conserva interfaz y gestos de VLC; útil como opción externa |
| LibVLC dentro de TubeGo | Permite observar eventos del motor y persistir mientras reproduce | Requiere controles propios, gestos de avance/volumen/brillo, servicio multimedia, PiP y manejo de ciclo de vida |
| App complementaria | Si es propia, puede exponer un protocolo de checkpoints | Duplica instalación, autenticación, permisos y ciclo de vida; no ofrece ventaja clara frente a integrar el motor |

Para cumplir **todos** los comportamientos pedidos, la recomendación provisional es LibVLC integrado más la opción de reproductor externo. No se agregó LibVLC en esta entrega: la prueba en dispositivo, la decisión final y la implementación del seguimiento siguen pendientes. Un callback externo incluso fiable no proporciona por sí solo guardado periódico, cobertura vista ni recuperación del último checkpoint ante muerte del reproductor. El modo externo deberá explicar su alcance al usuario y permitir el marcado manual.

El [README oficial de VideoLAN](https://github.com/videolan/vlc-android/blob/master/README.md?plain=1), consultado el 2026-11-17, distingue la aplicación VLC (GPLv2 o posterior) del motor Android LibVLC (LGPLv2). No reutilizar su UI como si fuera parte de la API del motor. Antes de distribuir una integración hay que revisar la versión/binarios concretos y sus licencias y componentes; esta nota no es un análisis de cumplimiento. Crear controles y gestos propios evita incorporar por accidente código de la UI GPL.

La implementación posterior debe separar checkpoint, cobertura y marcado manual:

- Identidad: origen + usuario + recurso + revisión del medio; el dispositivo y el ID de sesión identifican el origen del evento. Nunca asociar por título ni URI devuelta.
- Persistencia: checkpoint local atómico cada 5 s mientras se reproduce, al pausar y salir; reanudar con `max(0, posición - retroceso)` usando la preferencia existente (10 s). Guardar antes de confirmar eventos y conservar el último checkpoint válido tras un cierre inesperado. El intervalo es una propuesta, no una garantía de precisión.
- Cobertura: acumular intervalos observados durante reproducción continua y acotados por tiempo transcurrido y velocidad; un seek abre otro intervalo y no acredita el hueco saltado. El fin natural por sí solo tampoco demuestra haber visto todo.
- Estados: Sin empezar sin reproducción observada; En progreso al recibir evidencia válida; Terminado por marcado manual o por política explícita que combine cobertura suficiente y fin natural. Definir umbral antes de habilitar finalización automática. Abrir un archivo no cambia estos estados.
- Sincronización: outbox durable sin conexión, eventos idempotentes con secuencia por sesión, autorización del recurso en servidor y revisión de conflicto para marcado/desmarcado manual. No resolver conflictos por el máximo de posición: impediría retroceder y confundiría saltos con cobertura. Cambios de cuenta deben cancelar/aislar operaciones de la cuenta anterior.
- Probar reanudación entre sesiones/dispositivos, desconexión/reconexión, logout, seek al final, reproducción acelerada, finalización natural, marcado/desmarcado offline y cierres inesperados antes de publicar seguimiento.

## Decisión para el MVP (histórica, 2026-10-08)

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

## Continuación en dispositivo: 2026-11-17

ADB operativo en DN2103/API 33. Versiones instaladas confirmadas: TubeGo
0.5.13 (19), VLC 3.7.2 (13070209). Se compiló el probe con Gradle y se
instaló por primera vez en su paquete aislado. TubeGo no se actualizó ni
se borraron datos. Tras desbloquear se ejecutaron los ensayos físicos del registro enlazado: hay
callbacks reales de salida, PiP, recreación, audio y reanudación de un fixture
de veinte minutos. Se completaron las tres repeticiones de bloqueo y sus retornos después de
desbloquear y salir con Atrás (request 318–320).
Véase [registro y evidencia cruda](pla257/evidence/2026-11-17/README.md).
Las referencias anteriores al bloqueo de ADB corresponden a la sesión previa.

### Hallazgos físicos que cambian la evaluación

VLC 3.7.2 devuelve RESULT_OK con posición/duración Long válidas al salir normalmente
(6 ensayos, VLC cerrado o abierto), y aplica el retroceso incluso alrededor del
minuto 15 de un archivo sintético de 20 minutos. La matriz detallada y los request
codes están en el [registro físico](pla257/evidence/2026-11-17/README.md).

En tres finales naturales y tres saltos a 44 s devuelve el mismo 0/0; el helper lo
rechaza. Tres force-stop de VLC y tres muertes reales del probe dan cancelación
sin checkpoint. PiP no emite checkpoints durante reproducción; convertir a audio
emite un resultado al cambiar de modo y no otro al terminar. Quitar de recientes
la tarjeta compartida del probe/VLC tampoco entrega resultado en la observación.
Recrear la Activity sí conserva la asociación y devuelve posición (3/3).

Por tanto, esta versión permite checkpoints oportunistas al salir, pero Activity
Result no cumple el guardado periódico ni acredita cobertura/finalización. No se
flexibiliza el validador para aceptar -1, 0/0 o duración cero. La implementación
productiva de progreso/sincronización y la elección de interfaz siguen pendientes;
no se añadió LibVLC y PLA-257 no se marcó terminada. En los tres bloqueos la sesión multimedia se observó stopped y no hubo callback
durante la ventana de cuatro segundos; tras desbloquear y salir con Atrás
se recibieron checkpoints válidos 35402, 11002 y 11001 / 45000. Esto no acredita
audio continuo bajo bloqueo. No queda ensayo pendiente; consultar el registro físico.
La tabla original se conserva como alcance pendiente de cierre completo, no como
negación de los resultados físicos anexos.


## Implementación posterior autorizada

El 17 de noviembre el usuario pidió continuar con la implementación después de
revisar los resultados. Se implementa LibVLC integrado conservando el modo externo;
[detalle y pruebas](../PLAYBACK_PROGRESS.md). Las recomendaciones anteriores
reflejan el estado previo a esa autorización. PLA-257 no se cierra ni modifica
automáticamente; los ensayos externos conservan su alcance y limitaciones.
