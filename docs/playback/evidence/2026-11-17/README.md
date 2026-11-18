# Validación de progreso integrado — 2026-11-17

DN2103, Android API 33, serial R4HAEIVC9DQKXCDI. Motor Android LibVLC 3.7.7;
engine nativo observado 3.0.25 Vetinari (`3.0.24-35-gc7e5d68ee5`).

## Resultado

- Build productivo 0.6.0 (20), unit tests y lint pasan. 85 tests Android, cero
  fallos/errores; lint conserva advertencias (1003), sin errores.
- Backend: 361 tests pasan, incluidas privacidad por propietario, dos dispositivos,
  reintentos idempotentes, posición más reciente aunque menor, marcado manual,
  cobertura, paginación y rechazo de datos incoherentes/futuros.
- Localización: 939 cadenas ES/EN verificadas. `git diff --check` pasa.
- Cuatro pruebas instrumentadas nativas pasan en el paquete aislado
  `dev.arglabs.tubego.playbacktest`, con guardia que impide ejecutarlas contra el
  paquete productivo: cambio de cuenta detiene sin contaminar otra cuenta;
  checkpoint/pausa/reanudación y salto al final; brillo/volumen/avance por gestos;
  fin natural con cobertura. Se restaura el volumen después de probar el gesto.
- Recuperación abrupta adicional: se mata únicamente el proceso del harness tras
  guardar 15901 ms; al reiniciar reanuda en 5901 ms. El runner interrumpido durante
  force-stop es esperado. La fase de recuperación pasa como test independiente.

Los primeros ensayos nativos encontraron huecos artificiales por tiempos
cuantizados y un EndReached perdido con seek pendiente. Se corrigieron esas
causas y se añadieron tests de regresión, manteniendo el criterio de cobertura.
`native-first-pass.txt` conserva el primer grupo de tres pruebas que pasó;
`native-tests.txt` es el grupo final de cuatro. El ensayo de muerte tiene su APK
identificado en `crash-apk.json`, separado del APK productivo final.

## Actualización productiva

Se comparó el certificado del APK instalado y el nuevo antes de actualizar.
Coinciden: `9afed715ef049bee6fba4041987c984d27b0ac701ef73d198d3f5db4700fced1`.
Se ejecutó exclusivamente `adb install -r`, de 0.5.13 (19) a 0.6.0 (20).
No se desinstaló TubeGo ni se borraron datos, sesión o descargas. Se comprobó:

- UID y directorio de datos conservados.
- SHA-256 del archivo cifrado de sesión idéntico antes/después de la instalación.
- Número de archivos privados de medios no disminuyó.
- Versión instalada 0.6.0 (20), verificada con PackageManager.

`production-update.json` contiene hashes y observaciones, sin tokens, correo ni
nombres de recursos. La biblioteca se lanzó; el proceso productivo permanece vivo
sin FATAL EXCEPTION observado, pero la pantalla se había bloqueado automáticamente,
por lo que no se afirma validación visual de las fichas productivas. No se volvió
a bloquear deliberadamente el teléfono. Los tests de motor se hicieron con el
clip sintético y el proveedor productivo dentro del paquete aislado; no con los
archivos del usuario. Los APK y claves quedan fuera de Git.

## Límites

No se desplegó el backend. Hasta desplegarlo, progreso y estados son locales;
los pendientes se conservan para sincronizar después. Los ensayos de sincronización
usan dos sesiones de un mismo usuario en el backend de pruebas, no dos teléfonos
productivos. No se reprodujo un video largo completo con el integrado; la prueba
previa del fixture de veinte minutos corresponde al contrato externo PLA-257.
No se ejecutó emulador. El integrado se pausa al salir/bloquear: audio de fondo y
PiP requieren usar la opción externa con sus límites de seguimiento.

Se conservaron cambios previos. No se hizo commit ni se modificó/cerró PLA-257.
