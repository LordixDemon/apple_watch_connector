# Health defaults mirror — 375 / 6 October 2026

Scope remains the full active26groups/9phases goal, not just Health. Activated
Watch7,5/23S303 pair is preserved. Bridge0.2.375(575), Companion1.0.7(8).
This implements actual local typed value merge and authenticated read, **not**
native receive-state acceptance, clinical sample import or complete Health sync.

## Native evidence

Read-only WatchHealth262/HealthDaemon project and original23S303 image. All11
retained files still match374 size/hash; only `/tmp/aw-375-watch-working.a2s`
was supplied as writable disassembler cache, audited and removed after analysis.

| Native operation | Address/evidence | Confirmed behavior |
|---|---|---|
| Entity receive gate |1c64f499c/defaults-validation.log|HDNanoSyncStore canRecieveSyncObjectsForEntityClass returns1 |
| Engine data application |1c6486a5c/entity-apply-result.asm|decode original objects, invoke real consumer; consumer result1 enters error handling |
| Error handling |1c6486b98..6d24/entity-apply-result.asm|HK1403 resets received anchors; HK123 journal-full fails; other native ignorable errors can report bug and return success. No such suppression is copied to Android storage errors |
| Anchor read |1c64b421c/anchor-read.asm|failed read transaction returns-1, not an empty accepted anchor |
| Successful nil anchor row |1c64b8fec/anchor-read-row.asm +anchor-default-data.log|selects1f692b320; isa1efbac710 is Foundation NSConstantIntegerNumber, encoding`i` at1c65e147f, zero integer payload. No accepted local anchor store initialized in375 |
| Defaults categories |1c5dedc94/1c5dee338/defaults-category.asm|unprotected1, protected105 |
| Dictionary validation |1c646ea38/defaults-validation.log|category required; each key and timestamp required |
| Domain normalization |1c646c4b0..4c0/defaults-domain.asm|load original domain; nil selects CFString1f686b108 `""` before insertion. Presence is preserved in original PB, effective key namespace uses empty string |
| Value decode |1c618e710/default-value.asm|double→int64→string→raw NSData→nil; missing value becomes NSNull/tombstone at caller |
| Timestamp decode |1c618e7d0/default-date.asm|presence retained; NSDate reference seconds, not Unix/milliseconds |
| Insertion/update policy |1c646c750/defaults-insert.asm|updatePolicy2, sync provenance/legacy or resolved concrete sync identity |
| Date merge |1c646bf0c..c048/defaults-merge.asm +date-row.asm|SQL key/domain/category with stored mod_date>=incoming; matching row disables replacement. Read/SQL failure aborts instead of becoming empty lookup |
| Locked database path |1c646cf34/1c646d384/sample-default-updates.log|inaccessibility handler creates real key/value journal entries;375 does not claim full native journal/protection semantics |

Full logs are `health-375-native-{data-entry-anchors,entity-consumers,
storage-consumers,sample-default-updates,defaults-merge,defaults-validation}.log`.
Foundation class exported address confirmed directly from matching23S303 DSC in
`health-375-native-anchor-number-class.log`. Standalone fixup lookup produced no
class match; this empty attempted lookup is not evidence. Source/sample/device
consumer provenance, source transformation and concrete sync identities were
also traced; their complete materialization remains open.

## Implementation boundaries

`NativeHealthDefaults` owns the original dictionary/entries, preserves repeated
key order and full int64/double values, exposes typed values without byte/archive
coercion, and closes/wipes ownership. Missing keys/dates are held; nonfinite
dates are a conservative local hold, not a claimed native error. Double values
retain their original bits. Absent domain normalization is exactly the native
nil→empty behavior; an absent value remains a tombstone, not zero/empty data.

`HealthDefaultsRecord` stores the complete original dictionary plus an8-byte
version/selector header. Selected entry cannot escape its owned dictionary.
Length-prefixed UTF-8 domain/key encoding avoids separator collisions. Raw
dictionary syncIdentity is retained without resolving/adopting it.

`HealthDefaultsDatabase` maintains private `health-defaults-mirror-v1.db`,
SQLite version1/FULL transactions/bounded4096rows/16MiB. It uses separate Android
Keystore AES256/HMAC aliases, a five-UUID+version scope, entity isolation, original
variant/object authentication and unique scoped domain/key rows. Missing keys
on an existing DB refuse regeneration. A healthy empty lookup can insert;
corrupted rows/read/schema/key/SQL failures cannot turn into an empty date.
Newer values replace; equal/older values and tombstones retain their prior date.
Late invalid dictionaries or lost current-epoch eligibility roll back the batch.

Service writes only for current eligible manager preflight + supported speculative
defaults control plan; it rechecks current pair/profile/epoch before commit.
Historical replay may read/query original observations and mirror but cannot
write mirror values. No accepted receive anchors/sequence/native reply/grants,
child concrete identities, Android settings or clinical measurements are updated.
Full-message/per-change native manager transaction and response semantics still
need to be joined to real consumers; mirror is deliberately separate from them.

