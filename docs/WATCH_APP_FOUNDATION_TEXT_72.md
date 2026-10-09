# Original monogram text normalization — 72

Progress toward the original Watch.app editor. The full parity and measured
presentation optimization goal remains active. The user-owned fourth California
was retained. No installed app replacement/kill, draft apply/discard, physical
Watch/NPS write, HAL/radio restart, reset or pairing was performed.

## Source and native evidence

The normalization order established in stages69–71 remains unchanged: expand the
first five UTF-16 units to composed sequences, uppercase with the host locale,
then validate the resulting text using the native length/emoji rules. Spaces,
accents and supplementary non-emoji characters are preserved; no ASCII-only
restriction, trimming or invented initials were added.

Algorithm references are the pinned Apple/Swift CoreFoundation implementation:

* [CFUniChar.c](https://github.com/swiftlang/swift-corelibs-foundation/blob/b2112d2d80c4365dbb32479d89bb3177bfda8ea8/Sources/CoreFoundation/CFUniChar.c)
* [CFString.c](https://github.com/swiftlang/swift-corelibs-foundation/blob/b2112d2d80c4365dbb32479d89bb3177bfda8ea8/Sources/CoreFoundation/CFString.c)
* [NSString.swift](https://github.com/swiftlang/swift-corelibs-foundation/blob/b2112d2d80c4365dbb32479d89bb3177bfda8ea8/Sources/Foundation/NSString.swift)

Fetched files were compared byte-for-byte with that commit. This is an algorithm
reference, not a claim that its implementation is identical to proprietary
watchOS. Native differences were resolved from the actual23S303 CoreFoundation:

* Character cluster function: `c0e04..c2008`, bounded by the next symbol.
* Basic composed range: `c5f98..c6aa4`.
* Joining-pulli predicate: `c6aa4..c6c7c`.

Complete bounded disassemblies are retained as `watchos262-foundation-*-72.asm`
under `live-20261005-sync/`. Native basic ranges include ZWJ during both backward
and forward extension. Tamil pulli joins only the native consonant sequences
checked by the predicate; arbitrary Tamil virama does not join the next letter.
These differences cannot be inferred from the open-source version alone.

Uppercase handles native language/context rules, including Lithuanian dots,
Turkish/Azeri i and Greek tonos/decomposition. Capital sigma precedes the language
branch and does not establish a tonos context. The port keeps non-tonos suffixes
as the native algorithm does, rather than applying Android ICU Greek casing.

## Versioned scalar data and independent replay

`probe_native_foundation_text.m` runs in watchOS Simulator, reads Foundation and
the runtime ICU properties, and creates transient strings only. It enumerates all
1,112,064 Unicode scalars, excluding surrogate code points. Each no-language case
map is independently checked against `NSString.uppercaseString` on that runtime.
The schema2 capture contains1552 uppercase mappings,744 letter ranges,321 non-base
ranges,934 nonzero combining classes,249 Greek decompositions, and four additional
property range sets: Extend/Emoji_Modifier, Extended_Pictographic, Prepend and
SpacingMark. It does not read or change device preferences or face collections.

`build_native_foundation_text.py` validates the exact runtime, complete counts,
canonical ordered ranges and bounded UTF-16/scalar values. It generates a33,855B
binary table and a deterministic gzip/base64 Java payload with a SHA-256 digest.
The application loads it once, verifies the digest/schema, and uses sparse arrays
and binary search. Its bounded stream reader works at Android API24; no Android
ICU version or platform `String.toUpperCase` result is used as an approximation.

Independent native oracles retained in `live-20261005-sync/`:

| Capture | Replay result |
| --- | --- |
| `watchos262-foundation-text-72.json` | All1,112,064 scalar case/property entries match production Java |
| `watchos262-foundation-casing-72.json` | 9760 contextual uppercase cases,8 locales, zero mismatches |
| `watchos262-foundation-clusters-72.json` | Five templates across every scalar:5,560,320 boundary cases, zero mismatches |
| `watchos262-monogram-normalization-72.json` | 5670 full normalization cases,7 locales, zero Android/macOS mismatches |

The boundary capture stores the native exceptions to a surrogate-safe UTF-16
baseline in compact scalar ranges. Java expands those captured expectations
independently before comparing the production algorithm. The complete pipeline
includes cross-script pairs, surrogate boundaries, Hangul edge values, Prepend,
Indic joiners, Tamil conjuncts, controls, transcoding hints, regional indicators,
emoji and32–256 repetition sequences. Invalid final values remain invalid.

These checks cover the stated matrices; they do not prove every possible string
or every OS version. Scalar/emoji tables are explicitly pinned to23S303. Further
firmware generations remain part of the full parity work.

## Application integration and performance scope

The verified processor moved from `tools/java` into Companion's Android module.
`NativeTextAdapter` exposes only `normalizeMonogram` on a separate local text
MethodChannel, uses `Locale.getDefault()` for each request, and runs on a serial
background queue. It owns no Bridge/Binder/NPS access. MainActivity unregisters
the adapter on engine cleanup and destruction. The isolated profiling app still
skips this registration together with its other adapters.

macOS retains its actual Foundation implementation. The shared Dart adapter
checks input/reply sizes and well-formed UTF-16, then validates the final value.
It does not silently fall back when the native implementation is unavailable.

Local shell-VM measurement on CPH2653 used the production Java processor,2000
warmups and1000 measured calls per independently captured input. GC occurred
outside the timed loop; outputs were checked against the native oracle.
`native-monogram-text-benchmark-72.json` retains the raw result. Typical tested
inputs had p95 between0.781 and2.239µs; a261-unit input with256 combining marks
had p95=16.719µs. ART process allocation statistics are included and may be
quantized. This is processor latency/allocation, not MethodChannel latency,
Flutter frame time, editor animation performance or an end-to-end improvement.

## Validation and remaining work

Python generator/corruption/round-trip tests:3 passed. Flutter:355 passed,
including the5670-row final-validator/channel contract replay. Analyzer:0 issues.
Production Java data/context replay and both boundary/pipeline oracles passed in
an isolated Android `app_process` without installed-app or Watch access. Actual
macOS Foundation replay also passed all5670 cases.

The original installed-face text-entry screen and durable pair/epoch/clock-scoped
NPS writer are still outstanding. The new local channel does not claim a saved or
applied monogram. Delivery receipts and native readback must remain distinct.
Other library/gallery/live-preview, Photos, shared installation, firmware-version
coverage and measured presentation-time optimization work also remains.

The original Companion stays installed with its editor draft. Its processPID31249
remained alive, and read-only cache `original-72-final` retained count4 and
observedAt1791413175978. That cached snapshot is not a fresh Watch acknowledgement.

Companion Android release1.0.67/code68 built successfully in46.2s. AAPT confirms
the version and minSDK24. All18,948 managed native assets match their sources;
test fixtures and the profiling entry point are absent. APK239,586,823B/SHA-256:
`42f9c241fbe567dbb7bcb18a24e09d7c60f498f212fe6894313ded13c12d16b0`.
It was not installed. Bridge/Core/macOS source was unchanged in this stage;
their previous build/test results were not represented as new checks here.
Android `:app:lintRelease` passed. All tool sessions are terminal and the temporary
Android replay files have been removed. Generated tables/native receipts account
for9 files and2,207 added/0 deleted lines; source counts exclude pre-existing work.
