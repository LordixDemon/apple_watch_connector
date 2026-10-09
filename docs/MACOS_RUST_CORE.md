# macOS Companion and portable Rust core

Status, 2026-10-06: macOS now uses the phone Companion's Cupertino application,
navigation, All Watches screen, connection provider and PIN form. The separate
desktop dashboard/controller were removed. macOS policy permits code pairing
only; it exposes neither camera pairing nor Android bond tools. The app loads
the Rust library, requests Bluetooth access on Find a Watch, and discovers
real devices through the built-in
adapter. Physical discovery and BLE connection against the reset Watch are now
confirmed. The portable control-SA/salted-PIN engine and native ScalablePipe
adapter are implemented, but the live pairing stream did not open: this host's
bluetoothd forces encryption for clients without Apple's internal Bluetooth
entitlement. The normal app reports this restriction and pairing unavailable.
Full Mac pairing, bonding, activation and operational IDS are still unfinished.
The owner has reset the Watch and confirmed it is waiting for a new pair; this
Mac experiment did not reset it or install changes on Android. See the
[transport evidence and acceptance gates](MACOS_PAIRING_TRANSPORT_FINDINGS.md).

## Modules and builds

| Module | Responsibility |
| --- | --- |
| `watch-protocol` | Pure wire codecs, FE25 setup metadata, encrypted BT_CL normal-link handoff |
| `watch-pairing` | Control and salted-PIN uIKE SAs, cryptography, replay/fragment bounds; no OS I/O |
| `watch-core` | Observed device inventory, session epochs and state transitions, transport interface |
| `watch-transport-macos` | Objective-C CoreBluetooth ownership and event delivery; no pairing decisions |
| `watch-core-ffi` | Versioned C ABI, bounded JSON requests and snapshots, Rust allocation ownership |
| Flutter `lib/services/rust/` | Rust FFI and projection into the existing BridgeTransport contract |
| Flutter `lib/services/companion_backend.dart` | Selects Android Binder or macOS Rust at composition time |
| Flutter `lib/services/companion_platform.dart` | Platform input/tool policy, separate from native runtime capabilities |
| Flutter `lib/app/`, connection screens/providers | One phone/desktop interface and shared resource lifetime |
| Flutter `lib/main.dart`, `lib/main_desktop.dart` | One application bootstrap; desktop entry delegates to it |

The root research package re-exports the moved Rust codecs for compatibility
with the existing OnePlus probe. No second copy of those codecs is maintained.
Linux and Windows can reuse the protocol and session modules, but neither has
a Bluetooth backend yet. The macOS adapter uses compile-time platform gates;
an unsupported platform reports unavailable capabilities.

Build from the repository root:

```sh
python3 tools/build.py core
python3 tools/build.py macos
python3 tools/build.py android
```

The macOS build selects `main_desktop.dart`, builds `watch-core-ffi` for Xcode's
active architecture, embeds `libwatch_core_ffi.dylib` in `Contents/Frameworks`,
sets its `@rpath` install name and signs it. The current tested target is
arm64/macOS 15.8.1, Rust 1.98.1, Flutter 3.47.4/Dart 3.13.3, Xcode 26.2.
Flutter's generated platform project now requires macOS 12.0 or later.
The build accepts an Intel architecture when its Rust target is installed;
Intel has not been tested. The Android build command does not install APKs.
The macOS build command also verifies the complete app's code signature.

Output:
`apple-watch-companion/build/macos/Build/Products/Release/apple_watch_companion.app`.

## State and ownership

The C ABI exposes `snapshot`, `scan`, `connect`, `pair` (code mode), `submitPin`
and `stop`; its API version is 1.
The returned UTF-8 JSON allocation belongs to Rust and must be released once
with `aw_core_free`. Dart copies it and releases both input and output in
`finally`. Bluetooth objects and operations stay on the native main queue.
Snapshots carry `coreVersion` independently from the Flutter application
version. The embedded dylib is declared as an Xcode build-phase output so a
Rust-only update also invalidates the app's enclosing code signature.

The session follows:

```text
IDLE -> DISCOVERING -> CONNECTING -> BLUETOOTH_LINK
                              \-> FAILED
open pairing stream -> PAIRING_SECURITY -> PIN_REQUIRED -> PIN_DERIVATION
                    -> PIN_AUTHENTICATION -> PIN_AUTHENTICATED (bond pending)
stop selected link -> DISCONNECTING -> IDLE
permission loss / powered off -> FAILED
```

