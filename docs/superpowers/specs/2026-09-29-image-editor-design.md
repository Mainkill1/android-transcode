# Native image editor design

**Status:** proposed implementation contract. No image-editor production code or
Android test runner is added by this design draft.

**Intent:** add useful basic image editing to Forma's existing select → inspect →
edit → convert workflow, while retaining a broader capable-editor roadmap.
The [catalog](../../image-editor.md) defines A/B/C scope. A contains 47 requirements;
B/C must not delay the first real still-image export.

## 1. Repository fit and alternatives

Baseline inspected: `89ceb3b362872f193a54feacc99225c80e77f176`.

| Existing location | Integration decision |
| --- | --- |
| `core/src/main/kotlin/dev/forma/core/Models.kt` | Add an image-specific payload; do not fake positive video duration. |
| `core/src/main/kotlin/dev/forma/core/Planner.kt` | Keep AV behavior; dispatch images to a dedicated `ImagePlanner`. |
| `engine-ffmpeg/src/main/kotlin/dev/forma/ffmpeg/FfmpegBridge.kt` | Add a typed image `prepare` overload; every attempt passes through it. |
| `app/src/main/kotlin/dev/forma/app/data/FfmpegTranscoder.kt` | Reuse staging, worker ownership, cancellation and verified publication. |
| `app/src/main/kotlin/dev/forma/app/data/JobCodec.kt` | Deliberate versioned migration; preserve existing queue payloads. |
| `app/src/main/kotlin/dev/forma/app/TranscodeViewModel.kt` and `ui/FormaScreen.kt` | Add image routing and a focused inspector, not a second home screen. |

Use Kotlin/Compose UI, Android-independent core contracts and the existing
in-process FFmpeg export owner. Android decoding/Canvas are adapters for display,
metadata and markup planes, not a replacement product encoder.

An Android Bitmap-only exporter would be smaller initially but would split the
export contract and violate the repository's FFmpeg requirement. A full external
editor engine would add dependency/licensing and migration cost. The selected
approach extends the current modules and starts with a deliberately small graph.
No new module, FFmpeg source pin, native build profile or dependency version is
silently mandated by this document.

Open PRs #2/#3/#4/#5/#6 already overlap core models, queue serialization and tests.
The implementation agent must inspect their current heads and select one shared
queue/graph integration. Do not copy their older descriptions as current code or
merge those branches without review. Image work can start independently on main;
its integration tests must later cover the combined branch.

## 2. Native layout and interaction

Home remains the existing upload-first screen, including the chosen 10 MB goal.
After a supported still is inspected, show its preview and **Edit image** beside
More settings. Do not show trim brackets, audio controls, video FPS or bitrate.

| Area | Phone layout | Wide layout at 840 dp or more |
| --- | --- | --- |
| Header | Back, filename, Undo, Redo, overflow | Same actions above canvas |
| Canvas | Large pinch/pan preview with Fit/100% and Hold Original | Center canvas with the same controls |
| Primary tools | Scrollable labeled Crop, Adjust, Markup, Export | Same tools in a narrow left rail |
| Inspector | One scroll-owning bottom panel; expands for numeric input | 320 dp right inspector with one scroll owner |
| Footer | Current dimensions, format, upload limit and primary Convert | Same summary; current native work remains stoppable |

**Crop:** ratio chips; pixel rectangle; clockwise/counterclockwise; H/V flip;
resize pixels/percent/longest edge; aspect lock; canvas size/anchor/background.
Straighten, perspective and lens controls remain Planned until their B/C work.

**Adjust:** brightness, contrast, saturation, gamma, Gaussian blur and sharpen.
Each slider has a numeric entry, neutral marker, reset and short unit/help text.
A slider gesture commits one history entry, not one per pointer event.

**Markup:** text, arrow, line, rectangle, ellipse, solid redaction; object list
for selection/order/duplicate/delete; contextual size/fill/stroke/color controls.
Drawing, stamps, watermarks and layers enter through More tools when implemented.

**Export:** Auto/JPEG/PNG/WebP; available quality/lossless controls; output size;
allow resize to fit; preserve alpha or explicitly flatten over a chosen color;
metadata policy; save destination; effective capability/error explanation.
The preview background and export flatten background are visibly separate.

