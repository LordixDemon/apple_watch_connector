#!/usr/bin/env python3
"""Measure only the isolated profile APK, preserving the ordinary Companion."""
import argparse
import datetime
import json
import re
import selectors
import subprocess
import time
import threading
from pathlib import Path

PROFILE = 'dev.applewatchandroid.companion.apple_watch_companion.nativefaceprofile'
ORDINARY = 'dev.applewatchandroid.companion.apple_watch_companion'


def run(adb, output, device_state_output=None):
    original_pid = subprocess.run(adb + ['shell', 'pidof', ORDINARY], capture_output=True).stdout.strip()
    process = subprocess.Popen(adb + ['logcat', '-v', 'threadtime', '-T', '1', 'flutter:I', '*:S'],
                               stdout=subprocess.PIPE, stderr=subprocess.STDOUT, bufsize=0)
    selector = selectors.DefaultSelector()
    selector.register(process.stdout, selectors.EVENT_READ)
    buffer, lines, pid, complete = b'', [], None, False
    stop_sampling = threading.Event()
    samples, sampler = [], None

    def sample_device():
        while not stop_sampling.is_set():
            sample = {'utc': datetime.datetime.now(datetime.timezone.utc).isoformat(),
                      'monotonicSeconds': time.monotonic(), 'profilePid': pid}
            for name in ('display', 'thermalservice'):
                started = time.monotonic()
                try:
                    result = subprocess.run(adb + ['shell', 'dumpsys', name],
                                            capture_output=True, timeout=10)
                    sample[name] = {'exitCode': result.returncode,
                                    'stdout': result.stdout.decode(errors='replace'),
                                    'stderr': result.stderr.decode(errors='replace')}
                except (OSError, subprocess.TimeoutExpired) as error:
                    sample[name] = {'error': str(error)}
                sample[name]['durationSeconds'] = time.monotonic() - started
            samples.append(sample)
            stop_sampling.wait(5)
    try:
        subprocess.run(adb + ['shell', 'am', 'force-stop', PROFILE], check=True, capture_output=True)
        subprocess.run(adb + ['shell', 'am', 'start', '-n', PROFILE + '/' + ORDINARY + '.MainActivity'],
                       check=True, capture_output=True)
        deadline = time.monotonic() + 170
        while time.monotonic() < deadline and not complete:
            if not selector.select(1): continue
            chunk = process.stdout.read(65536)
            if not chunk: break
            buffer += chunk
            while b'\n' in buffer:
                line, buffer = buffer.split(b'\n', 1)
                text = line.decode(errors='replace')
                match = re.match(r'\S+\s+\S+\s+(\d+)\s+\d+\s+I\s+flutter\s*:', text)
                if not match: continue
                if 'NATIVE_FACE_PROFILE_LOAD_US' in text:
                    pid = match[1]
                    if device_state_output is not None and sampler is None:
                        sampler = threading.Thread(target=sample_device, daemon=True)
                        sampler.start()
                if pid != match[1] or 'NATIVE_FACE_PROFILE' not in text: continue
                lines.append(text)
                print(text, flush=True)
                if 'NATIVE_FACE_PROFILE_DONE' in text: complete = True
                if 'NATIVE_FACE_PROFILE_FAILED' in text: raise RuntimeError('Profile failed')
    finally:
        stop_sampling.set()
        if sampler is not None: sampler.join(timeout=22)
        if device_state_output is not None:
            device_state_output.write_text(json.dumps(samples, ensure_ascii=False, indent=2) + '\n')
        process.terminate(); process.wait(timeout=5); selector.close()
        output.write_text('\n'.join(lines) + '\n')
        # Resume, never force-stop, reinstall or clear the main app.
        if original_pid:
            current_pid = subprocess.run(adb + ['shell', 'pidof', ORDINARY], capture_output=True).stdout.strip()
            if current_pid != original_pid:
                raise RuntimeError('Ordinary Companion process changed during measurement')
            subprocess.run(adb + ['shell', 'am', 'start', '-n', ORDINARY + '/.MainActivity'],
                           check=True, capture_output=True)
    if not complete: raise RuntimeError('Profile did not finish')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--port', default='5037')
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--device-state-output', type=Path,
                        help='Optional display/thermal samples during the profile, every five seconds')
    args = parser.parse_args()
    if args.output.exists(): raise ValueError('Use a fresh measurement output')
    if args.device_state_output is not None and args.device_state_output.exists():
        raise ValueError('Use a fresh device state output')
    run(['adb', '-P', args.port, '-s', args.serial], args.output, args.device_state_output)
