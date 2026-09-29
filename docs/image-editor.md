# Image editor: feature catalog and delivery scope

**Status: A implementation is present; focused native qualification passed. Broader device/UI gates remain pending.**
The branch adds native image contracts, Compose tools, private staging, versioned
image drafts and tagged queue jobs, verified FFmpeg export, and an isolated direct
instrumentation runner. Host/build evidence is recorded below. A passing build is
not proof of physical output, accessibility or vendor compatibility.

All 47 A requirements have production consumers. The 83 B/C requirements remain
**Planned** backlog and appear only in the collapsed roadmap. Missing codecs or
filters are **Unavailable** for that build; unqualified input color/animation is
**Blocked for this image** with an actionable reason.

## Read in order

1. This catalog: **130 requirements in 13 groups**.
2. [Native UI and processing design](superpowers/specs/2026-09-29-image-editor-design.md).
3. [Ordered implementation plan](superpowers/plans/2026-09-29-image-editor.md).
4. [Isolated tests and direct ADB contract](../testing/image/README.md).

**A — first usable release:** basic still-image editing and trustworthy export.
**B — capable editor:** richer corrections, selections, layers and batch workflows.
**C — optional specialist work:** expensive formats, compositing and AI; not a
prerequisite for A. These are product choices, not a claim that every editor must
ship every specialist feature.

The smallest end-to-end milestone is import → orientation-correct crop/resize →
PNG export → independent decode/verification. Complete the other A requirements
before advertising the first release. Never expose a planned control as working.

## 1. Sources and inspection

| ID | Feature | Phase | Required behavior |
| --- | --- | --- | --- |
| SRC-01 | File/document import | A | Open JPEG, PNG and still WebP through Android document URIs; inspect bytes, not extensions. |
| SRC-02 | Android Share import | A | Accept image shares without discarding an existing unsaved draft. |
| SRC-03 | Multiple-image import | B | Separate source identities and edit snapshots; no implicit collage. |
| SRC-04 | Direct image URL | B | Reuse the URL-ingestion workstream with bounded transfer and content inspection. |
| SRC-05 | Clipboard image import | B | Copy granted image content into private staging; do not assume a permanent URI. |
| SRC-06 | Image information | A | Show dimensions, bytes, detected format, alpha, orientation, bit depth and known color profile. |
| SRC-07 | Orientation normalization | A | Handle all eight EXIF orientations exactly once. |
| SRC-08 | Animated/multipage detection | A | Reject animation/multiple frames explicitly until an intentional frame workflow exists. |
| SRC-09 | Damaged/large input handling | A | Reject truncation, overflow and unsupported dimensions before unbounded allocation. |
| SRC-10 | Replace/relink source | B | Check identity/dimensions and ask before reapplying edits to different content. |

## 2. Geometry and canvas

| ID | Feature | Phase | Required behavior |
| --- | --- | --- | --- |
| GEO-01 | Free crop | A | Drag handles plus exact source-pixel coordinates and reset. |
| GEO-02 | Crop ratios | A | Free, Original, 1:1, 4:3, 3:2, 16:9, 9:16 and custom ratio. |
| GEO-03 | Quarter-turn rotation | A | Clockwise/counterclockwise 90-degree steps, including portrait dimensions. |
| GEO-04 | Horizontal/vertical flip | A | Independent toggles with deterministic transform order. |
| GEO-05 | Straighten/free rotation | B | Numeric angle, horizon guide and explicit expand-versus-crop policy. |
| GEO-06 | Resize | A | Pixels, percentage and longest edge; aspect lock; no upscale by default. |
| GEO-07 | Resampling choice | B | Nearest for pixel art and qualified photographic algorithms; display the effective choice. |
| GEO-08 | Canvas/padding | A | Exact canvas size, nine-point anchor, transparent or chosen solid padding. |
| GEO-09 | Perspective/deskew | B | Four-corner document correction and reversible numeric controls. |
| GEO-10 | Lens/warp corrections | C | Distortion, chromatic aberration and mesh deformation with separate qualification. |

## 3. Light and color adjustments

