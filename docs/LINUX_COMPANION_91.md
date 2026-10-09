# Linux Companion: real pairing and activation

Validated on 9 October 2026 on Linux Mint 22.3 / Ubuntu noble, kernel
7.0.0-34-generic, x86_64, Intel AX211 built-in Bluetooth.
Passwords and PINs are not retained in this report.

## Observed result

The real Rust C ABI started the shared protocol worker and exclusive Linux HCI
broker. Discovery found Watch7,5, watchOS 26.2. Fresh pairing completed IKE,
authenticated PIN, SMP encryption, durable Class D/C registration, IDS control,
device information, data lanes and NanoRegistry configuration/IsPaired.

Albert SESSION and ACTIVATION returned HTTP 200. The first ACTIVATION delivery
was rewritten to binary plist and the Watch rejected its signature. The working
Android configuration was `raw`; Linux had inherited an old experimental binary
default and an Android-only configuration path. Production now preserves signed
Albert response bytes by default, with explicit experimental overrides resolved
under the platform state directory. A same-pair RetryActivation obtained a new
SESSION and ACTIVATION exchange, then authenticated Watch activation success.
`ACTIVATION_CONFIRMED` was persisted. PrepareInitialSync was acknowledged and
sync completion delivered. The user explicitly confirmed a working watch face.

Owner confirmation is stored separately from native setup evidence. The durable record is not
artificially promoted to OPERATIONAL_HEALTH_CONFIRMED. The Flutter application
restored this same activated pair and logged `BRIDGE_TRANSPORT_V1:READY` followed
by `OPERATIONAL IDS READY: same activated pair; Setup/Albert/Buddy replay disabled`.
The first application reconnect reached this barrier in 8.3 seconds.
The final installed build reconnected in 8.4 seconds and remained live. A second
worker was rejected by the session file lock before controller acquisition.

## Architecture and installation

- Flutter uses the same phone interface and code/PIN setup on desktop.
- `watch-core-ffi` selects the Linux composition through cfg. Rust owns process
  lifecycle, C ABI, observed state, stale-discovery rejection and HCI lease.
- `watch-transport-linux` supplies the bounded H4 broker. Linux HCI USER ownership
  is required because the Apple fixed L2CAP channel is not exposed through the
  ordinary BlueZ application socket interface. BlueZ releases the selected
  adapter before each lease; the Bluetooth service remains running.
- `linux-watch-host` reuses the production Java protocol engine with a Linux
  controller and encrypted storage. This is not a complete Rust protocol rewrite.
- Shared HAL code depends on `BluetoothController`; Android Binder access lives
  in `AndroidBluetoothController`. Portable paths, Base64 and deadlines replace
  Android-only APIs without changing the Android HAL entry point.

Build prerequisites: Rust 1.98.1, Flutter 3.47.4 / Dart 3.13.3, JDK 17+, clang,
GTK3 development files, BlueZ, libbluetooth, libusb, liblzma and libstdc++ headers.
Use Gradle's resolved dependencies, not independently selected crypto versions:

```sh
cd apple-watch-bridge
./gradlew :core:desktopRuntime
cd ..
python3 tools/build.py linux
python3 tools/install_linux.py apple-watch-companion/build/linux/x64/release/bundle
```

The bundle contains Flutter, `lib/libwatch_core_ffi.so`, shared protocol JARs and
the broker. The installer uses sudo only to install the root-owned broker under
`/usr/local/libexec/watch-companion/`, group-restricted mode 0750 and capabilities
`cap_net_admin,cap_net_raw=ep`. Java and Flutter run as the desktop user. It creates
`~/.local/share/applications/watch-companion.desktop`.

Only one process can own the Watch session. Close Companion before replacing a
loaded library or running the ABI probe. While the selected adapter is leased,
normal BlueZ peripherals on that adapter cannot use it concurrently. Other
adapters are unaffected. `WATCH_HCI_INDEX` selects the controller; the tested
adapter is hci0. No Watch identity, PIN, SSID or password is hardcoded.

## Persistence and completion