`QUEUED` only acknowledges a request. `BLUETOOTH_LINK` requires the native
connection callback for the selected device and current epoch. It does not
mean Watch pairing, encryption, IDS or setup has completed. `watchReady` remains
false. Pairing capability additionally requires an OS-authorized plaintext
bootstrap; IDS remains unavailable. PIN authentication does not establish a
durable Bluetooth bond or complete setup. Old callbacks and a
connection callback arriving after cancellation cannot restore a connected UI.
Native connection attempts time out after 20 seconds. Closing the Flutter
scope disposes the owned transport and stops scan/link ownership exactly once.
The adapter polls every 250 ms while observed, publishes only changed public
state, rejects incompatible ABI versions, and refreshes native discovery before
accepting selection. Errors remain visible in the common diagnostics. There is
no fallback to Android platform channels if desktop loading fails.

Device identifiers are observed CoreBluetooth UUIDs, never configured Watch
addresses. The inventory is bounded and stale advertisements expire; the
selected device's metadata remains until disconnection. Apple manufacturer
data alone never makes a device selectable in All Watches. Product
type and watchOS are decoded only from valid FE25 setup metadata, including
the same physical Watch7,5/26.2 fixture used by Java. This requires the Watch to
advertise setup metadata; an already paired Watch may not do so.

## Controller access: actual findings