| ID | Feature | Phase | Required behavior |
| --- | --- | --- | --- |
| COL-01 | Brightness | A | Neutral by default; bounded display-referred adjustment, not mislabeled exposure. |
| COL-02 | Contrast | A | Defined midpoint/range; no automatic clipping hidden from the user. |
| COL-03 | Saturation | A | Neutral at 1; zero gives deterministic grayscale; retain alpha. |
| COL-04 | Gamma | A | Defined transfer operation, numeric value and reset. |
| COL-05 | Exposure | B | Exposure stops in a documented linear-light pipeline. |
| COL-06 | Highlights/shadows/white/black | B | Separate tonal regions with clipping feedback. |
| COL-07 | White balance | B | Temperature/tint and neutral-point eyedropper; no claim to recover RAW data from JPEG. |
| COL-08 | Vibrance and selective HSL | B | Controlled skin/color response and individual hue ranges. |
| COL-09 | Levels and curves | B | RGB/luminance endpoints, editable curves and channel controls. |
| COL-10 | Monochrome/color grading | B | Channel-mixed monochrome, split toning and reversible color balance. |

## 4. Detail and creative effects

| ID | Feature | Phase | Required behavior |
| --- | --- | --- | --- |
| FX-01 | Sharpen/unsharp mask | A | Amount and radius; zero bypasses; no transparent-edge halos. |
| FX-02 | Gaussian blur | A | Bounded radius; predictable preview/output scaling. |
| FX-03 | Noise reduction | B | Separate luminance/chroma controls and detail comparison. |
| FX-04 | Clarity/local contrast | B | Bounded edge-aware adjustment, separate from ordinary contrast. |
| FX-05 | Dehaze | B | Explicit algorithm and clipping/halo tests. |
| FX-06 | Vignette | B | Center, radius, feather and strength with reset. |
| FX-07 | Grain | B | Amount/size plus deterministic seed persisted with the edit. |
| FX-08 | Presets/filter strength | B | Preview then explicit apply; preset expands into editable settings. |
| FX-09 | LUT import | B | Validated size/domain and color-space intent; never execute imported expressions. |
| FX-10 | Stylized effects | C | Posterize, threshold, duotone, edge and selective-focus effects as optional modules. |

## 5. Markup and drawing

| ID | Feature | Phase | Required behavior |
| --- | --- | --- | --- |
| ANN-01 | Text labels | A | Editable text, size, color, alignment and wrapping; preserve Unicode. |
| ANN-02 | Arrows and lines | A | Editable endpoints, width and arrowhead; remain sharp at output resolution. |
| ANN-03 | Rectangles/ellipses | A | Separate stroke/fill/opacity and editable bounds. |
| ANN-04 | Freehand pen/highlighter | B | Stroke smoothing and independent opacity without changing underlying pixels. |
| ANN-05 | Solid redaction | A | Opaque pixel replacement in flattened output; no original thumbnail or edit payload exported. |
| ANN-06 | Blur/pixelate region | B | Explicitly a visual-obscuring effect, not a secure-redaction guarantee. |
| ANN-07 | Text/image watermark | B | Anchoring, margin, opacity and optional repeat; never enabled by default. |
| ANN-08 | Imported image/sticker | B | Preserve transparency, source identity and transform controls. |
| ANN-09 | Object selection/editing | A | Move, resize, reorder, duplicate and delete basic annotations after creation. |
| ANN-10 | Color picker/swatches | A | Numeric RGBA, recent swatches and clear transparency; canvas background is separate. |

## 6. Selections and masks

| ID | Feature | Phase | Required behavior |
| --- | --- | --- | --- |
| SEL-01 | Rectangle/ellipse selection | B | Select pixels without confusing the selection with a crop. |
| SEL-02 | Freehand/polygon lasso | B | Add/remove points and close/cancel explicitly. |
| SEL-03 | Wand/color-range selection | B | Tolerance, contiguous switch and live mask preview. |
| SEL-04 | Selection operations | B | Add, subtract, intersect, invert, select all and deselect. |
| SEL-05 | Edge refinement | B | Feather, grow/shrink and edge smoothing with numeric radius. |
| SEL-06 | Paintable masks | B | Brush/erase with hardness, size, opacity and undo. |
| SEL-07 | Gradient masks | B | Linear/radial placement and feather controls. |
| SEL-08 | Mask inspection | B | Overlay, isolated grayscale view and enable/disable without deletion. |
| SEL-09 | Local adjustments | B | Bind corrections to a saved mask; reuse the global effect semantics. |
| SEL-10 | Assisted subject selection | C | Editable model-produced mask with manual correction and truthful backend reporting. |

