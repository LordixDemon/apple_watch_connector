# Native face configuration correction and gallery performance — 1.0.48+49

Contour previously offered an invalid default and misleading background choices.
The firmware probe compared every edit mode with the unnormalized initial face:
decoding its legacy `content: style 1` changed that field to `on`. Subsequent color
edits were incorrectly collected as background options, yielding 157 rows and
overwriting the ON label with color names.

The read-only research probe now round-trips resource-free prototypes through
NTKFaceConfiguration before extraction and restores the complete normalized
baseline before each option. Tokens are collected once; production profiles reject
duplicate tokens instead of silently replacing their labels. No family-specific
translation of the invalid token is added. Re-extraction changed only Contour's
configuration/options/headings; every other production profile stays exact.
Its background choices are ON/OFF and the native heading is DIAL COLOR.

Families without a customization dictionary also resolve exact previews now.
Absent means the canonical empty style; explicit null, strings and arrays stay
invalid. Python generation, the native verifier and Dart lookup agree. The native
Solar Graph prototype passed this path without adding guessed customization.

## Native rendering and compression

All 8,321 incoming requests passed actual NanoTimeKit apply/serialize/render checks,
including all 744 Contour combinations (two shapes × two backgrounds × 186 colors).
Eight local simulator renderer jobs completed in 52.89–53.09 seconds. The renderer
writes a bounded rejection report per input file; this distinguishes unavailable
families, native normalization, resource requirements and snapshot failures.

Coverage is now 20,894 exact configuration keys, including every declared option
combination of 35 of the 49 resource-free profiles. Twenty-nine families gained
complete coverage. This does not mean every live complication or family is covered:
the remaining 14 profiles have 72,883 declared combination keys without an exact
configured preview. Default samples remain explicitly distinguished.

The 8,286 incoming unique lossless WebPs initially occupied 181,594,850 bytes.
Factoring 6,542 of them into 47 shared native pixel planes reduced that subset from
108,420,906 to 38,949,291 bytes. Images without a worthwhile exact factor remain
WebP. No interpolation, reconstructed artwork or approximate color matching is used.
Python verifies every changed pixel; the independent production Dart codec verifies
all 16,793 indexed frames and 99 shared maps against their encoded and native RGBA
hashes before promotion. All 12,573 previous keys and all 10,351 previous image/map
files retain their exact references and bytes.

Final managed image/map data is 165,817,523 bytes across 18,684 files. The expanded
APK is larger than 1.0.47; compression does not imply a smaller total application.
The existing lazy 256-shard catalog and bounded worker/cache architecture remain.
Simulator language and locale were saved, switched for English generation, restored
and verified after reboot. Physical Watch locale was not changed.

## Physical gallery performance

The gallery's translucent Cupertino navigation bar repeatedly blurred the scrolling
scene, despite the list sitting below SafeArea. Only this gallery bar now uses the
opaque system background, disabling that unnecessary backdrop filter.

The physical OnePlus profile ran the production gallery with a transport that
refuses every Watch command. Each variant had two warmup cycles and three rounds
of ten open/full-scroll/close cycles, at a reported 90 Hz:

| Measurement | Translucent bar | Opaque bar |
| --- | --- | --- |
| Build p95, ms | 2.994 / 3.053 / 3.056 | 3.000 / 3.035 / 3.020 |
| Raster p95, ms | 19.175 / 19.168 / 19.166 | 3.010 / 3.065 / 3.198 |
| Build budget overruns | 0 / 0 / 0 | 0 / 0 / 0 |
| Raster budget overruns | 1421 / 1384 / 1385 | 0 / 0 / 0 |

Final image cache: 47 entries / 4,275,496 bytes; catalog cache: 47 shards / 697,177
encoded bytes, 47 memo entries. Pixel decoder: five decodes, zero errors, worker
p95/max 15.666 ms outside the UI thread, five compressed maps / 151,791 bytes.
All queues drained. This workload excludes user gestures, uploads, Watch IPC and
long-term soak; it does not establish zero jank throughout the application.

## Actual Watch verification

The ordinary release replaced profiling builds. Add through the phone UI produced
a fresh four-face native observation at 1791404964258, with the owned Contour selected
and exact CREAM / ON / REGULAR settings. Applying NEON GREEN / OFF / ROUNDED yielded
fresh observation 1791405050777 and exact native tokens:

```json
{"color":"seasons.fall2025.neonGreen","content":"off","style":"style 2"}
```

Only those three customization values changed. Original face configurations,
relative order and archives were preserved. These are committed physical native
readbacks, not an inference from IDS delivery acknowledgments. No photograph of
the physical display was taken; the phone image is the matched native style sample.

Original selection was restored at 1791405179345 before deleting only the recorded
test face. Final native observation 1791405282542 has exactly the original three
configurations, order and selection, with the test face absent. Both archive maps
are empty; this does not test resource archives. The phone remains on Manage Watch
Faces with enabled actions. HAL PID19119 and Bridge version remain unchanged.
There were no Watch setup/reset/re-pair/reboot or physical locale operations.

Private diagnostic
baselines, operation identity, native PNGs and profile logs are under
`/tmp/native-preview-coverage-51`; physical captures are under
`/tmp/native-intent-types-49`. They are not shipped in assets.

## Validation and remaining goal

249 full Flutter tests passed after asset promotion. After the gallery bar change,
the analyzer was clean and all 21 existing native editor/creation/compact-screen
tests passed. Five Python catalog/generator tests passed. Final ordinary ARM64 APK:
201,464,258 bytes, SHA-256
`bda089be472840031128c2e44192f8b8f2950a2287f55dc5675e5dfc32194c0b`.
Its 18,941 root/index/image/map assets match the final source catalog exactly;
no test fixtures are packaged. Package manager confirms 1.0.48/code49.

The full original goal remains active: remaining configured families, populated
live complication previews/provider choices, further intent/entity schemas,
advanced Photos/resources, Smart Stack and operational Mac protocol parity.
