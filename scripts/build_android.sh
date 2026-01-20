#!/bin/bash
set -e # Detener script si hay cualquier error

# Colores para output
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m' # No Color

echo -e "${GREEN}=== Constructor de APK para TubeGo (Clean Build) ===${NC}"

# 1. Verificaciones Previas
if ! command -v flutter &> /dev/null; then
    echo -e "${RED}Error: 'flutter' no está instalado.${NC}"
    exit 1
fi

# 2. LIMPIEZA PROFUNDA
echo -e "${YELLOW}[1/4] Limpiando archivos nativos corruptos...${NC}"
# Borramos carpetas que podrían tener el código de la "Counter App"
rm -rf build android ios lib test web linux macos windows pubspec.yaml analysis_options.yaml

# 3. Preparación
echo -e "${YELLOW}[2/4] Preparando proyecto Flet...${NC}"

if [ ! -f "requirements.txt" ]; then
    echo -e "${RED}Error: No se encuentra requirements.txt${NC}"
    exit 1
fi

# 4. Compilación
echo -e "${YELLOW}[3/4] Compilando APK...${NC}"
# --project: Nombre del proyecto
# --product: Nombre que ve el usuario
# --yes: Aceptar prompts automáticamente
flet build apk --project "tubego" --product "TubeGo" --description "Offline Video Downloader" --yes

# 5. Finalización
echo -e "${YELLOW}[4/4] Finalizando...${NC}"

# Flet ahora lo deja en build/apk
SOURCE_APK=$(find build/apk -name "*.apk" | head -n 1)
DEST_APK="./TubeGo.apk"

if [ -n "$SOURCE_APK" ] && [ -f "$SOURCE_APK" ]; then
    cp "$SOURCE_APK" "$DEST_APK"
    echo -e "${GREEN}==============================================${NC}"
    echo -e "${GREEN}¡ÉXITO! APK Reparado Generado.${NC}"
    echo -e "${GREEN}Archivo: $DEST_APK${NC}"
    echo -e "${GREEN}==============================================${NC}"
    echo ""
    echo "Instrucciones de corrección:"
    echo "1. DESINSTALA la app anterior de tu móvil."
    echo "2. Instala este nuevo APK: adb install TubeGo.apk"
else
    echo -e "${RED}Error: No se encontró el APK generado.${NC}"
    exit 1
fi
