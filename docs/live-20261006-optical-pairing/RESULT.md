# Optical pairing: live camera → authenticated new pair → visible Watch face

## Live Bridge385/Companion15 run, 19:44 onward

Both release APKs were reinstalled and their installed versions verified.
The human scanned the real animation in Flutter at19:44:49;36frames produced
the optical candidate. New saved pair e3de66fc-bc5b-59b9-bff5-6a89fe6ca028
passed PSK authentication and encrypted Bluetooth. The initial IDS session
remained alive but never completed mutual device-info exchange, holding the
initial NanoRegistry Check. No activation or Wi-Fi occurred in that session.

Flutter Stop setup at19:51:50 retained the pair and closed HCI. Exit2/STOP is
not protocol success. Continue setup at19:52:08 opened the same saved pair;
device-info exchange completed at19:52:15. Albert SESSION HTTP200 returned at
19:52:23, ACTIVATION request followed at19:52:27 and ACTIVATION_CONFIRMED was
recorded at19:52:31. PrepareInitialSync response released completion and the
automatic Wi-Fi worker. IDS QUEUED at19:52:31.895 and matching APP ACK at
19:52:32.092 prove fresh-setup network archive receipt without a button.
The user confirmed the Watch face and subsequently internet working and apps
appearing. They also reported the broadcast network labelled "Hidden Network";
this metadata issue is under investigation. Physical language preservation has
not been explicitly confirmed. Flutter Stop setup at19:54:54 exited0; owner
confirmation was saved. Ordinary Connect at19:57 reached Connected and an
operational request received APP_ACK at19:57:46. Setup replay disabled.

## Automatic Wi-Fi during fresh setup: Bridge385

The previous implementation synced Wi-Fi automatically only on operational READY,
leaving the live fresh-pair setup session without a network transfer. InitialWifiSyncWorker
now reads/validates/encodes the current phone network asynchronously inside root.
After activation, language completion, PBBridge Normal and the correlated
PrepareInitialSync response, the setup HAL sends native wifi.networksync DATA
with V2 ADD on its existing IDS lane. No manual button or operational reconnect
is needed. This is Android's chosen safe setup point; original iOS evidence traces
the network sync trigger to NanoRegistry devicedidpair, not PIN exchange.

Existing WPA2-PSK/CCMP validation and non-destructive ADD semantics are reused.
No password crosses Dart, Binder, CLI arguments or diagnostic logs. Reading failure
retries at most3times with5-second spacing. Owned archives expire after10seconds;
pair/generation/readiness changes refuse them. Stop/link reset wipes late results.
At most one successful dispatch per setup generation; matching topic/message app
ACK means the Watch received the archive, never proof of joining the network.
The operational auto sync and manual retry remain available. The live385 result
above subsequently verified fresh-pair automatic receipt and user-observed internet.

Validation: core1050tests/179suites PASS; release build PASS. Tests cover each
activation/initial-sync/IDS gate, exclusion of operational mode, one-shot archive
handoff, retry bounds, foreign pair/generation/disconnected/expired rejection and
wiping a late reader that ignores interruption. Bridge385(585) installed;
SHA25612ba5c0777422080f1be3fdf0e462d3e64ce0846a314bf3c1794575883d02ee7.
The installed APK's root-only reader probe returned1226bytes on the actual phone
with secrets logged=false/sent=false and exited0. This verifies current network
selection/encoding without opening Bluetooth. A fresh pairing was subsequently
initiated in the live run above; that read-only probe was not a transfer.

## Previous reset/preparation checkpoint and language correction

The user reset the Watch again after the successful run below. It waits for a
new pair; saved UUID7bbaa01a-d09b-5063-bb55-3023ac72a667 no longer proves a live
bond. Owner confirmation was saved before this reset. Ordinary reconnect tried
direct connection and bounded scans but found no matching bonded advertisement;
Flutter Disconnect stopped retries and HAL exited0 at19:18:26. Wi-Fi was not
transferred on this new pair. Current instruction: prepare before pairing again.

Bridge384(584) was installed for the language correction (now superseded by385);
Companion remains1.0.15. Bridge384 removes the setup
language overwrite caused by HalPostCommitOrchestrator's Locale.getDefault().
Authenticated Watch preferredLanguages/currentUserLocale now survive incoming
message destruction and provide the ordered language list/locale for PBBridge25
and its preferences archive. No fixed language, Android/app-locale fallback or
second Companion language chooser was added. Missing/malformed observations stop
before IsPaired/activation. Protobuf isSet=false is NSArray, not property absence.

