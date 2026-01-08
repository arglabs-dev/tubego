# Project Context: youtubeDownloader v0.3

## Project Overview
**Status:** Beta (v0.3) - Android Ready
**Tech Stack:** Python 3, Flet (GUI), yt-dlp.

A hybrid application designed to download YouTube videos for offline viewing. It works as a Desktop GUI, a CLI tool, and a native Android App.

## Architecture
- `main.py`: Entry point.
- `src/core.py`: Smart download logic (Auto-detects FFMPEG availability).
- `src/ui.py`: Material Design Interface with Tabs and History.
- `scripts/build_android.sh`: Automated script to build the APK.

## Usage

### 1. Desktop GUI
```bash
python main.py
```

### 2. Command Line (CLI)
```bash
python main.py "URL" --quality 1080
```

### 3. Build for Android
Run the automated builder script:
```bash
./scripts/build_android.sh
```
The output file `YouTubeDownloader.apk` will be created in the project root.

## Features
- **Smart Core:** Downloads high-quality merged files on PC (with ffmpeg), and compatible m4a/mp4 files on Android (without ffmpeg).
- **Quality Selector:** Max, 1080p, 720p, 480p, Audio Only.
- **History:** View downloaded files within the app.
