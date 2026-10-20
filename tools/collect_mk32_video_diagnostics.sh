#!/usr/bin/env bash
set -euo pipefail

ADB_BIN="${ADB_BIN:-$HOME/Android/Sdk/platform-tools/adb}"
MK32_ADB_TARGET="${1:-}"
OUTPUT_DIR="${2:-artifacts/mk32-video-diagnostics}"

mkdir -p "$OUTPUT_DIR"

DEVICE_SERIAL="$MK32_ADB_TARGET"
if [[ -n "$DEVICE_SERIAL" ]]; then
    if [[ "$DEVICE_SERIAL" == *:* ]]; then
        timeout 8 "$ADB_BIN" connect "$DEVICE_SERIAL" >/dev/null || true
    fi
else
    while read -r candidate; do
        model=$(timeout 5 "$ADB_BIN" -s "$candidate" shell getprop ro.product.model 2>/dev/null \
            | tr -d '\r' || true)
        if [[ "$model" == "MK32" ]]; then
            if [[ -n "$DEVICE_SERIAL" ]]; then
                echo "检测到多台 MK32，请用第一个参数指定序列号。" >&2
                exit 1
            fi
            DEVICE_SERIAL="$candidate"
        fi
    done < <("$ADB_BIN" devices | awk 'NR > 1 && $2 == "device" { print $1 }')
fi

if [[ -z "$DEVICE_SERIAL" ]] || ! timeout 5 "$ADB_BIN" -s "$DEVICE_SERIAL" shell true; then
    echo "未找到可用的 MK32 ADB 连接；请接入 USB，或指定可访问的 IP:5555。" >&2
    exit 1
fi

STAMP=$(date +%Y%m%d_%H%M%S)
PREFIX="$OUTPUT_DIR/$STAMP"

timeout 15 "$ADB_BIN" -s "$DEVICE_SERIAL" exec-out run-as com.climbwheel.mk32 \
    cat files/video_diagnostics.csv >"${PREFIX}_player.csv" 2>"${PREFIX}_player_error.txt" || true
timeout 15 "$ADB_BIN" -s "$DEVICE_SERIAL" exec-out run-as com.climbwheel.mk32 \
    cat files/video_diagnostics.previous.csv >"${PREFIX}_player_previous.csv" \
    2>"${PREFIX}_player_previous_error.txt" || true
timeout 15 "$ADB_BIN" -s "$DEVICE_SERIAL" logcat -d -v threadtime \
    -s Mk32Video:V Mk32VideoDiag:V RtspClient:V MediaCodecVideoRenderer:V '*:S' \
    >"${PREFIX}_logcat.txt"
timeout 15 "$ADB_BIN" -s "$DEVICE_SERIAL" shell \
    'ip -4 addr; ip route; getprop ro.product.model; getprop ro.build.version.release; dumpsys package com.climbwheel.mk32 | grep -E "versionCode|versionName"' \
    >"${PREFIX}_device.txt"

echo "诊断数据已保存："
echo "  ${PREFIX}_player.csv"
echo "  ${PREFIX}_logcat.txt"
echo "  ${PREFIX}_device.txt"