## 7. Layers and composition

| ID | Feature | Phase | Required behavior |
| --- | --- | --- | --- |
| LAY-01 | Layer stack | B | Names, thumbnails, visibility and lock controls. |
| LAY-02 | Layer ordering | B | Move, duplicate, delete and bounded undo. |
| LAY-03 | Layer transforms | B | Translate, scale, rotate, flip and reset without destroying source pixels. |
| LAY-04 | Opacity/blend modes | B | Normal, multiply, screen and overlay first; define color/alpha math. |
| LAY-05 | Layer/clip masks | B | Separate masks from layer opacity and selection state. |
| LAY-06 | Adjustment layers | B | Reusable non-destructive corrections with bypass. |
| LAY-07 | Groups/alignment | B | Group transforms, align/distribute and snapping guides. |
| LAY-08 | Merge/flatten | B | Explicit destructive project action; flattened export does not flatten the saved project. |
| LAY-09 | Collage/contact layouts | B | Explicit new composition with gaps, backgrounds and output dimensions. |
| LAY-10 | Linked/reusable assets | C | Relinkable source-backed objects and portable asset packaging. |

## 8. Retouching and restoration

| ID | Feature | Phase | Required behavior |
| --- | --- | --- | --- |
| RET-01 | Clone stamp | B | Source anchor, aligned mode and brush controls. |
| RET-02 | Healing/spot removal | B | Reversible stroke with visible source/result comparison. |
| RET-03 | Red-eye correction | B | Local pupil adjustment without changing unrelated colors. |
| RET-04 | Dodge/burn brush | B | Exposure-like local editing with bounded accumulation. |
| RET-05 | Local sharpen/blur | B | Masked application with the same detail semantics as global effects. |
| RET-06 | Background removal | C | Refine cutout edges and preserve alpha; no silent cloud submission. |
| RET-07 | Object removal/inpainting | C | User selection, result preview and undo; keep generated content distinguishable. |
| RET-08 | Deblur/super-resolution | C | Optional model, explicit scale, comparison and no invented detail guarantee. |
| RET-09 | Scratch/compression repair | C | Separate restoration algorithms and evidence for actual improvement. |
| RET-10 | Content-aware fill/resize | C | Explicit composition-changing action, never an upload-fit fallback. |

## 9. Preview and editing workflow

| ID | Feature | Phase | Required behavior |
| --- | --- | --- | --- |
| UX-01 | Zoom/pan/fit/100% | A | Pinch, buttons and numeric zoom; 100% means actual output-pixel inspection. |
| UX-02 | Before/after | A | Hold Original and split comparison; identify source versus rendered edited preview. |
| UX-03 | Undo/redo | A | Bounded command history; one slider gesture is one undo step. |
| UX-04 | Non-destructive draft | A | Original remains unchanged; edits are versioned instructions. |
| UX-05 | Autosave/recovery | A | Restore valid last draft; preserve and report corrupt/unsupported documents. |
| UX-06 | Local/global reset | A | Reset a tool, section or all edits with explicit scope. |
| UX-07 | Preview backgrounds/grid | A | Checkerboard, light/dark/custom background and thirds grid; never baked in implicitly. |
| UX-08 | Histogram/pixel inspection | B | RGB/luminance, clipping and sampled RGBA with clear working-space labels. |
| UX-09 | Responsive accessible UI | A | Phone/tablet layouts, 52 dp controls, large text, TalkBack and keyboard equivalents. |
| UX-10 | Preview responsiveness | A | Cancel superseded renders; no stale image presented as the current edit. |

## 10. Export and optimization

