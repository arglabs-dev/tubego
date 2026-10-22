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