The public [CoreBluetooth L2CAP API](https://developer.apple.com/documentation/corebluetooth/cbperipheral/openl2capchannel(_:))
opens a PSM. The existing bridge uses BT_CL signaling on fixed CID `0x003a`
before negotiating dynamic pairing/normal pipes. A public PSM API alone does
not establish access to that fixed channel.

The [original InternalBlue macOS adapter](https://github.com/seemoo-lab/internalblue/tree/master/macos/IOBluetoothExtended)
provided the historical IOBluetooth request ABI examined by the read-only
probe in `research-tools/macos_bluetooth_hci_probe.m`. On this actual macOS
15.8.1 arm64 host these exported functions are compatibility stubs:

```text
BluetoothHCIRequestCreate
BluetoothHCIRequestDelete
BluetoothHCISendRawCommand
BluetoothHCISendRawACLData

complete function body: 00 00 80 52 c0 03 5f d6
                       mov w0, #0; ret
```

The initial Read Local Version call returned 0, but there were no notifications.
Disassembly corrected the interpretation: no controller send was proven.
The probe now detects this stub and skips it, explicitly reporting
`controllerCommandVerified=false`. It sends no ACL, reset, power changes,
pairing commands or vendor commands.

The current host's CoreBluetooth `-[CBManager sendRawCommand:data:completionHandler:]`
is also `mov w0, #0; ret`, at cache address `0x197034618` in this OS image.
CoreBluetooth does contain `kCBConnectOptionFixedChannels`, private packet
L2CAP APIs, and data callbacks. Its `CBCentralManager sendData:toPeripheral:`
uses daemon message `0x90`, whose daemon label is
`CBMsgIdSendDataToObjectDiscoveryPeripheral`; it must not be assumed to send
arbitrary fixed-channel ACL data. The daemon contains native BT_CL/terminus
implementation. These are research candidates, not enabled capabilities.
Further disassembly resolves `openPacketL2CAPChannel:withIncomingMTU:options:`
at `0x197002f94`: it adds packet-interface/incoming-payload options and delegates
to the PSM opening method at `0x197003054`, which sends daemon message `0x1d`.
`CBL2CAPChannel sendData:withCompletion:` at `0x197010198` sends message `0x1f`
for an existing channel. This provides a concrete packet I/O candidate, but
does not prove that BT_CL fixed CID `0x003a` can be opened as a PSM. No guessed
PSM or private selector is invoked by the production adapter.
Nothing here proves that built-in Bluetooth is impossible; it rules out the
old raw HCI exports on this particular OS.

Reproduce the read-only check:

```sh
clang -fobjc-arc -Wall -Wextra -framework Foundation -framework IOBluetooth \
  research-tools/macos_bluetooth_hci_probe.m -o /tmp/macos_bluetooth_hci_probe
/tmp/macos_bluetooth_hci_probe
```

## Remaining implementation and acceptance gates

1. Resolve an authorized unencrypted bootstrap route on the built-in controller.
   ScalablePipe registration and BLE succeed, but ordinary clients' endpoints
   are forced to require encryption. Actual receive/transmit remains unproven.
2. Connect an operational packet backend to the shared BT_CL code. The Rust normal-link
   handoff already matches Java's exact SERVICE_ADDED/COMMON_SERVICES/CREATE/
   ACCEPT sequence, requires observed encryption, rejects wrong services/CIDs,
   and forbids duplicate registration. It is not yet connected to native macOS
   packet I/O.
3. Complete OOB bond handoff, durable encrypted pair storage, normal link and
   IDS transport/session. The portable uIKE control/PIN cryptography is now
   implemented and tested; its physical PIN exchange remains unproven.
   ScalablePipe owns ERTM, so no second ERTM layer belongs in that adapter.
4. Physically verify the wired code challenge/PIN flow, then implement
   activation/setup/Wi-Fi integration. Mac camera pairing is excluded by the
   owner's explicit requirement. Native stream bytes and secrets stay in Rust.
5. Physically prove a complete new Mac pair, activation, sync, visible Watch
   face and durable reconnect. The owner has already reset the Watch and
   authorized a new Mac pair; the bootstrap backend must work first.

Current validation: Rust workspace tests and Clippy, Flutter tests and analyzer,
release app build, embedded-library linkage and deep code-sign verification;
owner-confirmed real discovery. Full physical Watch pairing/IDS remains an open
acceptance gate.
The preceding shared UI release app was 47.3MB and passed deep/strict signature verification.
The Android Companion APK also builds (56.1MB); it was not installed. Both use
the then-current version 1.0.16+17. A read-only load of that embedded dylib
returns API1/core0.1.0 with discovery/BLE true and pairing/IDS false. The revised
Mac UI has widget-test validation, not a new physical screen inspection.

Preceding shared UI validation: 42 Rust tests and 72 Flutter tests, analyzer/Clippy zero
issues. The read-only probe compiled without compiler warnings. Graphify's
code graph was updated; some generated/configuration files have no AST nodes.
The shared macOS UI tests cover 1040×760 and 740×560 windows, actual discovery
selection, stale tokens, code input clearing, camera exclusion, unavailable
backends, ownership cleanup and the distinction between BLE and a ready pair.

## Generated change audit

The subsequent core0.1.1/control-PIN task has its own
[generated and formatting audit](MACOS_PAIRING_CHANGE_AUDIT.md). Its snapshot
counts exclude the older windows below. Current release evidence and remaining
physical gates are in [the transport report](MACOS_PAIRING_TRANSPORT_FINDINGS.md).

Ordinary source edits used `apply_patch`; generated/formatting windows were
compared against task snapshots. Cargo lock generation adds 126 lines and
removes 0; the direct FFI dependency changes the Flutter lockfile by +1/-1.
Generated localization changes are +108/-0 (`app_localizations.dart`) and
+59/-0 (`app_localizations_en.dart`). Final new-file formatting changes are
+36/-9 for `normal_link.rs` and +84/-48 for `desktop_connection_test.dart`.
Earlier snapshotted Rust/Dart formatting windows also include subsequent
edits from this task; their per-file counts are recorded in the tool output.
The first Flutter SDK/SPM migration of the macOS project was not snapshotted
before that initial build, so its exact line counts are not reconstructed.
The platform files were captured before the final builds; the only subsequent
project edit is the explicit Rust dylib build output added with `apply_patch`.
The captured final graph update changed `graph.json` by +7089/-6032,
`graph.html` by +4/-4 and `GRAPH_REPORT.md` by +552/-536. The final build
generated no further Podfile or Flutter lockfile changes. Loading the actual
embedded release dylib independently returned API 1/core 0.1.0 and the expected
macOS capability snapshot; strict signature verification also passed after a
Rust-only rebuild.

The subsequent shared UI task has its own before-tool snapshots in
`/tmp/watch-shared-ui-before-tools` and `/tmp/watch-shared-ui-before-graph`;
none of the preceding task's diffs are included. Localization regeneration
changes `app_localizations.dart` by +25/-103 and `app_localizations_en.dart`
by +15/-56, removing retired dashboard text and adding common connection text.
The captured Rust formatting window changes `session.rs` by +10/-5. Dart
formatting and subsequent ordinary source corrections were compared per file
and reported in tool output. Cargo/Flutter lockfiles, the Xcode project and
Podfile are unchanged across this task's captured build window. The final
top-level graph changes are +10323/-10045 (`graph.json`), +4/-4 (`graph.html`),
+604/-588 (`GRAPH_REPORT.md`) and +58/-53 (`manifest.json`). Graphify's dated
backup changes are also compared with the same task snapshot in tool output.
