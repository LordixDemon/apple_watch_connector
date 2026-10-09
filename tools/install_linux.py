#!/usr/bin/env python3
"""Install the narrow HCI broker and a launcher for an already built Linux bundle."""
import argparse
import difflib
import grp
import os
import pathlib
import platform
import subprocess

def main():
    if platform.system() != "Linux" or os.getuid() == 0:
        raise SystemExit("Run as the desktop user on Linux; sudo is used only for the broker")
    parser = argparse.ArgumentParser()
    parser.add_argument("bundle", type=pathlib.Path)
    args = parser.parse_args()
    bundle = args.bundle.resolve()
    executable = bundle / "apple_watch_companion"
    broker = bundle / "watch-linux-hci"
    if not executable.is_file() or not broker.is_file():
        raise SystemExit("Build the Linux release bundle first")
    if any(c in str(bundle) for c in '\n\r"`$\\'):
        raise SystemExit("Unsupported launcher path characters")
    destination = "/usr/local/libexec/watch-companion"
    group = grp.getgrgid(os.getgid()).gr_name
    subprocess.run(["sudo", "install", "-d", "-m0755", destination], check=True)
    subprocess.run(["sudo", "install", "-o", "root", "-g", group, "-m0750", str(broker), destination + "/watch-linux-hci"], check=True)
    subprocess.run(["sudo", "setcap", "cap_net_admin,cap_net_raw+ep", destination + "/watch-linux-hci"], check=True)
    applications = pathlib.Path(os.environ.get("XDG_DATA_HOME", str(pathlib.Path.home() / ".local/share"))) / "applications"
    applications.mkdir(parents=True, exist_ok=True)
    launcher = applications / "watch-companion.desktop"
    before = launcher.read_text(encoding="utf-8").splitlines() if launcher.exists() else []
    # Generated installation artifact; project source edits use apply_patch.
    launcher.write_text('[Desktop Entry]\nType=Application\nName=Watch Companion\n'
                        f'Exec="{executable}"\nPath={bundle}\nTerminal=false\n'
                        'Categories=Utility;\nStartupWMClass=dev.applewatchandroid.companion.apple_watch_companion\n', encoding="utf-8")
    difference = list(difflib.unified_diff(before, launcher.read_text(encoding="utf-8").splitlines()))
    added = sum(line.startswith("+") and not line.startswith("+++") for line in difference)
    deleted = sum(line.startswith("-") and not line.startswith("---") for line in difference)
    print(f"Generated launcher changes: +{added}/-{deleted}")
    print("Installed Watch Companion launcher and the HCI broker for group " + group)

if __name__ == "__main__":
    main()
