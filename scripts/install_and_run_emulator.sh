#!/usr/bin/env bash
set -euo pipefail

project_root="$(cd "$(dirname "$0")/.." && pwd)"
sdk_root="${ANDROID_SDK_ROOT:-/home/lpq/Android/Sdk}"
adb="$sdk_root/platform-tools/adb"

if ! "$adb" devices | grep -q '^emulator-.*device'; then
    echo "No emulator is running; starting MK32_AOSP_API28 first..."
    "$project_root/scripts/start_android_emulator.sh"
fi

cd "$project_root"
echo "Building and testing the Android application..."
./gradlew testDebugUnitTest assembleDebug lintDebug

echo "Installing the APK..."
"$adb" install -r app/build/outputs/apk/debug/app-debug.apk
"$adb" shell am force-stop com.climbwheel.mk32
echo "Opening 涂层测厚机器人..."
"$adb" shell am start -W -n com.climbwheel.mk32/.MainActivity
sleep 8

# A software-only emulator can make System UI miss its ANR deadline. Clear the
# one-time dialogs so they do not cover the application under test.
focus="$($adb shell dumpsys window windows 2>/dev/null | grep -m1 'mCurrentFocus' || true)"
if [[ "$focus" == *"Application Not Responding: com.android.systemui"* ]]; then
    "$adb" shell input tap 650 665
    sleep 2
fi
focus="$($adb shell dumpsys window windows 2>/dev/null | grep -m1 'mCurrentFocus' || true)"
if [[ "$focus" == *"ImmersiveModeConfirmation"* ]]; then
    "$adb" shell input tap 1170 402
    sleep 2
fi

mkdir -p artifacts
"$adb" shell screencap -p /sdcard/climbwheel-latest.png
"$adb" pull /sdcard/climbwheel-latest.png artifacts/climbwheel-latest.png
echo "Application started. Screenshot: artifacts/climbwheel-latest.png"
echo "If the emulator is hidden, use Alt+Tab and select Android Emulator - MK32_AOSP_API28."
