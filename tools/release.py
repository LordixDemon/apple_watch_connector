#!/usr/bin/env python3
"""Audit/package an existing Linux candidate; never archive the research workspace."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import stat
import struct
import subprocess
import tempfile

from build_linux_protocol import verify_runtime_jar
from release_archive import archive_epoch, normalized_mode, verify_archive, write_archive

ROOT = Path(__file__).resolve().parents[1]
FORBIDDEN_PARTS = {"protocol-classes", "identity", "device-backups", "firmware", "graphify-out", "__pycache__"}
FORBIDDEN_SUFFIXES = {".class", ".java", ".log", ".sealed", ".jks", ".keystore", ".p12"}


def distribution_certificate(report):
    if re.search(r"certificate DN:.*CN=Android Debug", report, re.IGNORECASE):
        raise ValueError("Debug certificate is not a distribution signing key")
    certificates = re.findall(r"Signer #\d+ certificate SHA-256 digest: ([0-9a-fA-F]{64})", report)
    if len(certificates) != 1:
        raise ValueError("Expected one verified APK signing certificate")
    return certificates[0].lower()


def audit_android(bridge_apk, companion_apk, apksigner):
    certificates = []
    for apk in (bridge_apk, companion_apk):
        report = subprocess.run([str(apksigner), "verify", "--print-certs", str(apk)],
                                check=True, capture_output=True, text=True)
        certificates.append(distribution_certificate(report.stdout))
    if certificates[0] != certificates[1]:
        raise ValueError("Bridge and Companion certificates differ; signature IPC would fail")
    print("Android distribution signature PASS: " + certificates[0])


def sha256(path):
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def elf_architecture(path):
    with path.open("rb") as stream:
        header = stream.read(20)
    if len(header) != 20 or header[:6] != b"\x7fELF\x02\x01":
        raise ValueError("Expected 64-bit little-endian ELF: " + str(path))
    machine = struct.unpack_from("<H", header, 18)[0]
    try:
        return {62: "x86_64", 183: "aarch64"}[machine]
    except KeyError:
        raise ValueError("Unsupported Linux ELF machine") from None


def audit_linux(bundle):
    bundle = bundle.resolve(strict=True)
    paths = sorted(bundle.rglob("*"))
    for path in paths:
        relative = path.relative_to(bundle)
        if FORBIDDEN_PARTS.intersection(relative.parts) or path.suffix in FORBIDDEN_SUFFIXES or path.name in {"storage.key", "bluetooth-adapter.json"}:
            raise ValueError("Non-runtime artifact in bundle: " + str(relative))
        if path.is_symlink():
            path.resolve(strict=True).relative_to(bundle)
            if Path(os.readlink(path)).is_absolute() or path.is_dir():
                raise ValueError("Only relative file symlinks are portable: " + str(relative))
        elif not path.is_file() and not path.is_dir():
            raise ValueError("Special file in bundle: " + str(relative))
    binaries = [bundle / "apple_watch_companion", bundle / "watch-linux-hci",
                bundle / "lib/libwatch_core_ffi.so"]
    architectures = {elf_architecture(path) for path in binaries}
    if len(architectures) != 1:
        raise ValueError("Mixed binary architectures")
    for executable in binaries[:2]:
        if not executable.stat().st_mode & stat.S_IXUSR:
            raise ValueError("Missing executable permission: " + str(executable))
    for name in ("lib/libflutter_linux_gtk.so", "data/icudtl.dat", "data/flutter_assets/AssetManifest.bin"):
        if not (bundle / name).is_file():
            raise ValueError("Missing Flutter runtime artifact: " + name)
    protocol = bundle / "lib/protocol"
    manifest = json.loads((protocol / "protocol-manifest.json").read_text(encoding="utf-8"))
    if manifest.get("schema") != 1 or manifest.get("javaRelease") != 17:
        raise ValueError("Unsupported protocol build manifest")
    expected = manifest.get("artifacts")
    if not isinstance(expected, dict) or "watch-linux-protocol.jar" not in expected:
        raise ValueError("Missing protocol artifact inventory")
    actual = {path.name: sha256(path) for path in protocol.glob("*.jar")}
    if actual != expected:
        raise ValueError("Protocol artifact hash/inventory mismatch")
    if {path.name for path in protocol.iterdir()} != set(expected) | {"protocol-manifest.json"}:
        raise ValueError("Unexpected protocol bundle content")
    verify_runtime_jar(protocol / "watch-linux-protocol.jar")
    if not (bundle / "data/flutter_assets/kernel_blob.bin").exists() and (bundle / "lib/libapp.so").is_file():
        build_mode = "aot"
    else:
        raise ValueError("Candidate must be a release AOT bundle")
    entries = {}
    for path in paths:
        name = path.relative_to(bundle).as_posix()
        if path.is_symlink():
            entries[name] = {"type": "symlink", "mode": 0o777, "target": os.readlink(path)}
        elif path.is_dir():
            entries[name] = {"type": "directory", "mode": 0o755}
        else:
            entries[name] = {"type": "file", "mode": normalized_mode(path), "sha256": sha256(path)}
    return {"schema": 2, "platform": "linux", "architecture": architectures.pop(), "buildMode": build_mode,
            "entries": entries, "files": {name: entry["sha256"] for name, entry in entries.items() if entry["type"] == "file"}}


def package_linux(bundle, output):
    bundle = bundle.resolve(strict=True)
    inventory = audit_linux(bundle)
    output = output.resolve()
    if output.is_relative_to(bundle):
        raise ValueError("Archive output must be outside the bundle")
    extras = {"install_linux.py": ROOT / "tools/install_linux.py", "README.md": ROOT / "README.md"}
    inventory["packageFiles"] = {name: [sha256(path), normalized_mode(path)] for name, path in extras.items()}
    epoch = archive_epoch()
    output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix=".release-", dir=output.parent) as scratch:
        stage = Path(scratch)
        (stage / "release-manifest.json").write_text(json.dumps(inventory, indent=2, sort_keys=True) + "\n", encoding="utf-8")
        archive = stage / "candidate.tar.gz"
        write_archive(archive, bundle, stage / "release-manifest.json", extras, epoch)
        verify_archive(archive, inventory, epoch)
        os.replace(archive, output)
    print(f"Linux candidate {inventory['architecture']}: {len(inventory['files'])} audited files")
    print("SHA256 " + sha256(output))
    print(output)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=["audit-linux", "package-linux", "audit-android"])
    parser.add_argument("--bundle", type=Path)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--bridge-apk", type=Path)
    parser.add_argument("--companion-apk", type=Path)
    parser.add_argument("--apksigner", type=Path)
    args = parser.parse_args()
    if args.action == "audit-android":
        if not all((args.bridge_apk, args.companion_apk, args.apksigner)):
            parser.error("audit-android requires --bridge-apk, --companion-apk and --apksigner")
        audit_android(args.bridge_apk, args.companion_apk, args.apksigner)
        return
    if args.bundle is None:
        parser.error("Linux actions require --bundle")
    if args.action == "package-linux":
        if args.output is None:
            parser.error("package-linux requires --output")
        package_linux(args.bundle, args.output)
    else:
        inventory = audit_linux(args.bundle)
        print(f"Linux bundle PASS: {inventory['architecture']}, {len(inventory['files'])} files")


if __name__ == "__main__":
    main()
