# 350: Companion phone signal state and actual stop

Installed Bridge0.2.350(550)/Companion1.0.4(5).738 Java tests/lint/assemble,
20 Flutter tests/analyze/build PASS. Full25-group/9-phase goal ACTIVE.
The349 native schema, hardware engine and durable claims remain in place.

## Implementation

PhoneFindObservation is separate from Watch DeviceObservation and native
operation statuses. It carries actual Android-owned signal activity,
behavior, original didPlay, source localProbe, observation time and stop
reason. Active None/failed start is invalid. Busy refusal or None cannot
replace the description of another currently active signal. A duplicate
claim does not manufacture a new hardware observation. Process creation
clears in-memory phone state; durable claims remain separate.

The signature-protected Messenger state now includes these scalars and
actual camera permission. Companion receives them through Kotlin/platform
events into a typed Dart model/provider. It does not start another sound
from an anonymous pingPhone event. Binding/state reads do not start a HAL,
create a pair or duplicate About reads. Missing/malformed/offline state is
unknown. Old About time is not reused as a phone signal time.

Companion shows signal/torch activity, stopped/refused/None, and explicitly
marks local APK probes. An active observation older than5.5s is labelled
stale while retaining a safe stop button. A stop queue receipt never
optimistically clears the activity display. Its stopPhonePing command runs
on the real APK main looper and returns STOPPED only after owned hardware
is stopped; it requires no ready Watch transport and never starts HAL.

The permission button opens a narrow Bridge PhoneFindPermissionActivity
protected by COMPANION_IPC signature permission. The user chooses the
CAMERA permission. OPENED is not treated as granted. Companion re-reads
state on resume without another native About request. Permission grant
changes publish state; ordinary sound does not require CAMERA.

Two probes are private/debug-only:349 phone effect probe and350 Companion
stop probe. The latter executes as the actual Companion UID, reads state,
sends one typed stop, reads resulting state and unbinds with a5s deadline.
It never starts a signal or a HAL and cannot fabricate a Watch request.

## Hardware evidence

Clean349 stop: HCI disconnect05:07:28.548, HAL closed.615,
root exited0 at05:07:29.472. Both APK updates preserve app data/pair.
350 same-pair READY05:08:07.486. About operation
592c7c3f-eb50-4951-874a-dc598ba45cc7 has actual correlated response
05:08:09.319, then Companion09.322 reports100/chargingfalse,
available40088465408B/userApps0/songs0/photos0. This storage reading is
different from347/349 and was not filled with a saved/default value.

One local tone+torch probe:

| Time +03:00 | Observed result |
|---|---|
|05:08:43.015–.026|AudioTrack starts; didPlay=true, localProbe=true; actual torch callback enabled=true.|
|43.027|Companion receives available/known/active=true, behavior1, source local, observedAt1791252523026.|
|43.218|Actual playback2560frames, routeType2=built-in speaker.|
|43.748|Companion UID probe GET_STATE sees the active signal and same observation time.|
|43.756–.757|Companion typed stop releases torch and stops owned sound; reason Companion stop action.|
|43.759|Actual torch callback enabled=false; Companion receives active=false/time1791252523757 and STOPPED receipt.|
|43.760|Companion probe second GET_STATE confirms active=false and exact stop reason; didPlay=true refers to the original start.|

`phone-350-power-after-stop.log` proves wake-lock release. Native main
Companion process26333 also receives both active and stopped events, not
just the private probe. Bridge26145/Root26385 stay live on the same pair.
No signal re-send, setup, Albert/Buddy replay, watch reset or reboot.

Normal ADB UID2000 attempting to open the permission Activity was rejected
with COMPANION_IPC permission denial, as expected. CAMERA was already
granted on349; the permission dialog's positive manual flow is not hardware
accepted by this test. `phone-350-keyguard.log` still shows NotificationShade
and mDreamingLockscreen=true. Real foreground pixel/manual button acceptance
remains open; native Binder acceptance above is actual installed-device
evidence, while widget layout/state tests are synthetic.

Captures: `phone-350-ready.log`, `companion-350-state.log`,
`phone-350-companion-stop.log`, `companion-350-stop-observed.log`,
`phone-350-power-after-stop.log`, `phone-350-permission-screen-denial.log`,
package snapshots. Journal overlap does not mean extra actions.

## Test scope and remaining work

Three new Java tests cover observation invariants, stop source/result and
subscriber isolation. Five new Flutter tests cover unknown/invalid/offline
state, ignoring anonymous ping events, exact STOPPED receipt, no optimistic
state update, stale safe stop, failed stop, permission OPENED vs granted,
None and local source. They do not prove a real Watch-origin command.
The owner request to press «Find iPhone» once remains unanswered; no fresh
native incoming phone-ping request has been observed on350. Phone audio
frames/torch callbacks do not prove physical perception. Full settings,
faces, calls/Health/media/apps/server paths and long stability remain open.
Next native work should derive settings/readback and face collection from
exact23S303 sources rather than using catalog IDs as installed Watch IDs.

Flutter3.47.4/Dart3.13.3 verified by local skill doctor; SDK commit
9584c6713b324636289d067944a46fd6b49df14b matches installed source. Common
State lifecycle checked against installed framework; rolling docs were not
used to select a newer API. No dependency migration. AGP/Kotlin deprecation
notices remain non-failing build warnings.

Bridge archive11237000B SHA256
ab220c74494e8cae8638f5580d4b3f45abf5eacfd85a40af7a8cfccbb4a325f9.
Companion archive156237398B SHA256
06128090eaa0d6d07a7a22e3ff123490f8f5af174208c493d5b008d023546ec1.