| ID | Feature | Phase | Required behavior |
| --- | --- | --- | --- |
| EXP-01 | JPEG/PNG/WebP export | A | Per-build capability checks, correct MIME/extension and independently decoded output. |
| EXP-02 | Image quality controls | A | Codec-specific lossy quality; do not call PNG compression a lossy-quality slider. |
| EXP-03 | Lossless export | A | PNG and qualified lossless WebP; distinguish encoding losslessness from unchanged source pixels. |
| EXP-04 | Target-byte fitting | A | Decimal limit, strictly smaller final file, maximum seven attempts from the original. |
| EXP-05 | Dimension/alpha constraints | A | Preserve exact requested geometry and alpha unless the user explicitly permits changes. |
| EXP-06 | JPEG flatten background | A | Require explicit flatten consent for alpha-bearing content and preview the chosen background. |
| EXP-07 | Measured export preview | B | Show decoded candidate, measured bytes and effective settings, not only an estimate. |
| EXP-08 | Save/share/collision handling | A | Save a copy; verify before publication; do not overwrite originals or existing results. |
| EXP-09 | Output variants | B | Several explicit format/size targets from one immutable edit snapshot. |
| EXP-10 | HEIF/AVIF output | C | Independently qualify still-image encoder, container, color and metadata support. |

## 11. Metadata and color integrity

| ID | Feature | Phase | Required behavior |
| --- | --- | --- | --- |
| META-01 | Strip ancillary metadata | A | Default removal of location, EXIF/XMP/IPTC descriptions, thumbnails and embedded edit history. |
| META-02 | Selective metadata retention | B | Allowlisted date/copyright/author fields; location requires an explicit separate choice. |
| META-03 | Metadata viewer | B | Separate technical color/orientation information from personal/camera tags. |
| META-04 | sRGB handling | A | Qualified 8-bit SDR sRGB path; label untagged assumptions and block unqualified conversions. |
| META-05 | Profile conversion | B | Convert pixels between supported profiles; never substitute relabeling or blind ICC stripping. |
| META-06 | Wide-gamut editing | C | End-to-end Display P3/wide-gamut preview, edits and export qualification. |
| META-07 | High precision/HDR | C | 16-bit/float processing and explicit tone mapping, with display-independent verification. |
| META-08 | Ultra HDR gain maps | C | Preserve/adjust valid gain maps or explicitly create SDR; never retain stale gain data. |
| META-09 | Print size/resolution | B | DPI/PPI metadata and physical units without silently resampling pixels. |
| META-10 | CMYK/soft proof | C | Profile-aware print workflow; not required for ordinary phone image exports. |

## 12. Batch operations and automation

| ID | Feature | Phase | Required behavior |
| --- | --- | --- | --- |
| BAT-01 | Batch conversion | B | Per-file outcome and immutable job settings; continue/cancel policy is explicit. |
| BAT-02 | Copy/paste edits | B | Select groups to copy and validate normalized geometry against each destination. |
| BAT-03 | Saved edit recipes | B | Versioned, bounded and inspectable; unsupported active edits block export. |
| BAT-04 | Batch naming | B | Tokens, extension handling and collision preview; no path traversal. |
| BAT-05 | Queue controls | A | Stop/current-file progress; never invent video duration for a still. |
| BAT-06 | Resource limits | A | Shared worker ownership, bounded memory/disk and cancellation cleanup. |
| BAT-07 | Battery/thermal policy | B | Consume the settings workstream's real policy; no duplicate monitor or fake mid-file resume. |
| BAT-08 | Device test commands | A | Separate instrumentation APK using direct ADB and production processing. |
| BAT-09 | Capability/error reports | A | Requested versus effective route, native identity and actionable unsupported reasons. |
| BAT-10 | Removable test tooling | A | Test source sets/fixtures remain outside shipping sources and removable without product changes. |

## 13. Specialist interoperability and AI

| ID | Feature | Phase | Required behavior |
| --- | --- | --- | --- |
| PRO-01 | Camera RAW development | C | Demosaic and sensor-specific handling; embedded JPEG preview is not RAW editing. |
| PRO-02 | TIFF/high-bit-depth interchange | C | Explicit page, compression, precision and profile support. |
| PRO-03 | Animated-image editing | C | Frame selection, timing, disposal/blend rules and loop count; no accidental flattening. |
| PRO-04 | Video-frame extraction | B | Exact chosen frame/time and color policy; connect to the video editor rather than duplicate it. |
| PRO-05 | Layered file interchange | C | Capability table for PSD/OpenRaster-style imports/exports; warn before unsupported features flatten. |
| PRO-06 | SVG/vector/PDF raster import | C | Restricted renderer, explicit resolution/page and no external-resource execution. |
| PRO-07 | OCR/document cleanup | C | On-device text extraction, deskew and readable scan output; optional model/runtime. |
| PRO-08 | Assisted enhancement | C | Auto suggestions are previewed settings, not mandatory edits applied on import. |
| PRO-09 | NPU/GPU effect execution | C | Measure full pipeline and numerical/visual parity; CPU fallback only when policy allows. |
| PRO-10 | Panorama/HDR/focus merge | C | Explicit multi-image workflow with alignment/ghosting tests, separate from basic transcoding. |