Read-only Watch26.2 NanoRegistry decompilation: initializeGetters at10008cae4
maps currentUserLocale to global block1001544f8/invoke10008ee8c, which returns
NSLocale.currentLocale.localeIdentifier; preferredLanguages maps to global
block100154538/invoke10008ef54, which returns a copy of NSLocale.preferredLanguages.
The separate Watch26.6 symbol index confirms both NR property imports and getters.
This is source evidence, not a live observation of the next pairing.

Validation: core1045tests/178suites PASS, release build PASS. Regression tests
exercise an explicit NSArray protobuf flag, retained properties after message
destruction, language order/region on a Russian phone, missing-value refusal
before dispatch, and actual PBBridge fields/preferences archive equality. No
Watch apply/completion is inferred from preparing the send. APK SHA256:
ebd579018f5901f14946fdef3d6484e6bad5075c1f92c86a4364cfc4dedc1021.
Installed successfully; HAL remains stopped and no new pairing was initiated.

## Live result on 6 October, 19:06–19:09

Companion **1.0.14(15)** and Bridge **0.2.381(581)** were installed together.
The user pointed the camera at the real pairing animation, scanned it and
accepted **Replace Saved Pair** in Flutter. The actual app reported recognition
after **11 frames**; no fixture or saved code was injected into this live run.

The private native candidate was consumed once through its120-second handle.
Only the signature-protected native Binder/foreground-service/stdin path carried
the110bytes. Dart/UI/preferences/CLI args do not contain the optical key.
Bridge verified the requested old pair identity, staged/readback-verified/fsynced
encrypted session/bond archives, then retired their default paths and owner
confirmation. Keystore aliases, IDS identity and stable phone BLE identity were
preserved. Backup metadata contains only public pairing identity/confirmation.

Observed protocol events:

| Local time | Evidence |
| --- | --- |
|19:06:44.969| Previous encrypted pair archived and verified |
|19:06:45.452| Camera identity matched fresh Watch setup advertisement |
|19:06:46.452| Authenticated control SA selected PSK/1, asynchronous request ACKed |
|19:06:46.940| Responder optical PSK AUTH verified atMID2 |
|19:06:47.091| Private notifications validated; optical requestMID3 |
|19:06:47.170| New encrypted pairing-material checkpoint persisted/readback verified |
|19:06:47.832| Controller accepted SC LTK and link encryption |
|19:06:55.348| IDS ready and initial NanoRegistry setup started |
|19:07:17.140| Correlated PrepareInitialSync response; completion update sent |
|19:08–19:09| Human explicitly reported a visible Watch face |
|19:09:30.718| Flutter Stop setup completed HAL cleanup and exit0 |

New saved public pair UUID: `7bbaa01a-d09b-5063-bb55-3023ac72a667`.
The actual durable state reached ACTIVATION_CONFIRMED. The coordinator did not
report OPERATIONAL_HEALTH_CONFIRMED, IsSetup/PairedSync/Clock protocol barriers
as complete. Physical Clock evidence is the user's answer; no send/ACK is
being promoted to that evidence. Owner confirmation was subsequently saved;
ordinary reconnect failed, then the user reset the Watch. No Watch reset/reboot/
erase was requested by the implementation.

New safety checks cover encrypted backup/restore, changed-source abort before
any deletion, corrupt-archive refusal, symlink/staging failure, same-pair optical
replacement policy and optical CLI exclusion from operational/SMP recovery
modes. Core **1039 tests/177 suites PASS**, Flutter **61 PASS**, analyze0 and
both release builds PASS. Source1.0.15/0.2.382 additionally removes the misleading
Finish initial synchronization button and rejects its legacy local command.

Generated-change audit against `/tmp/optical14-before-generated`:

| File/window | Added/deleted lines |
| --- | --- |
| l10n app_localizations.dart | +18 / −0 |
| l10n app_localizations_en.dart | +11 / −0 |
| connection screen format window, including later guard adjustment | +13 / −11 |
| optical_reader.dart formatting | +3 / −1 |
| connection provider format window, including nullable pair correction | +14 / −7 |
| connection test formatting, before final assertion correction | +50 / −20 |

Ordinary edits used apply_patch. The first l10n command ran from the repository
root and found no generate-enabled Flutter package; it changed no localization
source. Correct generated-file baselines were captured before running from
Companion. Subsequent source corrections are visible apply_patch edits.

## Historical recognition-only stage (1.0.13)

Companion **1.0.13 (14)** is installed; Bridge **0.2.380 (580)** remains
installed. The Watch is waiting for a new pair. No optical authentication or new
pair has been initiated in this work. The old encrypted pair remains saved.

