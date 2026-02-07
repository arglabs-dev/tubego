# Project Context: TubeGo v1.0 Ultimate

## Project Overview
**Name:** TubeGo
**Status:** Production (v1.0)
**Tech Stack:** Python 3, Flet (GUI), yt-dlp, Telethon, python-telegram-bot.

TubeGo is a hybrid application designed to download YouTube/Twitter videos. It operates as a Desktop GUI, CLI tool, and a powerful **Hybrid Telegram Bot**.

## Architecture
- `main.py`: Entry point (GUI/CLI).
- `src/bot.py`: Ultimate Telegram Bot (Hybrid Mode + i18n).
- `src/core.py`: Download logic (yt-dlp wrapper).
- `src/manager.py`: Task and State Manager.
- `docs/ARCHITECTURE.md`: Detailed technical documentation.
- `docs/telegram_bot.md`: Bot configuration guide.

## Usage

### 1. Telegram Bot (Server)
The core component for remote management.
```bash
python src/bot.py
```

### 2. Desktop GUI
```bash
python main.py
```

### 3. Command Line (CLI)
```bash
python main.py "URL" --quality 1080
```

## Features
- **Smart Core:** Auto-detects FFMPEG.
- **Hybrid Bot:**
    - **Bot API:** Fast uploads for files < 50MB.
    - **Userbot (Telethon):** Bypasses limits for files > 50MB (up to 2GB).
    - **i18n:** Supports English and Spanish.
- **Quality Selector:** Smart selection menu (1080p, 720p, Audio).
- **Remote Management:** `/files`, `/clean`, `/speedtest`, `/update`.
