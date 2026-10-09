#!/usr/bin/env python3
"""Build the shared protocol engine with the Linux controller implementation.

Dependencies are copied from core:desktopRuntime, so Android and desktop use
the same Gradle-resolved cryptography/HTTPS versions. No Android SDK stubs.
"""
import argparse
import hashlib
import json
import os
import pathlib
import shutil
import subprocess
import tempfile
import zipfile

ROOT = pathlib.Path(__file__).resolve().parents[1]
SOURCE_ROOTS = (
    "apple-watch-bridge/core/src/main/java",
    "apple-watch-bridge/protocol-runtime/src/main/java",
    "linux-watch-host/src",
)
RESEARCH_CLASSES = {"ClockFaceSessionProbe", "ClockFaceJournalProbe",
                    "ClockFaceInventoryProbe", "IkeVectorSelfTest"}


def verify_runtime_jar(path, windows=False):
    with zipfile.ZipFile(path) as archive:
        names = archive.namelist()
        for name in names:
            if name.endswith(".java") or name.rsplit("/", 1)[-1].split("$")[0].removesuffix(".class") in RESEARCH_CLASSES:
                raise ValueError("Research source/class in runtime: " + name)
        required = {"WindowsProtocolHost", "WindowsBluetoothController", "WindowsSecretStore", "HalTransportSession"} if windows else {"LinuxProtocolHost", "HalTransportSession", "LinuxSecretStore"}
        classes = {name.rsplit("/", 1)[-1].removesuffix(".class") for name in names}
        if not required.issubset(classes):
            raise ValueError("Incomplete Linux protocol runtime")


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()

def build(destination, dependencies, run_tests=False, windows=False):
    destination = destination.absolute()
    destination.parent.mkdir(parents=True, exist_ok=True)
    if destination.is_symlink():
        raise ValueError("Protocol output must not be a symlink")
    jars = sorted(dependencies.glob("*.jar"))
    if not jars:
        raise SystemExit("Missing shared dependencies: run :core:desktopRuntime first")
    roots = SOURCE_ROOTS + (("windows-watch-host/src",) if windows else ())
    sources = sorted(path for directory in roots
                     for path in (ROOT / directory).rglob("*.java"))
    classpath = os.pathsep.join(str(path) for path in jars)
    # A fresh compiler directory prevents removed/renamed classes from surviving.
    # Stage the entire runtime before replacing it; compilation failure preserves it.
    with tempfile.TemporaryDirectory(prefix=".protocol-build-", dir=destination.parent) as scratch:
        stage = pathlib.Path(scratch)
        classes, runtime = stage / "classes", stage / "runtime"
        classes.mkdir()
        runtime.mkdir()
        arguments = ["--release", "17", "-encoding", "UTF-8", "-cp", classpath,
                     "-d", str(classes), *map(str, sources)]
        argument_file = stage / "javac.args"
        argument_file.write_text("\n".join('"' + argument.replace("\\", "\\\\").replace('"', '\\"') + '"'
                                           for argument in arguments), encoding="utf-8")
        subprocess.run(["javac", "@" + str(argument_file)], check=True)
        jar = runtime / ("watch-windows-protocol.jar" if windows else "watch-linux-protocol.jar")
        subprocess.run(["jar", "--create", "--date=2020-01-01T00:00:00Z", "--file", str(jar),
                        "-C", str(classes), "."], check=True)
        verify_runtime_jar(jar, windows)
        for dependency in jars:
            if dependency.name == jar.name:
                raise ValueError("Dependency name collides with protocol worker")
            shutil.copy2(dependency, runtime / dependency.name)
        if run_tests:
            tests = sorted((ROOT / ("windows-watch-host/test" if windows else "linux-watch-host/test")).rglob("*.java"))
            test_classes = stage / "tests"
            test_classes.mkdir()
            test_classpath = str(jar) + os.pathsep + classpath
            subprocess.run(["javac", "--release", "17", "-cp", test_classpath, "-d", str(test_classes),
                            *map(str, tests)], check=True)
            for test in tests:
                subprocess.run(["java", "-Dwatch.test.helper=" + str(ROOT / "target/x86_64-pc-windows-msvc/release/watch-windows-hci.exe"), "-cp", str(test_classes) + os.pathsep + test_classpath,
                                "dev.applewatchandroid.bridge." + test.stem], check=True)
        manifest = {
            "schema": 1, "javaRelease": 17,
            "sourceRoots": list(roots),
            "sources": {str(path.relative_to(ROOT)): digest(path) for path in sources},
            "artifacts": {path.name: digest(path) for path in sorted(runtime.glob("*.jar"))},
        }
        (runtime / "protocol-manifest.json").write_text(
            json.dumps(manifest, indent=2, sort_keys=True) + "\n", encoding="utf-8")
        previous = stage / "previous"
        if destination.exists():
            destination.rename(previous)
        try:
            runtime.rename(destination)
        except BaseException:
            if previous.exists():
                previous.rename(destination)
            raise

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=pathlib.Path, default=ROOT / "target/linux-protocol")
    parser.add_argument("--dependencies", type=pathlib.Path,
                        default=ROOT / "apple-watch-bridge/core/build/desktop-runtime")
    parser.add_argument("--test", action="store_true", help="Run isolated worker regressions before publishing output")
    parser.add_argument("--windows", action="store_true", help="Build Windows USB HCI host and DPAPI persistence")
    args = parser.parse_args()
    build(args.output, args.dependencies, args.test, args.windows)

if __name__ == "__main__":
    main()
