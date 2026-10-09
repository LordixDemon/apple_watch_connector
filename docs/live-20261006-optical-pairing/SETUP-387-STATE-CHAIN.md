# Setup state audit: Bridge 0.2.387 / Companion 1.0.16

The working Watch pair is retained. The owner reported two completed setups
whose phone screen still claimed an active setup stage. This audit changes the
public progress projection, not the activation/synchronization protocol.

## Cause

Bridge previously inferred progress from journal strings. `INITIAL NANO SETUP
START` was displayed as synchronization even though it begins registry/property
reading. `ACTIVATION WIRE REQUEST` selected Activating, but the activation
confirmation, correlated PrepareInitialSync response and completion publication
did not advance the UI. Only the strict `POST-COMMIT COMPLETE` health checkpoint
could select completion. That checkpoint needs physical Clock evidence and a
controlled reconnect, so a working Watch face could coexist with Activating.

Both Flutter surfaces also used `setupRunning` to show continuous activity. The
root host deliberately holds the Bluetooth/IDS connection after delivery;
ownership of that host does not prove that setup work is still progressing.
The installed 1.0.15 screen even retained `Request accepted. Waiting for the
watch…` beside an ordinary Connected card.

## Observed chain

| Public phase | Source observation | Phone meaning |
| --- | --- | --- |
| STARTING | New engine run resets its progress and transient fields | Starting the watch session |
| DISCOVERING | HAL LE scan active | Looking for a watch |
| CONNECTING | Watch target locked | Connecting to the discovered watch |
| PIN_REQUIRED | Current HAL PIN request, if PIN pairing is used | Enter the code; no work spinner |
| SECURITY | SMP/paired link encryption active | Establishing a secure pair |
| IDS | IDS control checkpoint | Connecting watch services |
| REGISTRY | Initial Nano setup starts | Reading watch configuration |
| CONFIGURING | Validated properties / pre-activation coordinator state | Applying watch settings |
| ACTIVATING | Coordinator arms an activation attempt | Activating with Apple |
| ACTIVATION_INPUT | Current activation challenge | Owner action required; no work spinner |
| ACTIVATED | Coordinator confirms activation | Watch activated; does not claim a visible face |
| SYNCING | Correlated initial-sync response, before completion publication drains | Synchronizing watch setup |
| WAITING_FOR_WATCH | Initial-sync preparation and completion publication sent, or coordinator Clock barrier | Initial synchronization sent. Check your watch; no work spinner |
| VERIFYING_RECONNECT | Actual Clock/setup observations reached the health barrier | Connection verification pending; no work spinner |
| VERIFIED | Strict coordinator COMPLETE, or explicit owner face confirmation saved for this activated pair | Watch setup complete |
| STOPPING | User/engine stop request | Disconnecting |
| STOPPED | Setup connection stopped | Setup connection stopped |
| FAILED | Protocol/host/cleanup/confirmation failure | Error; no continuing work spinner |

Post-commit phases are explicit bounded `WATCH_SETUP_PHASE_V1` stdout events
from the live HAL coordinator snapshot. They are consumed separately from the
journal. They are emitted even when a coordinator update has no follow-up
actions. Operational-mode sessions do not emit setup events.

`SetupProgressState` prevents late scan/security/registry events from regressing
the observed setup. Activation input can return to Activating. Stop/failure is
terminal until a new run resets the state. Late activation challenges cannot
restore credential fields after activation or stop. Repeated current challenges
publish updated fields even when the phase is unchanged.

## Completion and receipts

An outgoing send or APP_ACK never becomes physical completion. An owner can
confirm an already visible face directly during the retained setup connection.
Bridge stops that root session, requires exit 0 and completed Bluetooth/handoff
cleanup without a fatal result, re-reads and validates the exact activated pair,
then commits owner-confirmed operating mode. Failed cleanup/save stays Failed.
The idle confirmation path is retained. Legacy force-finish remains rejected.

Flutter uses one shared localized phase label for the card and setup panel.
Durable activation takes precedence over stale pre-activation Binder labels.
A completed operational pair does not keep showing an old setup phase. Real
progress clears queued command receipts; a receipt arriving after newer progress
cannot restore the old waiting message. Receipts cannot change pairing state.

## Verification

- Core: 1054 tests / 180 suites, zero failures/errors/skips.
- Real coordinator sequence asserts activation while locale status is pending,
  activated status, rejection of an unrelated preparation response, syncing
  after the matching response, and waiting after completion publication.
- Progress tests cover non-regression, terminal errors/stops, new-run reset and
  strict event decoding. Connection policy tests cover activated matching-owner
  confirmation and rejection of wrong pair/unactivated/operational ownership.
- Flutter: 65 tests pass; analyze reports no issues. Both setup surfaces replay
  all progress labels and activity indicators; owner confirmation is actionable
  during live setup; late queued receipts cannot imply completion.
- Bridge release build and app/HAL lint pass. Companion release build passes.
- Fresh setup on 387 has not been run against this already activated Watch.
  The existing pair will be used for installation/reconnect verification;
  preserving it avoids an unnecessary reset merely to exercise progress UI.

## Installed pair verification

Installed package versions: Bridge0.2.387(587), Companion1.0.16(17).
Operational Disconnect at20:19:28 closed HCI and the root exited0 at20:19:29.
Pair and bond containers had identical SHA256 before/after the package installs.
Ordinary Connect at20:22:46 used the same pair, reached IDS READY at20:23:05,
and received a native application response/ACK. The actual Flutter screen
shows Connected without the old request-waiting message or setup activity.
The screenshot is `setup387-connected.png` in this folder.

The final stale-challenge ID reset correction was installed after another clean
Disconnect at20:24:16, HCI close20:24:16 and root exit0 at20:24:17. The pair
container had legitimately changed during the preceding live reconnect, then
remained byte-identical across the final install, as did the bond container.
Final reconnect reached IDS READY at20:25:19, followed by matching native
APP_ACK/application response at20:25:20. Root PID9382 holds operational mode
for the same pair; Flutter again shows Connected without stale setup/waiting
text. `setup387-connected.png` now captures this final installed revision.
Final APK SHA256:

- Bridge: bede6e7bb0709676ae629144245c50f226cfc2ebb5ff0797c613d3a5bc19d02b
- Companion: 18abb070f744c0f9f3c0799aa1972c93b5698a8375992c70f1d6edc4baa869aa

Generated localization change, relative to a captured pre-generation baseline:
89 added / 4 deleted lines across two generated files. Formatting relative to
the immediately captured source baseline: 192 added / 109 deleted lines in four
files. These counts exclude earlier source changes and unrelated prior work.
