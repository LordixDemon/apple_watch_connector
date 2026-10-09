#!/usr/bin/env python3
"""Explicit build targets; mobile Java HAL and desktop Rust remain isolated."""
import argparse
import os
import pathlib
import platform
import shutil
import subprocess
import tempfile
import uuid

ROOT = pathlib.Path(__file__).resolve().parents[1]

def run(*args, cwd=ROOT, env=None):
    if platform.system() == "Windows":
        args = (shutil.which(args[0]) or args[0], *args[1:])
    subprocess.run(args, cwd=cwd, env=env, check=True)

def embed_macos():
    arch = os.environ.get("CURRENT_ARCH", platform.machine())
    if arch == "undefined_arch":
        arch = platform.machine()
    target = {"arm64": "aarch64-apple-darwin", "x86_64": "x86_64-apple-darwin"}.get(arch)
    if target is None:
        raise SystemExit(f"Unsupported macOS architecture: {arch}")
    cargo = shutil.which("cargo") or str(pathlib.Path.home() / ".cargo/bin/cargo")
    env = dict(os.environ)
    env["MACOSX_DEPLOYMENT_TARGET"] = os.environ.get("MACOSX_DEPLOYMENT_TARGET", "13.0")
    run(cargo, "build", "--locked", "--release", "-p", "watch-core-ffi", "--target", target, env=env)
    folder = pathlib.Path(os.environ["TARGET_BUILD_DIR"]) / os.environ["FRAMEWORKS_FOLDER_PATH"]
    folder.mkdir(parents=True, exist_ok=True)
    output = folder / "libwatch_core_ffi.dylib"
    shutil.copy2(ROOT / "target" / target / "release/libwatch_core_ffi.dylib", output)
    run("install_name_tool", "-id", "@rpath/libwatch_core_ffi.dylib", str(output))
    run("codesign", "--force", "--sign", os.environ.get("EXPANDED_CODE_SIGN_IDENTITY") or "-", str(output))

def embed_linux(lib_dir):
    if platform.system() != "Linux":
        raise SystemExit("Build Linux on Linux")
    cargo = shutil.which("cargo") or str(pathlib.Path.home() / ".cargo/bin/cargo")
    run(cargo, "build", "--locked", "--release", "-p", "watch-core-ffi", "--lib")
    run(cargo, "build", "--locked", "--release", "-p", "watch-transport-linux", "--bin", "watch-linux-hci")
    folder = pathlib.Path(lib_dir)
    folder.mkdir(parents=True, exist_ok=True)
    shutil.copy2(ROOT / "target/release/libwatch_core_ffi.so", folder / "libwatch_core_ffi.so")
    shutil.copy2(ROOT / "target/release/watch-linux-hci", folder.parent / "watch-linux-hci")
    run("python3", str(ROOT / "tools/build_linux_protocol.py"), "--output", str(folder / "protocol"))