## Working recognition backend in Companion 1.0.13

To move the actual user flow forward while the independent spatial port continues,
the local build now packages a **transitional original-firmware backend**.
`prepare_optical_android_asset.py` verifies the supplied 23G71 image hash, adapts
pointer-auth instructions only, then verifies the output hash. Algorithms and
tables remain original firmware. This is not described as a finished Java/Dart
spatial rewrite or as a generally redistributable decoder.

`OpticalDecoderService` is non-exported and runs in `:optical`; one worker thread
owns the JNI loader and reader. Bounds: 90 frames, 960×540 UV maximum, a 128MiB
arena. A native fault ends the worker process rather than Flutter's process.
Unsupported native ABIs reject initialization. Reader/frame buffers are wiped at
close. Build supports the current arm64 phone; other device families are unverified.

`OpticalDecoderClient` provides serial private Binder requests and 10-second
deadlines. A restored key stays in native Kotlin memory behind a single-use UUID
handle for at most120seconds; Flutter sees only handle and public advertising
name. Close/failure clears the candidate. No raw code bytes are converted to a
Dart string, written to preferences, or logged.

Actual release-device proof: platform `OpticalDecoderInstrumentation`, run against
the installed release worker, consumed the real private camera fixture. It
restored110bytes after20frames and compared them byte-for-byte with the independent
private oracle result. Result: **PASS**. Explicit test fixtures and test APK were
removed after verification. An initial AndroidX test-runner shrinker dependency
failure was resolved by replacing that runner with a small platform-only harness;
this failure occurred before tests and is not a decoder result.

All Watches → **Pair with Camera** now opens a genuine live rear-camera preview,
guide and Scan action in Flutter. Actual screen/permission/preview opened on the
phone; screenshot `/tmp/companion-optical13-camera.png`. The Watch was not centered
for that screenshot. The user route currently returns a recognized-code dialog,
not a new paired/activated Watch. A live Watch recognition followed by PSK, fresh
record and setup still requires end-to-end hardware verification.

The actual camera/channel/worker chain was also exercised without a Watch
animation: it reached90analyzed frames and offered retry without recognition or
pairing. The initial build's generic camera-error text was corrected to a distinct
no-animation message in the final installed build. Camera closed after QA; no
root HAL was started. A recognized result from a live Watch in the final Flutter
route is still unproven; the positive result above uses the private real-frame fixture.

## Verified on the actual camera sample

- Companion's rear camera captured 90 YUV420 frames, with separate plane row
  and pixel strides normalized to packed UV. Sensor image 1280×720, chroma
  640×360; private capture 41,474,168 bytes. No JPEG/RGB conversion was used.
- The original 23G71 HCImagePerspectiveReader, run in a bounded ARM64 Unicorn
  research oracle, reconstructed the payload on frame 19 (20 frames).
- The portable Java signature mask, circular Viterbi, packet erasure decoder,
  payload CRC and six-field parser reconstructed the **same bytes** from all
  20 original-reader signature/packet traces. They do not yet localize the
  watermark in a raw camera image independently.
- A separate NDK ARM64 research executable ran the PAC-adapted original
  reader **on CPH2653 Android**, using the same private camera sample. It also
  completed on frame 19; its 110-byte result was compared privately and was
  byte-identical to the Mac result. This executable is not linked into either
  APK. It relies on the locally supplied original firmware, not the Java port.
- Live code shape: six fields, magic format 4, compatibility 25, 32-byte key,
  material 14, size 19, watchOS 26.2. No key/PIN/payload contents are logged.
- Portable OpticalChromaSignal produces the same fixed-point UV signal as the
  original reader in **all 230,400 pixels** of the first actual camera frame.
  A separate synthetic native fixture checks rounding and saturation in tests.
- Portable OpticalLocalContrast matches original octaxis_O on synthetic ties,
  edges and padded rows. OpticalAffinePatch matches every one of 1,296 sampled
  pixels (plus output stride padding) from the original Q20 bilinear sampler on
  a rotated/scaled synthetic image. These two stages require a known transform;
  they do not discover the grid in a raw frame yet.

Real captures and decoded material remain outside the source tree for ongoing
decoder work. Temporary research executables, firmware image and key-containing
captures/output have been removed from `/data/local/tmp` and the Companion
capture cache after comparison. Committed fixtures contain synthetic data only.

## Source chain and protocol facts

Local iOS 26.6 23G71 sources:

