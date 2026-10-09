#!/usr/bin/env python3
"""Exercise the installed Windows ABI against hardware, without fabricated states.

JSON commands arrive on stdin. Only public snapshots and command results are
printed; PIN values remain inside the input pipe and protocol worker.
"""
import argparse
import ctypes
import json
import os
from pathlib import Path
import queue
import sys
import threading
import time


def main():
    sys.stdout.reconfigure(encoding="utf-8")
    sys.stderr.reconfigure(encoding="utf-8")
    # ConPTY must not echo a PIN command back into the public terminal output.
    console = ctypes.WinDLL("kernel32", use_last_error=True)
    console.GetStdHandle.restype = ctypes.c_void_p
    input_handle = console.GetStdHandle(-10)
    input_mode = ctypes.c_ulong()
    if console.GetConsoleMode(ctypes.c_void_p(input_handle), ctypes.byref(input_mode)):
        if not console.SetConsoleMode(ctypes.c_void_p(input_handle), input_mode.value & ~4):
            raise ctypes.WinError(ctypes.get_last_error())
    parser = argparse.ArgumentParser()
    parser.add_argument("bundle", type=Path)
    parser.add_argument("--adapter", required=True)
    parser.add_argument("--connect-single", action="store_true")
    parser.add_argument("--resume", action="store_true")
    parser.add_argument("--duration", type=float, default=300)
    args = parser.parse_args()
    bundle = args.bundle.resolve()
    for name, path in {
        "WATCH_WINDOWS_PROTOCOL_DIR": bundle / "protocol",
        "WATCH_WINDOWS_HCI_HELPER": bundle / "watch-windows-hci.exe",
        "WATCH_WINDOWS_JAVA": bundle / "jre/bin/java.exe",
    }.items():
        os.environ[name] = str(path)
    library = ctypes.CDLL(str(bundle / "watch_core_ffi.dll"))
    library.aw_core_command.argtypes = [ctypes.c_char_p, ctypes.c_size_t]
    library.aw_core_command.restype = ctypes.c_void_p
    library.aw_core_free.argtypes = [ctypes.c_void_p]

    def command(request):
        data = json.dumps(request).encode()
        pointer = library.aw_core_command(data, len(data))
        if not pointer:
            raise RuntimeError("Rust core returned no response")
        try:
            return json.loads(ctypes.string_at(pointer))
        finally:
            library.aw_core_free(pointer)

    requests = queue.Queue()

    def read_commands():
        for line in sys.stdin:
            try:
                requests.put(json.loads(line))
            except ValueError:
                requests.put({"method": "invalid"})

    threading.Thread(target=read_commands, daemon=True).start()
    previous = None
    connected = False
    deadline = time.monotonic() + args.duration
    try:
        while command({"method": "snapshot"}).get("adapterSelectionStatus") == "LOADING":
            if time.monotonic() >= deadline:
                raise TimeoutError("USB inventory unavailable")
            time.sleep(0.05)
        result = command({"method": "selectAdapter", "id": args.adapter})
        print(json.dumps(result), flush=True)
        if result.get("status") != "APPLIED":
            raise RuntimeError("Selected controller unavailable")
        print(json.dumps(command({"method": "resume" if args.resume else "scan"})), flush=True)
        while time.monotonic() < deadline:
            snapshot = command({"method": "snapshot"})
            visible = {key: snapshot.get(key) for key in (
                "epoch", "phase", "adapterState", "selectedId", "devices", "pairId",
                "activationConfirmed", "watchReady", "pinRequired", "error", "logPath",
                "selectedAdapterId", "adapterSelectionStatus",
            )}
            if visible != previous:
                print(json.dumps(visible), flush=True)
                previous = visible
            if snapshot.get("phase") == "FAILED":
                print(snapshot.get("journal", ""), flush=True)
                return 1
            devices = snapshot.get("devices", [])
            if args.connect_single and not connected and snapshot.get("phase") == "DISCOVERING" and len(devices) == 1:
                result = command({"method": "connect", "id": devices[0]["id"]})
                print(json.dumps(result), flush=True)
                connected = result.get("status") == "QUEUED"
            try:
                request = requests.get(timeout=0.25)
            except queue.Empty:
                continue
            if request.get("method") == "journal":
                print(snapshot.get("journal", ""), flush=True)
            else:
                print(json.dumps(command(request)), flush=True)
            if request.get("method") == "stop":
                break
    finally:
        print(json.dumps(command({"method": "stop"})), flush=True)
        deadline = time.monotonic() + 6
        while command({"method": "snapshot"}).get("phase") == "DISCONNECTING" and time.monotonic() < deadline:
            time.sleep(0.1)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
