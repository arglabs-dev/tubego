#!/bin/bash
set -e # Detener script si hay cualquier error

# Colores para output
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m' # No Color

echo -e "${GREEN}=== Constructor de APK para TubeGo ===${NC}"

# 1. Verificaciones Previas
echo -e "${YELLOW}[1/4] Verificando entorno...${NC}"

if ! command -v flutter &> /dev/null; then
    echo -e "${RED}Error: 'flutter' no está instalado o no está en el PATH.${NC}"
    echo "Por favor instala Flutter SDK: https://docs.flutter.dev/get-started/install/linux"
    exit 1
fi

# 2. Preparación
echo -e "${YELLOW}[2/4] Preparando proyecto Flet/Flutter...${NC}"

# Asegurar que requirements.txt está listo
if [ ! -f "requirements.txt" ]; then
    echo -e "${RED}Error: No se encuentra requirements.txt${NC}"
    exit 1
fi

# Generar estructura de Flutter si no existe (carpeta 'build' o configuración 'pubspec.yaml')
# Usamos 'tubego' como nombre de proyecto (snake_case requerido por flutter)
echo "Generando configuración nativa..."
flet create . --project "tubego" --description "TubeGo - Offline Video Downloader"

# 3. Compilación
echo -e "${YELLOW}[3/4] Compilando APK (Esto puede tardar unos minutos)...${NC}"
echo "Ejecutando: flet build apk"

# Ejecutamos el build. Pasamos 'yes' para aceptar instalaciones de SDK si hacen falta
yes | flet build apk

# 4. Finalización
echo -e "${YELLOW}[4/4] Finalizando...${NC}"

# La ruta de salida puede variar dependiendo de la versión de flutter, buscamos el archivo
# Flet ahora lo deja en build/apk
SOURCE_APK=$(find build/apk -name "*.apk" | head -n 1)
DEST_APK="./TubeGo.apk"

if [ -n "$SOURCE_APK" ] && [ -f "$SOURCE_APK" ]; then
    cp "$SOURCE_APK" "$DEST_APK"
    echo -e "${GREEN}==============================================${NC}"
    echo -e "${GREEN}¡ÉXITO! APK generado correctamente.${NC}"
    echo -e "${GREEN}Archivo: $DEST_APK${NC}"
    echo -e "${GREEN}==============================================${NC}"
    echo ""
    echo "Instrucciones de instalación:"
    echo "1. Conecta tu móvil por USB."
    echo "2. Ejecuta: adb install TubeGo.apk"
else
    echo -e "${RED}Error: No se encontró el APK generado.${NC}"
    # Listar contenido para debug si falla
    ls -R build/app/outputs/flutter-apk/ 2>/dev/null || echo "Carpeta de output vacía."
    exit 1
fi
