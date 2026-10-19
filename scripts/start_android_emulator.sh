#!/usr/bin/env bash
set -euo pipefail

sdk_root="${ANDROID_SDK_ROOT:-/home/lpq/Android/Sdk}"
avd_name="MK32_AOSP_API28"
adb="$sdk_root/platform-tools/adb"
window_args=()
accel_args=(-accel off)
accel_description="TCG 软件模拟（未检测到可用的 /dev/kvm）"
if [[ -c /dev/kvm && -r /dev/kvm && -w /dev/kvm ]]; then
    accel_args=(-accel on)
    accel_description="KVM 硬件加速"
fi
if [[ "${1:-}" == "--headless" ]]; then
    window_args=(-no-window)
fi

configure_emulator_ui() {
    "$adb" shell settings put global hide_error_dialogs 1 >/dev/null 2>&1 || true
    "$adb" shell settings put global anr_show_background 0 >/dev/null 2>&1 || true
    "$adb" shell settings put global window_animation_scale 0 >/dev/null 2>&1 || true
    "$adb" shell settings put global transition_animation_scale 0 >/dev/null 2>&1 || true
    "$adb" shell settings put global animator_duration_scale 0 >/dev/null 2>&1 || true
    "$adb" shell settings put global airplane_mode_on 1 >/dev/null 2>&1 || true
    "$adb" shell am broadcast -a android.intent.action.AIRPLANE_MODE --ez state true >/dev/null 2>&1 || true
    "$adb" shell settings put secure immersive_mode_confirmations confirmed >/dev/null 2>&1 || true
}

if "$adb" devices | grep -q '^emulator-'; then
    configure_emulator_ui
    echo "Android emulator is already running. The window may be behind the terminal; use Alt+Tab to select it."
    exit 0
fi

mkdir -p build
nohup "$sdk_root/emulator/emulator" \
    -avd "$avd_name" \
    -no-boot-anim \
    -no-audio \
    -gpu swiftshader_indirect \
    "${accel_args[@]}" \
    -cores 2 \
    -memory 1536 \
    "${window_args[@]}" \
    > build/emulator.log 2>&1 &

echo "Starting $avd_name with $accel_description. First boot can take several minutes..."
"$adb" wait-for-device

for _ in $(seq 1 240); do
    if "$adb" shell pm path android >/dev/null 2>&1 &&
       "$adb" shell service check activity 2>/dev/null | grep -q 'found'; then
        configure_emulator_ui
        echo "Android package and activity services are ready."
        echo "The emulator window may be behind this terminal; use Alt+Tab if necessary."
        exit 0
    fi
    sleep 2
done

echo "Emulator did not become ready. Check build/emulator.log." >&2
exit 1
