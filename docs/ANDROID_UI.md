# Interfaz oficial de TubeGo Android

La implementación oficial vive exclusivamente en `android/`. La versión 0.5.0 (código 6) incorpora el diseño elegido, con biblioteca como inicio, una paleta compartida en las 30 pantallas, Ajustes por finalidad y alcance, y administración visible solo para cuentas autorizadas.

- Paquete oficial: `dev.arglabs.tubego`. Nombre: **TubeGo**.
- Android mínimo: 10 (API 29). SDK de compilación y destino: 35.
- Mantiene la firma de depuración local para actualizar instalaciones compatibles, sin desinstalar ni borrar datos.
- Componentes: `Ds`, `UiKit` y `ScreenInfo`. Las pantallas existentes adoptan la misma paleta e iconos mediante una cabecera común.
- Ajustes separa descargas, teléfono, cuenta, archivos propios del servidor, ayuda y administración. Cada destino indica su finalidad y alcance. La limpieza global se identifica y se oculta a usuarios normales.
- Las gráficas de almacenamiento distinguen teléfono y servidor, con sus propios umbrales. Las preferencias, la seguridad de cuenta, la conservación y el mantenimiento se dividen en grupos explicados.

## Compilar

Requiere JDK 21, Gradle 8.11.1 y Android SDK 35 configurado en `ANDROID_HOME`.

```sh
./scripts/build_android.sh
```

El script verifica la localización, compila, ejecuta Lint y las pruebas unitarias, y compila el APK de instrumentación. El APK instalable se genera en `android/app/build/outputs/apk/debug/app-debug.apk`. No elimina código ni datos.

```sh
adb install -r android/app/build/outputs/apk/debug/app-debug.apk
```

La prueba visual reproducible utiliza una API HTTPS local con datos sintéticos y escrituras bloqueadas; ver `android/ui-tests/README.md`. Cubre las pantallas declaradas, navegación de ajustes, roles, botones, hojas y confirmaciones, incluyendo fuente ampliada. No equivale a ejecutar correos, limpiezas o descargas contra producción.

## Identidad de la versión oficial

La vista previa independiente `dev.arglabs.tubego.claude` sirvió para evaluar el diseño. La app oficial conserva el identificador anterior de TubeGo y un código de versión mayor que las instalaciones 0.4.1 (código 5). Los APK oficiales de CI se construyen desde `android/`, que contiene el mismo diseño elegido.
