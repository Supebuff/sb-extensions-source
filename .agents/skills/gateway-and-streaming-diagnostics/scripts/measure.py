"""Summarize gateway timings and AniZen logs without exporting log lines."""
import argparse
from collections import Counter, defaultdict
from datetime import datetime
import json
from math import ceil, isfinite
from pathlib import Path
import re
import statistics
import subprocess
import sys
from time import time


THREADTIME = re.compile(
    r"^(\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{1,6})\s+(\d+)\s+\d+\s+[VDIWEF]\s+"
    r"(mpv|RealImageLoader|OkHttp)\s*:\s*(.*)"
)
FIRST_FRAME = "[cplayer:v] first video frame after restart shown"
GATEWAY_ASSET = "/v1/sites/x1hub/asset?"
OKHTTP_ASSET = re.compile(r"^<-- ([1-5]\d{2}) (?:.*? )?https?://\S+/v1/sites/x1hub/asset\?\S+ \((\d+)ms\b")
METRICS = (
    "queue_ms", "pacing_ms", "navigation_ms", "image_fetch_ms", "media_open_ms",
    "upstream_headers_ms", "headers_ms", "first_body_ms", "total_ms",
)


def summary(values):
    ordered = sorted(values)
    return {
        "n": len(ordered),
        "p50_ms": round(statistics.median(ordered), 2) if ordered else None,
        "p95_ms": round(ordered[ceil(0.95 * len(ordered)) - 1], 2) if len(ordered) >= 20 else None,
    }


class PhoneMeasurements:
    def __init__(self):
        self.pending = {}
        self.starts = 0
        self.frames = defaultdict(list)
        self.images = Counter()
        self.asset_responses = Counter()
        self.asset_times = []

    def add(self, line):
        event = THREADTIME.match(line)
        if not event:
            return
        stamp, pid, tag, message = event.groups()
        if tag == "OkHttp":
            response = OKHTTP_ASSET.match(message)
            if response:
                self.asset_responses[response[1]] += 1
                if response[1] in ("200", "206"):
                    self.asset_times.append(int(response[2]))
            return
        if tag == "RealImageLoader":
            success = re.search(r"Successful \((NETWORK|DISK|MEMORY_CACHE|MEMORY)\)", message)
            if success:
                self.images[success[1]] += 1
            elif "Cancelled" in message:
                self.images["cancelled"] += 1
            return
        if message == "event: start-file":
            self.starts += 1
            self.pending[pid] = stamp, "unknown"
        elif pid in self.pending:
            start, route = self.pending[pid]
            if message.startswith("[stream_callback:v] Opening ") and GATEWAY_ASSET in message:
                self.pending[pid] = start, "x1hub_gateway"
            elif message.startswith("event: end-file"):
                del self.pending[pid]
            elif message == FIRST_FRAME:
                del self.pending[pid]
                try:
                    begin = datetime.strptime("2000-" + start, "%Y-%m-%d %H:%M:%S.%f")
                    end = datetime.strptime("2000-" + stamp, "%Y-%m-%d %H:%M:%S.%f")
                except ValueError:
                    return
                if begin.month == 12 and end.month == 1:
                    end = end.replace(year=2001)
                elapsed = (end - begin).total_seconds()
                if elapsed >= 0:
                    self.frames[route].append(elapsed * 1000)

    def result(self):
        found = sum(map(len, self.frames.values()))
        return {
            "starts": self.starts,
            "without_first_frame": self.starts - found,
            "mpv_start_to_first_frame_ms": {
                route: summary(self.frames[route]) for route in ("x1hub_gateway", "unknown")
            },
            "anizen_image_events": self.images,
            "x1hub_asset_http_statuses": self.asset_responses,
            "x1hub_asset_http_response_ms": summary(self.asset_times),
        }


def number(value):
    return type(value) in (float, int) and isfinite(value) and value >= 0