## Delivery and coordination

Implement A in the ordered plan; B/C are retained backlog, not empty interactive
buttons. Use `Planned`, `Unavailable` and `Blocked for this image` distinctly.
Keep Home minimal and place tools inside **Edit image**. Keep original inputs,
queue snapshots and the existing 10 MB upload goal intact.

Coordinate with editor #2, audio #3, settings #4 and acceleration #5/#6. Their
branches are separate and changing: re-read their current implementations before
resolving overlapping models, queue schemas or Gradle source sets. This branch deliberately merges the reviewed audio/Share foundation at `3082e1c`.
It uses tagged queue schema 3 and reads main schema 1 plus that foundation’s audio
schema 2. Other open branches also use schema 3 with different meanings: they are
not interchangeable. Unknown tags/shapes fail without overwriting saved data.
The eventual integration must unify these envelopes explicitly; this branch does
not merge the other drafts.

## A implementation and qualification evidence

Every row below has an implemented production consumer. The direct native/Android
suite passed 26/26 on OnePlus LE2125, API 36, arm64, 4 KiB pages at production
revision `ea886a2`. Host tests cover pure contracts and persisted data. The matrix
describes implemented behavior; separate UI/accessibility/API and 16 KiB runtime
gates below remain pending.

| ID | Production consumer | Behavior/evidence |
| --- | --- | --- |
| SRC-01 | `ImageInputAdapter / MediaFiles` | byte sniff, private granted-URI copy, full native decode |
| SRC-02 | `MainActivity / ImageInputAdapter` | image Share MIME and private staging; per-source drafts survive |
| SRC-06 | `ImageEditorPanel / ImageProbe` | dimensions, bytes, actual format, alpha, EXIF, precision and sRGB assumption |
| SRC-07 | `ImageGeometry / ImageDisplayAdapter` | all 8 matrices; explicit noautorotate; both platform display routes |
| SRC-08 | `ImageProbe` | APNG/WebP animation, GIF, MPO/multiple JPEG rejected before one-frame encode |
| SRC-09 | `ImageProbe / ImageValidation` | CRC/range checks, 64 MiB encoded and 40 Mpx ceiling; memory preflight |
| GEO-01 | `ImageCropPanel / ImageCanvas` | inverse-mapped handles, exact upright pixel fields and reset |
| GEO-02 | `ImageCropEditing / ImageCropPanel` | all named/custom ratios; persistent lock and bounded handle movement |
| GEO-03 | `ImageGeometry / ImageCropPanel` | clockwise/counterclockwise quarter turns |
| GEO-04 | `ImageGeometry / ImageCropPanel` | independent H/V transforms in defined order |
| GEO-06 | `ImageGeometry / ImageCropEditing` | pixel/percent/long edge; half-up rounding; height/width authority; no upscale default |
| GEO-08 | `ImageGeometry / ImageCropPanel` | exact canvas, all nine anchors and RGBA padding |
| COL-01 | `ImageEffects / ImageAdjustPanel` | bounded encoded-sRGB brightness and neutral bypass |
| COL-02 | `ImageEffects / ImageAdjustPanel` | defined midpoint contrast, numeric entry/reset |
| COL-03 | `ImageEffects / ImageAdjustPanel` | defined luminance saturation, zero grayscale, alpha separate |
| COL-04 | `ImageEffects / ImageAdjustPanel` | defined gamma transfer, numeric entry/reset |
| FX-01 | `ImageEffects / ImageAdjustPanel` | float RGB Gaussian unsharp amount/radius with premultiplied edge policy |
| FX-02 | `ImageEffects / ImageAdjustPanel` | bounded Gaussian sigma in output pixels and proxy scaling |
| ANN-01 | `ImageMarkupRenderer / ImageMarkupPanel` | system-font StaticLayout, Unicode, wrapping and alignment |
| ANN-02 | `ImageMarkupRenderer / ImageMarkupPanel` | editable line/arrow endpoints and output-resolution strokes |
| ANN-03 | `ImageMarkupRenderer / ImageMarkupPanel` | rectangle/ellipse bounds, stroke/fill and opacity |
| ANN-05 | `ImageMarkupRenderer / ImageMetadata` | last opaque output-space replacement; no source/project metadata |
| ANN-09 | `ImageMarkupPanel` | select/move/resize/order/duplicate/delete and keyboard arrows |
| ANN-10 | `ImageMarkupPanel / ColorField` | numeric RGBA and recent colors; separate preview background |
| UX-01 | `ImageCanvas / ImagePreviewRenderer` | pinch/pan/buttons/numeric zoom; bounded actual-pixel regions |
| UX-02 | `ImageCanvas` | Original label/hold and labeled proxy split; split disabled for an actual-pixel region |
| UX-03 | `ImageHistory / TranscodeViewModel` | 100 commands / 8 MiB; one slider/drag commit per gesture |
| UX-04 | `ImageEditDocument / ImageJobSpec` | immutable versioned edits; original hash checked before/after every candidate |
| UX-05 | `ImageDraftRepository / JobCodec` | atomic off-main saves, revision fences, corrupt/future data preserved |
| UX-06 | `ImageEditorPanel / tool panels` | tool/section/all reset and explicit dirty-close choices |
| UX-07 | `ImageCanvas` | checker/light/dark/custom and thirds are display-only |
| UX-09 | `ImageEditorPanel / FormaScreen` | phone panel, wide left rail + 320 dp inspector, 52 dp actions and keyboard labels |
| UX-10 | `ImagePreviewController` | shared 32 MiB cache reserve, 150 ms debounce, superseded cancellation/join, immutable revision/key fence |
| EXP-01 | `ImagePlanner / ImageVerifier` | qualified encoder/demuxer/muxer/pixel-format/filter routes; full decode |
| EXP-02 | `ImageExportPanel / ImagePlanner` | JPEG qscale mapping, WebP quality, PNG compression effort |
| EXP-03 | `ImagePlanner` | PNG RGBA and lossless WebP BGRA; no implied untouched pixels |
| EXP-04 | `ImageFitPolicy / ImageTranscoder` | strict finalized bytes; <=7 distinct attempts, always from original |
| EXP-05 | `ImageGeometry / ImageVerifier` | exact geometry and graph-derived alpha hash; resize fit only on explicit consent |
| EXP-06 | `ImagePlanner / ImageExportPanel` | explicit opaque JPEG flatten color; same visual preview graph |
| EXP-08 | `ImageTranscoder / MediaFiles` | decode before atomic publication; original/private URI guard and new observably empty Save destination only |
| META-01 | `ImageMetadata` | strip PNG/JPEG/WebP ancillary data; retain own technical sRGB/orientation only |
| META-04 | `ImageProbe / ImageEffects` | 8-bit SDR sRGB; label assumptions; ICC/CMYK/HDR/uncalibrated blocked |
| BAT-05 | `TranscodeService / ProgressView` | shared Stop owner and named image stages without duration/ETA |
| BAT-06 | `ImageValidation / RunCoordinator` | bounded memory/disk, one native owner, cancellation cleanup |
| BAT-08 | `testing/image/android` | separate instrumentation APK, opt-in named direct runner and fresh reports |
| BAT-09 | `ImageExportDiagnostics / ImageEditorPanel` | requested/effective route, quality/dimensions/bytes/attempts and native identity |
| BAT-10 | `Gradle imageTests switch` | test-only sources; absent testing/image product-only release build |

