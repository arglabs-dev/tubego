# Progreso de reproducción — Android 0.6.1

Implementación posterior a la matriz de PLA-257. El usuario autorizó continuar
con LibVLC integrado el 17 de noviembre de 2026. PLA-257 no se modifica ni se
cierra automáticamente por este cambio.

## Uso

Reproducir abre el motor integrado. Funciona con los archivos locales existentes,
sin streaming ni descargar otra copia. La interfaz reproduce la distribución del
reproductor VLC Android 3.7.2: controles blancos sobre degradados, barra naranja,
reproducción central, pistas y orientación a la izquierda, formato y menú lateral
a la derecha. Los controles se ocultan al reproducir y reaparecen con un toque.
La selección externa para video/audio se conserva en Ajustes → Preferencias de
reproducción y descarga; Abrir externamente permite usarla una sola vez.

Gestos del integrado, implementados en TubeGo:

- Deslizar horizontalmente previsualiza el destino; al soltar cambia de posición.
  El desplazamiento considera distancia física y permite ajuste fino diagonal.
- Vertical izquierda: brillo de esta ventana. Derecha: volumen multimedia del teléfono.
- Doble toque en los cuartos exteriores: ±10 s; toques adicionales acumulan saltos.
  Doble toque central: pausa/reproducción.
- Mantener pulsado: 2× temporal; al soltar restaura la velocidad previa.
- Pellizcar: ajustar a pantalla/restaurar formato anterior.

El menú ofrece bloqueo con deslizador para desbloquear, temporizador, velocidad,
ir a tiempo, ecualizador, PiP, repetición, información, bucle A-B y configuración
de gestos. Pistas de audio/subtítulos y sus retardos se seleccionan en el control
inferior izquierdo. Las opciones de controles son locales al teléfono.

El integrado se pausa al salir o bloquear la pantalla. La opción Reproductor
miniatura entra explícitamente en PiP y sigue guardando checkpoints mientras se
reproduce; requiere permiso del sistema. No implementa un servicio de audio en
segundo plano. Solicita foco de audio y pausa ante pérdida de foco. El icono de
transmisión abre la configuración de proyección de Android; no implementa un
renderer Chromecast propio, ni la biblioteca/listas o descarga de subtítulos de
la aplicación VLC. La interfaz e iconos son una implementación propia; no se
copió código ni recursos de la interfaz GPL de VLC.

## Posición y estados

- Checkpoint local cada cinco segundos y al pausar/salir. Cada archivo se escribe
  en temporal, se fuerza a disco y se renombra atómicamente. Tras un cierre brusco
  se recupera el último checkpoint durable, sin garantizar precisión absoluta.
- Reanudar aplica `max(0, posición - retroceso)`. Se reutiliza la preferencia de la
  cuenta, inicialmente 10 s, entre 0 y 120. Una búsqueda nativa pendiente no puede
  reemplazar el checkpoint anterior por cero.
- Sin empezar: no hay posición positiva ni cobertura. Abrir no registra progreso.
- En progreso: existe posición o cobertura observada, sin condición de terminado.
- Terminado automático: al menos 95 % de cobertura observada y un EndReached real.
  Buscar al final, posición máxima, apertura o descarga completada no bastan.
- Marcar como visto/no visto es un registro separado. Tiene prioridad incluso
  sobre reproducciones posteriores; desmarcar conserva la posición para continuar.
  Para volver a marcar visto se usa la misma acción explícita.

La cobertura es una unión de intervalos, no una suma de tiempos ni posiciones
máximas. Se muestrea cada 500 ms; búsquedas explícitas, pausas y huecos de muestreo
mayores de 2.5 s rompen la continuidad. Se contempla que LibVLC entregue tiempos
cuantizados: duplicados no reinician el reloj y se tolera hasta un segundo de
jitter. No equivale a demostrar atención humana. Como máximo se retienen 4096
intervalos; si se supera, se descartan los más pequeños, conservadoramente.

## Persistencia y sincronización

`PlaybackStore` guarda JSON en `playback/<resource UUID>.json` dentro de la raíz
privada existente de origen/usuario/dispositivo. Se verifica la sesión aprobada y
el token antes de leer/escribir; cambiar cuenta detiene el reproductor. No cambia
ni elimina archivos descargados, sesiones, manifiestos ni preferencias anteriores.
La limpieza explícita de la raíz privada mantiene las reglas existentes; el
progreso que ya llegó al servidor permanece en el historial de la cuenta.

