# Administración móvil (PLA-251)

La pantalla principal ofrece un único acceso **Administración**, visible únicamente si la sesión local corresponde a un administrador aprobado. Al volver a la app se consulta el estado de la cuenta y se actualiza la visibilidad; una sesión rechazada deja de mostrar el acceso. Cambiar de servidor calcula el rol para ese origen, sin reutilizar el de otro servidor.

El centro exige una verificación de sesión y rol en el backend al abrirse o actualizarse. No permite administrar sin conexión. Cada pantalla protegida existente también comprueba el rol local al entrar y vuelve a consultar la identidad antes de sus llamadas; las rutas administrativas siguen siendo la autoridad final y rechazan sesiones revocadas, cuentas no verificadas, bloqueadas o usuarios normales. Todas las actividades administrativas están marcadas `exported=false`; ocultar botones no sustituye esa autorización.

El centro enlaza:

- Solicitudes con correo verificado: aprobar o rechazar.
- Usuarios: ver rol, estado y verificación de correo; bloquear, eliminar, desbloquear y revisar limpieza pendiente. No añade una facultad de promover administradores.
- Prioridad normal/prioritaria persistente y reparto 3:1 sin interrumpir la operación activa.
- Conservación individual y plazos globales, inicialmente 72 horas desde listo y 4 horas desde primera entrega. La pantalla existente revisa el impacto sobre archivos actuales y confirma antes de aplicar un cambio destructivo.
- Avisos administrativos y errores de descargas.
- Limpieza del servidor y mantenimiento, mediante las pantallas correspondientes de PLA-245 y PLA-252.

Los accesos a una función que aún no esté registrada en ese APK aparecen deshabilitados. Cuando se integran las respectivas actividades se activan sin cambiar el centro. No se necesita una página web de administración. El administrador gestiona sus propios dispositivos desde Mi cuenta; esta tarjeta no añade acceso a dispositivos o bibliotecas privadas de otros usuarios.

## Almacenamiento real

`GET /api/v1/admin/storage` usa `src.disk_guard.DiskGuard(data_dir).sample()` sin callback de notificaciones. Devuelve capacidad, bytes usados/libres, porcentaje usado, pausa actual, causa, umbral de 90%, reserva mínima de espacio y timestamp UTC de consulta. Observa la misma regla del downloader: pausa al alcanzar 90% o si no queda la reserva mínima de 1 MiB. No inicia limpiezas, elimina archivos, cambia configuración ni emite avisos. Las rutas internas del sistema de archivos no se publican.

Si no se puede medir el volumen, el porcentaje es `null` y la causa es `storage_unavailable`; la UI muestra el fallo explícitamente, en vez de representar cero uso como una medición válida. Si falla la red se informa que no pudo consultarse el almacenamiento. Los resultados de cambios de usuarios, prioridad y conservación solo se presentan tras respuesta confirmada. Bloqueo, eliminación y conservación/plazos destructivos usan sus diálogos existentes con consecuencias, incluyendo los archivos en dispositivos desconectados y los límites de control sobre copias exportadas.

## Verificación

API: `backend/tests/test_admin_storage.py` comprueba medición del guard, ausencia de efectos sobre alertas/limpieza, volumen no disponible, rechazo de usuario normal/no verificado y revocación/democión administrativa. Se mantienen las suites de usuarios, aprobación, prioridades y conservación.

JUnit: `AdminRoleTest` cubre rol, aprobación y presencia de sesión para la navegación.

Android:

```
adb shell am instrument -w -e class dev.arglabs.tubego.AdminCenterInstrumentedTest dev.arglabs.tubego.test/android.test.InstrumentationTestRunner
```

La instrumentación utiliza Keystore real sin requerir servidor: usuario normal no ve navegación administrativa y una entrada forzada a las cinco pantallas devuelve denegación sin botones de acción. La autorización real se verifica en las pruebas de API; la prueba visual no sustituye al backend.
