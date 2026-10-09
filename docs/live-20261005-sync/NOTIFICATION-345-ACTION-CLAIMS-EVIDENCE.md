# Bridge0.2.345: exact revision actions and durable claims

Installed545, Companion3 unchanged.714 Java tests/0fail/errors/skips,
lint/assemble PASS (`build-345-revision-actions.log`). APK11182852B,
SHA256 e479ec2d3ceb8eb9d3867f3c61d34514fc5a4c6dcd7c53a8f891290929bd0c23.

## Implementation and limits

- Reply identifier publisher.reply.revisionToken must match active exact
  publisher/record/section and the current Android RemoteInput target.
- Pair-encrypted queue schema2 stores reply/dismiss claims before Android
  effects. Fixed schema1 fixture migrates IDs/body/pending; future schemas reject.
- One reply attempt per content revision; new content resets reply claim.
  Dismiss claim survives content updates until removal. Reused Android key
  after removal receives a different publisher and new claims.
- Commit-before-effect guarantees at-most-once attempt for a retained claim.
  Android rejection or crash after commit leaves unknown outcome and no
  automatic retry. PendingIntent send does not prove recipient acceptance.
- Known Android snapshot entries migrate to revision actions silently.
  Action claims do not store reply text. Rich attachments, per-app controls,
  native visual update behavior, worker I/O under maximum load remain open.

Five new tests exercise stale revision/action rejection, persisted claims
across acknowledgement/restart, exact recipient/dismiss/reused-key handling,
fixed v1 migration, native supplementary action identifier through codec.
The supplementary action test is synthetic unit evidence, not a Watch reply.

## Hardware migration

344 stopped cleanly: HCI disconnect03:44:49.764, HALclosed.832, rootExit0
03:44:50.687. Install-r345 succeeded; activated pair
5e111a6b-a56a-5b3a-b795-1c9799c1d92a preserved.

APK29668 loaded old journal4entries/pending0 at03:45:05.858. Migration
committed pending1→2→3→4 and reconciled active3/pending4 at.911. One removed
Bridge notification became a tombstone; three active cards reencoded quietly.
Own local probe posted03:45:30.535, entries5/pending5 committed.555.
Root started03:45:16.064, same-pair IDS READY03:45:35.300;5 requests enqueued.320:

| Request UUID | Removal |
|---|---|
| b4d7d5ce-5c59-48d6-96c0-aa2925b124ae | false |
| 3c2f5fb1-e1c8-49c5-a07f-23186a252668 | false |
| 520944a0-c323-4143-ae9c-e497e246232e | false |
| e04e2326-b6e8-486c-b9f7-82c45ae01f7c | true |
| e69d0f40-0e0b-4bf7-a8cf-8eec044a938c | false |

AppACKs03:45:39.298 and03:45:40.018/.019/.020/.021; APK committed
pending4→3→2→1→0 by03:45:40.076, entries4. Matched lights03:45:39.997
played=false/exactNotification=true. Registry490f276a-cac7-4992-b5d9-97388f2332f2
appACK03:45:40.168 but no native values. No real reply/dismiss callback or
Watch visibility/haptic/sound confirmed. Local probe remains available.
03:50:02 live IDS traffic; Root29883/APK29668/Companion15038 observed.
Sources: phone-345-migration-receipts.log, phone-345-revision-actions-full.log.

## Exact firmware battery research

Extracted BatteryCenter, NanoSystemSettings and CompanionServices from
Watch7,5 23S303 dyld; local nanosystemsettingsd previously extracted from
same build. No external decompiler/provider used. About response writeTo
0x1c9084d74 encodes uint64 field5 and bool field6 (ivar0x1eea90594/598).
Daemon handleAboutInfoReqMsg0x10000882c schedules block0x100008ac4.
At0x100008d60 MGCopyAnswer("BatteryCurrentCapacity")→unsignedLongLongValue
→setBatteryCurrentCapacity at0x100008d74. At0x100008d84
MGCopyAnswer("BatteryIsCharging")→boolValue→setBatteryIsCharging at0x100008d98.
Block sends correlated About response through outgoingResponseIdentifier.
Read-only topic com.apple.private.alloy.systemsettings, request5/response6
already exists in NSS diagnostics. Capacity units still require verification;
hardware reading and APK consumer remain next work. No percentage invented.
Disassemblies battery-345-about-{write,handler,handler-full,block}.log.
