#!/usr/bin/env bash
set -euo pipefail

PROJECT_ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
command -v gradle >/dev/null || { echo "Gradle 8.11.1 debe estar disponible en PATH." >&2; exit 1; }
python3 "$PROJECT_ROOT/android/localization/verify.py"
gradle --no-daemon -p "$PROJECT_ROOT/android" assembleDebug lintDebug testDebugUnitTest assembleDebugAndroidTest
printf '\nAPK oficial: %s\n' "$PROJECT_ROOT/android/app/build/outputs/apk/debug/app-debug.apk"
