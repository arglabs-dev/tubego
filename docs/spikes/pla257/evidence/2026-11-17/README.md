# Verificación física del 17 de noviembre de 2026

- ADB operativo; serial R4HAEIVC9DQKXCDI, modelo DN2103, Android API 33.
- TubeGo instalado: 0.5.13, versionCode 19. No se actualizó, detuvo ni borró.
- VLC instalado: 3.7.2, versionCode 13070209.
- Probe no instalado inicialmente; build Gradle `-PvlcProbe :vlcProbe:assembleDebug` correcto e instalación inicial con `adb install` correcta.
- APK probe SHA-256: `6f4162911a8ed947f042913856b883ec114d50b40e8bffc988f9628e1ba14883`.
- Certificado probe SHA-256: `9afed715ef049bee6fba4041987c984d27b0ac701ef73d198d3f5db4700fced1` (Android Debug).
- PiP VLC: appops sin operaciones explícitas, modo predeterminado allow. Ajustes internos de VLC aún no verificados. El instalador registrado es `com.android.vending`; hashes de base y splits en `vlc-apks.json` (esto registra procedencia local, no una comparación con una distribución oficial).
- La Activity del probe inició; el teléfono está bloqueado (`showing=true`, `showingAndNotOccluded=true`). Se solicitó desbloqueo al usuario.
- Tras desbloquear, se ejecutaron los ensayos de la tabla inferior. `evidence.jsonl` conserva eventos crudos, incluidos intentos fallidos; `observations.jsonl` registra acciones y observaciones de ADB. `trials.json` se regenera con `python3 summarize.py`. No se borra evidencia anterior.

PLA-257 no se cierra. Las tres repeticiones de bloqueo ya tienen retorno capturado. Siguen pendientes emulador y proveedor productivo. La opción de LibVLC sigue siendo una recomendación, pendiente de elegir la experiencia de reproducción con el usuario.

## Verificación del repositorio

` :app:assembleDebug :app:testDebugUnitTest :app:lintDebug` completó correctamente con Gradle 8.11.1/JDK 21/API 35. 75 tests, cero fallos/errores/omitidos. Lint: {'Warning': 965}. No se instaló el APK productivo. `git diff --check` pasó. No se ejecutó instrumentación.

## Resultados reales

Todos los valores de posición/duración de la tabla son milisegundos. Cada fila
con tres request codes corresponde a tres ensayos distintos, no tres lecturas
del mismo ensayo. `RESULT_OK` es -1; cancelación es 0. El validador no se cambió.

| Caso | Request codes | Resultado observado |
|---|---|---|
| VLC cerrado, pausa y Atrás | 259–261 | 3 checkpoints válidos: 16502, 16509, 14504 / 45000 |
| VLC abierto en su interfaz, pausa y Atrás | 274–276 | 3 válidos: 14502, 14252, 14015 / 45000; inicio observado 10000 |
| Retroceso cerca de cero (5000 - 10000) | 277–279 | Inicio observado 0; salida válida 3651, 4401, 4402 / 45000 |
| Fin natural, iniciando en 33000 | 271–273 | 3 RESULT_OK con acción correcta y Long, pero 0/0: rechazados |
| Salto a 44000 con «Ir al tiempo» | 314–316 | Seek observado en MediaSession; 3 retornos 0/0, indistinguibles del fin natural |
| Home y PiP | 280–282 | PiP confirmado como tarea `mode=pinned`; cero callbacks durante PiP; válidos al restaurar y salir |
| force-stop de VLC | 266–268 | 3 cancelaciones, acción nula y extras ausentes; ningún checkpoint |
| Recreación del probe durante VLC | 291–293 | `caller_recreate`, destroy/recreated y 3 callbacks válidos asociados al UUID original |
| Muerte real del proceso del probe | 298–300 | `caller_die` y cambio de PID; 3 cancelaciones sin checkpoint; no se relanza la orden restaurada |
| Convertir a audio y dejar terminar | 301–303 | Callback válido al convertir (13005, 13511, 13253 / 45000), mientras MediaSession sigue playing; al terminar no llega otro resultado |
| Quitar tarjeta de recientes | 307–309 | Tarjeta «TubeGo VLC Probe», compartida por llamador/VLC; swipe elimina la reproducción; ningún callback en ventana observada ni al reabrir antes de abandonar |
| Reanudar fixture de 20 min alrededor del minuto 15 | 304–306 | Válidos 930507, 928107, 926707 / 1200008; cada reapertura inicia exactamente 10000 antes de su último checkpoint |
| Bloqueo de pantalla | 318–320 | En los tres, MediaSession pasa a stopped sin callback durante los 4 s observados de pantalla apagada. Tras desbloquear y salir con Atrás, checkpoints válidos: 35402, 11002, 11001 / 45000. No acredita audio continuo bajo bloqueo |

