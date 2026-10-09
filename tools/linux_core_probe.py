#!/usr/bin/env python3
"""Live desktop ABI harness. Uses the installed backend; never substitutes protocol facts.

Commands are JSON lines on stdin; snapshots are compact public state on stdout.
Private material remains inside the protocol worker. PIN values are not printed.
"""
import argparse
import ctypes
import json
import pathlib
import select
import sys
import time
import termios

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("bundle", type=pathlib.Path)
    parser.add_argument("--scan", action="store_true")
    args = parser.parse_args()
    library = ctypes.CDLL(str(args.bundle.resolve() / "lib/libwatch_core_ffi.so"))
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
    previous = None
    terminal = termios.tcgetattr(sys.stdin) if sys.stdin.isatty() else None
    try:
        if terminal is not None:
            private_terminal = terminal.copy()
            private_terminal[3] &= ~termios.ECHO
            termios.tcsetattr(sys.stdin, termios.TCSANOW, private_terminal)
        if args.scan:
            deadline = time.monotonic() + 4
            while command({"method": "snapshot"}).get("adapterSelectionStatus") == "LOADING" and time.monotonic() < deadline:
                time.sleep(0.05)
            print(json.dumps(command({"method": "scan"})), flush=True)
        while True:
            snapshot = command({"method": "snapshot"})
            visible = {key: snapshot.get(key) for key in ["epoch", "phase", "adapterState", "selectedId",
                "devices", "pairId", "activationConfirmed", "watchReady", "pinRequired", "error",
                "bluetoothAdapters", "selectedAdapterId", "adapterSelectionStatus", "adapterSelectionBusy", "adapterSelectionError"]}
            if visible != previous:
                print(json.dumps(visible), flush=True)
                previous = visible
            ready, _, _ = select.select([sys.stdin], [], [], 0.25)
            if ready:
                line = sys.stdin.readline()
                if not line:
                    break
                request = json.loads(line)
                if request.get("method") == "journal":
                    print(snapshot.get("journal", ""), flush=True)
                else:
                    print(json.dumps(command(request)), flush=True)
                if request.get("method") == "stop":
                    time.sleep(1)
                    break
    finally:
        command({"method": "stop"})
        if terminal is not None:
            termios.tcsetattr(sys.stdin, termios.TCSANOW, terminal)

if __name__ == "__main__":
    main()