| Stage | Evidence |
| --- | --- |
| Camera UV frame → watermark packets | VisualPairing HCImagePerspectiveReader::ProcessUVFrame, 0x29acbf358 |
| 1024 soft correlations → payload bits | Decode::DoRealWork, 0x29acbb134; TailbiteConvCode924::Init, 0x29acd008c |
| Frame packet → 114-byte erasure result | RandomErasureDecoder::AddPacketToDecodeQueue, 0x29acc268c |
| Camera result → pairing coordinator | Bridge COSMagicCodeScanner captureOutput, 0x1000787f8; scanned-code block, 0x1000f0dcc |
| Payload parser/generator | PBBridgeSupport PBBridgeMagicCodeString, 0x26862bd74, and PBBridgeMagicCodeDecoder |
| Optical auth derivation | terminusd PSK branch 0x100139c28–0x100139c6c; establishPairingSessionIfNeeded 0x10012d7bc |

Payload is `format--compatibility--advertisedName--hexKey--material--size&&OS`.
The name binds the five-digit advertising ID, NetworkRelay strategy, material
and size. The parser validates these against the actual 12-byte setup
advertisement and checks advertised compatibility/system version before deriving
the mutable 44-byte `key || advertisementData` secret.

The five packet-ID bits are LSB-first; each of the following 15 packet bytes is
**MSB-first**. Payload/CRC bit expansion is MSB-first too. Checking the actual
sample exposed the earlier LSB interpretation error; the synthetic oracle and
portable CRC were corrected together. Native independent fixtures and the live
20-frame byte comparison now pass.

The watermark has a public fixed 1024-bit mask, a 100-bit header and 924
convolution symbols. Constraint length 9, generators 315/331/441/501/431/485,
149 data+CRC bits, 30 repeated symbols. Packet CRC is 24 bits; erasure payload
CRC is 32 bits. GF(256) polynomial 0x11D and native LCG generate packet equations.
Eight distinct packet IDs normally suffice; duplicate frames do not advance rank.

Camera setup is a separate **PSK/1** control negotiation. Pairing IKE AUTH is
method 2, KeyID `com.apple.networkrelay.companionlink.pairing.auth.randomKey`.
It does not run PIN PBKDF2, SPAKE2+ or mandatory PIN PPK. The existing live HAL
only implements the PIN branch; camera material must not be sent to that branch.

New portable protocol modules (not connected to the hardware HAL yet):

- OpticalAuthMethodNegotiation distinguishes an empty encrypted ACK from PSK
  selection. It requires C546/authMethod=1, handles direct replies or ACK followed
  by Watch request MID0, and rejects PIN selection/salt, duplicate fields, malformed
  data and AEAD tampering. The caller must acknowledge a Watch request.
- OpticalPskSession snapshots SA_INIT/intermediate transcripts and the bound
  44-byte secret. It sends IKE_AUTH MID2/method2 and verifies the responder MAC
  in constant time with the randomKey identity. Independent Python HMAC-SHA512
  vectors check both signatures. Negative tests cover wrong key/identity/method/
  transcript/MID and AEAD tampering; reordered/duplicate fragments and cleanup
  are tested. Borrowed transport keys remain available for the following exchange.
- Optical SA_INIT excludes PIN/SPAKE2+/USE_PPK and rejects PIN capabilities in
  the response. Private-notify request/accumulator accept optical MID3 while
  existing PIN callers retain MID5. Tests reject a PIN ACK in the optical exchange.