Use existing 52 dp components; maintain TalkBack names, selected states, value
announcements and focus restoration. Keyboard equivalents include Undo/Redo,
arrow-key object movement and numeric crop fields. At 200% text scale all actions
must remain reachable. Closing a dirty draft offers Save draft, Discard and
Cancel. Opening tools/Advanced or changing upload goal must not reset edits.

Only A controls that have a real consumer may be enabled. Planned controls are
shown in a collapsed roadmap, not as dozens of dead sliders. A feature can be
Implemented but Unavailable in this build, or Blocked for this image; show why.
Inherit theme/defaults from #4 when integrated; freeze resolved job values.

## 3. Pure models and persistence

Create pure models under `core/.../image/`. No Bitmap, Context or Android URI type
crosses into core. Model names below are the proposed shared interfaces.

| Type | Required fields/semantics |
| --- | --- |
| `ImageInfo` | Encoded width/height, detected format, EXIF orientation, known frame count, bit depth, alpha state, profile/gain-map state, source hash and byte length. Unknown facts stay unknown. |
| `NormalizedCrop` | Finite left/top/right/bottom in canonical oriented source coordinates; default 0,0,1,1. |
| `ImageAdjustments` | Brightness 0, contrast 1, saturation 1, gamma 1, blur/sharpen 0. |
| `ImageAnnotation` | Stable ID, kind, canonical coordinates, text/style and ordering; no raw FFmpeg snippets. |
| `ImageOutputPolicy` | Format, codec-specific quality/lossless, geometry, padding/alpha choice, metadata policy and nullable `targetBytes`. |
| `ImageEditDocument` | Schema 1, source identity, revision, crop, quarter turns, flips, adjustments, annotations and output policy. |
| `ImageJobSpec` | Job ID and deep-frozen document/source reference; retries do not mutate it. |
| `QueueJobSpec` | Tagged `Av(JobSpec)` or `Image(ImageJobSpec)` payload; a still never becomes a synthetic video. |
| `ImageAttempt` | Attempt index, actual format/quality/dimensions, renderer identity and requested-document hash. |
| `ImagePlan` | Validated attempt, typed graph, expected geometry/alpha/color and required capabilities. |

Bounds: quarter turns 0..3; brightness -1..1; contrast 0..2; saturation 0..3;
gamma 0.1..3; Gaussian sigma 0..20 final-output pixels; unsharp radius 1..5
integer final-output pixels and amount 0..2 (amount 0 bypasses). The default
unsharp radius is 1. All numeric values must be finite. Reject unknown active operations,
invalid enums, wrong types and malformed coordinates at load/queue/prepare.
Retain unknown saved data for recovery, but do not silently drop it or overwrite
it with a default. Defensively copy lists before storing snapshots.

Use a maximum 1 MiB serialized image document, 128 annotation objects and 4096
Unicode code points per text object for A. History is bounded to 100 committed
commands and 8 MiB estimated serialized state, evicting oldest entries. Autosave
is off-main-thread, revision-checked and atomic; restore the last valid document
without auto-starting interrupted exports.

Queue envelope schema selection belongs to the integration commit: read main's
schema-1 queues and every already-merged schema-2 variant before choosing the next
unused envelope version. The image document's schema 1 is independent. Never
assume all open branches' schema 2 means the same payload. Keep migration fixtures.

## 4. Geometry, color and alpha contract

Normalize EXIF orientation **once**, before user-space operations. For the native
FFmpeg source path disable implicit autorotation and apply the explicit orientation
mapping; a decoder adapter that already normalizes must report that fact instead
of applying another transform. Test all eight orientations, including mirrors.

Canonical coordinates refer to the full upright original. Convert crop bounds to
pixels using floor(left/top × dimension), ceil(right/bottom × dimension), then
validate a nonempty in-bounds half-open rectangle. The fixed A order is:

`oriented source → crop → user clockwise quarter turns → H/V flips → resize → pad`

Annotation coordinates remain source-relative and are transformed by the same
matrix; crop hides outside objects without deleting them. The crop UI inverse-maps
screen handles back to source space, including quarter-turn ratio changes.
Resize preserves aspect by default, uses round-half-up for the derived dimension
and never imports video even-dimension rules. Odd-size PNGs remain odd-size PNGs.
Padding cannot shrink the image and uses one of nine explicit anchor positions.

Reference case: upright 1200×800, crop (0.25,0.25,0.75,0.75) → 600×400;
clockwise quarter turn → 400×600; longest edge 300 → **200×300**.
An identity 101×77 PNG must remain 101×77, not 100×76.

