import pathlib
import subprocess
import tempfile
import unittest
from unittest.mock import patch

import build


class WindowsBuildTest(unittest.TestCase):
    @staticmethod
    def write_runtime(bundle):
        for name in ("protocol/watch-windows-protocol.jar", "protocol/protocol-manifest.json", "jre/bin/java.exe"):
            path = bundle / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(b"new runtime")

    @staticmethod
    def compiled_binaries(root, target="x86_64-pc-windows-msvc"):
        compiled = root / "target" / target / "release"
        compiled.mkdir(parents=True)
        for name in ("watch_core_ffi.dll", "watch-windows-hci.exe"):
            (compiled / name).write_bytes(b"new native")

    def test_bundle_uses_requested_architecture(self):
        for target in ("x86_64-pc-windows-msvc", "aarch64-pc-windows-msvc"):
            with self.subTest(target=target), tempfile.TemporaryDirectory() as scratch:
                root = pathlib.Path(scratch)
                dll = root / "target" / target / "release/watch_core_ffi.dll"
                dll.parent.mkdir(parents=True)
                dll.write_bytes(target.encode())
                helper = dll.with_name("watch-windows-hci.exe")
                helper.write_bytes(b"native USB HCI")
                bundle = root / "bundle"
                with patch.object(build, "ROOT", root), patch.object(build.platform, "system", return_value="Windows"):
                    with patch.object(build, "run") as run, patch("build_windows_runtime.build_runtime", side_effect=self.write_runtime) as runtime:
                        build.embed_windows(bundle, target)
                self.assertEqual((bundle / "watch_core_ffi.dll").read_bytes(), target.encode())
                self.assertEqual((bundle / "watch-windows-hci.exe").read_bytes(), b"native USB HCI")
                runtime.assert_called_once()
                self.assertNotEqual(runtime.call_args.args[0], bundle)
                self.assertEqual((bundle / "jre/bin/java.exe").read_bytes(), b"new runtime")
                self.assertEqual(run.call_args.args[-2:], ("--target", target))
                self.assertIn("--locked", run.call_args.args)

    def test_failed_compile_preserves_previous_bundle(self):
        with tempfile.TemporaryDirectory() as scratch:
            bundle = pathlib.Path(scratch)
            (bundle / "watch_core_ffi.dll").write_bytes(b"previous")
            with patch.object(build.platform, "system", return_value="Windows"):
                with patch.object(build, "run", side_effect=subprocess.CalledProcessError(1, "cargo")):
                    with self.assertRaises(subprocess.CalledProcessError):
                        build.embed_windows(bundle, "x86_64-pc-windows-msvc")
            self.assertEqual((bundle / "watch_core_ffi.dll").read_bytes(), b"previous")

    def test_foreign_host_is_rejected_before_building(self):
        with patch.object(build.platform, "system", return_value="Linux"), patch.object(build, "run") as run:
            with self.assertRaisesRegex(SystemExit, "Build Windows on Windows"):
                build.embed_windows(pathlib.Path("unused"), "x86_64-pc-windows-msvc")
            run.assert_not_called()

    def test_the_flutter_application_directory_is_never_replaced(self):
        with tempfile.TemporaryDirectory() as scratch:
            bundle = pathlib.Path(scratch)
            executable = bundle / "apple_watch_companion.exe"
            executable.write_bytes(b"application")
            with patch.object(build.platform, "system", return_value="Windows"), patch.object(build, "run") as run:
                with self.assertRaisesRegex(ValueError, "dedicated native staging"):
                    build.embed_windows(bundle, "x86_64-pc-windows-msvc")
                run.assert_not_called()
            self.assertEqual(executable.read_bytes(), b"application")

    def test_protocol_or_jre_failure_preserves_every_published_component(self):
        for failed_component in ("protocol", "jre"):
            with self.subTest(component=failed_component), tempfile.TemporaryDirectory() as scratch:
                root = pathlib.Path(scratch)
                self.compiled_binaries(root)
                bundle = root / "bundle"
                previous = ("watch_core_ffi.dll", "watch-windows-hci.exe",
                            "protocol/watch-windows-protocol.jar", "jre/bin/java.exe")
                for name in previous:
                    path = bundle / name
                    path.parent.mkdir(parents=True, exist_ok=True)
                    path.write_bytes(b"previous " + name.encode())
                before = {name: (bundle / name).read_bytes() for name in previous}

                def fail(stage):
                    self.write_runtime(stage)
                    raise subprocess.CalledProcessError(1, failed_component)

                with patch.object(build, "ROOT", root), patch.object(build.platform, "system", return_value="Windows"):
                    with patch.object(build, "run"), patch("build_windows_runtime.build_runtime", side_effect=fail):
                        with self.assertRaises(subprocess.CalledProcessError):
                            build.embed_windows(bundle, "x86_64-pc-windows-msvc")
                self.assertEqual(before, {name: (bundle / name).read_bytes() for name in previous})
                self.assertFalse(list(root.glob(".windows-bundle-*")))

    def test_incomplete_runtime_is_not_published(self):
        with tempfile.TemporaryDirectory() as scratch:
            root = pathlib.Path(scratch)
            self.compiled_binaries(root)
            with patch.object(build, "ROOT", root), patch.object(build.platform, "system", return_value="Windows"):
                with patch.object(build, "run"), patch("build_windows_runtime.build_runtime"):
                    with self.assertRaisesRegex(ValueError, "Incomplete"):
                        build.embed_windows(root / "bundle", "x86_64-pc-windows-msvc")
            self.assertFalse((root / "bundle").exists())

    def test_failed_directory_swap_restores_the_previous_bundle(self):
        with tempfile.TemporaryDirectory() as scratch:
            root = pathlib.Path(scratch)
            self.compiled_binaries(root)
            bundle = root / "bundle"
            bundle.mkdir()
            (bundle / "watch_core_ffi.dll").write_bytes(b"previous")
            rename = pathlib.Path.rename

            def fail_publish(path, destination):
                if path.name == "bundle" and path.parent.name.startswith(".windows-bundle-"):
                    raise PermissionError("bundle is in use")
                return rename(path, destination)

            with patch.object(build, "ROOT", root), patch.object(build.platform, "system", return_value="Windows"):
                with patch.object(build, "run"), patch("build_windows_runtime.build_runtime", side_effect=self.write_runtime):
                    with patch.object(pathlib.Path, "rename", fail_publish):
                        with self.assertRaises(PermissionError):
                            build.embed_windows(bundle, "x86_64-pc-windows-msvc")
            self.assertEqual((bundle / "watch_core_ffi.dll").read_bytes(), b"previous")

    def test_success_replaces_the_entire_previous_bundle(self):
        with tempfile.TemporaryDirectory() as scratch:
            root = pathlib.Path(scratch)
            self.compiled_binaries(root)
            bundle = root / "bundle"
            bundle.mkdir()
            (bundle / "obsolete.dll").write_bytes(b"obsolete")
            with patch.object(build, "ROOT", root), patch.object(build.platform, "system", return_value="Windows"):
                with patch.object(build, "run"), patch("build_windows_runtime.build_runtime", side_effect=self.write_runtime):
                    build.embed_windows(bundle, "x86_64-pc-windows-msvc")
            self.assertFalse((bundle / "obsolete.dll").exists())
            self.assertEqual((bundle / "watch_core_ffi.dll").read_bytes(), b"new native")
            self.assertFalse(list(root.glob(".windows-previous-*")))

    def test_failed_rollback_retains_a_recoverable_previous_bundle(self):
        with tempfile.TemporaryDirectory() as scratch:
            root = pathlib.Path(scratch)
            self.compiled_binaries(root)
            bundle = root / "bundle"
            bundle.mkdir()
            (bundle / "watch_core_ffi.dll").write_bytes(b"previous")
            rename = pathlib.Path.rename

            def fail_restore(path, destination):
                if path.parent.name.startswith(".windows-bundle-") or path.name.startswith(".windows-previous-"):
                    raise PermissionError("destination is locked")
                return rename(path, destination)

            with patch.object(build, "ROOT", root), patch.object(build.platform, "system", return_value="Windows"):
                with patch.object(build, "run"), patch("build_windows_runtime.build_runtime", side_effect=self.write_runtime):
                    with patch.object(pathlib.Path, "rename", fail_restore):
                        with self.assertRaises(PermissionError):
                            build.embed_windows(bundle, "x86_64-pc-windows-msvc")
            previous = list(root.glob(".windows-previous-*"))
            self.assertEqual(len(previous), 1)
            self.assertEqual((previous[0] / "watch_core_ffi.dll").read_bytes(), b"previous")


if __name__ == "__main__":
    unittest.main()
