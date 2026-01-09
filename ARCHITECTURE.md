# Architecture Documentation: TubeGo

## 1. Overview
TubeGo is a cross-platform application built on **Python**. Its main feature is the ability to run in three different modes sharing the same logical core:
1.  **CLI:** Automation script.
2.  **Desktop GUI:** Desktop app (Windows/Linux/macOS).
3.  **Mobile App:** Native Android application (APK).

## 2. Key Technologies

| Component | Technology | Function |
|------------|------------|---------|
| **Language** | Python 3.10+ | Business logic. |
| **UI Framework** | Flet (based on Flutter) | Reactive UI and mobile compilation. |
| **Download Engine** | yt-dlp | Video/audio stream extraction. |
| **Processing** | FFmpeg (Optional) | Format conversion and stream merging. |

## 3. Data Flow Diagram

```mermaid
graph TD
    A[User] -->|Input| B(main.py)
    B -->|CLI Arguments| C[src/cli.py]
    B -->|No Arguments| D[src/ui.py]
    C --> E[src/core.py]
    D --> E
    
    E -->|Check Environment| F{Has FFmpeg?}
    
    F -->|YES (Desktop)| G[High-Performance Mode]
    G --> G1[Download separate Video and Audio]
    G1 --> G2[Merge with FFmpeg (MP4 1080p+)]
    G --> G3[Convert Audio to MP3]
    
    F -->|NO (Android)| H[Mobile-Safe Mode]
    H --> H1[Download Single Stream (MP4 720p/360p)]
    H --> H2[Download Native Audio (M4A/AAC)]
    
    G2 --> I[Filesystem /downloads]
    G3 --> I
    H1 --> I
    H2 --> I
```

## 4. System Components

### 4.1. Entry Point (`main.py`)
Acts as a **Dispatcher**. It analyzes `sys.argv`. If it detects arguments, it invokes `src/cli.py`. If not, it launches the `src/ui.py` graphical interface.

### 4.2. Core Logic (`src/core.py`)
The heart of the system. Implements the `Downloader` class.
*   **Environment Detection:** In the `__init__` constructor, it verifies the existence of `ffmpeg` in the system PATH (`shutil.which('ffmpeg')`).
*   **Format Selection:**
    *   **With FFmpeg:** Requests `bestvideo+bestaudio` and uses post-processors to merge into MP4 or convert to MP3.
    *   **Without FFmpeg:** Requests `best[ext=mp4]` (pre-merged video+audio) or `bestaudio[ext=m4a]`. This is crucial for Android, where bundling FFmpeg binaries is complex.

### 4.3. User Interface (`src/ui.py`)
Powered by **Flet**.
*   Runs in a separate thread (`threading`) to avoid freezing the UI during downloads.
*   Implements a tab system to separate download actions from history.
*   On Android, the UI automatically adapts to touch controls (Material Design 3).

## 5. Build Strategy
The APK build process (`scripts/build_android.sh`) leverages Flet's ability to communicate with the Flutter SDK.
1.  Python code is bundled with a lightweight Python interpreter.
2.  A Flutter app shell is created.
3.  Pure Python libraries (`yt-dlp`) are included in the package.
4.  The result is an installable APK with no external dependencies required by the end-user.