A supports a qualified 8-bit SDR sRGB/RGBA path. Untagged supported RGB inputs are
explicitly labeled assumed sRGB. Do not claim arbitrary ICC, CMYK, 16-bit PNG,
HEIC, AVIF or Ultra HDR conversion works because a decoder accepts the bytes.
Detect and block unqualified color/gain-map inputs before processing; B/C adds
conversion/preservation with separate evidence.

For normalized encoded sRGB RGB channels C, A adjustment math is defined as:
`C1 = clamp((C - 0.5) * contrast + 0.5 + brightness, 0, 1)`;
`C2 = pow(C1, 1 / gamma)`;
`Y = 0.2126*R2 + 0.7152*G2 + 0.0722*B2`;
`C3 = clamp(Y + saturation*(C2 - Y), 0, 1)`.
This is display-referred correction, not exposure or scientific linear-light
color processing. B exposure must be a different operation.

The compiler may use qualified RGB LUT/matrix primitives; neutral adjustments
must omit the processing block. Do not blindly reuse a video `eq`/YUV420 graph.
A PNG geometry-only identity must retain decoded pixel values. Non-neutral pixel
math tests allow at most one 8-bit code value of implementation rounding.

Keep alpha separate from RGB corrections. Blur/resampling must use an explicit
premultiplication policy to prevent fringes; alpha and RGB are transformed
consistently. Premultiply only at adapter boundaries that require it, and clear
hidden RGB when unpremultiplying alpha zero. No implicit RGB565 or YUV420P path.
Lossy formats have separately documented codec tolerances, not pixel-exact claims.

## 5. Processing ownership and preview

Production flow:

`stage original → inspect/validate → prepare attempt → orient/crop/transform →`
`base adjustments/detail → map and composite markup → optional alpha flatten →`
`FFmpeg still encode → metadata finalization → full decode/verify → publish copy`

All stages run within the existing process-wide worker/cancellation ownership.
Every attempt calls the new image overload of `FfmpegBridge.prepare`; arguments
are tokens, not a shell string. Still exports select exactly one image, disable
unneeded audio/subtitle/data streams and have no synthetic duration or frame rate.
Animation inspection must happen before limiting output to one frame.

Basic text/shapes are rasterized by an Android Canvas/StaticLayout adapter into a
private transparent plane at requested output resolution, then composited in the
FFmpeg-owned graph. The same layout code is used for rendered preview and export.
Never export a screenshot of Compose. Capture font/style identity, bounds and
Unicode shaping; no implicit font download or unreviewed font redistribution.
Solid redaction is a final opaque replacement above other content. Flattened
exports never embed the project, original thumbnail or source attachment.

Edited preview is produced by the same typed graph, with a maximum 1600-pixel
long edge for the interactive proxy. Label it Preview, not final quality. A 100%
inspection request renders the relevant output-resolution region or a bounded
full image; never stretch a proxy and call it actual pixels. Display-only handles
may update immediately, but transient approximation is marked Updating until the
matching render arrives. Debounce at 150 ms, cancel superseded work, and key cache
entries by source hash + document revision/hash + renderer + output size/color.
A completed older render cannot overwrite a newer revision.

The original preview is explicitly Original. It is not an edited preview simply
because a crop outline or color slider appears over it. Full export takes worker
priority; preview does not seize or cancel another job's native session.

## 6. Formats, size fitting and metadata

A import: JPEG, PNG and single-frame WebP. A export: PNG, JPEG and WebP only when
encoder/muxer/pixel-format support is actually present. Auto prefers qualified
WebP; otherwise alpha-bearing content uses PNG and opaque content uses JPEG.
Explicit format selection never silently switches format. Missing native payload
is Unavailable, not a Bitmap-only fallback. AV1 video encode support is not proof
of an AVIF still-image export route.

Defaults: JPEG quality 90, WebP quality 80, PNG lossless compression level 6.
Quality is a per-codec UI scale, not a common perceptual metric: JPEG requires a
qualified qscale mapping; WebP uses its supported quality/lossless controls.
PNG offers compression effort, not an invented lossy-quality mode.

Retain [upload-size contract](../../upload-limits.md): default 10,000,000 bytes;
custom 32,000..2,000,000,000 integer bytes; null explicitly means no size guarantee.
After metadata finalization, require `0 < actualBytes < targetBytes`.
Every retry rereads the immutable original and reapplies every edit.