The source-built image bundle requires upstream `--enable-lib-android-zlib` and
`--enable-lib-libwebp` with the repository’s unchanged source/licensing pins.
Video/audio payload availability alone does not qualify image decoding. The
previous payload failed a real PNG decode and is not counted as a pass. The first
image-capable device run at `9d48f5f` passed 20/26; EXIF fixture ICC, transparent
blur edges, markup thread budgeting and active verification cancellation failed.
The corrected `ea886a2` pair passed 26/26 in fresh run
`e606eaf0-35fc-46e7-85e6-4e35ff69155c` (JUnit OK 1, zero skipped cases).
Its source hashes, prepared routes/arguments, decoded output and installed APK
identity were verified by the root agent. The prior report remains a historical
failure.

Host gates: 140 JVM tests (core 77, engine 20, app 43), zero failures/skips;
matching lab APK assembly and debug lint;
39 CLI core checks and 14 Android/native-build contract tests. A product-only
release archive builds with `testing/image` physically absent and
`-PimageTests=false`; release DEX/assets contain no image scenario, fixture,
report or test UI classes, and native ELF/APK payload checks pass. These checks do
establish packaging/exclusion only; native processing has the separate run above.

Saved records require explicit nullable intent keys. Frozen image queue jobs keep
requested Auto separate from their resolved codec; main schema 1 and audio schema
2 migrate without changing bounded trim or recognized audio parameters. Earlier
unreleased image snapshots lacking original URI/resolved format fields are
preserved as unsupported/corrupt rather than assigned fabricated intent.

