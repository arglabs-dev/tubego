# Envío desde la laptop (PLA-267)

La API sirve una página sin dependencias externas en `GET /send`. Abre
`https://<tu-servidor>/send` desde la laptop e inicia sesión con la misma cuenta
verificada y aprobada usada en Android. No requiere instalar otra app ni cambiar
el APK. El proxy debe reenviar `/send` además de `/api/v1/`; usa HTTPS confiable.

## Flujo

1. Inicia sesión en Android para vincular al menos un teléfono.
2. Abre `/send`, inicia sesión y pega una URL individual.
3. Selecciona 480p, 720p, 1080p, mejor disponible o audio y confirma el envío.
4. El backend reutiliza `POST /api/v1/resources`: validación de fuentes,
   preferencias explícitas, cola persistente e identificador idempotente.
5. El worker existente prepara el contenido y publica entregas a los dispositivos
   activos de la cuenta. El navegador no se registra como dispositivo.
6. Android consulta su sincronización periódica y descarga según sus políticas.
   Un teléfono desconectado recibe la entrega al reconectarse. La web actualiza
   el estado cada 15 segundos y ofrece actualización manual.

La página distingue preparación del servidor de confirmación de descarga local.
No ofrece entrega instantánea ni push: Android programa sincronización periódica
cada 15 minutos y puede demorarla por restricciones del sistema, Wi-Fi o cierre
forzado. El servidor y su worker deben estar ejecutándose.

## Sesiones y privacidad

`POST /api/v1/web/login` usa las mismas credenciales y límites de intentos del
login móvil. Crea una sesión de ocho horas con `device_id=NULL`; solo se guarda
el hash del token en el servidor. El navegador mantiene el token en memoria,
sin cookies ni almacenamiento persistente. Recargar requiere iniciar sesión.
`POST /api/v1/web/logout` revoca exclusivamente la sesión web; no desvincula ni
borra contenido del teléfono. Las sesiones abandonadas caducan a las ocho horas.
La página no contiene scripts externos y utiliza CSP con nonce y respuestas
`no-store`. No se deben registrar cuerpos de solicitudes ni encabezados de auth.

`GET /api/v1/web/deliveries` devuelve únicamente dispositivos activos, las últimas
50 tareas con su URL/título y hasta 100 estados de entrega de la cuenta autenticada.
Cuentas bloqueadas, sesiones revocadas o caducadas no tienen acceso. No expone
rutas de archivos ni permite acceso administrativo a contenido de otras cuentas.

## Alcance

Esta versión utiliza la distribución existente a **todos los dispositivos activos**
de la cuenta. No selecciona un teléfono individual. Enlaces ya registrados muestran
un aviso y conservan el comportamiento de deduplicación existente: no reactivan
contenido borrado ni fuerzan una nueva descarga; usa la recuperación en Android.
No recibe listas ni descarga archivos en la laptop.

## Verificación

`backend/tests/test_web.py` cubre login, aprobación, límites compartidos, aislamiento
entre cuentas, sesión sin dispositivo, logout sin afectar Android, envío idempotente,
publicación al teléfono, sincronización y confirmación local. La prueba usa archivos
y teléfonos simulados; no sustituye una descarga real en un teléfono físico.

## Miniaturas y nombres de archivo (Android 0.5.5)

La app muestra la miniatura de la fuente al consultar un enlace y en la biblioteca.
El backend descarga únicamente previews pequeñas (hasta 32 KiB, JPEG/PNG/WebP),
a través del mismo proxy de salida restringida que los videos. Las imágenes de
biblioteca se consultan con la sesión de su propietario y se guardan en la carpeta
privada de su cuenta/dispositivo para poder verlas sin conexión. No se incorporan
imágenes en los listados JSON de biblioteca. Cuando una fuente no ofrece miniatura,
se mantiene el ícono; para videos locales anteriores también puede mostrarse un
fotograma del archivo. Un error de la miniatura no impide descargar el video.

