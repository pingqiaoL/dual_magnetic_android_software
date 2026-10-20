#!/usr/bin/env python3
"""Check MK32 loopback A/B logs; these are buffer measurements, not camera latency."""

import argparse
import csv
import json
import re
import statistics


def latest_session(path):
    rows = []
    with open(path, encoding="utf-8") as source:
        for row in csv.DictReader(source):
            if row["event"] == "SESSION":
                rows = []
            row["values"] = dict(re.findall(r"(?:^| )(\w+)=([^ ]+)", row["details"]))
            rows.append(row)
    return rows


def playing_stats(rows):
    return [row for row in rows if row["event"] == "STATS"
            and row["values"].get("state") == "READY"
            and row["values"].get("playing") == "true"]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("baseline")
    parser.add_argument("catch_up")
    parser.add_argument("recovery", nargs="?")
    args = parser.parse_args()
    baseline = latest_session(args.baseline)
    catch_up = latest_session(args.catch_up)
    baseline_stats = [row for row in playing_stats(baseline)
                      if int(row["values"]["position_ms"]) >= 2000]
    settled = playing_stats(catch_up)[-5:]
    assert len(baseline_stats) >= 5 and len(settled) == 5, "Not enough playing samples"
    before = statistics.mean(int(row["values"]["buffered_ms"]) for row in baseline_stats)
    after = statistics.mean(int(row["values"]["buffered_ms"]) for row in settled)
    assert before > 500, f"Baseline did not reproduce the backlog: {before} ms"
    assert after < 250 and before - after > 300, f"Backlog did not drain: {before} -> {after} ms"
    assert all(float(row["values"]["speed"]) == 1 for row in settled), "Speed did not settle"
    assert all(int(row["values"]["dropped"]) == 0 for row in settled), "Renderer dropped frames"
    assert not any(row["event"] in ("PLAYER_ERROR", "RECONNECT") for row in catch_up), \
        "Catch-up triggered an error or reconnect"
    changes = [row for row in catch_up if row["event"] == "CATCH_UP"]
    assert changes and float(changes[0]["values"]["speed"]) > 1
    assert float(changes[-1]["values"]["speed"]) == 1
    drain_ms = int(changes[-1]["elapsed_realtime_ms"]) - int(changes[0]["elapsed_realtime_ms"])
    result = {
        "transport": "RTSP/RTP over TCP via USB ADB reverse (controlled test only)",
        "baseline_buffer_mean_ms": round(before, 2),
        "settled_buffer_mean_ms": round(after, 2),
        "catch_up_duration_ms": drain_ms,
        "settled_speed": 1.0,
        "settled_renderer_dropped_frames": 0,
        "catch_up_reconnects": 0,
    }
    if args.recovery:
        recovery = latest_session(args.recovery)
        failure_index = next(i for i, row in enumerate(recovery)
                             if row["event"] == "RECONNECT"
                             and "reason=buffering timeout" in row["details"])
        buffering = next(row for row in reversed(recovery[:failure_index])
                         if row["event"] == "BUFFERING")
        reconnect = recovery[failure_index]
        timeout_ms = int(reconnect["elapsed_realtime_ms"]) - int(buffering["elapsed_realtime_ms"])
        assert 350 <= timeout_ms <= 750, f"Unexpected buffering timeout: {timeout_ms} ms"
        remaining = recovery[failure_index + 1:]
        assert any(row["event"] == "RELEASE" for row in remaining), "Old player not released"
        assert any(row["event"] == "OPEN" for row in remaining), "No new session"
        assert any(row["event"] == "FIRST_FRAME" for row in remaining), "No recovered first frame"
        recovered_stats = playing_stats(remaining)[-3:]
        assert len(recovered_stats) == 3, "Not enough recovered video samples"
        assert all(int(row["values"]["buffered_ms"]) < 300 for row in recovered_stats)
        result["rebuffer_timeout_ms"] = timeout_ms
        result["automatic_recovery"] = True
    print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
