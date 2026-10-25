# Idioma y ayuda móvil (PLA-255)

Tubego usa español e inglés para sus pantallas nativas. Antes de elegir un idioma,
usa español si el idioma del sistema es español; en los demás casos usa inglés.
La preferencia pertenece a la cuenta y al origen HTTPS, no al teléfono ni al
idioma global de Android. Los títulos, enlaces, correos y nombres de versiones
son datos del usuario: no se traducen. Los códigos de estado y error se presentan
con mensajes legibles, sin mostrar registros del extractor ni rutas del servidor.

`LanguagePreferences.saveLocal` conserva el idioma y una revisión pendiente aun
sin red. La cola común de PLA-246 registra su `Dispatcher` desde la aplicación;
recibe `{origin,user_id,language,revision}` y encola la operación `language`.
El ACK debe llamar `acknowledge(context,origin,user_id,revision)`: un ACK viejo no
borra un cambio posterior. El arranque/flush consulta `pending` para recuperar
el cambio si murió el proceso antes de encolarlo. No hay una segunda cola ni un
job independiente para idiomas. Sin PLA-246, la pantalla indica pendiente y no
pretende que se sincronizó. `refresh` lee GET `/account/preferences/language`
fuera del bloqueo de sesión, protege cambios pendientes y respuestas de otra
sesión. Main al volver y el flush de PLA-246 actualizan el idioma remoto.
PUT al mismo endpoint es el contrato online; cuerpo estricto `{language:es|en}`.
El backend valida sesión, correo y aprobación otra vez dentro de la transacción.
La preferencia de idioma no modifica calidad ni retroceso al guardar preferencias.

Ayuda funciona sin conexión. Explica pegar/compartir enlaces, calidad/audio,
Wi-Fi y autorización de datos por archivo y teléfono, pausa/reanudación,
almacenamiento persistente, historial y recuperación, borrado por dispositivos o
servidor, cierre de sesión y permisos de administración. El reproductor es externo:
PiP, gestos y audio con pantalla bloqueada se configuran en VLC u otro reproductor;
VLC tiene modos diferentes para PiP y reproducción de video como audio.
Guardar la posición exacta es un spike, no una función garantizada del MVP.

## Paridad del bot

| Acción actual | App móvil |
| --- | --- |
| start/help | Inicio y Ayuda |
| language | Idioma por usuario |
| URL/quality/audio | Agregar/compartir, metadatos y preferencias |
| status/files | Biblioteca e historial; descargas locales |
| cancel/retry | Controles de cola y descargas fallidas |
| resend | Solicitar otra vez/descargar directo, sin Telegram |
| clean/sent | Limpieza confirmada del servidor, independiente del historial |
| refresh_menu | Actualizar listas y acciones pendientes |
| log | Diagnóstico y avisos personales; diagnósticos del servidor solo admin |
| speed | Mantenimiento, prueba en servidor; no prueba grande en teléfono |
| update/update_ytdlp/restart | Mantenimiento exclusivo de administradores |

Telegram sigue activo con almacenamiento y listas independientes.
Mantenimiento puede requerir habilitación del operador del servidor; el menú no
concede permisos. La ayuda no promete una implementación para comandos aún no
integrados: la navegación de mantenimiento depende de PLA-252.

## Verificación

`python3 android/localization/generate.py --check` compara ES/EN y formatos;
`catalog.json` es la fuente y `ui_<sha256-12>` son claves deterministas. El código
solo traduce los textos de interfaz, nunca reemplaza todo el contenido de un View.
Pruebas JVM verifican defaults y claves; nativas verifican cuenta/idioma offline,
ACK atrasado y ayuda ES/EN. Backend verifica privacidad, persistencia, aprobación,
revocación y que idiomas no restablezcan preferencias de medios.