## Validation and actual phone results

- Final975JVM tests/164suites, zero failures/errors/skipped; lintDebug,
  assembleDebug and assembleDebugAndroidTest PASS. Final build log:
  `health-375-gradle-domain-final.log`. Nineteen new JVM tests exercise presence,
  precedence, signed-width/double bits, dates, tombstones, selectors/lifetime,
  domain normalization/key framing/AAD and safe logging.
- Separate platform Instrumentation test APK:20 real Android SQLite probes PASS
  in `health-375-domain-final-android-sqlite-probes.log`. Test-only random scope/
  keys/synthetic defaults/cache DB; production Watch data/stores/keys untouched.
  Native nil→empty namespace collision, ordered duplicate merge, resurrection,
  scopes/categories, rollback on late invalid input/epoch end, missing-key refusal,
  reopened read and tampered-ciphertext refusal all exercised.
- Initial19-probe runner called finish before finally, leaving one synthetic
  cache DB16384B/journal0B. Fixed cleanup-before-finish; exact owned leftover
  removed, subsequent19- and20-probe runs left no test DB. Production hashes
  remained unchanged throughout. The failed cleanup is recorded, not hidden.
- First stop13:30:24.069/rootexit0 at25.032; install575. First reconnect start
 13:31:05.280; corrected test rerun was preceded by another clean stop.
- Next start13:32:38.049; seventh actual Watch wave13:33:10.896–11.254 grows
  encrypted inbox44→51. Six object-bearing changes pass actual current-epoch
  manager preflight32.309–34.988. Source/Device plans project native code100
  unsupported speculative; these are local plans, not sent errors/Watch errors.
- Protected defaults actual mirror at13:33:33.466: replaced1/retained0/records1/
  integer1. Ordinary2dictionaries initially roll back due to absent domain.
  Native shape has explicit-domain1pair and absent-domain3pairs; protected1pair.
  All category/key/date/finite-date/schema checks otherwise match. Contents are
  not logged. Nil-domain handling fixed from ASM, JVM and real SQLite probes;
  the next fresh ordinary Watch packet remains required for hardware write proof.
- Intermediate authenticated restart query13:38:38.466 and final query
 13:42:48.262 both read mirror1integer. Final ordinary old replay stays stale;
  no old packet is made current after reconnect. Final start13:42:27.768 has
  setup=false/activation=false/freshPairing=false. Pre-install exactRootHAL0 and
  rootexit0 at13:41:18.896 verified before final installation/instrumentation.
- Original observation query13:42:48.208:13rows,6Quantity/1Category definitions,
  6canonical units/finite values, no original unit/value, zero unrecognized.
  Source1/Device2/defaults2/protected1/Quantity6/Category1 retained; seven sample
  UUIDs remain observations, not accepted clinical data. Final replay51twice.
- CompanionUID actual Binder probe13:43:56.792 connected=true. Old357face
  projection retained, observedAt1791259810919; this is not a new face fetch.
  Bridge26649/exactRootHAL26729/Companion14205, root role matched by exact NUL
  argument in `health-375-final-process-verification.json`.

| Retained phone file | Bytes | SHA256 |
|---|---:|---|
| defaults mirror |16384|161a92a425e851a0d6e94b0bbc38364414c27c4f7f23d819b72cdb1471bdf59e|
| original observations |24576|7b2fd2d2460873949808a4425a95a01a793067dcbb4c2bfbde803015bbef19d5|
| finished Restore |266|a289aa18f379b5a040e4a81be288437dcf868d1874e978dfe6c46705a4f5ca6c|
| EXPIRED Changes |191|89bc7fe7a735fb836d147b4acb499bce44211e4d5953197a60380789beec44dc|

Final installed APK13632563B/SHA256
`bb2d0eefaab7032aee85bf78d7c4f1bafea6c211f5720297c88cc0c37b867e14`,
matching `app-0.2.375-defaults-mirror.apk`. Test APK94344B/SHA256
`0d35e3f5b8b3de2c7aa51e7ff7fcf76d7f421cf8abdb013b28b64c080b107f73`.
Earlier first-live and shape-interim APKs retained separately; final native
write proof occurred on first-live APK with the same mirror backend and old
absent-domain hold, not on a fictional fresh final replay.

## Remaining full-scope work

Native consumer concrete identities/provenance/Source/Device/Quantity/Category,
data+sequence/received/validated transaction, manager partial failure/status,
obliteration history, durable new Changes session/correlated outgoing replies,
authorization/grants, clinical query/conversion/Companion/Health Connect remain
open. Defaults mirror/probe counts do not close G14 or unrelated feature groups.
All26groups/9phases remain active;251unique IDs/R195/I45/H8/E3 unchanged.

Source graph17655nodes/45234edges/745communities,93preexisting zero-node warnings,
3self-loops, no missing/dangling/duplicate/collapsed candidate edges. Documents
were not semantically rebuilt. Baselines:379source/doc/tool files and graph copy
before375 edits; actual task-only numstat/manifests saved as generated375 reports.
