# Setup completion routing — Bridge388 / Companion1.0.19

## Cause and behavior

The saved pair was activated and its Watch face was working, as explicitly
confirmed by the owner in this turn. Bridge's durable state was still
ACTIVATION_CONFIRMED and its separate owner-confirmation marker was absent.
Flutter consequently offered Continue setup, even while asking the owner to
confirm the face. An explicit retry at22:51 had re-entered setup and failed:
'Authenticated Watch language/locale is required; phone fallback disabled'.

Activated pairs now offer Finish setup first. Only a separate 'My watch still
shows setup' choice can resume synchronization, after an explanatory dialog.
Pre-activation saved pairs retain Continue setup. Both dialogs capture their
pair ID and revalidate the current pair and readiness before sending a command.
Queued receipts remain receipts; only native observations update readiness.
Completed operational pairs hide obsolete setup/error panels, and the card
shows Connected when an authenticated connection and a BLE flag coexist.

There was also an actual fully native completion bug: operating mode always
required an owner marker, even after the native coordinator had verified all
barriers. OPERATIONAL_HEALTH_CONFIRMED now qualifies without that marker only
when the serialized record has observed setup evidence. Activation, publication,
ACK, intermediate checkpoints and unobserved legacy terminal records do not
qualify. The store and public snapshot use the same policy. VERIFIED progress
removes the transient false readiness overlay before refreshing identity.
Pair matching remains mandatory for storage/confirmation, including terminal
records. No synthetic Clock or operational-health evidence was introduced.

## Physical verification

Installed Bridge0.2.388/versionCode588 and Companion1.0.19/versionCode20 with
adb install -r on577d0b47. No active setup/operational service was running
before the package update. Pair and bond encrypted-container SHA256 values
were byte-identical before/after installation:

- pair: `cfb99ee44d64a06fb76b2f5c561dd3364623d8013643ea16d1e1df7e1c6e7759`
- bond: `9c8edfb7c7db48ed0518fa4ce263b3766379bc857cabc8e3753b867531d42d09`

Used the existing app Finish setup / visible-face confirmation, based on the
owner's explicit report. At23:04:38 normal mode was saved for the same pair
5f98bb3b-e812-afaf-35f7-79be0a392dea. The UI switched from Finish setup to
Connect and retained the identity. No private preference file was edited.

The first connection verification tap landed on the control after it had
already become Disconnect. That run exited0 with setup replay attempted=false.
Then read the current accessible Connect label/bounds before clicking it and
restored normal mode. Final screen reports Connected with a fresh battery
reading97%/not charging at23:06:33; IDS application ACK traffic was observed.
All Watches shows Disconnect with neither Continue setup nor Finish setup.
The phone was returned to My Watch and left connected.

This hardware run uses owner-confirmed completion. It does not claim that the
Watch emitted all strict native Clock/reconnect completion evidence. No Watch
reset, fresh pair, activation retry, language/locale write or Wi-Fi change was
performed. Explicit unfinished-setup retry still validates Watch-supplied
locale; that path was not changed into a phone-locale fallback.

Screenshots: `live-20261006-setup-finish/installed.png`, `finish-action.png`,
`completed.png`, `reconnected.png`, `connected-my-watch.png`.

## Validation

- 81 Flutter tests passed; analyzer reports no issues.
- Tests cover activated idle completion, native-ready stale progress, correct
  connection labels, guarded retries, pair replacement while a dialog is open,
  live retained setup confirmation and no receipt-derived readiness.
- 1055 JVM tests /180 suites pass; app and HAL release lint/build pass.
- Native policy tests reject activation-only and unobserved legacy completion,
  fresh pairing and malformed identity; observed terminal health is accepted.
- Graph updated:20,120 nodes/48,981 edges/847 communities.

## Task-local change counts

Baseline `/tmp/watch-setup-finish-before` was captured before edits. The workspace
has no Git checkout; counts compare these snapshots, excluding earlier work.
Ordinary source edits used apply_patch. Formatter deltas are included below.

| File | Added | Deleted |
| --- | ---: | ---: |
| Companion lib/l10n/app_en.arb | 4 | 0 |
| lib/providers/watch_connection_provider.dart | 8 | 0 |
| lib/models/watch_connection_state.dart | 17 | 0 |
| lib/widgets/watch_connection_card.dart | 2 | 2 |
| lib/screens/connection/watch_connection_screen.dart | 75 | 31 |
| test/watch_connection_test.dart | 127 | 4 |
| Bridge core/.../OperationalSessionPolicy.java | 15 | 5 |
| core test/.../OperationalSessionPolicyTest.java | 18 | 0 |
| Companion pubspec.yaml | 1 | 1 |
| Bridge app/build.gradle | 2 | 2 |
| app/.../BridgeSetupEngine.java | 6 | 0 |
| app/.../BridgeIdentityStore.java | 3 | 2 |
| app/.../CompanionSessionState.java | 1 | 1 |
| **Source total** | **279** | **48** |

Generated localization: app_localizations.dart +24/-0 and
app_localizations_en.dart +13/-0. Generated Android local.properties +2/-2
for version values. pubspec.lock unchanged. Binary APKs/screenshots have no
source line counts. This document and SUMMARY.md use visible apply_patch edits.

Generated graph counts include the dated snapshot rotation in this operation:

| File under graphify-out/ | Added | Deleted |
| --- | ---: | ---: |
| .graphify_labels.json.sig | 1 | 1 |
| graph.html | 4 | 4 |
| GRAPH_REPORT.md | 553 | 478 |
| .graphify_labels.json | 95 | 76 |
| manifest.json | 60 | 30 |
| graph.json | 6719 | 6185 |
| cache/stat-index.json | 1 | 1 |
| 2026-10-06/GRAPH_REPORT.md | 498 | 488 |
| 2026-10-06/.graphify_labels.json | 84 | 73 |
| 2026-10-06/manifest.json | 60 | 30 |
| 2026-10-06/graph.json | 6683 | 5778 |
| 15 new one-line AST cache files | 15 | 0 |
| **Graph total** | **14773** | **13144** |
