# Historial y controles de cola (PLA-242 / PLA-236)

**Biblioteca / Historial** reúne fichas permanentes privadas y las copias físicas del teléfono. Las fichas incluyen URL, título, formato/calidad, fecha, última tarea con fase/progreso/error, estado del dispositivo y último abierto. Borrar un archivo no elimina la ficha. La eliminación de la cuenta es la excepción. No hay carpetas ni listas personalizadas.

La búsqueda por título y los filtros Todos, Descargados en este teléfono, Pendientes, Con error y Borrados/No disponibles funcionan sobre los datos guardados cuando no hay conexión y consultan páginas privadas cuando la hay. La presencia física del archivo prevalece sobre el estado del servidor: un video borrado del backend sigue pudiendo reproducirse localmente. La lectura de las copias locales conserva la verificación y los permisos descritos en `ANDROID_PLAYBACK.md`.

El historial completo permanece en el servidor y se recorre sin límite artificial mediante cursores. La copia sin conexión conserva hasta 2000 fichas consultadas por namespace de origen/usuario/dispositivo; la pantalla muestra 50 elementos por página y avisa de ese límite. También integra las fichas locales aunque no estén dentro de esas 2000. Al abrir la pantalla muestra primero lo guardado, sin esperar a la consulta de red. Un cierre de sesión retira la copia local como el resto de archivos administrados; el historial permanente del servidor no se elimina.

El último abierto se registra cuando se elige y lanza un reproductor, no al abrir el selector. No significa reproducción completada ni posición de reproducción. Se conserva sin conexión y se envía con tráfico de control; el backend conserva el mayor timestamp UTC entre dispositivos. Timestamps de reloj futuro inválido y recursos ya eliminados no deben atascar el resto de transferencias. La investigación de posición VLC pertenece a otra tarjeta.

## API

- `GET /resources?order=newest&limit=50&cursor=...&search=...`: cursores estables por fecha/UUID; por compatibilidad `order=oldest` es el valor predeterminado. Cada elemento y `GET /resources/{id}` incluyen `latest_task`, `device_delivery`, `last_opened_at`, `priority` y `server_available`. No incluyen rutas, identidades de otro dispositivo ni datos de otro usuario. Los filtros existentes permanecen; se añaden `pending`, `error`, `unavailable` y `downloaded` (este último significa confirmación de ese dispositivo; la UI comprueba el archivo físico).
- `POST /resources/{id}/opened {opened_at: ISO8601 con zona}`: escritura monotónica privada.
- `POST /resources/{id}/priority {request_id: UUID}`: selección persistente por recurso y usuario. Repetir el mismo ID devuelve el mismo resultado; usarlo para otro recurso devuelve 409. Actualiza atómicamente la prioridad de una tarea en espera, sin cambiar una tarea activa o completada. Un archivo listo en servidor también puede priorizarse para el teléfono.
- `GET /device/queue-order?cursor=...&limit=100`: todas las entregas pendientes de ese dispositivo mediante cursor, con prioridad. `/device/sync` publica la misma prioridad para los demás dispositivos.

La última selección explícita se coloca como siguiente por un número de secuencia privado creciente; las prioridades anteriores se conservan y se ordenan por esa secuencia. Sin selección se usa FIFO por fecha y UUID para desempatar. El reparto 3:1 entre usuarios del servidor sigue siendo independiente. El driver Android vuelve a consultar el orden antes de cada siguiente archivo y nunca durante una transferencia activa.

Los botones de servidor Cancelar/Reintentar/Poner siguiente muestran resultado confirmado por la API y despiertan el driver. Cancelar y reintentar operan sobre la tarea existente, sin duplicar trabajos. En esta entrega esos controles requieren conexión y lo explican si no se confirmó la acción; la cola de comandos general sin conexión pertenece a PLA-246. Los botones de borrado y volver a solicitar enlazan las pantallas de PLA-244 y PLA-243, con `server_url` y `resource_id`. La reproducción no solicita una nueva descarga y usa solo el archivo local.

## Pruebas

Pruebas de API: `backend/tests/test_history.py`, junto con privacidad, scheduler, entregas y worker. Cubren más de 50 fichas, filtros sobre la última tarea (sin falsos errores antiguos), aislamiento entre usuarios y dispositivos, prioridad sincronizada e idempotente, cursores, ninguna mutación de operación activa y última apertura monotónica con revocación.

JUnit: `HistoryCacheTest` comprueba persistencia, límite e independencia del namespace; `QueueOrderTest` comprueba FIFO, selección posterior y que la transferencia ya activa quede fuera del conjunto que se reordena.

Instrumentación sin servidor requerido:

```
adb shell am instrument -w -e class dev.arglabs.tubego.HistoryLibraryInstrumentedTest dev.arglabs.tubego.test/android.test.InstrumentationTestRunner
```

La prueba conserva una ficha con archivo ausente y servidor no disponible, verifica visualización antes del timeout de red y que otra cuenta no vea esa ficha.
