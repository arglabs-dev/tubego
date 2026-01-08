#!/bin/bash
set -e

# Colores para la terminal
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

echo -e "${GREEN}=== Inicializando Entorno de YouTube Downloader ===${NC}"

# 1. Crear entorno virtual si no existe
if [ ! -d "venv" ]; then
    echo -e "${YELLOW}[1/3] Creando entorno virtual (venv)...${NC}"
    python3 -m venv venv
else
    echo -e "${YELLOW}[1/3] El entorno virtual ya existe.${NC}"
fi

# 2. Activar venv e instalar/actualizar pip
echo -e "${YELLOW}[2/3] Actualizando pip y herramientas de base...${NC}"
source venv/bin/activate
pip install --upgrade pip setuptools wheel

# 3. Instalar requerimientos
if [ -f "requirements.txt" ]; then
    echo -e "${YELLOW}[3/3] Instalando dependencias desde requirements.txt...${NC}"
    pip install -r requirements.txt
else
    echo -e "${YELLOW}⚠️ No se encontró requirements.txt, instalando dependencias base...${NC}"
    pip install yt-dlp flet
fi

echo -e "${GREEN}==============================================${NC}"
echo -e "${GREEN}¡Entorno listo!${NC}"
echo -e "Para comenzar, activa el entorno con:"
echo -e "   ${YELLOW}source venv/bin/activate${NC}"
echo -e "Luego ejecuta la app con:"
echo -e "   ${YELLOW}python main.py${NC}"
echo -e "${GREEN}==============================================${NC}"
