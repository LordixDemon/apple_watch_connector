import subprocess
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch
import zipfile

import build_magisk_module as builder


class MagiskModuleTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.apk = self.root / "bridge.apk"
        self.apk.write_bytes(b"synthetic test APK")
        self.output = self.root / "module.zip"
        self.template = self.root / "template"
        for name in builder.MODULE_FILES:
            path = self.template / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text("version=0.1.0\nversionCode=1\n" if name == "module.prop" else "fixture")

    def build(self):
        with patch.object(builder, "apk_identity", return_value=("0.2.427", "627")):
            return builder.package(self.apk, self.output, self.template)

    def test_exact_inventory_and_version_from_apk(self):
        self.build()
        with zipfile.ZipFile(self.output) as archive:
            self.assertEqual(set(archive.namelist()), {*builder.MODULE_FILES, builder.APK_ENTRY})
            self.assertEqual(archive.read("module.prop"), b"version=0.2.427\nversionCode=627\n")
            self.assertEqual(archive.read(builder.APK_ENTRY), self.apk.read_bytes())

    def test_rebuild_removes_stale_entries_and_is_reproducible(self):
        with zipfile.ZipFile(self.output, "w") as archive:
            archive.writestr("private-old.log", "must not survive")
        self.build()
        first = self.output.read_bytes()
        self.build()
        self.assertEqual(first, self.output.read_bytes())
        with zipfile.ZipFile(self.output) as archive:
            self.assertNotIn("private-old.log", archive.namelist())

    def test_signature_failure_keeps_previous_archive(self):
        self.output.write_bytes(b"previous")
        with patch.object(builder, "apk_identity", side_effect=subprocess.CalledProcessError(1, "apksigner")):
            with self.assertRaises(subprocess.CalledProcessError):
                builder.package(self.apk, self.output, self.template)
        self.assertEqual(self.output.read_bytes(), b"previous")

    def test_linked_template_entry_is_rejected(self):
        path = self.template / "sepolicy.rule"
        path.unlink()
        path.symlink_to(self.apk)
        with self.assertRaises(ValueError):
            self.build()

    def test_wrong_apk_package_is_rejected(self):
        result = subprocess.CompletedProcess([], 0, "package: name='other.app' versionCode='1' versionName='1.0'")
        with patch.object(builder, "android_tools", return_value=(Path("aapt"), Path("apksigner"))), \
                patch.object(builder.subprocess, "run", return_value=result):
            with self.assertRaises(ValueError):
                builder.apk_identity(self.apk)


if __name__ == "__main__":
    unittest.main()
