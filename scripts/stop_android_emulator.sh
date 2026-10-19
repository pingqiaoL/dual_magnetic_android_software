#!/usr/bin/env bash
set -euo pipefail

sdk_root="${ANDROID_SDK_ROOT:-/home/lpq/Android/Sdk}"
"$sdk_root/platform-tools/adb" emu kill

