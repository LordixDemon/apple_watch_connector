# Native monogram editor and manual preference delivery

The fourth California belongs to the user and must remain in the library. No
physical preference/face write, ordinary app installation, app termination,
draft application/discard, pairing or radio restart was performed in this stage.
The existing editor-draft choice remains separate from the ownership answer.

## Original evidence and implemented behavior

The retained iOS 26.6 disassembly in
`live-20261005-sync/ios266-monogram-69.asm` establishes a separate installed-face
edit row, a modal entry controller with Done, character validation while typing,
and the `textFieldDidEndEditing:` sequence. Empty text preserves the existing
preference. Nonempty text selects the first five UTF-16 units expanded to a native
composed range, then uses the current host locale for uppercase, then validates
the resulting preference. Uppercase expansion can exceed the final limit and
must not be sent. The previously verified Foundation processor from stage72
supplies this transformation; there is no locale-independent Dart fallback.

The installed-face monogram section now opens this editor when its complication
is enabled. Gallery drafts retain the switch without a global preference editor.
The text is a per-pair `com.apple.NanoTimeKit/customMonogram` preference, separate
from the face's `complications.monogram` dictionary. Editing text never stages a
face delta, changes the selected face, or inserts a fabricated preview image.

Flutter input preserves active IME composition. Committed emoji/malformed UTF-16
is rejected; no five-character keyboard truncation or uppercase occurs while
typing. A 4096 UTF-16 resource bound protects the native channel. Done and keyboard
submission perform normalization before dispatch. This implements the verified
Done flow; it does not establish identical behavior for every UIKit focus-change
or navigation action, nor exact visual parity for every native entry cell.

## Persistence and transport

`NativeMonogramSyncController` saves a pair-owned manual intent before invoking
the Bridge. Persistence failure or a changed pair/epoch/revision before invocation
prevents sending. Saved pending intents reopen as uncertain and never replay
automatically. Completed intents do not override newer Watch preferences after
restart. Status distinguishes awaiting delivery, IDS delivery, uncertainty,
storage failure and a matching owned native receipt. An ACK cannot establish
application. Native confirmation requires matching text/pair/epoch, a sufficiently
new source timestamp and a matching REMOTE HAL mirror; LOCAL never confirms.

Bridge validates the same pair, operational epoch, opaque mirror revision and
final native text. The authenticated HAL send thread rechecks these conditions,
advances the source clock even for successive writes in one millisecond, encodes
the exact native TwoWaySync NPS payload, and atomically persists LOCAL provenance
before returning bytes to transport. Queue expiry after preparation leaves an
uncertain local value; reopening the store does not send it. Genuine NPS reports
promote an exact local echo to REMOTE without inventing a timestamp. Older or
equal conflicting reports and reports too far ahead of the source clock are
ignored. Unknown baseline and observed deletion remain distinct.

The per-pair store includes identity, version, checksum, bounded text and atomic
file/directory synchronization. Corrupt/foreign state cannot supply a baseline.
The separate mirror IPC preserves provenance without manufacturing a live Watch
receipt. Application-side IPC accepts only the active connected READY session.
Incoming pigment and monogram reports share one bounded NPS envelope decode;
their durable writes fail independently. No preference text is logged.

Android exposes the writer through existing Binder/MethodChannel plumbing. The
shared macOS editor uses Foundation normalization, but its Rust transport does
not yet implement this writer: unavailable transport stays unconfirmed. No macOS
delivery or physical Watch application is claimed here.

## Optimization and validation boundaries

Identical monogram publications do not notify or enqueue reconciliation. The
monogram section listens to its controller directly; status updates do not add
a whole-Provider subscription to the face editor/preview. A test repeats 1000
equivalent Bridge publications and observes one notification and zero sends.
This is a verified rebuild reduction, not a hardware frame-time improvement.
The broader frame-performance goal remains open.

Java tests cover manual first creation, observed deletion, native payload,
persist-before-wire, clock advancement, stale context/revision, genuine echo,
old/conflicting/future reports, corruption, foreign stores, atomic-write failure,
malformed IPC/command frames, command injection and disconnected dispatcher state.
Flutter tests cover mirror validation, persistence failure, context races, early
ACK, independent native confirmation, restart without replay, composition/input
validation, empty Done, invalid normalized values and gallery/installed row scope.
Build, suite totals, APK receipts and source-change counts are recorded in
`SUMMARY.md` once final checks finish. Prepared APKs are not installed while the
ordinary editor's unsaved-draft choice remains unresolved.

Full original Watch.app parity remains active: native live previews and
complications, Photos integration, shared application/complication installation,
firmware coverage, hardware verification and measured frame optimization are
still required. This stage is not a completion claim for that larger objective.