The MAC construction follows [RFC7296 section 2.15](https://www.rfc-editor.org/rfc/rfc7296#section-2.15)
and the intermediate transcript suffix follows [RFC9242 section 3.3](https://www.rfc-editor.org/rfc/rfc9242#section-3.3).
Apple profile/identity/control selection evidence comes from the local firmware
chain above. Passing synthetic protocol tests is not a claim of Watch acceptance.

## Product code and remaining work

The Flutter diagnostic route is **All Watches → Connection diagnostics →
Optical Pairing Lab**. It has a genuine camera preview, bounded explicit sample
capture, lifecycle disposal, retry, private temporary storage and English ARB
strings. It explicitly states that recognition is not enabled there. Production
Add Watch does not pretend to scan or claim a pair from a camera frame.

Portable core owners: OpticalChromaSignal, OpticalAffinePatch, OpticalLocalContrast,
OpticalConvolution, OpticalWatermarkSignature, OpticalWatermarkPackets,
OpticalPairingCode, OpticalAuthMethodNegotiation and OpticalPskSession. Frame copying/capture are separate
Flutter modules. Version1.0.13 additionally packages the pinned original reader
described above; the earlier1.0.12 diagnostic APK did not contain it.

Still required before this is a completed user flow:

1. Integrate the tested PSK control/authentication modules with HAL transport,
   verify optical IDS auth material and the subsequent OOB/private notifications.
2. Fresh-pair replacement that preserves an encrypted backup of the reset
   Watch's stale saved record and serial HAL ownership.
3. Production Flutter scan → matched fresh advertisement → authenticated pair
   → activation/setup → connected Watch. Verify this on the actual hardware.

Independent spatial-port work also remains: grid localization/scale/rotation/phase,
perspective compensation and 1024 correlation extraction. Compare with the private
raw-frame/native-signature sample. The transitional backend does not prove that
the portable port is finished.

Current progress is decoding evidence, **not** a completed camera pairing,
activation, or independent portable Android raw-image decoder.

## Validation and generated-change audit

- Core: **1033 tests / 176 suites**, zero failures/errors/skips. Optical tests
  cover independent native encoding, UV/filter/resampling, CRC corruption,
  erasures, duplicates, signal errors, advertisement binding, PSK signatures,
  negotiation, fragment handling and rejected authentication/downgrade inputs.
- HAL and app release Java compilation PASS after the core protocol additions.
- Flutter: **60 tests**, analyze **0 issues**, release build PASS. Three new
  optical channel tests cover start/stop/UV shape, handle-only recognition,
  malformed results and no implied pair command.
- Installed Companion reports1.0.13/versionCode14,minSdk24; actual home
  shows the saved Watch disconnected. No root HAL process runs.
- Native private probe: Mac/Android payload byte comparison PASS; portable
  signature/packet/payload comparison PASS on all 20 frames used for completion.
- Camera stream behavior physically verified on 1.0.11 before the final cleanup
  fixes;1.0.13 adds verified release-worker recognition and actual live preview.
  No claim of a new camera-auth pairing.

Generated changes, compared to the camera-task snapshot only:

| File | Added/deleted lines |
| --- | --- |
| lib/l10n/generated/app_localizations.dart | +42 / −0 |
| lib/l10n/generated/app_localizations_en.dart | +25 / −0 |
| pubspec.lock | +66 / −2 |
| android/app/src/main/java/io/flutter/plugins/GeneratedPluginRegistrant.java | +10 / −0 |
| ios/Runner/GeneratedPluginRegistrant.m | +7 / −0 |
| Dart formatter: optical_capture.dart | +2 / −1 |

Ordinary source/document edits used apply_patch. Baseline:
`/tmp/optical-pairing-before`; formatter snapshot `/tmp/optical-format-before`.
Original firmware/native algorithm and Ghidra logs are local research inputs;
the APK itself uses camera 0.12.1 and project-owned source modules.

Additional generated research export in the protocol/spatial stage:
`spatial-demodulator-decompile.log` is a new file, **+963 / −0 lines**.
It contains the original Demodulate, CalculateXform and phase-estimation functions
for the next spatial port. No existing source was rewritten by the export.

The newer core protocol/filter/patch changes remain source-only in Bridge;
Bridge0.2.380 is still installed. Companion1.0.13 contains the transitional reader
and Flutter camera route. PSK modules have not been invoked against the Watch.

Version1.0.13 generated-change audit against its own pre-l10n/pre-format snapshot
(`/tmp/optical13-before-generated`), excluding older camera work:

| File/operation | Added/deleted lines |
| --- | --- |
| New l10n generation: app_localizations.dart | +48 / −0 |
| New l10n generation: app_localizations_en.dart | +30 / −0 |
| optical_reader.dart formatting window (includes explicit null-guard fix) | +14 / −5 |
| Camera screen first formatting pass | +24 / −6 |
| Camera screen second formatting window (includes explicit no-match reset) | +4 / −1 |
| Connection screen formatting window (includes camera-label rename) | +25 / −8 |
| optical_reader_test.dart formatter | +72 / −40 |

The test's first snapshot command used an incorrect working-directory-relative
path. Its exact apply_patch source was restored, then captured correctly and the
formatter rerun; this table uses that verified snapshot. Ordinary source and
documentation edits used apply_patch. The generated local reader asset is binary:
493936bytes, SHA256b4f744035e6f82f1a83808d310be9e0f02f4e2d97355ae063aab418066db1081;
no source line counts apply. The installed final release is21136523bytes,
SHA256ba08dcee1478c3ddc76e67ae56dfbc470afa236e7978e66de66a38ac9e53d60f.

Camera package lifecycle/format references:
[camera 0.12.1](https://pub.dev/packages/camera),
[CameraX implementation](https://pub.dev/packages/camera_android_camerax).
