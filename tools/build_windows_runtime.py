#!/usr/bin/env python3
"""Package the existing portable protocol with native Windows HCI and DPAPI."""
import os
import pathlib
import shutil
import subprocess
import tempfile
from build_linux_protocol import ROOT, build


def build_runtime(destination):
    destination = pathlib.Path(destination).absolute()
    java = shutil.which("java")
    java_home = pathlib.Path(os.environ.get("JAVA_HOME", pathlib.Path(java).parent.parent if java else ""))
    if not (java_home / "bin/javac.exe").is_file() or not (java_home / "bin/jlink.exe").is_file():
        raise SystemExit("Set JAVA_HOME to a Windows JDK 17+ containing javac and jlink")
    env = dict(os.environ)
    env["JAVA_HOME"] = str(java_home)
    env["PATH"] = str(java_home / "bin") + os.pathsep + env.get("PATH", "")
    subprocess.run([str(java_home / "bin/java.exe"), "-cp", "gradle/wrapper/gradle-wrapper.jar",
                    "org.gradle.wrapper.GradleWrapperMain", ":core:desktopRuntime", "--console=plain"],
                   cwd=ROOT / "apple-watch-bridge", env=env, check=True)
    # build() invokes the JDK tools; use only this build process's PATH.
    previous = os.environ.get("PATH", "")
    try:
        os.environ["PATH"] = env["PATH"]
        build(destination / "protocol", ROOT / "apple-watch-bridge/core/build/desktop-runtime", windows=True)
    finally:
        os.environ["PATH"] = previous
    runtime = destination / "jre"
    if not (runtime / "bin/java.exe").is_file():
        with tempfile.TemporaryDirectory(prefix=".windows-jre-", dir=destination) as scratch:
            staged = pathlib.Path(scratch) / "jre"
            subprocess.run([str(java_home / "bin/jlink.exe"), "--add-modules",
                            "java.base,java.logging,java.net.http,java.management,jdk.crypto.ec,jdk.unsupported",
                            "--strip-debug", "--no-header-files", "--no-man-pages", "--output", str(staged)], check=True)
            staged.rename(runtime)


if __name__ == "__main__":
    import argparse
    parser = argparse.ArgumentParser()
    parser.add_argument("destination", type=pathlib.Path)
    build_runtime(parser.parse_args().destination)