State root is `$XDG_STATE_HOME/watch-companion`, or
`~/.local/state/watch-companion`. Root and identity directory are 0700; the AES
storage key and sealed files are 0600. Records use AES-GCM with filename AAD,
atomic replacement, fsync and decrypted readback before protocol ACK. This is
filesystem-protected storage, not a hardware-backed Android Keystore.

The public pair summary enables restore without exporting bond or NetworkRelay
secrets. Actual operational authorization still validates the encrypted activated
record and its pairing generation UUID. Native health verification and explicit
same-pair owner confirmation are separate facts. Neither an HTTP response nor an
IDS ACK alone produces an operational watch. Active readiness requires the live
operational IDS barrier and is revoked on transport DOWN. Reconnecting a confirmed
pair is projected as connection work, not unfinished setup.

The application automatically resumes an eligible stored pair once on startup.
Explicit disconnect does not restart it. Owner confirmation in the shared UI is
enabled only by the Linux capability and only for an activated stored pair. The
worker validates the supplied generation before persisting confirmation, stops
setup and reconnects in operational mode without replaying activation/Buddy.

Diagnostic logs are private and rotate at 16 MiB, retaining one previous file.
Secret IPC is intercepted before Rust/UI logs; activation body previews and
application payload envelopes are omitted from the UI journal. The ABI probe
disables terminal echo so PIN input is not echoed.

## Remaining limitations

The NetworkManager adapter identifies the active validated broadcast WPA2
connection and can build the existing Wi-Fi archive when credentials are readable.
On this laptop NetworkManager returns an empty PSK to the ordinary process;
administrator access confirms a PSK exists. Automatic Wi-Fi transfer therefore
did not occur. No network/password fallback or false delivery claim was added.
A permission-aware NetworkManager credentials flow is still required.

Linux operational services beyond connection/setup are not yet fully exposed
through the Rust/Flutter command contract. Shared Java protocol support does not
imply desktop parity for watch faces, health, notifications or other Android
services. `wifi` capability remains false until a supported operational command
and permission flow exist. Long-running reconnect/soak coverage remains open.

## Verification and generated-change audit

- Linux Cargo: eight tests (five observed-state, two H4 boundary/coalescing, one
  C ABI ownership/invalid input); Clippy with all targets and -D warnings passed.
- Java core tests and Android HAL compilation passed. New default/raw activation
  regression proves byte preservation and explicit portable override handling.
- Linux store regression passed restart readback, owner permissions, tampering,
  filename AAD, path traversal, symlink rejection and temporary-file cleanup.
- Ten shared desktop Flutter tests passed; analyzer reported no issues.
- Release Linux bundle built and real GUI/operational reconnect verified.

Ordinary source edits used apply_patch. Captured formatter-only windows:

| Window | Files | Added | Deleted |
| --- | --- | ---: | ---: |
| First captured Rust pass | Linux state | 11 | 11 |
| Second Rust pass | Linux state / HCI | 52 | 14 |
| Rust + Dart pass | FFI / Linux state / desktop transport / tests | 108 | 44 |
| Final Rust + Dart pass | Linux state / desktop transport / tests | 26 | 12 |

These are individual operation deltas, not aggregate source-edit counts. Generated
desktop launcher: +8/-0 lines. Build binaries are generated outputs. Graph refresh
has a separate before snapshot at `/tmp/watch-linux91-graph-before`.

AST graph refresh completed with 24,228 nodes and 56,226 edges. Generated refresh
deltas from that before snapshot: graph/report/manifest/labels (six files)
+472,981/-350,296 lines; curated backup copies (five files) +962,578/-0;
AST/stat caches (406 files) +406/-1. These count the actual generated operation,
including copies and re-indexing the previously stale graph, not protocol source
authorship. No LLM labeling or semantic extraction ran.

Only application/core/build sources and runtime assets are needed on the laptop.
The Mac's 42 GiB firmware archive and large raw research captures are not part
of the deployed executable bundle; keep their original copies for reverse
engineering. The report and current SUMMARY accompany the Linux installation.
