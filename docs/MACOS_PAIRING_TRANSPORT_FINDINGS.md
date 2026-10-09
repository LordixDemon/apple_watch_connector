# macOS pairing transport: physical result, 2026-10-06

Host: macOS 15.8.1 arm64, built-in Bluetooth. The owner reset the Watch and
confirmed it was waiting for a new pair. The observed FE25 advertisement decoded
to Watch7,5/watchOS 26.2.0. No configured address or model was used to select it.

## Physical experiment

The bounded `research-tools/macos_pairing_probe.m` loaded the production Rust
C ABI, discovered an actual setup Watch, registered only its own
`com.apple.terminusPairing` endpoint and connected the selected BLE peer.
Endpoint registration and the BLE callback succeeded. No pairing stream opened;
the attempt timed out after 25 seconds and released its endpoint and BLE link.
The probe did not submit a PIN, create a bond, activate, erase or reset the Watch.
These observations establish discovery and BLE, not a successful Mac pair.

The endpoint requested type 1, priority 2, RequiresEncryption=false, and
StayConnectedWhenIdle=true through CBScalablePipeManager. Its native stream
would carry uIKE directly; bluetoothd owns BT_CL and ERTM below that stream.
Sending raw ACL or adding ERTM framing to this stream would be incorrect.

At 22:07:35 local time, bluetoothd reported the registered endpoint as
**encryption required**, then logged:

```text
Skipping service com.apple.terminusPairing requiresEncryption=1 unpublishing=0
```

At 22:08:00, cleanup unregistered the owned endpoint. The Watch never sent a
control-SA response through this transport. A fresh Watch cannot establish this
bootstrap over a channel that requires an existing encrypted Bluetooth bond.

## Why the requested option was ignored

Disassembly of this host's `/usr/sbin/bluetoothd` identifies the relevant policy:

| Address in this OS image | Evidence |
| --- | --- |
| 0x1002a20b4–0x1002a20e8 | Client entitlement classification: `com.apple.bluetooth.internal` sets client+0x50 to 2; `com.apple.bluetooth.system` sets it to 1 |
| 0x1005acda0–0x1005acdb0 | Endpoint registration checks client+0x50; values other than 2 force the transport/encryption tuple to 0/1 |
| 0x1005acd30 | Endpoint type 2 additionally requires the networkrelayTransport entitlement; changing the type is not a substitute |
| 0x196ff3a98 in CoreBluetooth | Registration reads the RequiresEncryption boolean and forwards it correctly; the dictionary key is not the failure |

Addresses are evidence for this exact OS image, not portable call targets.
Inspect with the local `research-tools/objc.py` disassembler and cached ipsw
dyld tools. A focused read-only log query is:

```sh
/usr/bin/log show --last 3m --style compact --info --debug \
  --predicate 'process == "bluetoothd" AND (eventMessage CONTAINS "terminusPairing" OR eventMessage CONTAINS "Skipping service")'
```

The release app has normal sandbox/Bluetooth/network entitlements. It has no
Apple internal Bluetooth grant. Bluetooth user permission and endpoint
registration do not remove this daemon restriction. The production adapter now
checks its own OS-validated entitlement via SecTask before registering this
unencrypted endpoint. Missing authorization publishes
`MACOS_PAIRING_BOOTSTRAP_RESTRICTED`, disables pairing capability and rejects a
connection attempt with a specific error. It does not modify security policy,
system services, entitlements or controller state.

At 22:20:37, a separate endpoint-only probe was locally signed with the internal
entitlement to test whether ordinary ad-hoc signing could authorize it. macOS
terminated it before main (exit 137). AMFI logged restricted entitlements and a
fatal code-signature validation failure. No endpoint registration or Watch I/O
was possible from that process. This was a temporary binary, not the Companion
signature or a system-policy change. A locally signed entitlement declaration
is therefore insufficient on this host; it must not be added to the release
app's plist as a purported fix.
Re-signing the same temporary executable without that entitlement restored a
successful run: adapterState=5, endpointRegistered=true,
internalEntitlementPresent=false, streamBytesWritten=0. This control excludes a
generally broken probe executable as the cause of the launch rejection.

This rules out the current ScalablePipe bootstrap for an ordinary app on this
host. It does not prove every possible built-in Bluetooth transport impossible.
An alternative must demonstrate actual bidirectional access to the unencrypted
bootstrap, not merely exported selectors, queue receipts or a BLE connection.
Read-only IORegistry inspection still finds an IOBluetoothHCIController and the
daemon's IOBluetoothHCIUserClient. Their presence alone does not establish a
supported user-client contract or authorize raw ACL access. No guessed IOKit
client type/external-method selector was sent to them in this experiment.

## Release verification

Companion 1.0.17+18 was built and opened on the Mac with embedded core0.1.1.
Deep/strict code-sign verification passed. A read-only load of the actual
packaged library returned API1, discovery/BLE capabilities true, pairing/IDS
false, watchReady=false and MACOS_PAIRING_BOOTSTRAP_RESTRICTED. Its signature
retains normal sandbox/Bluetooth/network entitlements; the rejected diagnostic
entitlement was not added to the app. The release bundle is 47.7MB.

Validation: 52 Rust workspace tests in total (including the subsequently added
cancel/failure callback test), Clippy with warnings denied and cargo fmt check
passed; 73 Flutter tests and analyzer with no issues passed. The native stream
test also passed. The production dependency tree does not enable ML-KEM hazmat;
that feature is used only for deterministic test responders. The revised UI has
widget-test validation; no new physical screen inspection was performed.

## Implemented protocol and remaining work

`watch-pairing` is a platform-independent Rust engine for the control SA and
salted-PIN SA: X448, ML-KEM-1024, AES-GCM, SHA-512, mandatory PPK, Apple legacy
scalar derivation and SPAKE2+ confirmation/AUTH. Deterministic synthetic Java
Bouncy Castle vectors validate the cryptographic primitives, wire profile,
fragment handling and control exchange. No real PIN or pair key is in those
fixtures. The PIN exchange is implemented but has not completed physically on
Mac. PBKDF2 runs outside the UI/runtime mutex; codes and keys are zeroized.

The C ABI connects private native stream events to that engine with peer/epoch
ownership, bounded queues and stage deadlines. Raw pairing bytes and key
material do not enter snapshots or Flutter. A verified PIN would stop at
PIN_AUTHENTICATED/BOND_REQUIRED, not claim a durable pair or Watch readiness.

Still required for a complete Mac setup:

1. A working authorized bootstrap transport on the built-in controller.
2. Physical control-SA/PIN exchange and verified OOB-to-system-bond handoff.
3. Encrypted durable pair storage, operational normal link and IDS.
4. Activation, setup, automatic Wi-Fi transfer and completed-sync observations.
5. Owner-confirmed Watch face and reconnect after restarting Companion.

The current adapter is tested offline for stream ownership, partial writes,
backpressure, cancellation and late callbacks with
`research-tools/macos_pairing_stream_test.m`. Successful offline tests do not
meet these physical acceptance gates.
