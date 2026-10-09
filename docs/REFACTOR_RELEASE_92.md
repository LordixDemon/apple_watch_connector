# Shared runtime refactor and release candidate — stage 92

Date: 2026-10-09. Source versions remain Bridge 0.2.426/626,
Companion 1.0.83+84, Rust workspace 0.1.1. Candidate suffix `rc92` identifies
this build; these changes do not claim complete feature parity.

## Implemented boundaries

| Module | Ownership |
| --- | --- |
| Java core | Portable wire formats, crypto, storage models and coordinators |
| Java protocol-runtime | Shared serial session, controller interface, command input and platform hooks |
| Android HAL | Binder/VINTF, root entry and Android face-export ownership/SELinux adapter |
| Linux worker | HCI broker adapter, installation persistence, private IPC and session orchestration |
| Flutter Companion / Rust ABI | Existing common UI and platform backend contract |

Eight portable classes moved out of Android HAL into `protocol-runtime`.
HAL depends on that Java module; Linux compiles its source root directly,
without Android stubs or a manual list of selected HAL classes. Package names,
wire commands, ordered handshake and setup completion policy stay compatible.
The large serial protocol state machine remains one owner; this is not a full
Java-to-Rust protocol rewrite.

Four offline Java CLI probes moved into `core/src/research`; `researchJar`
compiles them on demand. Six local Android probe receivers and their manifest
declarations moved into `app/src/debug`. Runtime notification mirroring keeps
its diagnostic-channel policy without depending on a debug-only class.

LinuxProtocolHost now has 122 lines. LinuxIdentityStore owns generation,
authenticated readback and public/private key matching. New IDS installation
material is one atomic sealed record. Complete legacy identities migrate without
changing any public key or installation ID; partial/mismatched records fail
before HCI acquisition and retain existing material. Legacy ciphertext remains
available for rollback.

LinuxProtocolOutput owns bounded journals, private-envelope persistence ACKs,
body-preview masking and stdout restoration, including bootstrap failure.
Unexpected private output stops the worker. Controller cleanup now closes/reaps
its broker even if stdin close or initialization fails. Session ownership lock
still precedes first storage-key creation.

## Build and package cleanup

The Linux compiler uses a fresh temporary directory and stages the complete
runtime. Failed compilation/tests retain the previous runtime. Removed classes
and dependency jars cannot survive replacement. `desktopRuntime` uses Gradle
Sync. Java archives use a fixed entry timestamp; manifests record source/jar
hashes. CMake installs only the FFI library and protocol runtime, excluding old
compiler directories.

`tools/release.py` audits Linux ELF architecture, AOT/Flutter prerequisites,
protocol inventory/hashes, required worker classes, symlink containment and
non-runtime artifacts. The candidate contains only the app bundle, installer,
release guide and full file-hash manifest. No workspace/firmware dumps, pairing
records, logs or compiler output are included.

Both Android apps apply `release/android-signing.gradle`. Local artifacts retain
debug signing; distribution mode requires external shared signing properties.
Final distribution APK checks reject Android debug certificates and different
Bridge/Companion certificates. No distribution key was created or installed.

## Validation

- Java: 1,197 core tests + 2 platform-export-hook tests; research jar compiled.
- Android Bridge: debug/release APKs and app/HAL release lint passed. Actual
  release manifest and compiled app/core jars contain no moved probes.
- Companion: analyzer passed, all 423 Flutter tests passed, Android release APK
  and macOS release app built; macOS signature verification passed.
- Cargo: full locked workspace tests and all-target Clippy with warnings denied
  passed on macOS and Linux; formatting passed. Linux includes the real broker
  and Linux ABI tests.
- Worker: four standalone regressions passed on both hosts: encrypted storage,
  identity restart/migration/mismatch, private output/journal lifetime and actual
  child reaping after a synthetic pipe-close failure. No test opens Bluetooth.
- Build/release: five Python regressions passed; distribution mode with missing
  signing configuration failed as intended. Auditing the existing debug-signed
  APK as distribution failed as intended. Real distribution signing awaits a key.

## Physical Linux upgrade check

Mint 22.3 x86_64 laptop at 192.0.2.23. Closed Companion, observed HCI disconnect
and JVM exit, rebuilt, then launched the final bundle in the desktop session.
The same activated pair resumed, with `OPERATIONAL PAIR VERIFIED`, transport
READY and `OPERATIONAL IDS READY` at T+21042ms. No Setup/Albert/Buddy replay.
Migration had already preserved the same pair/public IDS bundle during the
preceding restart. Public metadata still reports activation true, native protocol
evidence false, matching owner confirmation true; no synthetic health evidence.
Final checks show zero session/storage failures or link-down events. Root broker
hash matches the bundled broker; no privilege change or reinstallation was needed.
Companion remains running as the session owner. No fresh pairing/reset, phone APK
installation or face mutation was performed during this task.

## Candidate and audits

Archive: `target/release92/watch-companion-linux-x86_64-1.0.83-rc92.tar.gz`
on both hosts. Size 205,010,704 bytes. Bundle inventory: 20,155 files. Every
archived bundle file was independently hashed against its manifest after copying
the archive to the Mac.

SHA256: `15789fcdfbaa2af10342046e2b993175b9d958c45591cdafc501bd2d1b0511f8`.

Generated release-manifest text: +20,163/-0 lines inside the new archive.
AST graph update (no LLM) produced 24,296 nodes / 56,400 edges. Task-only audit
against the pre-update clone: 10 graph/report/backup files +35,764/-32,709 lines;
47 cache files +47/-1. The existing zero-node JSON warnings and five retained
out-of-corpus nodes are graph coverage limits, not compiler failures.
Ordinary source/doc edits and moves used apply_patch. Firmware, historical
evidence and device backups were preserved outside release artifacts.

## Public distribution gates

The candidate is for testing. Linux NetworkManager credential access and most
desktop application-service commands remain open; Wi-Fi capability stays false.
macOS physical pairing/IDS and Windows backend remain incomplete. Native face
visual parity remains unfinished. Dedicated signing, dependency notices and
redistribution permissions for firmware-derived assets remain unresolved.
Existing Gradle/AGP/Kotlin compatibility warnings also need a separately
validated toolchain migration; no dependency upgrade was mixed into this refactor.
Fresh-pair/upgrade acceptance on every supported target is required per public
candidate; this task physically verified same-pair Linux reconnect.

Commands, installation, rollback and signing setup: [release guide](../release/README.md).
