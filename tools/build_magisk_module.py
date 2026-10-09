#!/usr/bin/env python3
"""Package a verified Bridge APK as a versioned Magisk module; no device access."""
import argparse
import hashlib
import os
from pathlib import Path
import re
import stat
import subprocess
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parents[1]
MODULE_FILES = (
    "module.prop", "customize.sh", "sepolicy.rule",
    "system/etc/sysconfig/apple-watch-bridge-hiddenapi-whitelist.xml",
)
APK_ENTRY = "system/priv-app/AppleWatchBridge/AppleWatchBridge.apk"


def android_tools():
    sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    if not sdk:
        properties = ROOT / "apple-watch-bridge/local.properties"
        if properties.is_file():
            sdk = next((line[8:] for line in properties.read_text().splitlines()
                        if line.startswith("sdk.dir=")), None)
    if not sdk:
        raise ValueError("Set ANDROID_HOME or configure Bridge local.properties")
    gradle = (ROOT / "apple-watch-bridge/app/build.gradle").read_text()
    version = re.search(r'buildToolsVersion\s+"([^"]+)"', gradle)
    if not version:
        raise ValueError("Bridge build tools version is missing")
    directory = Path(sdk) / "build-tools" / version[1]
    tools = directory / "aapt", directory / "apksigner"
    if any(not path.is_file() for path in tools):
        raise ValueError("Install the Bridge Android SDK build tools")
    return tools


def apk_identity(apk):
    aapt, apksigner = android_tools()
    subprocess.run([str(apksigner), "verify", str(apk)], check=True,
                   capture_output=True, text=True)
    report = subprocess.run([str(aapt), "dump", "badging", str(apk)],
                            check=True, capture_output=True, text=True).stdout
    match = re.search(r"^package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'",
                      report, re.MULTILINE)
    if not match or match[1] != "dev.applewatchandroid.bridge":
        raise ValueError("Expected a signed Apple Watch Bridge APK")
    if not re.fullmatch(r"[0-9]+(?:\.[0-9]+)*(?:[-+][A-Za-z0-9.-]+)?", match[3]):
        raise ValueError("Invalid Bridge version name")
    return match[3], match[2]


def package(apk, output, template=None):
    apk = Path(apk).resolve(strict=True)
    output = Path(output).absolute()
    template = Path(template) if template else ROOT / "apple-watch-bridge/tool/magisk_module"
    if output.is_symlink() or output.resolve() == apk:
        raise ValueError("Module output must not replace the APK or follow a symlink")
    version, code = apk_identity(apk)
    entries = {}
    for name in MODULE_FILES:
        path = template / name
        if path.is_symlink() or not path.is_file():
            raise ValueError("Missing or linked module template entry: " + name)
        data = path.read_bytes()
        if name == "module.prop":
            text = data.decode("utf-8")
            for key, value in (("version", version), ("versionCode", code)):
                text, count = re.subn(r"^" + key + r"=.*$", key + "=" + value, text,
                                     flags=re.MULTILINE)
                if count != 1:
                    raise ValueError("Expected one module " + key)
            data = text.encode("utf-8")
        entries[name] = data
    entries[APK_ENTRY] = apk.read_bytes()
    output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix=".magisk-", dir=output.parent) as scratch:
        archive = Path(scratch) / "module.zip"
        with zipfile.ZipFile(archive, "w", compression=zipfile.ZIP_DEFLATED) as zip_file:
            for name, data in sorted(entries.items()):
                info = zipfile.ZipInfo(name, (2020, 1, 1, 0, 0, 0))
                info.create_system = 3
                mode = 0o755 if name == "customize.sh" else 0o644
                info.external_attr = (stat.S_IFREG | mode) << 16
                info.compress_type = zipfile.ZIP_DEFLATED
                zip_file.writestr(info, data)
        with zipfile.ZipFile(archive) as zip_file:
            if zip_file.testzip() or set(zip_file.namelist()) != set(entries):
                raise ValueError("Invalid Magisk ZIP inventory or CRC")
            if any(zip_file.read(name) != data for name, data in entries.items()):
                raise ValueError("Magisk ZIP content mismatch")
        os.replace(archive, output)
    return version, code


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    version, code = package(args.apk, args.output)
    digest = hashlib.sha256(args.output.read_bytes()).hexdigest()
    print(f"Magisk module PASS: Bridge {version} / code {code}")
    print("SHA256 " + digest)
    print(args.output)
    print("No ADB, USB, phone, or Magisk command was executed.")


if __name__ == "__main__":
    main()