`PlaybackProgress` reintenta desde los trabajos de transferencia y al actualizar
la biblioteca. Su revisión dirty cambia con cada edición. Una respuesta tardía no
puede dar por enviada una modificación más reciente. Se conserva el pendiente tras
error de red/servidor; un recurso rechazado no impide intentar los siguientes.

Backend aditivo, sin migración de esquema:

- `PUT /api/v1/resources/{resource_id}/playback`: fusiona un snapshot privado.
- `GET /api/v1/playback?limit=100&after=...`: sincronización privada paginada.
- Las fichas de biblioteca añaden `playback`, sin alterar los campos existentes.

Posición y marcado manual son registros independientes, ordenados por instante
observado e ID de observación UUID. Gana la observación más reciente, nunca el
minuto más alto. La cobertura se une de forma idempotente. Repetir un envío tras
perder la respuesta no duplica cobertura ni revierte un marcado más reciente.
Los relojes de dispositivos deben ser correctos: instantes más de cinco minutos
en el futuro se rechazan y se conserva el pendiente para corregir/reintentar.
No se promete ordenar cronológicamente relojes arbitrariamente desajustados.
Administradores no tienen acceso implícito al progreso de otros usuarios.

Es necesario desplegar el backend de este cambio para sincronizar entre equipos.
Con el backend anterior, los checkpoints y estados siguen guardándose localmente;
las respuestas 404 no borran los pendientes. Esta sesión no despliega el servidor.

## Motor, fuentes y compilación

`org.videolan.android:libvlc-all:3.7.7`, fijado sin rango dinámico. Requiere
compileSdk 36; minSdk permanece 29 y targetSdk 35. Gradle 8.11.1 y JDK 21.
El motor ARM64 observado se identifica como 3.0.25 Vetinari,
`3.0.24-35-gc7e5d68ee5`. La interfaz y los gestos se implementan en Java propio, usando el comportamiento de VLC como referencia.

Fuentes primarias:

- [POM y fuentes Java del artefacto exacto](https://repo.maven.apache.org/maven2/org/videolan/android/libvlc-all/3.7.7/).
- [Tag Android libvlc-3.7.7](https://github.com/videolan/vlc-android/tree/libvlc-3.7.7), commit `7a7c8f14be6759f9bb1b9a934fd086259473e20e`.
- [Revisión nativa observada](https://code.videolan.org/videolan/vlc/-/tree/c7e5d68ee5).

El POM y las cabeceras Java declaran LGPL 2.1 (o posterior según cabeceras).
Los Lua del paquete conservan sus fuentes y avisos originales, incluidos scripts
GPL 2 o posterior. Se empaquetan LGPL-2.1, GPL-2 y aviso de procedencia en assets,
consultables en Ayuda → Licencias de reproducción. El AAR no se modifica.

```sh
JAVA_HOME=/home/areyes/.local/share/tubego-toolchain/jdk/jdk-21.0.12.1+1 \
ANDROID_HOME=/home/areyes/.local/share/tubego-toolchain/android-sdk \
/home/areyes/.local/share/tubego-toolchain/gradle-8.11.1/bin/gradle \
  --no-daemon -p android :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

## Pruebas de dispositivo aisladas

`-PplaybackHarness` cambia solo el applicationId a `dev.arglabs.tubego.playbacktest`.
La instrumentación comprueba ese ID antes de crear una sesión o fixture sintético;
no admite ejecutarse contra TubeGo productivo. No usar ese flag para una entrega.
Reutiliza el clip de 45 s y el proveedor productivo dentro del paquete aislado.

```sh
gradle --no-daemon -p android -PplaybackHarness :app:assembleDebug :app:assembleDebugAndroidTest
# Inspeccionar si el paquete existe; comparar firma antes de actualizar con -r.
adb -s R4HAEIVC9DQKXCDI shell am instrument -w \
  -e class dev.arglabs.tubego.PlaybackInstrumentedTest \
  dev.arglabs.tubego.playbacktest.test/android.test.InstrumentationTestRunner
```

`PlaybackCrashInstrumentedTest` se ejecuta por métodos separados. Primero
`testPrepareAbruptExit`; esperar `files/crash-ready.json` con ready=true. Después
usar force-stop **solo** en `dev.arglabs.tubego.playbacktest` (la interrupción del
runner es esperada), y ejecutar `testResumeAfterAbruptExit`. Nunca aplicar esa
preparación ni ese cierre forzado al paquete productivo. No se desinstala nada.

[Evidencia de ejecución](playback/evidence/2026-11-17/README.md).

[Evidencia de UI y gestos 0.6.1](playback/evidence/2026-11-17-ui/README.md).
