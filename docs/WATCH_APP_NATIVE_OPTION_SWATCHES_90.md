# Original option swatches: Activity Analog (stage 90)

The original Watch.app parity work remains incomplete. This stage produces checked
research components and native row measurements. It does **not** replace the
production text option buttons or claim coverage of other face families.
Companion on the phone remains 1.0.83/code84; no APK was built or installed here.

The user explicitly owns the blue/circular California: keep it. A read-only
capture of the stored five-face snapshot is byte-identical to stage 89:
SHA256 `634aa6da495b07b7403539a1ef98b2236d47902c7404c290d3742945c2fce41d`,
observedAt `1791484468615`. This is stored state, not a fresh Watch acknowledgment.
There were no physical face edits, selection changes, pairing or Watch commands.

## Native source and capture

The iOS 26.6 wrapper forwards to `_swatchImageForEditOption:mode:withSelectedOptions:`.
The actual Activity compositor is in the iOS 26.2 simulator's
`NTKActivityFaceBundleCompanion` Mach-O: provider at `0x5bf8`, drawing block at
`0x6128`. The retained disassembly is `ios-native-option-swatch-90.asm`.

The native provider composites original UIImage resources: ring, notches,
the chosen face details, date where applicable, and the second hand. It tints
separate masks with the actual color scheme. A white palette substitutes the
native orange foreground. The legacy `white` token also uses a different
source-alpha topology, so it requires its own component bases.

`probe_native_option_swatches.m` hooks the actual compositor in the short-lived
simulator research process. All four method ABIs are checked first. Sources
are exported with their original PNG bytes and SHA256 identities; draw order,
geometry, size, scale, orientation and native RGBA values are recorded.
Black/white bases are rendered by the original provider while preserving each
source's native alpha. No full-image PNG per shade is shipped.

Complete option customizations are obtained before creating a live face view.
Legacy serialized color names are matched through the native face model, rather
than being treated as pigment fullnames. `supportsSlider` determines whether
fractions are reachable in the original UI. Unsupported fractions are rejected.
The earlier 929-context experiments included unsupported shade requests and are
**not** the authoritative validation dataset.

## Authoritative results

The final supported-controls capture exits 0: 185 native colors, including
19 with sliders. Each slider has native references at 0/25/50/75/100 percent;
Blue also has independent references at 1/33/67/99 percent. Both style options
retain complete configuration identity: `detail=simple` (Rings) and
`detail=detailed` (Subdials), mode 11, family `type:activity analog rich`.

The builder validates 530 actual native provider images, with zero cache hits.
It produces 370 style/base entries and four NFCP programs totaling 127670 bytes.
Maximum premultiplied channel difference is 2/255. Alpha difference is zero;
pixels with no color-dependent component have zero difference. Native extended
sRGB values are retained in palette metadata and clamped at bitmap evaluation.
The gray pure-white endpoint retains an explicit native foreground branch.

Research files are in `/tmp/watch-original-90/activity-validated-programs/`.
The catalog, validation and four programs are retained under this document's
neighboring `native-option-swatch-components-90/` directory. They are research
artifacts, not production assets or a declaration of runtime UI coverage.

The attached iPhone scene capture uses the original generic section factory:
row height 163⅔pt, item width 104pt, item gap 6⅔pt, image 90⅔pt square at
`(6⅔, 23)`. The native selection outline is a circle with a 2⅔pt stroke;
the selected text has its own capsule. Actual row and layer geometry are
retained alongside the component catalog. The forced selection affects only
the transient collection child, never a face library.

The green accent is native: `NTKCActiveColor` in the iOS 26.6 code constructs
it directly; the attached iOS 26.2 row agrees. Its function is included in the
retained disassembly. It is not an inferred replacement for the app theme.

## Validation and remaining work

Both Objective-C probes compile with `-Wall -Wextra -Werror`. Native basis
guards reject a missing input (exit 2), an invalid role (exit 2), and a source
RGBA mismatch (exit 6). Relevant Python tests cover complete option identity,
source-byte/geometry changes, source pixel/scale consistency, ambiguous tint
roles, fixed alpha, extended gamut and interpolation precision.
All seven swatch tests and both existing component-program tests pass. Replacing
one Red reference with the original Blue image makes the complete builder fail
with `max=222` and produces no partial output directory. The simulator research
app was removed; the iPhone simulator was restored to Shutdown while the
preexisting standalone Ultra simulator remained Booted.
The retained generated evidence adds 8200 text lines, deletes zero text lines,
and adds four binary programs totaling 127670 bytes. Ordinary tool/source edits
are separate from those generated artifacts.

Production integration still needs a typed swatch catalog accepting fixed
native alpha and extended palette stops, strict mode/field/value identity,
shared creation/editor rendering, adaptive native row geometry, and device
validation of unsaved drafts. Never feed this research catalog into the current
component definition parser without adding those validations: its existing
schema requires opaque bounded stops and allows fractions for every entry.
Preserve `supportsShades=false` on the 166 native colors without sliders.

Other generic face families, the type-3 toggle path, and matched physical
performance verification remain part of the full goal. Stage 85's build-time
regression remains unresolved. No overall performance improvement is claimed.
