import json
import os
from pathlib import Path
import struct
import subprocess
import tempfile
import tarfile
import unittest
from unittest.mock import patch
import zipfile

import build_linux_protocol as protocol
import release


def write_jar(path, extra=()):
    with zipfile.ZipFile(path, "w") as archive:
        for name in ("LinuxProtocolHost", "HalTransportSession", "LinuxSecretStore", *extra):
            archive.writestr("dev/applewatchandroid/bridge/" + name + ".class", b"fixture")


class ProtocolBuildTest(unittest.TestCase):
    def test_staging_discards_stale_outputs_and_preserves_runtime_on_failure(self):
        with tempfile.TemporaryDirectory() as scratch:
            root = Path(scratch)
            source = root / "src/Host.java"
            source.parent.mkdir()
            source.write_text("class Host {}")
            deps = root / "deps"
            deps.mkdir()
            write_jar(deps / "dependency.jar")
            output = root / "runtime"
            output.mkdir()
            (output / "removed.jar").write_bytes(b"old")

            def compile_fixture(command, **kwargs):
                if command[0] == "jar":
                    write_jar(Path(command[command.index("--file") + 1]))

            with patch.object(protocol, "ROOT", root), patch.object(protocol, "SOURCE_ROOTS", ("src",)):
                with patch.object(protocol.subprocess, "run", side_effect=compile_fixture):
                    protocol.build(output, deps)
                self.assertEqual({p.name for p in output.iterdir()},
                                 {"dependency.jar", "watch-linux-protocol.jar", "protocol-manifest.json"})
                before = {p.name: p.read_bytes() for p in output.iterdir()}
                with patch.object(protocol.subprocess, "run", side_effect=subprocess.CalledProcessError(1, "javac")):
                    with self.assertRaises(subprocess.CalledProcessError):
                        protocol.build(output, deps)
                self.assertEqual(before, {p.name: p.read_bytes() for p in output.iterdir()})
                self.assertFalse(list(root.glob(".protocol-build-*")))

    def test_research_classes_and_inner_classes_are_rejected(self):
        with tempfile.TemporaryDirectory() as scratch:
            jar = Path(scratch) / "runtime.jar"
            for name in ("ClockFaceSessionProbe", "IkeVectorSelfTest$Fixture"):
                write_jar(jar, (name,))
                with self.assertRaises(ValueError):
                    protocol.verify_runtime_jar(jar)


class ReleaseAuditTest(unittest.TestCase):
    def test_distribution_requires_non_debug_single_matching_certificates(self):
        report = "Signer #1 certificate DN: CN=Watch Release\nSigner #1 certificate SHA-256 digest: " + "a" * 64
        self.assertEqual(release.distribution_certificate(report), "a" * 64)
        with self.assertRaisesRegex(ValueError, "Debug certificate"):
            release.distribution_certificate(report.replace("Watch Release", "Android Debug"))
        with self.assertRaises(ValueError):
            release.distribution_certificate("")
        responses = [subprocess.CompletedProcess([], 0, report),
                     subprocess.CompletedProcess([], 0, report.replace("a" * 64, "b" * 64))]
        with patch.object(release.subprocess, "run", side_effect=responses):
            with self.assertRaisesRegex(ValueError, "certificates differ"):
                release.audit_android(Path("bridge.apk"), Path("companion.apk"), Path("apksigner"))

    def bundle(self, root):
        for name in ("apple_watch_companion", "watch-linux-hci", "lib/libwatch_core_ffi.so"):
            path = root / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(b"\x7fELF\x02\x01" + b"\0" * 12 + struct.pack("<H", 62))
            path.chmod(0o755)
        for name in ("lib/libapp.so", "lib/libflutter_linux_gtk.so", "data/icudtl.dat",
                     "data/flutter_assets/AssetManifest.bin"):
            path = root / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(b"fixture")
        folder = root / "lib/protocol"
        folder.mkdir()
        write_jar(folder / "watch-linux-protocol.jar")
        manifest = {"schema": 1, "javaRelease": 17,
                    "artifacts": {"watch-linux-protocol.jar": release.sha256(folder / "watch-linux-protocol.jar")}}
        (folder / "protocol-manifest.json").write_text(json.dumps(manifest))

    def test_candidate_inventory_and_tampering(self):
        with tempfile.TemporaryDirectory() as scratch:
            root = Path(scratch)
            self.bundle(root)
            self.assertEqual(release.audit_linux(root)["architecture"], "x86_64")
            (root / "lib/protocol/watch-linux-protocol.jar").write_bytes(b"tampered")
            with self.assertRaisesRegex(ValueError, "hash/inventory"):
                release.audit_linux(root)

    def test_archive_is_reproducible_with_root_alias_and_changed_timestamps(self):
        with tempfile.TemporaryDirectory() as scratch:
            root = Path(scratch) / "bundle"
            root.mkdir()
            self.bundle(root)
            (root / "runtime-link").symlink_to("lib/libapp.so")
            alias = Path(scratch) / "alias"
            alias.symlink_to(root, target_is_directory=True)
            first, second = Path(scratch) / "first.tar.gz", Path(scratch) / "second.tar.gz"
            release.package_linux(alias, first)
            for path in root.rglob("*"):
                os.utime(path, (1900000000, 1900000000), follow_symlinks=False)
            release.package_linux(root, second)
            self.assertEqual(first.read_bytes(), second.read_bytes())
            with tarfile.open(first) as archive:
                self.assertTrue(archive.getmember("watch-companion/bundle").isdir())
                manifest = json.load(archive.extractfile("watch-companion/release-manifest.json"))
            release.verify_archive(first, manifest, release.archive_epoch())
            manifest["entries"]["lib/libapp.so"]["sha256"] = "0" * 64
            with self.assertRaisesRegex(ValueError, "content mismatch"):
                release.verify_archive(first, manifest, release.archive_epoch())

    def test_verification_failure_preserves_previous_candidate(self):
        with tempfile.TemporaryDirectory() as scratch:
            root = Path(scratch) / "bundle"
            root.mkdir()
            self.bundle(root)
            output = Path(scratch) / "candidate.tar.gz"
            output.write_bytes(b"previous verified candidate")
            with patch.object(release, "verify_archive", side_effect=ValueError("changed during packaging")):
                with self.assertRaises(ValueError):
                    release.package_linux(root, output)
            self.assertEqual(output.read_bytes(), b"previous verified candidate")
            self.assertFalse(list(Path(scratch).glob(".release-*")))

    def test_secrets_stale_classes_and_external_symlinks_rejected(self):
        with tempfile.TemporaryDirectory() as scratch:
            root = Path(scratch) / "bundle"
            root.mkdir()
            self.bundle(root)
            for name in ("storage.key", "bluetooth-adapter.json", "bond.sealed", "protocol.log", "protocol-classes/Old.class"):
                path = root / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes(b"never ship")
                with self.assertRaisesRegex(ValueError, "Non-runtime"):
                    release.audit_linux(root)
                path.unlink()
                if path.parent != root:
                    path.parent.rmdir()
            (root / "outside").symlink_to(Path(scratch))
            with self.assertRaises(ValueError):
                release.audit_linux(root)


if __name__ == "__main__":
    unittest.main()