Los archivos nuevos utilizan el título normalizado con Unicode NFKC: se eliminan
separadores de ruta, caracteres de control y caracteres no válidos en Windows;
se limitan los títulos a 160 bytes UTF-8 y se corrigen nombres reservados. El nombre
privado incluye el identificador del recurso para evitar colisiones y permanece
estable aunque cambie el título. Se usa `.mp4` para video y `.mp3` para audio.
Al compartir y en la cabecera HTTP se ofrece el título descriptivo normalizado.
Los archivos ya guardados con el nombre interno anterior siguen funcionando;
no se mueven ni se vuelven a descargar. El borrado y cierre de sesión incluyen
los nuevos archivos y las miniaturas.

Esta ampliación requiere instalar el APK Android 0.5.5 (código 11).

## Correcciones de caché y almacenamiento compartido (Android 0.5.6)

Las miniaturas extraídas de videos locales también se persisten, además de las
miniaturas de fuente. Se reutilizan desde una caché de memoria acotada y desde
el disco, se coordinan cargas concurrentes y se evitan solicitudes de red cuando
la copia local ya está disponible. La caché se elimina con la biblioteca privada.
La URI de compartir termina en el título normalizado, además de informar
DISPLAY_NAME y EXTRA_TITLE, para receptores que usan el último segmento como nombre.
Los detalles muestran la ubicación real completa (o la URI del proveedor cuando
no existe una ruta local convencional).

Las descargas nuevas se publican por defecto mediante MediaStore en
`Download/TubeGo`. En Ajustes → Espacio en este teléfono → Carpeta de descargas
se puede elegir otra carpeta con el selector del sistema y un permiso persistente
SAF de lectura/escritura. No se solicitan permisos de acceso a todos los archivos,
no se necesita root y no se utiliza chmod.

Solo se conserva una copia final. La transferencia usa staging privado; tras la
confirmación de descarga, se copia al destino, comprueba tamaño/SHA-256, sincroniza,
guarda su URI en el manifiesto y elimina el staging. Una publicación interrumpida
reutiliza el destino pendiente para no crear duplicados. MediaStore mantiene el
archivo pendiente oculto hasta completar la publicación. Los temporales pendientes
se limpian al borrar el recurso o la biblioteca privada.

Los archivos publicados pertenecen al usuario: se conservan al cerrar sesión y
al desinstalar, y pueden modificarse desde el administrador de archivos. La app
admite la reproducción de esas modificaciones sin exigir el hash del original.
Si el archivo se mueve a otra URI o se borra externamente, se muestra que falta;
no se vuelve a descargar automáticamente. El borrado explícito desde TubeGo sí
elimina su archivo publicado cuando conserva permiso para hacerlo.

Los videos privados anteriores no se mueven automáticamente. En sus detalles,
**Mover a la carpeta de descargas** permite trasladarlos explícitamente, verificando
la copia antes de eliminar el original. El cierre de sesión sigue borrando las
copias privadas heredadas; las copias compartidas ya publicadas permanecen.

Verificación en el teléfono Android 13 conectado: pruebas de copia única,
MediaStore, ruta real, edición externa, compartir con nombre descriptivo, conservar
el archivo después del logout y borrado explícito. Se generaron 24 miniaturas
persistentes y se comprobó que, al reiniciar el proceso, se cargan desde disco.

El índice local de archivos publicados se conserva por cuenta en este teléfono,
sin credenciales. Al volver a iniciar sesión se reconstruyen sus manifiestos a
partir de ese índice, sin descargar ni duplicar los videos que siguen disponibles.
Los nombres repetidos crean archivos distintos, sin sobrescribir el anterior.
Las publicaciones fallidas vacían el temporal de salida, y se respeta la reserva
de espacio al finalizar en Descargas. Una confirmación ya recibida del servidor
no se repite al reanudar una publicación local pendiente.

La biblioteca muestra primero los datos guardados y después consulta la red;
la comprobación del estado de la cuenta usa un ejecutor separado para evitar
que una conexión lenta retrase la primera vista local.

Validación final 0.5.6: compilación y lint, 72 pruebas unitarias, 13 pruebas en el
teléfono real (incluyendo colisiones, publicación interrumpida y recuperación
tras logout), más 24 lecturas de miniatura desde disco al reiniciar el proceso.
