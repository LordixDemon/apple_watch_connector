# Watch Companion release candidates

This is a test candidate, not a declaration of complete Watch.app parity.
Supported physical evidence: Ultra 2 (`Watch7,5`), watchOS 26.2. Android pairing,
activation and reconnect are verified. Linux Mint 22.3 x86_64 pairing,
activation, visible watch face and reconnect are verified. Windows 10 x64 with
Realtek `0bda:b00e` also has physical PIN, activation and encrypted reconnect
evidence. Other models/controllers need their own hardware acceptance run.

For installation from a clean machine, use the executable sequences in the
README: [English](../README.md#en-start) / [Русский](../README.md#ru-start).
They cover host dependencies, SDK selection, Android prerequisites and ADB,
Linux broker setup, Windows controller selection/recovery, macOS discovery,
logs and saved-pair updates. This document covers candidate auditing and signing.

## Candidate versions and support

Current source versions: Companion `1.0.85+86`, Android Bridge `0.2.427` (code
`627`), shared Rust crates `0.1.1`. These components have independent version
numbers; publish the exact tested combination and its Git revision together.
The current status is **experimental release candidate**, not a stable 1.0
support promise. Changes awaiting a candidate are in [CHANGELOG](../CHANGELOG.md).

Retained evidence reports describe historical hardware runs. Personal pairing,
face and device identifiers in their public examples and test fixtures have been
anonymized consistently; they must not be used to address a real device. Runtime
identity always comes from authenticated discovery/pairing records.

| Service | Android | Linux | Windows | macOS |
| --- | --- | --- | --- | --- |
| New pairing / activation / saved-pair reconnect | Physically verified | Physically verified | Physically verified on the controller above | Incomplete; BLE discovery/link only |
| Native face library and mutations | Implemented; readback required | Not exposed by desktop FFI | Not exposed by desktop FFI | Not exposed by desktop FFI |
| Battery / About; wrist, orientation, 24-hour setting | Native observations and three whitelisted writes | Not exposed by desktop FFI | Not exposed by desktop FFI | Not exposed by desktop FFI |
| Phone Wi-Fi profile transfer | Tested WPA2-PSK/CCMP network | Unavailable | Unavailable | Unavailable |
| Notification relay / Find Phone | Implemented, permission and acceptance limits apply | Unavailable | Unavailable | Unavailable |
| Complete Health, app management, calls/audio | Incomplete | Unavailable | Unavailable | Unavailable |

The UI reads backend-declared feature groups. Unsupported sections/actions are
hidden; the Available Features panel explains what is implemented. Operational
IDS readiness is a connection state and never enables application services by
itself. An absent feature declaration is shown as unknown.

Android root HAL access remains restricted to OnePlus CPH2653; testing one phone
does not establish support for every Android Bluetooth HAL. Stock global IRK
alignment/rollback refuses configs containing another bonded device; ordinary
bond import preserves unrelated config bytes and uses the authenticated record's
identity, without compiled-in personal addresses.

## Windows installation and verification

Build on Windows with Visual Studio C++ desktop tools, Rust MSVC, Flutter and JDK
17–22 (tested with 17). From the project root:

```powershell
python tools/build.py windows
.\tools\run_windows_companion.ps1 -Check
.\tools\run_windows_companion.ps1
```

The launcher selects a sole supported controller automatically; multiple
controllers require an explicit choice. `-InstanceId` selects an exact present
USB node, and `-NonInteractive` fails instead of choosing arbitrarily. UAC
prepares the controller; Flutter and the DPAPI protocol worker stay unelevated.
The native DLL, HCI broker, protocol JARs and Java runtime are built in staging
and swapped together only after successful validation. Runtime state is kept
in `%LOCALAPPDATA%/watch-companion`, outside the bundle.

The WinUSB lease exclusively owns the selected controller. Confirm restoration
of the original Bluetooth driver on exit and test crash, sleep, unplug and
reboot recovery before releasing each candidate. Preserve the lease's
`original.json` recovery record until restoration is confirmed. The latest
controller-selection/staging refactor has local tests, but needs a new physical
Windows acceptance run. See [Windows connection](../README.md#en-windows).

## macOS status

The Flutter/Rust/CoreBluetooth build supports discovery and BLE connection.
`operational_ids` remains false; this is not an activated Watch companion.
Ship it only as a clearly labelled diagnostic build until pairing and IDS are
completed and physically tested. See [transport findings](MACOS_PAIRING_TRANSPORT_FINDINGS.md).

## Linux installation

Requires a normal desktop session, GTK 3, BlueZ, `busctl` with JSON output, a Java 17+ runtime,
`sudo`, `install` and `setcap`. The current HCI USER transport exclusively owns
the selected built-in adapter while connected. Other Bluetooth peripherals on
that adapter cannot use it during the session.

From the extracted package:

```sh
python3 install_linux.py bundle
./bundle/apple_watch_companion
```

The installer grants capabilities only to the root-owned narrow HCI broker.
Java and Flutter run as the desktop user. Pairing records stay outside the
bundle, in `$XDG_STATE_HOME/watch-companion` or `~/.local/state/watch-companion`.
They use owner-only files and AES-GCM, without a hardware-keystore guarantee.
Keep that directory during upgrades. Close Companion before replacing a bundle;
keep the previous bundle for rollback. Do not run two session owners together.

In **All Watches → Bluetooth Adapter**, choose the controller to use. One
available adapter is selected and saved automatically; with multiple adapters,
choose explicitly. The preference is stored by Bluetooth address in
`bluetooth-adapter.json`, separately from Watch pairing records. HCI numbers are
resolved again on each connection, and the worker verifies the address before
leasing the controller. An absent saved adapter never falls back to other
hardware. Disconnect the Watch to refresh or change adapters; then connect again.
The list refreshes while idle and has a manual Refresh Adapters action for
hot-plugged controllers. Legacy `WATCH_HCI_INDEX` is imported only when no saved
preference exists; UI selection takes precedence afterward.

## Build and verify from source

Use the checked-in Cargo/pub/Gradle dependency versions. Current validation:
Rust 1.98.1, Flutter 3.47.4/Dart 3.13.3, JDK 17; Android SDK 36,
build tools 36.1.0 and NDK 28.2.13676358. Platform builds run on their host OS.
Linux protocol dependencies are staged by `:core:desktopRuntime` (Gradle Sync);
the Linux builder does not resolve unpinned jars from the network. The shared
Java modules use strict Gradle lockfiles, including transitive dependencies.
Intentional updates require `:core:resolvePortableDependencies
:protocol-runtime:resolvePortableDependencies --write-locks` followed by tests;
ordinary builds do not rewrite them.

After cloning, select the tested SDKs and run
`flutter pub get --enforce-lockfile` inside `apple-watch-companion` before
building/testing. Use `python` for the Python commands on Windows and run
Gradle wrapper tasks there with `gradlew.bat`; the Linux sequence below uses a
POSIX shell. Host-sensitive Rust tests are selected by `tools/build.py core`.

```sh
python3 tools/build.py core
cd apple-watch-bridge
./gradlew :core:test :protocol-runtime:test :core:researchJar :core:desktopRuntime
cd ..
python3 tools/build_linux_protocol.py --test
python3 -m unittest discover -s tools -p 'test_release_build.py'
cd apple-watch-companion
flutter analyze
flutter test
cd ..
python3 tools/build.py linux
python3 tools/release.py audit-linux --bundle apple-watch-companion/build/linux/x64/release/bundle
python3 tools/release.py package-linux --bundle apple-watch-companion/build/linux/x64/release/bundle --output target/watch-companion-linux-rc.tar.gz
```

`protocol-manifest.json` records source and jar hashes. Packaging audits the
actual bundle, rejects secret records, logs, compiler output and research classes,
and verifies every archived file, link, directory and executable mode against
the manifest before publishing the candidate. Archives normalize ownership and
timestamps (`SOURCE_DATE_EPOCH`, default 2020-01-01 UTC) for reproducible packaging
of the same built bundle. This does not promise reproducible compiler output.
Never package the workspace, firmware dumps,
device backups, graphs, or protocol logs.

Offline Java probes remain available in `:core:researchJar`; Android local
notification/diagnostic/reboot probes exist only in the debug source set.

## Android signing

The camera decoder's build step currently depends on an external pinned
`VisualPairing` firmware input and the private asset-generation tooling under
`research-tools`. Those inputs are absent from this source checkout. A clean
Android package build is therefore an open release gate until that asset
pipeline is made distributable or optional with an explicit unavailable
capability. Compiling Kotlin with `-x prepareOpticalAsset` checks source changes
only; it does not produce or validate a working camera-pairing release.

Ordinary `tools/build.py android` creates local debug-signed test artifacts.
Distribution builds require one external signing-properties file shared by both
apps, because Companion IPC is protected by their signing certificate:

```sh
export WATCH_RELEASE_SIGNING_PROPERTIES=/absolute/private/signing.properties
python3 tools/build.py android --distribution
```

That file contains `storeFile`, `storePassword`, `keyAlias`, `keyPassword`.
A distribution build verifies both final APK signatures, rejects the Android
debug certificate and requires matching signing certificates for IPC.
A relative `storeFile` is resolved against the properties file's directory.
Keep both files outside the project; do not copy them into candidate packages.
Changing the installed signing certificate requires a separate migration plan;
debug-signed test installs cannot be updated in place by a different key.

## Remaining release gates

- Linux automatic Wi-Fi credential access through NetworkManager remains open;
  its Wi-Fi capability is false. Most desktop application-service commands need
  wiring/acceptance; connection alone does not establish full feature support.
- macOS has discovery but no completed physical pairing/IDS activation path.
  Linux/Windows connection support does not provide desktop application services.
- Watch.app face rendering, previews and feature parity remain incomplete.
- Dedicated distribution signing, dependency notices and permissions to ship
  native firmware-derived assets must be resolved before public distribution.
- Android/desktop regression tests and builds are required per candidate, then
  real fresh pairing, same-pair restart, disconnect/reconnect and upgrade checks.
- CI and Windows/macOS package audit/signing remain separate open release gates.
- Serializer fixtures must stay synthetic. Never add device activation material,
  personal Bluetooth addresses, pairing keys or account credentials to Git.

Hardware history and limitations: [Linux Companion](LINUX_COMPANION_91.md)
and [feature coverage](WATCH_FEATURE_COVERAGE_PLAN.md).
