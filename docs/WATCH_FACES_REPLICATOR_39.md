# Native Replicator transport and codec verification

This milestone fixes production OPACK decoding and adds a verified binary
NetworkMessenger header codec. It does **not** deliver configured snapshots to
the Companion UI. The phone remains Companion1.0.36+37 / Bridge0.2.402.
No Watch command, read request, install, setup, reboot or pairing operation was
performed during this work. The original full Watch face goal remains active.

## Primary evidence and correction to research38

The exact Watch7,5 / watchOS26.2 / 23S303 dyld cache already exists at
`artifacts/watchos-26.2-ultra2-dyld/23S303__Watch7,5/System/Library/Caches/com.apple.dyld/dyld_shared_cache_arm64e`.
Research38's inability to inspect the standalone cached framework was a tooling
limitation, not absence of the full firmware cache. Native ReplicatorCore and
ReplicatorEngine were extracted into `/tmp/watch-snapshot-replicator-39/frameworks`.
The native OTA replicatord was also extracted; its main delegates to
ReplicatorCore.Daemon.start. Full-cache Swift metadata parsing succeeds.

Private metadata captures in the same temporary directory are
ReplicatorEngine-swift.txt, ReplicatorCore-swift.txt and NanoTimeKit-swift.txt.
These establish field types and conformances. They do not establish application
publication direction or the ordering of a complete live session.

## Two distinct transports

ReplicatorCore uses IDS service `com.apple.private.alloy.replicator`. In native
IDSServiceWrapper.incomingMessage, the Swift body at 0x1aefce850 extracts
NSDictionary key `message` as NSData (0x1aefceb94–0x1aefcec40), then invokes the
native OPACK decoder. IDSZoneAdvertiser carries zone-version advertisements.

Records and associated files have a separate NetworkMessenger path, with
connections/listener, response handlers, partial messages, extended attributes,
and compressed files. Native Core strings include
`com.apple.ApplicationService.replicatord.terminus` and connection type
standard/terminus. This is not evidence that bulk images should be sent directly
as ordinary IDS advertisement bodies.

The inspected OTA BOM contains the NanoTimeKit client descriptor from research38
but no matching Replicator IDS ServiceDefinitions plist. No guessed protection
class or priority was added to IdsApplicationRoute. A bounded read of the
phone's current event journal found no Replicator topic; this does not establish
that a future connection cannot receive one.

The local XPC server/client entry points remain distinct from these peer
transports. The requestSnapshot local XPC branch documented in research38 still
does not prove an Android command for rendering arbitrary uninstalled drafts.

## Genuine native codec samples

`tools/probe_native_opack.m` invokes actual CoreUtils OPACKEncoderCreateData and
OPACKDecodeData. Scalars include negative integers, floating values, NSDate,
UUID, repeated values, nested objects, empty string/data and Unicode. The
boundary sample includes 65,535-byte and 65,536-byte strings. Native decoding
independently accepts duplicate inline values and four/eight-byte references.

`tools/probe_native_replicator_header.swift` resolves the actual native types and
uses their Decodable/Encodable witnesses, then actual ReplicatorEngine.OPACKCoder.
Modes cover header, Message, ACK, advertisement, handshake request and sync.
For sync, an explicit encoder version0 removes record.protocolVersion and encodes
Record.ID.zone; version8 uses zoneIdentifier with clientID/id. The canonical
JSON printed by JSONEncoder uses its own current representation; the actual
OPACK fixture is the versioned evidence.

All inputs are controlled simulator data with no physical pairing material.
The OPACK header sample deliberately has a controlled prefix/headerLength; it
tests Codable serialization, and is **not** a valid NetworkMessenger preamble.
Message.encodedBody and sync.value.data._1 are real NSData in OPACK, rather than
the base64 strings used to feed JSONDecoder.

## Actual binary header, separately verified

Native firmware NetworkSyncHeader.data at 0x1aefe2cc4–0x1aefe304c appends UUID
tuples and UInt32 fields. Sender 0x1af002978 and failure sender 0x1af00b314 set
headerLength72. The protocol magic comes from string0x1af093560, initialized
at 0x1aefe2380: `64d52923-8384-4b61-b55b-e53e9d20272c`.