PNG gAMA/cHRM tags must match standard sRGB values (45455 and the standard sRGB
chromaticities); explicit non-sRGB values and ICC profiles remain blocked. Spatial
operations use float premultiplied RGB, then quantize once for the encoder.
Preview reserves worst-case PNG, metadata rewrite and alpha/markup scratch
storage before rendering; Fit scales its proxy to that budget; when an old
preview cannot share the budget, it returns to labeled Original while updating.
Actual-pixel preview can be unavailable for large outputs; reduce output size or
use Fit. Its cached full rendered graph and displayed region are both bounded.

Verified image-native Maven AAR SHA-256:
`75d5f5c1b7c2e11160589f87343dd1c73b1eaaa092790e32e52a36583d91c693`.
Qualified debug app APK SHA-256:
`11ae673c3567231f7efa678679d7099b3daf50f477a68e18b66b60df4a1a1083`;
matching 26-case test APK:
`e1598569bbefa73a72dac9c76e59fc9034f52732cefbfba7c5060041df66dd1f`.
Product-only unsigned release APK SHA-256:
`1492640830a9476ec72952d39884c4d6b9640ef525ef6a98441fb04099215990`;
it was built from `ea886a2` with image tests physically absent, retains product
image rendering, and contains no test runner/fixture/report/UI/provider entries.

The pending WebP-focused extension runs lossless/lossy alpha variants inside
`alpha_geometry`; the existing 26-case pass does not itself qualify those variants.
Parent-owned provenance/reports are retained under `vendor/pr-readiness/pr8` and
`vendor/pr-readiness/pr8-native-provenance.json` outside source commits.

Pending root-owned qualification: WebP-focused extension; ordinary native/Compose
regressions; Compose large-text,
TalkBack, keyboard, lifecycle/Share/Save flows; API 26/27 and another vendor when
available; actual 16 KiB page-size execution. Root alone owns ADB and retains run
reports/artifact hashes. The phone pass above is limited to the stated device,
revision, artifacts and cases.

## Primary references

The inventory is a proposed Forma product scope. GIMP's tool taxonomy was used
as a breadth cross-check, not a requirement to port GIMP or add its dependencies.
Android format availability and FFmpeg build availability must be checked
separately. See the design for technical implications.

- [GIMP tool taxonomy](https://docs.gimp.org/3.0/en/gimp-tools.html).
- [Android image formats](https://developer.android.com/media/platform/supported-formats).
- [Android ImageDecoder](https://developer.android.com/reference/android/graphics/ImageDecoder).
- [FFmpeg filters](https://ffmpeg.org/ffmpeg-filters.html).
- [AndroidX ExifInterface](https://developer.android.com/reference/androidx/exifinterface/media/ExifInterface).
- [Editing Ultra HDR](https://developer.android.com/media/grow/ultra-hdr/edit).

Sources reviewed September 29, 2026. Online documentation may describe newer
APIs than this repository's API 26 minimum/SDK 36 build or its pinned FFmpeg.