El request 318 debe contrastarse con `trials.json` al continuar: si una nueva
sesión añade ensayos, usar UUID/request, no solo el nombre del caso.

## Ajustes y límites

PiP estaba permitido por el modo predeterminado de AppOps y funcionó al pulsar
Home. El menú inicial de VLC indicaba **Repetir archivo actual** (XML adjunto).
Se cambió a **No repetir** para fin natural, salto al final y audio. Al acabar
esos ensayos se restauró **Repetir archivo actual**, confirmado en observaciones
`settings-restore-1`. No se modificaron ajustes de seguridad del teléfono. Los
ajustes internos de fondo y otros ajustes de VLC no se inventarían a partir de
este comportamiento: su revisión completa sigue pendiente.

Las lecturas de MediaSession con `adb shell dumpsys` son observación privilegiada
de diagnóstico. No son un mecanismo de checkpoints accesible a TubeGo en producción.
No se escuchó audio para confirmar salida física (volumen observado inicialmente 0);
se verificó el estado del motor/sesión y los callbacks.

El fixture largo se generó con FFmpeg 6.1.1 y dura 1200.008005 s; hash, bytes y
comando en `long-clip-metadata.json`. Solo se probó seek/reanudación cerca del
minuto 15: no se reprodujeron veinte minutos completos ni se midió cobertura.
Ambos fixtures pertenecen al probe. No se usaron archivos descargados de TubeGo.

Los ensayos `zero-1..3` (262–264) pausan demasiado pronto y reciben posición -1:
son retornos rechazados, no una validación del inicio cero. Los intentos
`caller_kill-v2` no mataron el proceso (`am kill` conservó el PID), y `v3` falló
al invocar kill. `caller_kill-v4` reveló una repetición de la orden restaurada;
se corrigió consumo persistente y se repitió como `v5` (298–300). Los primeros
intentos de salto mediante coordenadas fallaron parcialmente; no cuentan como
las tres repeticiones, que se hicieron con el diálogo y request 314–316. La
etiqueta audio-v1-1 se reutilizó antes de incorporar consumo persistente; solo
301–303 son la conversión a audio completada. No filtrar evidencia solo por etiqueta.

## Implicación para la implementación

Activity Result permite rescatar un checkpoint en salidas normales de esta
versión, pero no cubre guardado periódico, muerte del motor/llamador ni fin
fiable. El mismo 0/0 aparece tras fin natural y salto a 44 s. Ningún callback
registra qué tramos se vieron. No inferir Terminado de apertura, posición máxima,
resultado OK ni proximidad al final; conservar el último checkpoint válido.

La interfaz externa de VLC puede mantenerse con estos límites y marcado manual.
Para el objetivo completo se recomienda motor controlado por TubeGo (LibVLC,
interfaz y gestos propios), persistencia de checkpoints y cobertura, sincronización
por usuario/recurso y marcado manual independiente. No se añadió LibVLC ni se
presenta esa recomendación como decisión ya aprobada. Se solicitó la preferencia
al usuario mientras continuaban las pruebas.

