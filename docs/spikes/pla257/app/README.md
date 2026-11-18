# Probe manual de VLC externo

Aplicación de diagnóstico, **no reproductor complementario productivo**.
Paquete `dev.arglabs.tubego.vlcprobe`, distinto de `dev.arglabs.tubego`.
No solicita permisos de red/almacenamiento, no lee sesiones de TubeGo ni archivos
descargados. Su único medio es el MP4 sintético de 45 s ya usado en instrumentación.
El proveedor privado concede lectura de ese archivo mediante un URI exacto.

## Compilar e instalar

Desde la raíz del repositorio, con JDK 17+, SDK 35 y Gradle 8.11.1:

```sh
gradle --no-daemon -p android -PvlcProbe :vlcProbe:assembleDebug
adb -s R4HAEIVC9DQKXCDI install docs/spikes/pla257/app/build/outputs/apk/debug/vlcProbe-debug.apk
adb -s R4HAEIVC9DQKXCDI shell am start -n dev.arglabs.tubego.vlcprobe/dev.arglabs.tubego.spike.ProbeActivity
```

La instalación inicial no lleva `-r`: si ya existe el probe, se detiene. Para
actualizarlo se debe comprobar primero la firma y usar `install -r`. Nunca
desinstalar TubeGo ni instalar otro APK sobre su paquete para ejecutar este probe.
Si hay conflicto de firma, detenerse. El módulo solo se incluye con `-PvlcProbe`;
los builds normales de TubeGo no lo incorporan.

En esta sesión se generó además un APK con AAPT2/D8 y una clave de diagnóstico
temporal porque Gradle no pudo inicializar su cache de bibliotecas nativas en el
entorno restringido. No se instaló. Los builds posteriores de Gradle pueden usar
otra firma: no asumir compatibilidad de actualización del probe.

## Ejecutar y extraer evidencia

1. Completar antes los tutoriales de VLC. Anotar ajustes de reproducción en fondo,
   PiP y permiso PiP; el probe no los cambia. Registrar origen y hash del APK VLC.
2. Escribir el nombre del caso. El valor inicial 20000 ms con retroceso de 10 s
   solicita iniciar en el segundo 10. Observar y anotar dónde comienza realmente.
3. Pulsar **Abrir clip en VLC y capturar resultado**. Para la salida normal, pausar
   cerca del segundo 20 y volver con Atrás. El callback debe aparecer en evidencia
   con código, acción, valores y tipos, aun cuando el helper lo rechace.
4. Repetir con VLC previamente abierto y cerrado, inicio cerca de cero, final
   natural, Home, PiP, bloqueo, conversión a audio, cierre desde recientes y
   force-stop de VLC. Repetir cada caso tres veces. No usar force-stop de TubeGo
   productivo; para muerte del llamador usar únicamente el paquete del probe.
5. Si no retorna resultado, volver al probe y pulsar Actualizar. Registrar la
   ausencia y el comportamiento observado. **Cerrar ensayo pendiente** conserva
   la evidencia y permite otro caso; no significa que VLC haya parado.

```sh
adb -s R4HAEIVC9DQKXCDI exec-out run-as dev.arglabs.tubego.vlcprobe cat files/evidence.jsonl > /tmp/pla257-vlc-evidence.jsonl
```

La vista en pantalla muestra el mismo archivo. Cada evento se escribe y se fuerza
a disco; el UUID del ensayo se persiste antes de lanzar VLC y sobrevive recreación
o muerte del llamador. Un cierre forzado no garantiza eventos de ciclo de vida.
Se admite un ensayo pendiente; cerrarlo manualmente termina su asociación.
Cada ensayo recibe un request code distinto, persistido con su UUID. Un resultado
tardío conserva la asociación al ensayo original y no cierra otro ensayo pendiente.
Antes del siguiente ensayo, salir de la reproducción anterior y anotar cualquier
resultado tardío. El registro no se borra desde la UI.

