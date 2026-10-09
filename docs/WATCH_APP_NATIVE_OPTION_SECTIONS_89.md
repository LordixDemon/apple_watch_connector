# Original generic option rows — stage 89

The Watch.app parity and subsequent performance goal remains active. The
human-owned blue/circular California is retained. All five observed faces,
including the selected Flux, are preserved. This work uses transient simulator
prototypes and unsaved phone drafts, without physical face mutations.

## Evidence

`probe_native_edit_sections.m` now records the original collection and option
`swatchStyle`, option class, and optional `--companion` factory geometry. Signed
style `-1` is preserved as the native sentinel, rather than an unsigned maximum.
The simulator guard and volatile English locale remain in place. Geometry is
captured after option enumeration: changing every option while a native renderer
observes the face triggers unavailable resource paths in some simulator bundles.
The final probe exits successfully and observes 70 transient families. Earlier
failed research runs changed neither production assets nor physical devices.

Of the 68 production generic sections, 57 match the watchOS reference's **entire
baseline customization and every option's complete customization**, in the same
order. Eleven sections are unavailable on the current iOS prototype device:
Kaleidoscope content/style, Galleon detail/content/night, Leghorn
style/position/night, and Foghorn content/style/night. Their layouts are not
inferred from other families. The comparison is retained in
`native-option-sections-89.json`; its geometry is explicitly detached from a
window and is not promoted to production layout metadata.

iOS 26.6 source is retained in `ios266-native-option-cell-89.asm`. The generic
factory chooses vertical for collection type 2 and horizontal otherwise. The
horizontal cell class is NTKCFaceDetailEditOptionCell. Actual native style sizes
on the expanded iOS simulator include 42⅓-square small circles, 90⅔-square large
circles, 71⅓-square small rectangles, and 90⅔ × 113⅓ large rectangles. The row
also accounts for explanation text, outline padding, and multi-line labels;
these are not interchangeable fixed text-button dimensions.

Native child labels use 12-point bold inactive and heavy active fonts. Their
selected background and outline belong to separate native components. The
image comes from `swatchImageForEditOption:mode:withSelectedOptions:refreshHandler:`,
not from a generic full-face snapshot.

The new simulator-only `probe_native_option_swatches.m` exercises that exact
provider. Activity Analog Rich Rings/Subdials each return 90⅔-point, 3× native
swatches. Changing the full context from Multicolor to Blue changes 12,421 and
12,638 pixels, respectively, out of 73,984. These four **research-only** PNGs
remain under `/tmp/watch-original-89`; none are shipped. Default artwork cannot
represent all selected colors, and existing full-face images cannot be silently
reused as exact editor swatches. A context-aware swatch renderer, attached row
capture, all remaining sections, and full interaction parity remain necessary.

## Implemented behavior

The generic Flutter row now follows the source-backed visibility rule already
used by the native color strip: center the initial/offscreen choice, preserve
even partial visibility. Replacing a section with the same selected token but
a different option order also updates the reveal. Queued reveals validate the
section, selected token, ticket and horizontal lifecycle before using a scroll
controller. This prevents a stale callback from acting on a replaced row.

Text geometry is retained only for the row's last labels, viewport, text style,
scaler, direction and locale. A selection-only rebuild reuses it; changes to
translated labels or font/viewport settings invalidate it. No global cache or
new assets are introduced. This removes repeated measurement work but does
not establish an end-to-end performance improvement. The stage 85 frame build
regression and matched hardware profiling remain open.

Final focused validation: 44 Flutter tests passed, three existing Python
section-builder tests passed, and Flutter analysis reported zero issues.
The native probes compile with ARC, Wall/Wextra/Werror and their final runs
exit successfully. Tests cover centering, partial visibility, reordered section
identity, translated/scaled text, exact selection, disabled actions, editor
transactions and uncertain delivery. Earlier test invocations named absent
files; the final run uses verified existing paths and passes.

The updated probe also compiles for watchOS Simulator and runs on the existing
booted Ultra prototype. All 57 watchOS families retain identical complete
baselines, section identities and option customizations against the old capture.
The temporary iPhone simulator is restored to Shutdown; the preexisting watch
simulator remains Booted. No probe app bundle is installed.

## Installed verification

Ordinary Companion 1.0.83/code 84 builds in 39.3 seconds, 246,976,557 bytes,
SHA-256 `7481249e5575067749ef2e57415f08e3899491664c223a11246648a75473ee65`.
All 20,126 managed native assets match source, the APK signature verifies, no
diagnostic fixtures/profile mode are packaged, and ZIP gaps above 1,000 bytes
are absent. Installation succeeds and the phone reports version 1.0.83/code 84;
the separate diagnostic package is absent.

On the physical phone, a new unsaved California draft initially centers its
California numeral choice. After scrolling, selecting the partially visible
Chinese option sets its selected semantics and updates the actual style preview
without shifting any of the four visible option bounds. The draft is discarded
with Back; Add to Watch is never pressed. This tests row behavior, not thumbnail
or full-screen parity. Screenshots and XML remain under `/tmp/watch-original-89`.

The phone returns to My Watch. The final stored five-face snapshot is byte-for-
byte identical to the before-install capture, SHA-256
`634aa6da495b07b7403539a1ef98b2236d47902c7404c290d3742945c2fce41d`,
including archives, order and selected Flux. It retains the prior observation
timestamp and does not prove a fresh Watch ACK. The receipt is
`/tmp/watch-original-89/physical-final-receipt.json`.