def embed_windows(lib_dir, target):
    if platform.system() != "Windows":
        raise SystemExit("Build Windows on Windows with the MSVC toolchain installed")
    folder = pathlib.Path(lib_dir).absolute()
    if folder.is_symlink() or folder.exists() and not folder.is_dir():
        raise ValueError("Windows native bundle must be a directory, not a linked path")
    if any((folder / name).exists() for name in ("apple_watch_companion.exe", "flutter_windows.dll")):
        raise ValueError("Use a dedicated native staging directory, not the Flutter application bundle")
    cargo = shutil.which("cargo") or str(pathlib.Path.home() / ".cargo/bin/cargo.exe")
    run(cargo, "build", "--locked", "--release", "-p", "watch-core-ffi", "--lib", "--target", target)
    run(cargo, "build", "--locked", "--release", "-p", "watch-transport-linux", "--bin", "watch-windows-hci", "--target", target)
    from build_windows_runtime import build_runtime
    folder.parent.mkdir(parents=True, exist_ok=True)
    # Stage every component together. A Cargo, protocol or jlink failure must
    # leave the currently published bundle intact, including its Java runtime.
    with tempfile.TemporaryDirectory(prefix=".windows-bundle-", dir=folder.parent) as scratch:
        stage = pathlib.Path(scratch) / "bundle"
        stage.mkdir()
        for name in ("watch_core_ffi.dll", "watch-windows-hci.exe"):
            shutil.copy2(ROOT / "target" / target / "release" / name, stage / name)
        build_runtime(stage)
        required = ("watch_core_ffi.dll", "watch-windows-hci.exe",
                    "protocol/watch-windows-protocol.jar", "protocol/protocol-manifest.json",
                    "jre/bin/java.exe")
        if any(not (stage / name).is_file() for name in required):
            raise ValueError("Incomplete Windows native bundle")
        # Keep rollback data outside the staging directory: if restoring it
        # fails (e.g. a concurrent process locks the destination), cleanup must
        # not delete the last working bundle.
        previous = folder.parent / (".windows-previous-" + uuid.uuid4().hex)
        if folder.exists():
            folder.rename(previous)
        try:
            stage.rename(folder)
        except BaseException:
            if previous.exists():
                previous.rename(folder)
            raise
        if previous.exists():
            shutil.rmtree(previous)

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("target", choices=["core", "android", "macos", "linux", "windows", "embed-macos", "embed-linux", "embed-windows"])
    parser.add_argument("--lib-dir", type=pathlib.Path)
    parser.add_argument("--rust-target", choices=["x86_64-pc-windows-msvc", "aarch64-pc-windows-msvc"],
                        default="x86_64-pc-windows-msvc")
    parser.add_argument("--distribution", action="store_true",
                        help="Android distribution build; requires external shared signing properties")
    args = parser.parse_args()
    if args.distribution and args.target != "android":
        parser.error("--distribution currently applies to Android only")
    if args.target == "embed-macos":
        embed_macos()
    elif args.target == "embed-linux":
        if args.lib_dir is None:
            parser.error("embed-linux requires --lib-dir")
        embed_linux(args.lib_dir)
    elif args.target == "embed-windows":
        if args.lib_dir is None:
            parser.error("embed-windows requires --lib-dir")
        embed_windows(args.lib_dir, args.rust_target)
    elif args.target == "core":
        # Linux worker/adapter tests require Linux processes and file semantics.
        host_tests = () if platform.system() == "Linux" else ("--exclude", "watch-transport-linux")
        run("cargo", "test", "--workspace", "--locked", *host_tests)
        run("cargo", "clippy", "--workspace", "--all-targets", "--locked", "--", "-D", "warnings")
    elif args.target == "android":
        env = dict(os.environ)
        if args.distribution:
            if not env.get("WATCH_RELEASE_SIGNING_PROPERTIES"):
                raise SystemExit("Set WATCH_RELEASE_SIGNING_PROPERTIES to an external signing properties file")
            env["WATCH_DISTRIBUTION_RELEASE"] = "1"
        run("./gradlew", ":app:assembleRelease", cwd=ROOT / "apple-watch-bridge", env=env)
        run("flutter", "build", "apk", "--release", "-t", "lib/main.dart", cwd=ROOT / "apple-watch-companion", env=env)
        if args.distribution:
            from release import audit_android
            sdk = env.get("ANDROID_HOME") or env.get("ANDROID_SDK_ROOT")
            if sdk is None:
                properties = (ROOT / "apple-watch-bridge/local.properties").read_text(encoding="utf-8")
                sdk = next((line.removeprefix("sdk.dir=") for line in properties.splitlines()
                            if line.startswith("sdk.dir=")), None)
            if sdk is None:
                raise SystemExit("Set ANDROID_HOME to verify distribution APK signatures")
            audit_android(ROOT / "apple-watch-bridge/app/build/outputs/apk/release/app-release.apk",
                          ROOT / "apple-watch-companion/build/app/outputs/flutter-apk/app-release.apk",
                          pathlib.Path(sdk) / "build-tools/36.1.0/apksigner")
    elif args.target == "macos":
        if platform.system() != "Darwin":
            raise SystemExit("Build macOS on macOS with Xcode installed")
        run("flutter", "build", "macos", "--release", "-t", "lib/main_desktop.dart", cwd=ROOT / "apple-watch-companion")
        run("codesign", "--verify", "--deep", "--strict", str(
            ROOT / "apple-watch-companion/build/macos/Build/Products/Release/apple_watch_companion.app"))
    elif args.target == "linux":
        if platform.system() != "Linux":
            raise SystemExit("Build Linux on Linux with GTK development dependencies installed")
        run("flutter", "build", "linux", "--release", "-t", "lib/main_desktop.dart", cwd=ROOT / "apple-watch-companion")
    elif args.target == "windows":
        if platform.system() != "Windows":
            raise SystemExit("Build Windows on Windows with Visual Studio C++ desktop tools installed")
        run("flutter", "build", "windows", "--release", "-t", "lib/main_desktop.dart", cwd=ROOT / "apple-watch-companion")

if __name__ == "__main__":
    main()
