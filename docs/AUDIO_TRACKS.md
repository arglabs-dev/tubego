# Pistas de audio por solicitud — PLA-265

Al agregar o compartir un enlace, el análisis obtiene las pistas de audio que la
fuente expone a yt-dlp. La hoja **Agregar enlace** permite elegir **Original** o
una pista con idioma/variante regional. **Doblaje** y **Doblaje automático** solo
se muestran cuando la fuente lo indica; de otro modo aparece **Procedencia no
indicada**. Los subtítulos y las traducciones de subtítulos nunca se ofrecen como
audio. Sin español se informa y se puede continuar con Original o cerrar.

La elección también se aplica a **Solo audio**. Se guarda en la cola local,
solicitud, recurso, historial y sincronización de dispositivos. La biblioteca y
el detalle muestran el idioma efectivo y su verificación. Enlaces de YouTube con
pistas alternativas abren la hoja aunque la preferencia de calidad omita la
pregunta habitual; las fuentes sin alternativas y la entrada sin conexión
conservan el flujo original.

**Cambiar pista de audio** está disponible en el detalle de una solicitud en cola.
Requiere conexión y el servidor comprueba, en una transacción, que el trabajador
no la haya iniciado y que no exista ya esa variante. Si el procesamiento comenzó,
se debe agregar otra solicitud. No se cambian archivos que ya se están preparando
ni los de otras cuentas. El selector de `/send` permite hacer la misma elección
antes de enviar un enlace desde la laptop.

## Contrato y selección

* `POST /api/v1/media/analyze`: añade `audio_tracks` con `{id, language, kind}`.
* `POST /api/v1/resources`: acepta `audio_track`, predeterminado `original`.
  El identificador semántico es, por ejemplo, `es-419~auto` o `es~unknown`.
  No se admiten expresiones de selección, URLs de medios ni IDs de formato del cliente.
* `POST /api/v1/resources/{id}/audio-track`: acepta `audio_track` y un UUID
  `request_id`. Solo antes del primer intento y sin archivo listo; repeticiones
  exactas son idempotentes. Conflictos responden 409, recursos ajenos 404.
* Biblioteca, tareas y entregas mantienen la elección; biblioteca y entregas
  incluyen `audio_resolved` con idioma, procedencia, ID efectivo y `verified`.

La identidad del recurso incluye pista, formato y calidad, dentro de la cuenta.
Original conserva las claves previas: una actualización no duplica el historial
anterior. Original se fija a la pista marcada como original cuando es inequívoca;
con metadatos incompletos conserva el comportamiento del extractor. Las elecciones
explícitas se vuelven a resolver al descargar: si desaparecen, fallan con
`audio_track_unavailable`, sin sustituirlas por otro audio.

Se mantienen todos los formatos de video sin audio y solo los formatos de la pista
resuelta. El selector de calidad existente elige y combina formatos de esa lista;
no hay un fallback capaz de introducir audio de otro idioma. Se comprueba el
formato de audio realmente elegido y se usa FFmpeg/ffprobe para comprobar el
archivo final antes de publicarlo. Una mezcla fallida o sin audio produce
`audio_verification_failed`; el usuario puede reintentar o elegir otra pista.

Las etiquetas de idioma de los contenedores no identifican por sí solas la pista:
en la prueba real YouTube entregó el formato español `251-11` con una etiqueta
interna `eng`. Tras comprobar la identidad del formato elegido, se escriben las
etiquetas correctas y se comprueban con ffprobe. La región se conserva en el
historial, aunque MP4/MP3 solo representen el idioma base. Esta verificación
comprueba selección, flujo y etiquetas; no es reconocimiento de voz ni traducción.
Las herramientas de verificación usan exclusivamente archivos locales. La reserva
de espacio y la cancelación se comprueban antes y después de la operación local.

## Validación reproducible

```sh
python -m pytest -q backend/tests/test_audio_tracks.py
./scripts/build_android.sh
adb shell am instrument -w -e class dev.arglabs.tubego.AudioTrackInstrumentedTest \
  dev.arglabs.tubego.test/android.test.InstrumentationTestRunner
```

La prueba Android usa la API HTTPS desechable de `android/ui-tests/README.md`.
No se conecta a producción. El POST de análisis solo devuelve metadatos sintéticos;
las mutaciones siguen bloqueadas. Comprueba variantes regionales, procedencia
manual/automática, ausencia de español, cancelación y cola persistente de Solo audio.

Las pruebas Python cubren original, español manual, automático, `es-es`, `es-419`,
solo audio, fuentes sin pistas, subtítulos sin audio, identidad por usuario,
repetición de solicitudes, edición antes/después de la toma por el trabajador,
selección real de yt-dlp, descarga HTTP, mezcla y entrega del archivo. Las pistas
sintéticas tienen frecuencias distintas: se decodifican los bytes entregados y se
comprueba qué pista contienen, independientemente de sus etiquetas. Las URLs
`http://video.example/v.mp4`, `/a-en.m4a`, `/a-es.m4a` y `/a-es-auto.m4a` solo existen
en ese servidor de prueba local controlado, no son servicios públicos.

### Comprobación pública del 29 de octubre de 2026

| URL | Resultado observado |
| --- | --- |
| https://www.youtube.com/watch?v=TJ2ifmkGGus | Original `en` y español `es`, entre otros idiomas. La fuente no indicó procedencia manual/automática para español. Descarga completa a 480p con `es~unknown`, formato de audio `251-11`, 33,612,010 bytes, tarea completada y `audio_resolved.verified=true`. |
| https://www.youtube.com/watch?v=jNQXAC9IVRw | No expuso catálogo de pistas de audio; se conserva Original y no se ofrece español. |

No se presume que estas URLs mantengan los mismos formatos o sigan disponibles.
El doblaje automático y las variantes regionales se validaron con metadatos y medios
sintéticos reproducibles; no se afirma haber encontrado una muestra pública
permanente de cada combinación. No se solicitan cookies ni se evaden restricciones.

Para verificar en VLC el archivo real ya descargado, copiarlo al directorio externo
de TubeGo en un emulador desechable y ejecutar:

```sh
adb shell am instrument -w \
  -e class dev.arglabs.tubego.VlcIntegrationInstrumentedTest#testChosenSourceAudioFilePlaysOfflineInVlc \
  -e audioClip /sdcard/Android/data/dev.arglabs.tubego/files/audio-track-live.mp4 \
  dev.arglabs.tubego.test/android.test.InstrumentationTestRunner
```

Se crea un registro privado temporal, se concede una URI de solo lectura al VLC
instalado, y se verifica su estado de reproducción local. La ejecución del 29 de octubre de 2026 pasó con el Wi-Fi del emulador desactivado
y el archivo real español indicado en la tabla. Esta comprobación no
necesita la API y no intenta identificar el idioma hablado mediante reconocimiento.

Referencia de selección y mezcla: [documentación oficial de yt-dlp](https://github.com/yt-dlp/yt-dlp#format-selection),
verificada también contra el código instalado de yt-dlp 2026.8.19.