Clip SHA-256: `88458d71d326574e0ba9a86a853724d54c4833b787cfb453631cb5282bbc2ef3`.
Para cada caso guardar también observación manual de inicio, pausa, fin y cambios
de modo. El probe registra versiones instaladas (si son visibles), modelo/API,
tiempo UTC/monotónico y hash del clip. La duración exacta debe contrastarse con
los metadatos del clip; no deducirla solo del valor devuelto por VLC.

## Límites

- **Ejecutado en DN2103/API 33 con VLC 3.7.2 (13070209).** Véase el
  [registro físico](../evidence/2026-11-17/README.md). El emulador y el proveedor
  productivo siguen pendientes; compilar por sí solo no demuestra callbacks.
- Solo mide `Activity Result`; no observa periódicamente la posición del motor.
- `accepted_checkpoint` evalúa el helper existente. Un rechazo no elimina los
  valores crudos y debe investigarse por versión; no flexibilizarlo a ciegas.
- Un callback válido es un punto observado, no prueba de fin ni cobertura.
- El retorno al convertir a audio puede preceder al fin real de reproducción.
- El clip y proveedor son aislados. Una integración posterior debe probarse de
  nuevo dentro de TubeGo con su proveedor y asociación usuario/recurso/sesión.
- El fixture largo de veinte minutos ya se ensayó cerca del minuto 15, tres veces.
  No equivale a reproducirlo completo; faltan proveedor productivo y emulador.
- Dejar PLA-257 en progreso hasta reunir y evaluar evidencia real.

## Automatización diagnóstica

El probe acepta órdenes explícitas en su Activity. La orden RUN se consume una
sola vez de forma persistente por `command_id`, incluyendo restauración tras
muerte de proceso. Usar un identificador nuevo por ensayo. Android requiere
`--activity-single-top` para entregar los extras al probe ya abierto.

```sh
adb -s R4HAEIVC9DQKXCDI shell am start --activity-single-top \
  -a dev.arglabs.tubego.vlcprobe.RUN \
  -n dev.arglabs.tubego.vlcprobe/dev.arglabs.tubego.spike.ProbeActivity \
  --es command_id normal-001 --es scenario normal-001 --el saved_ms 20000 --ei rewind_s 10
```

La Activity sigue llamando a `startActivityForResult`; no se lanza VLC directamente
con `am start` para probar el contrato. `--es caller_event recreate` o
`--es caller_event die` recrea o mata **solo el probe**, tres segundos después de
lanzar. El evento se registra antes de ejecutarse. ABANDON, con el mismo componente
y acción `dev.arglabs.tubego.vlcprobe.ABANDON`, cierra la asociación pendiente sin
borrar evidencia. La captura incluye los tipos de todos los extras y los valores
escalares; no se altera el validador.

### Fixture largo aislado

Generar fuera del repositorio (no añadir el MP4 ni APK ni claves a Git):

```sh
ffmpeg -hide_banner -loglevel error -stream_loop -1 \
  -i android/app/src/androidTest/res/raw/vlc_sample.mp4 -t 1200 \
  -c copy -movflags +faststart /tmp/pla257-long.mp4
adb -s R4HAEIVC9DQKXCDI push /tmp/pla257-long.mp4 /data/local/tmp/pla257-long.mp4
adb -s R4HAEIVC9DQKXCDI shell 'run-as dev.arglabs.tubego.vlcprobe sh -c "cat /data/local/tmp/pla257-long.mp4 > files/long.mp4"'
```

Seleccionar el checkbox del fixture largo o agregar `--ez long_clip true` a RUN.
El proveedor solo admite dos URI exactos, sample.mp4 y long.mp4; el segundo se
limita a `files/long.mp4` del probe. No acepta rutas ni archivos de TubeGo. Antes
de lanzar se extrae la duración y se calcula el SHA-256 del archivo real en flujo.
El límite de posición se valida contra sus metadatos. Registrar comando de
creación, versión de FFmpeg, hash y duración: los bytes pueden variar entre
versiones del generador.
