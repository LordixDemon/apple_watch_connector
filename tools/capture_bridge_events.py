#!/usr/bin/env python3
"""Preserve selected bridge events before the phone's bounded journal rotates."""
import argparse
from pathlib import Path
import subprocess
import time


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("output", type=Path)
    parser.add_argument("--seconds", type=int, default=120)
    args = parser.parse_args()
    if args.output.exists():
        raise ValueError("Use a new output file so earlier evidence stays intact")
    last = None
    count = 0
    deadline = time.monotonic() + args.seconds
    with args.output.open("x", encoding="utf-8") as output:
        while time.monotonic() < deadline:
            result = subprocess.run(["adb", "exec-out", "su", "-c",
                "cat /data/user/0/dev.applewatchandroid.bridge/files/bluetooth-runs.log"],
                capture_output=True, text=True, timeout=8)
            lines = result.stdout.splitlines()
            start = 0
            if last:
                matches = [i for i, line in enumerate(lines) if line == last]
                if matches:
                    start = matches[-1] + 1
            for line in lines[start:]:
                if any(token in line for token in (
                    "RESOURCE RX", "COMMAND INPUT", "OUTBOUND APP TX", "CONTROL_READY",
                    "APP_ACK_RECEIVED", "protobufType=9", "HARDWARE ERROR", "FAILED",
                    "ROOT HAL START", "INITIAL SYNC", "PAIRED SYNC",
                    "PROTOBUF_RECEIVED", "WATCH REGISTRY RX", "PAIREDSYNC GATE",
                    "PUBLISH PAIRED SYNC", "PAIREDSYNC RX")):
                    output.write(line + "\n")
                    count += 1
            output.flush()
            if lines:
                last = lines[-1]
            time.sleep(1)
    print(f"Saved {count} selected event lines to {args.output}")


if __name__ == "__main__":
    main()
