#!/usr/bin/env bash
set -euo pipefail

RTSP_URL="${1:-rtsp://192.168.144.129:8554/live}"

exec gst-launch-1.0 -v \
    rtspsrc location="$RTSP_URL" protocols=udp latency=0 \
        drop-on-latency=true do-retransmission=false timeout=2000000 udp-reconnect=true \
        name=source \
    source. ! application/x-rtp,media=video \
        ! rtph264depay \
        ! h264parse \
        ! avdec_h264 \
        ! videoconvert \
        ! autovideosink sync=false

