#!/usr/bin/env bash
set -euo pipefail

python3 scripts/gradle.py --no-daemon :app:connectedDebugAndroidTest
adb uninstall com.pebblepile.android >/dev/null 2>&1 || true
adb install "$APK_PATH"
adb shell am start -W -n com.pebblepile.android/.MainActivity
sleep 5
adb shell pidof com.pebblepile.android