| Offset | Bytes | Field | Byte order |
| ---: | ---: | --- | --- |
| 0 | 16 | prefix | canonical UUID tuple |
| 16 | 4 | headerLength72 | little endian |
| 20 | 16 | messageID | canonical UUID tuple |
| 36 | 16 | senderID | canonical UUID tuple |
| 52 | 4 | payload length | little endian |
| 56 | 4 | type: data/file/extendedAttributes/failure/compressedFile (0–4) | little endian |
| 60 | 4 | sequenceCount | little endian |
| 64 | 4 | sequenceIndex | little endian |
| 68 | 4 | priority: high/medium/low (0–2) | little endian |

The arm64 23S303 simulator has the same binary getter at image offset0xa96cc.
The research-only assembly bridge supplies Swift self in x20 and returns its
actual Foundation.Data. The probe checks the native public OPACK encoder image
offset0x1303cc before calling the audited private getter. This private function
is never loaded or called by a production app. One actual72-byte header and
all15 native type/priority combinations were captured. Their native output,
not our own encoder, is the independent reference for the Java codec.

ReplicatorNetworkHeader parses/encodes this verified format without opening a
connection or allocating the advertised payload. It rejects unknown preambles,
header extensions, enums, unsigned-overflow lengths and invalid sequences.
Its4MiB payload /1,024-fragment limits are application policies, not claims
about all limits accepted by Apple's decoder. It currently has no live transport
caller. Static enum tables avoid allocation for each received field.

## Production OPACK repairs and optimization

OpackDecoder already serves live Sharing state telemetry. Genuine native samples
exposed and verified fixes for little-endian float/double/date values, opcode07
(−1), empty-object reference positions, four/eight-byte lengths and references,
and duplicate inline values. The old equality scan changed reference indexes
and performed linear work for each inline object; unconditional bounded table
append removes that scan and matches native decoding.

Malformed UTF-8, duplicate/non-string dictionary keys, non-finite numbers and
overflowing lengths are rejected before allocation/interpretation. Existing
256KiB input, depth12, 1,024-item, 64KiB-string and4,096-reference bounds remain.
These are data-only readers; no classes are instantiated from input names.

Eleven new genuine binary fixtures total134,190 bytes. Names beginning
opack-39-* cover native scalar/boundary and seven Replicator Codable samples;
replicator-39-native-header.bin and replicator-39-native-header-enums.bin cover
actual binary framing. The former72B SHA is
53cf114ac36e567bfba769588c29982d763bd0952860cbe6e66a756350e2598a;
the latter1,080B SHA is
2ae6de76a829ec67864639a7426082a81d2c5a2c434ccac0f598b8e97cffc553.
This verifies codecs, not physical image delivery or end-to-end performance.

## Validation and current physical state

Full core JVM suite:1,110 tests /188 suites, zero failures/errors. HAL release
Java compilation, app lintRelease and assembleRelease pass. The final source
Bridge APK is10,415,955B, SHA
519263885254aee3310494182fe0a11e26b73f6d01a46c52cf3fd6970cfce4d3.
It has the same602/0.2.402 version and was not installed. No Flutter, Rust,
SDK or dependency changes were made. The research simulator was shut down.

Final read-only phone evidence is saved under the private temporary directory.
Pair4a8c08cd-7bdb-5718-b9f6-316651d517b5, empty session, original two IDs/order and
second selected remain. OperationalWatchService is listed. The latest snapshot
is SHA-verified daf929716a415316039f8a5fca944332639ed540cb46b20a93a0af0a99d9a1f4,
observedAt1791353746791. It differs from research38's earlier snapshot: selected
face color changed leghorn.hero-2→leghorn.hero-3 in the received state. The first
original configuration is semantically unchanged; this task did not send a face
mutation or restore the earlier color. Do not overwrite this newer observation.

Still needed: verified live connection setup/identity and routing, incremental
bounded frame/file assembly, record reconciliation and ACKs, actual native
snapshot record/file decoding and configuration binding, CPBitmap/ASTC handling,
and production cache/UI integration. Library/gallery publication direction and
arbitrary draft rendering remain open. Advanced Photos, arbitrary providers and
intents, Smart Stack, Mac IDS/NTK and physical performance acceptance retain
their original scope.

Task-only before baseline: `/tmp/watch-snapshot-replicator-39-before`.
Source edits use apply_patch; generated binary samples are audited separately.

Final task-only text audit: nine files, +791 / −12 lines. Eleven new native
binary fixtures add134,190 bytes. Earlier task changes and generated Gradle
reports/build products are excluded from source line counts.
