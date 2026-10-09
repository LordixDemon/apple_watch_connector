# Linux backend boundaries and deterministic packaging — stage 93

Date: 2026-10-09. Continues stage 92. Bridge 0.2.426/626, Companion 1.0.83+84
and Rust 0.1.1 remain unchanged; `rc93` identifies this test candidate.

## Code and build boundaries

Linux Rust composition is now three modules behind the unchanged `LinuxBackend`
and C ABI: `state` owns observed facts, `worker` owns bounded event input and
process cleanup, `backend` routes commands and owns the session. Failure and
explicit stop clear readiness immediately and reject late ready events. EOF,
invalid UTF-8, truncated or oversized output fail without exposing bytes.
Old worker epochs cannot change a new session.

STOP/EOF first allow orderly Java/HCI cleanup. A five-second stop deadline and
a bounded destructor kill/reap an unresponsive worker. Command-pipe and wait
failures cannot leave the UI claiming a live session. Pair metadata stays intact;
normal reconnection does not replay Setup/Albert/Buddy.

`gradle/portable-java.gradle` replaces duplicate Java settings in core and
protocol-runtime. Both use Java release 17, UTF-8 and JUnit, with strict Gradle
lockfiles for all resolvable configurations, including transitive dependencies.
`resolvePortableDependencies --write-locks` is the explicit maintenance path.
Versions were fixed at the already-resolved versions, without dependency upgrades.
Android AGP/SDK warnings remain a separate migration.

Linux auditing remains in `tools/release.py`; deterministic archive writing and
verification are in `tools/release_archive.py`. Root bundle aliases resolve to
the actual directory before archiving. Only contained relative file symlinks
are accepted. Schema 2 records files, links, directories and executable modes;
the installer and guide are hashed too. Ownership, permissions and timestamps
are normalized, gzip omits host filenames. `SOURCE_DATE_EPOCH` defaults to
2020-01-01 UTC. Every archive member is verified before atomic publication;
a failed verification retains the previous candidate. Reproducibility applies
to packaging an identical built bundle, not compiler output across hosts.

## Validation

- Locked Cargo workspace tests and all-target Clippy with warnings denied passed
  on Mac and Linux. Linux backend has 14 tests, including actual EOF-driven child
  exit, kill/reap deadlines, late callbacks, malformed input and epoch fencing.
  Formatting passed.
- Java: 1,197 core + 2 protocol-runtime tests passed; all portable configurations
  resolve offline using strict locks. Research jar and desktop dependencies built.
- Android Bridge release APK and release lint passed with the shared Java policy.
  No phone installation or pairing change was performed.
- Four standalone Java worker regressions passed on both hosts; no test acquires
  Bluetooth. Seven Python release/build tests passed on both hosts, including
  deterministic packaging through a root symlink after timestamp changes,
  archive tampering and preservation of the previous candidate on failure.
- Linux release app rebuilt on Mint 22.3 x86_64. Dart/UI sources were not changed;
  the stage-92 Flutter/Android Companion/macOS results remain prior evidence.

## Physical upgrade and artifact

Closed the old GUI, observed HCI Disconnection Complete and process exit before
replacing the library. New GUI resumed pair
`9cbad3f5-9749-5045-ba7b-03cc33fe62cc`, with transport READY and operational IDS
at T+20338ms. Its subsequent close also completed HCI cleanup and reaped the JVM.
Final launch resumed the same pair with operational IDS at T+21019ms; the GUI
remains the live session owner, without session/storage/output failure markers.
Root broker hash still matches the bundled broker; no privilege change required.
Native protocol evidence remains false, activation and matching owner confirmation
true; no synthetic health evidence, reset, fresh pair or face mutation.

Archive on both hosts:
`target/release93/watch-companion-linux-x86_64-1.0.83-rc93.tar.gz`.
Size: 205,922,878 bytes; 20,155 regular bundle files. Two full physical-bundle
packaging runs produced the same SHA-256:
`e36b920c06b6febd626380c461ecc1c312f82134349f1531c8437713895aaa6f`.
Previous rc92 remains available for rollback.
The copied archive was independently verified on the Mac, including all 20,171
bundle entries and package files. SHA-256 matches the laptop's archive.

## Generated changes and release scope

Ordinary code/docs edits and moves used apply_patch. Formatter snapshots isolate
this task's two formatting passes: +81/-20 and +24/-7 lines. New generated Gradle
locks: core +15/-0, protocol-runtime +15/-0. New archived release-manifest:
+121,014/-0 lines. Final AST graph: 24,354 nodes, 56,522 edges, 973 communities.
Against the pre-task clone: 10 graph/report/backup files +41,988/-35,007 lines;
19 cache files +19/-1. Existing zero-node JSON coverage warnings and five retained
out-of-corpus nodes remain graph limitations. No historical firmware/evidence/
backups were removed.

Public distribution gates remain as in [stage 92](REFACTOR_RELEASE_92.md): Linux
Wi-Fi credentials and application-service commands, macOS pairing/Windows backend,
face visual parity, distribution signing, notices and native-asset redistribution
permissions. This candidate completes the described refactor and packaging work;
it does not establish full Watch.app parity or public release readiness.

Installation/build/rollback: [release guide](../release/README.md).
