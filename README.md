# TubeGo - YouTube Downloader 📺

**TubeGo** is a hybrid application (CLI, Desktop, and Mobile) developed in Python to download YouTube content efficiently for offline use.

It is designed with an intelligent architecture that adapts to the device's capabilities: it uses **FFmpeg** on desktop for maximum quality and conversion, and **native codecs** on Android to run without heavy external dependencies.

---

## 🚀 Quick Start

### 1. Environment Setup
To start developing or using the tool, we have prepared an automatic script that creates the virtual environment and installs the dependencies.

```bash
# Grant execution permissions (first time only)
chmod +x setup.sh

# Run setup
./setup.sh
```

Once finished, activate your environment:
```bash
source venv/bin/activate
```

### 2. CLI Usage (Command Line)
TubeGo includes a powerful command-line interface.

**Basic Command (480p Video - Default):**
```bash
python main.py "https://www.youtube.com/watch?v=VIDEO_ID"
```

**Advanced Options:**
| Flag | Description | Values | Default |
|------|-------------|---------|---------|
| `--type` | Content type | `video`, `audio` | `video` |
| `--quality` | Max resolution | `max`, `1080`, `720`, `480` | `480` |

**Examples:**
```bash
# Download audio only (Ideal for music)
python main.py "URL" --type audio

# Download in Full HD
python main.py "URL" --quality 1080
```

### 3. Graphical Interface (GUI)
If you prefer a visual experience:
```bash
python main.py
```
This will open a Material Design window where you can paste links, choose quality, and view your history.

---

## 📱 Build for Android (APK)

TubeGo can be transformed into a native Android app thanks to Flet.

**Requirements:**
- Flutter SDK installed.
- Android SDK configured.

**Generate APK:**
Run our automated build script:

```bash
./scripts/build_android.sh
```

Upon completion, you will find the `YouTubeDownloader.apk` file in the project root. Install it using `adb install` or by copying it to your mobile device.

---

## 📂 Project Structure
- `main.py`: Intelligent entry point (CLI/GUI).
- `src/`: Source code.
- `scripts/`: Automation scripts (build, setup).
- `downloads/`: Folder where files are saved.