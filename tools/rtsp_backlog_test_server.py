#!/usr/bin/env python3
"""Loopback-only RTSP test source, independent of the Orin camera.

Requires python3-gi, GStreamer RTSP server introspection and x264enc. On MK32,
use ADB reverse and debug extras video_test_url, video_test_disable_catch_up and
video_test_pause_ms. The one-time client pause creates a reproducible backlog
while the server keeps sending normal H.264 at 30 FPS.
"""

import argparse
import signal

import gi

gi.require_version("Gst", "1.0")
gi.require_version("GstRtspServer", "1.0")
from gi.repository import GLib, Gst, GstRtspServer

Gst.init(None)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port", type=int, default=18554)
    args = parser.parse_args()
    if not 0 < args.port < 65536:
        parser.error("port must be 1–65535")

    server = GstRtspServer.RTSPServer()
    server.set_address("127.0.0.1")
    server.set_service(str(args.port))
    factory = GstRtspServer.RTSPMediaFactory()
    factory.set_shared(False)
    factory.set_latency(0)
    factory.set_launch(
        "( videotestsrc is-live=true pattern=ball "
        "! video/x-raw,width=1280,height=720,framerate=30/1 "
        "! timeoverlay "
        "! x264enc tune=zerolatency speed-preset=ultrafast bitrate=6000 "
        "key-int-max=30 bframes=0 rc-lookahead=0 "
        "! video/x-h264,profile=constrained-baseline "
        "! rtph264pay name=pay0 pt=96 config-interval=-1 )"
    )
    server.get_mount_points().add_factory("/live", factory)
    if server.attach(None) == 0:
        raise RuntimeError("Unable to bind RTSP test port")
    loop = GLib.MainLoop()
    for signum in (signal.SIGINT, signal.SIGTERM):
        GLib.unix_signal_add(GLib.PRIORITY_DEFAULT, signum, lambda: loop.quit())
    print(f"RTSP test: rtsp://127.0.0.1:{args.port}/live", flush=True)
    loop.run()


if __name__ == "__main__":
    main()