def gateway_report(lines, since, until):
    groups = {}
    skipped = 0
    for line in lines:
        try:
            row = json.loads(line)
        except ValueError:
            skipped += 1
            continue
        if not isinstance(row, dict) or not number(row.get("start_epoch")):
            skipped += 1
            continue
        if not since <= row["start_epoch"] < until:
            continue
        kind, cache = row.get("kind"), row.get("cache")
        if kind not in ("page", "image", "tickets", "media"):
            skipped += 1
            continue
        group = kind + (".range" if kind == "media" and row.get("range") is True else "")
        if cache in ("hit", "miss", "refresh"):
            group += "." + cache
        statuses, outcomes, attempts, samples = groups.setdefault(
            group, (Counter(), Counter(), Counter(), defaultdict(list))
        )
        status, outcome = row.get("status"), row.get("outcome")
        statuses[str(status) if type(status) is int and 100 <= status <= 599 else "missing"] += 1
        outcomes[outcome if outcome in ("complete", "disconnected", "incomplete") else "unknown"] += 1
        count = row.get("upstream_attempts")
        if type(count) is int and 1 <= count <= 6:
            attempts[count] += 1
        if outcome == "complete" and status in (200, 206):
            for field in METRICS:
                value = row.get(field)
                if number(value):
                    samples[field].append(value)
    return {
        "skipped_rows": skipped,
        "groups": {
            name: {
                "requests": sum(statuses.values()),
                "statuses": statuses,
                "outcomes": outcomes,
                "upstream_attempts": attempts,
                "successful_timings": {field: summary(values) for field, values in samples.items()},
            }
            for name, (statuses, outcomes, attempts, samples) in groups.items()
        },
    }


def capture(phone, adb, serial):
    command = [adb]
    if serial:
        command += ["-s", serial]
    try:
        device_time = subprocess.run(
            command + ["shell", "date", "+%s"], capture_output=True, text=True, timeout=10, check=True,
        ).stdout.strip()
    except subprocess.SubprocessError:
        return "adb_failed"
    if not re.fullmatch(r"[0-9]{9,12}", device_time):
        return "adb_failed"
    command += [
        "logcat", "-v", "threadtime", "-T", device_time + ".000", "mpv:V", "RealImageLoader:V", "OkHttp:I", "*:S",
    ]
    with subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
                          text=True, encoding="utf-8", errors="replace") as process:
        print("ADB capture started. Perform the test on the phone. Press Ctrl+C to report.", file=sys.stderr)
        try:
            for line in process.stdout:
                phone.add(line)
        except KeyboardInterrupt:
            process.terminate()
            return "stopped"
        return "ended" if process.wait() == 0 else "adb_failed"


def epoch(value):
    try:
        stamp = datetime.fromisoformat(value)
        if stamp.tzinfo is not None:
            return stamp.timestamp()
    except ValueError:
        pass
    raise argparse.ArgumentTypeError("use ISO 8601 with a timezone, for example 2026-11-18T22:00:00+08:00")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    modes = parser.add_subparsers(dest="mode", required=True)
    report = modes.add_parser("report", help="Read an AniZen export and/or gateway timing file.")
    report.add_argument("--phone-log", type=Path)
    adb = modes.add_parser("capture", help="Collect filtered ADB logcat until Ctrl+C.")
    adb.add_argument("--adb", default="adb")
    adb.add_argument("--serial")
    for mode in (report, adb):
        mode.add_argument("--gateway-log", type=Path)
        mode.add_argument("--since", type=epoch, default=float("-inf"))
        mode.add_argument("--until", type=epoch, default=float("inf"))
        mode.add_argument("--label", choices=("cold", "warm", "unspecified"), default="unspecified")
    args = parser.parse_args()
    if args.since >= args.until:
        parser.error("--since must precede --until")
    result = {"condition": args.label}
    if args.mode == "capture":
        phone = PhoneMeasurements()
        started = time()
        result["capture_status"] = capture(phone, args.adb, args.serial)
        args.since, args.until = max(args.since, started), min(args.until, time())
        result["phone"] = phone.result()
    elif args.phone_log:
        phone = PhoneMeasurements()
        with args.phone_log.open(encoding="utf-8-sig", errors="replace") as lines:
            for line in lines:
                phone.add(line)
        result["phone"] = phone.result()
    elif not args.gateway_log:
        parser.error("report requires --phone-log and/or --gateway-log")
    if args.gateway_log:
        with args.gateway_log.open(encoding="utf-8-sig") as lines:
            result["gateway"] = gateway_report(lines, args.since, args.until)
    print(json.dumps(result, indent=2))
    return int(result.get("capture_status") == "adb_failed")


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (OSError, ValueError) as error:
        print(json.dumps({"error": type(error).__name__}))
        sys.exit(1)
