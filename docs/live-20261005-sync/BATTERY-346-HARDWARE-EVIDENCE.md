# Bridge0.2.346: real correlated native About readings

Installed546, Companion3 unchanged.718 Java tests/0fail/errors/skips,
lint/assemble PASS (`build-346-native-about-final.log`). APK12859479B,
SHA2561f4de2ae95f30d2fdecc3c759475e70282668967730383f078632b1e918002a2.
Four new tests: exact native scalar mapping/absence, invalid or unmatched
response cannot replace valid observation, APK route/sentinel/nullable
charging/disconnect behavior, exact typed command excludes payload/setup lines.
[Native units/schema](BATTERY-346-NATIVE-SCHEMA.md).

## Real device events

345 clean stop: HALclosed03:55:40.415/rootExit0 03:55:41.273. Install-r346
succeeded, pair5e111a6b-a56a-5b3a-b795-1c9799c1d92a retained. Rootstarted
03:56:09.744, same activated pair IDS READY03:56:29.045; no setup/activation
replay. Auto About and Companion refresh each queued an empty NSS type5.

| Operation UUID | AppACK | AppResponse | Native validated response | APK IPC | Companion state |
|---|---|---|---|---|---|
| 1301fd99-ae0a-4195-b2d1-88e691717422 | 03:56:30.277 | 03:56:30.695 | 03:56:30.696 | 03:56:30.698 | 03:56:30.699 |
| 5617cb68-d087-46b4-8368-d6740eeedaf8 | 03:56:30.309 | 03:56:31.172 | 03:56:31.173 | 03:56:31.175 | 03:56:31.176 |

Both native responses type6/response=true/22B matched outgoing IDS UUID,
fields1..7 each once, batteryCurrentCapacity=100/batteryIsCharging=false.
Only validated responses passed HAL→APK. Native percentage is not an assumed
default. Companion logged state updates after both application messages;
its log only reports field presence, not the percentage. Explicit-1 is also
sent when unknown, so the field-presence log alone does not prove a reading.
The native trace, IPC trace and decoder tests establish the reading path.

Separate own-notification request342a5786-31ab-4767-a8a4-f48afe916856 appACK
03:56:32.581; no visible notification/reply claim. Root531/APK32727/
Companion15038 observed. Initial capture overlaps earlier345; do not count
old events as new sends. Short hold only, not24h/20reconnect acceptance.

## What remains

Physical charging comparison/state changes, percentage changes, foreground
Companion visual acceptance, explicit timestamp/freshness UI, refresh
coalescing and storage/count presentation remain. uiautomator capture
companion-346-battery-ui.xml is the locked phone's keyguard; its100% refers
to the PHONE battery and is not evidence of Watch UI. Existing Companion3
can consume percent and the charging icon but ignores new timestamp fields.
No phone unlock guessed or Watch reset performed. Goal remains ACTIVE for
the whole feature plan. Raw storage/count contents and reply text not logged.

Sources: phone-346-native-about-initial.log, companion-346-native-about.log,
phone-346-native-about-late.log. Wrong-tag empty companion-346-about.log
retained as failed capture rather than overwritten.