## APK y conservación

El APK inicial y cada actualización del probe usan el mismo certificado SHA-256.
Antes de cada `install -r` se extrajo el APK instalado y se comparó su certificado
con el nuevo. `probe-update-1.json`, `-2.json`, `-3.json` y `probe-update.json`
registran hashes de las versiones diagnósticas sucesivas (versionCode 1). Las
actualizaciones preservaron la evidencia. No se instaló ningún APK en
`dev.arglabs.tubego`, ni se accedió a su sesión o descargas. No se hizo commit.

## Repetir y continuar

Los scripts finales están junto a este archivo. Ejecutarlos desde la raíz del
repositorio con un nombre de corrida nuevo para no repetir command_id, por ejemplo:

```sh
python3 docs/spikes/pla257/evidence/2026-11-17/driver.py warm nueva-corrida
python3 docs/spikes/pla257/evidence/2026-11-17/matrix_ui.py audio nueva-corrida
python3 docs/spikes/pla257/evidence/2026-11-17/jump_menu.py nueva-corrida
python3 docs/spikes/pla257/evidence/2026-11-17/long_resume.py nueva-corrida
python3 docs/spikes/pla257/evidence/2026-11-17/summarize.py
```

El driver rechaza sobrescribir evidencia local si el archivo del teléfono no
contiene ese prefijo. Los scripts de UI usan controles observados en este DN2103;
requieren pantalla desbloqueada y el ajuste de repetición adecuado. No ejecutarlos
sobre un ensayo pendiente. No instalan APK, no desinstalan paquetes ni borran datos.
Los scripts finales incorporan correcciones hechas durante las pruebas; los
intentos fallidos anteriores se mantienen en la evidencia, no se reetiquetan.

La consulta de Linear del 17 de noviembre confirmó **In Progress**. Se leyó la
tarjeta y no se escribió en ella. Su alcance actual es investigación, no introducir
un reproductor integrado. Se mantiene pendiente la elección de interfaz para el
seguimiento productivo solicitado por el usuario.

Verificación final del probe: `:vlcProbe:lintDebug` pasa con 18 advertencias y
cero errores. La auditoría de evidencia pasa con 63 ensayos y 59 callbacks
asociados; las ausencias incluyen recientes e intentos fallidos de automatización, no constituyen
resultados válidos. ResumePosition: 13 comprobaciones pasan; localización:
907 cadenas ES/EN verificadas. No se ejecutó instrumentación.

Estado histórico al ceder control antes de las 17:31 UTC (resuelto): el teléfono seguía bloqueado; ensayo 319 (`b0cd5a74-54f4-4a72-a5ad-e04577221655`, screen-lock-v1-2) persistido, sin resultado. No iniciar otro ensayo antes de capturar su retorno. Tras ese retorno falta la tercera repetición de bloqueo.

Continuación a las 17:31 UTC: se capturó el retorno válido del ensayo 319. Se inició el tercer y último bloqueo, request 320 (`a270846d-7a77-4978-9f54-34715da54589`), sin callback durante pantalla apagada. Se solicitó desbloqueo; no iniciar otra reproducción mientras esté pendiente.


Actualización final a las 17:40 UTC: el ensayo 320 devolvió RESULT_OK, acción
`org.videolan.vlc.player.result`, Long 11001/45000 y checkpoint aceptado al salir
con Atrás. No había resultado antes de salir. Las tres repeticiones de bloqueo
están capturadas; no queda ensayo pendiente en el probe. El teléfono se deja
desbloqueado, sin volver a bloquearlo. La evidencia de retorno tras desbloqueo
no demuestra continuidad de audio: en pantalla apagada la sesión se observó stopped.
No se aplicaron actualizaciones a TubeGo ni se modificó Linear.
