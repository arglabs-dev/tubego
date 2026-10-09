# TubeGo v0.9 Ultimate

TubeGo es una aplicación "Todo en Uno" para descargar contenido multimedia. Funciona como aplicación de Escritorio, herramienta de Línea de Comandos (CLI) y un potente **Bot de Telegram Híbrido**.

## 🚀 Características Principales

- **Multi-Plataforma:** Descarga desde YouTube, X (Twitter) y más.
- **Calidad Inteligente:** Selector automático de 1080p, 720p, 480p o Audio.
- **Bot Híbrido (Telegram):**
    - Sube archivos < 50MB usando la API rápida de Bots.
    - **Sube archivos hasta 2GB** usando la integración Userbot (Telethon).
    - Gestión remota: `/files`, `/clean`, `/speedtest`.
    - Auto-Actualizable: `/update`.
- **Interfaz Gráfica:** GUI moderna construida con Flet (Python).

---

## 📚 Documentación

Toda la documentación técnica se ha movido a la carpeta `docs/`:

- **[🤖 Guía del Bot de Telegram](docs/telegram_bot.md):** Cómo configurar el Token, el Userbot y los comandos.
- **[🏗 Arquitectura](docs/ARCHITECTURE.md):** Estructura interna del código y flujos de datos.
- **[🧠 Contexto del Proyecto](docs/GEMINI.md):** Historial y contexto para desarrolladores/IA.

---

## 🛠 Instalación y Uso

### 1. Requisitos Previos
Necesitas Python 3.10+ y `ffmpeg` instalado en tu sistema.

```bash
# Crear entorno virtual
python3 -m venv venv
source venv/bin/activate

# Instalar dependencias
pip install -r requirements.txt
```

### 2. Configuración (.env)
Crea un archivo `.env` en la raíz con tus credenciales (Ver [Guía del Bot](docs/telegram_bot.md)):

```env
TELEGRAM_TOKEN=tu_token_aqui
ALLOWED_USER_ID=tu_id_telegram
API_ID=tu_app_id
API_HASH=tu_app_hash
```

### 3. Ejecutar el Bot
```bash
python src/bot.py
```

### 4. Ejecutar la GUI (Escritorio)
```bash
python main.py
```

### 5. Ejecutar CLI
```bash
python main.py "URL_VIDEO" --quality 720
```

---

## 📱 Build para Android
Para compilar la versión APK:
```bash
flet build apk -v
```
_(Consulta la documentación oficial de Flet para requisitos de Android SDK)_
## Independent Telegram and mobile channels

Telegram continues to work alongside the Android MVP with separate files and queues.
See [storage configuration and deployment](docs/INDEPENDENT_CHANNELS.md) before
running both services.
