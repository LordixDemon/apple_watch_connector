# Linux Bluetooth adapter selection — stage 94

Date: 2026-10-09. Companion 1.0.84+85. Bridge source remains 0.2.426/626;
Rust workspace remains 0.1.1.

## User flow

**All Watches → Bluetooth Adapter** lists BlueZ host controllers with their
alias, current HCI name and Bluetooth address. Tap a row to save the choice;
a check mark identifies the selected adapter. Refresh Adapters reloads the list.
The idle list also updates automatically, without blocking Flutter on D-Bus.
Switching/refreshing requires disconnecting the Watch first. Connection controls
stay disabled when a choice is required or saved hardware is missing.

Exactly one available controller is selected automatically, regardless of its
HCI number. Multiple controllers require an explicit choice. A saved controller
is resolved by its address on future connections; reindexing or moving a USB
adapter does not intentionally change the selected hardware. If that address
is unavailable, no other controller is substituted. Selecting another controller
does not erase Watch keys or repeat activation.

## Ownership and persistence

`crates/watch-transport-linux/src/adapters.rs` owns bounded asynchronous BlueZ
GetManagedObjects discovery, parsing and a schema-1 address preference.
`bluetooth-adapter.json` lives in the application state directory, outside the
bundle and Watch identity records. Writes use a private temporary file, fsync
and atomic rename; new file/directory permissions are 0600/0700. Corrupt or
unwritable preferences require explicit reselection rather than silent fallback.
Legacy `WATCH_HCI_INDEX` can seed the first choice; a saved UI choice wins later.

The Rust snapshot/C ABI gained adapter inventory, selection/loading/error status
and busy fields; commands `selectAdapter` and `refreshAdapters` are additive.
The shared Dart model/transport/provider exposes them through platform capability,
so Android/macOS retain the same connection screens without Linux controls.
UI strings are in the English ARB and generated localization classes.

When the HCI USER lease starts, in-flight inventory replies are fenced by
generation. BlueZ omits a leased controller; that must not invalidate the active
choice or hide pairing/PIN controls. After stop, inventory is refreshed again.
Before any lease, LinuxBluetoothController verifies the current BlueZ address
against the selected stable identity. A stale HCI index fails before powering
down or acquiring a different controller. Java no longer defaults to hci0.

## Validation

- 427 Flutter tests passed, including four new desktop selection tests: stable
  command identity, loading/auto-resume, missing hardware, busy guards and an
  interactive picker at 360px width. Analyzer and formatting passed.
- 19 Linux backend tests passed; five new adapter tests cover actual index
  changes, missing hardware without fallback, single hci7, malformed preferences,
  BlueZ parsing and late inventory during a lease. Locked Cargo workspace tests
  and Clippy passed on Linux; Mac focused tests and workspace Clippy passed.
- Four Java worker regressions passed on both hosts. Controller regression now
  rejects mismatched/truncated/extra adapter-address reports. Seven Python
  build/release tests passed; packaging rejects the new host preference file.
- Linux release app rebuilt on the Mint laptop. Actual ABI discovered the real
  controller, saved/reselected it, rejected unknown IDs, refreshed the list,
  resumed operational IDS with the same activated pair and rejected changes
  during the session. STOP reaped the worker and cleanly disconnected HCI.
  Pair-public metadata remained unchanged. No reset, face writes or fresh pair.
  Final GUI launch restored the saved controller and became operational at
  T+20134ms. Companion stays running as session owner. Preference mode is 0600;
  installed root broker still matches the bundled binary, without privilege changes.

The laptop has one physical adapter. Multiple-adapter selection, missing hardware
and reindexing were tested with fixtures; a second physical USB controller and
another laptop still need hardware acceptance. Android/macOS builds were not
repeated for this Linux capability; shared UI regressions passed.

## Generated changes

Ordinary source/docs edits used apply_patch. Formatter snapshots isolate this
task: Rust +254/-62 then +23/-5 lines; Dart +364/-202 lines. Localization generator:
two files +114/-0 lines. AST graph updated: 24,443 nodes / 56,586 edges /
980 communities. Existing empty-JSON and retained-node coverage warnings remain.
Against the pre-task graph clone: 10 graph/report/backup files +15,006/-13,296
lines; 23 cache files +23/-1. New archived release-manifest: +121,014/-0 lines.

## Candidate

`target/release94/watch-companion-linux-x86_64-1.0.84-rc94.tar.gz` contains the new
Linux app and audited runtime, with no host adapter preference or Watch identity.
205,957,433 bytes, 20,155 regular bundle files / 20,171 bundle entries. Copied
archive independently verified on the Mac. SHA-256:
`df1fb976c15d6007819e80c167c6e868fc7cc1b682f28d4d1c59c7268f4b1c17`.
Previous candidates remain available. Public distribution gates from stage 93
are unchanged; this adds Linux controller selection, not full feature parity.

Dependencies/install/upgrade instructions: [release guide](../release/README.md).