A bounded proposed candidate ladder has at most seven unique attempts:
quality multipliers `[1,.9,.8,.7,.6,.5,.4]`, geometry multipliers
`[1,1,.85,.75,.65,.55,.45]`. Round dimensions without upscaling; quality floor is
`min(initialQuality,35)`. Quality changes apply only to lossy encoders. Geometry
changes apply only with visible **Allow resize to fit** consent; explicit exact
size disables that permission until the user reenables it. Derive both image and
padding canvas dimensions from the selected attempt scale. Deduplicate candidates.
A lossless/no-resize request may have only one eligible attempt and may fail.
Do not change format, alpha, crop, annotations, lossless intent or metadata policy
to manufacture success. This ladder is bounded policy, not an optimal-size claim.

Exhaustion yields Cannot fit these constraints; offer a larger limit or explicit
quality/resize change. Cancellation, corrupt media, missing codec, storage failure
and verification failure are not size-fit retries. Never truncate with `-fs` or
encode the previous lossy candidate.

Default removal covers personal/ancillary EXIF/XMP/IPTC, original thumbnails and
edit payloads. Required color signaling is a separate technical policy: tag the
qualified sRGB output correctly rather than copying a source profile. Normalize
orientation to 1 or omit the tag after pixels are normalized. Selective metadata
retention is B; ExifInterface's writing support is format-specific. Measure and
verify the final file after all metadata writes, not the earlier encoder artifact.

## 7. Limits, verification and release boundaries

Keep the reference's **40,000,000-pixel source ceiling**, checked with overflow-safe
arithmetic; it is an upper limit, not a promise every device can edit 40 MP.
Estimate peak decoded buffers, markup planes, scratch and output before each
operation, account for native allocations, and reject work above the runtime
budget. Limit preview cache to 32 MiB and one current plus one in-flight render.
Do not allocate full-resolution history bitmaps. Low-memory devices may have a
lower effective limit, disclosed before queueing. Check free storage for source,
all simultaneously live scratch and output; retain at most one candidate.

Verification must prove: actual format/MIME; exactly one decoded image; planned
dimensions; correct alpha capability and representative alpha pixel assertions in
tests; color/orientation policy; clean metadata; nonzero bytes and strict cap;
unchanged original; no output published on failure or cancellation. Codec exit
zero or a successful header probe alone is insufficient.

Cancellation waits for native teardown before buffer/file cleanup or slot release.
Process death produces Interrupted, never automatic continuation of partial output.
Use stage progress for stills, and indeterminate progress where no honest fraction
exists. Core tests, API-only compilation, emulator UI, native packaging and real
phone output are five separate evidence categories.

## 8. Framework references and qualification rules

- [FFmpeg filters](https://ffmpeg.org/ffmpeg-filters.html) provides geometry, RGB
  LUT/matrix, blur/sharpen and composition primitives. Query the pinned build:
  online availability does not establish packaged filter or pixel-format support.
- [FFmpeg image2](https://ffmpeg.org/ffmpeg-formats.html#image2-2) documents still
  image muxing. Qualify one-frame output and actual codec/container combinations.
- [Android ImageDecoder](https://developer.android.com/reference/android/graphics/ImageDecoder)
  starts at API 28; retain a sampled BitmapFactory adapter for API 26/27. Decode
  off the UI thread; mutable processing requires compatible software allocation.
- [Android formats](https://developer.android.com/media/platform/supported-formats)
  is a platform baseline, not proof of the application's FFmpeg import/export path.
- [ExifInterface](https://developer.android.com/reference/androidx/exifinterface/media/ExifInterface)
  reads more formats than it writes; documented writing is JPEG/PNG/WebP.
- [Ultra HDR editing](https://developer.android.com/media/grow/ultra-hdr/edit)
  requires gain-map handling consistent with edits. Keeping a stale map is not
  preservation; A blocks this path instead of silently promising HDR support.
- [Storage Access Framework](https://developer.android.com/guide/topics/providers/document-provider)
  supplies URI-based access. Persist eligible grants or privately copy transient
  shares; do not convert arbitrary content URIs to filesystem paths.

No Media3-only image export, RenderScript requirement, cloud upload, Python device
controller, test HTTP endpoint, bundled AI model or scheduled automation. GPU/NPU
are optional measured effect backends, not generic encoders or prerequisites.
