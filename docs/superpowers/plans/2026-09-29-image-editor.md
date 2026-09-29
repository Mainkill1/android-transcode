# Native Image Editor Implementation Plan

> **For agentic workers:** Use superpowers:executing-plans for inline execution,
> or superpowers:subagent-driven-development when independent agents are available.
> Execute and verify each task before claiming its checkbox is complete.

**Goal:** deliver the catalog's 47 A requirements as a native, non-destructive
still-image editor with verified FFmpeg exports and isolated direct-ADB testing.

**Architecture:** add pure image contracts/planning inside core; keep native
render/encode ownership in engine-ffmpeg and the existing service coordinator.
Compose and Android storage/markup adapters consume those contracts. Do not create
a parallel export service, web editor or test-only implementation of image edits.

**Tech stack:** existing Kotlin, Compose, coroutines, FFmpeg bridge; Android
BitmapFactory/ImageDecoder and Canvas/StaticLayout adapters; format-qualified
AndroidX ExifInterface only where needed. Preserve source/licensing pins.

**Spec:** [native image design](../specs/2026-09-29-image-editor-design.md).
**Catalog:** [image requirements](../../image-editor.md).
**Tests:** [direct ADB and verification](../../../testing/image/README.md).

**Current status:** this plan and the test manifest are proposed, not implemented.
The image classes, Gradle image-test switch and instrumentation commands below do
not exist on the inspected baseline. No checkbox is pre-completed by this PR.

## Global constraints

- Android API 26 minimum; SDK 36/JDK 17 baseline; no new module or implicit native pin change.
- Preserve Home, default 10,000,000-byte goal, separate queue and 52 dp controls.
- All attempts use original input and the image overload of `FfmpegBridge.prepare`.
- A input/output is qualified 8-bit SDR JPEG/PNG/still WebP; no fake duration or video even-size rule.
- Output must be nonempty and strictly below an enabled integer byte cap; maximum seven unique attempts.
- Source ceiling is 40,000,000 pixels; lower runtime memory limits remain enforceable.
- Image document schema 1; envelope migration must reconcile already-merged AV schemas.
- Test-only sources stay under `testing/image/`; direct ADB only; no production receiver/server.
- B/C features stay Planned; a native wrapper/API compile is not a phone export pass.

## Review focus

1. A mirrored camera image is not rotated twice: task 2/3 tests all eight orientations through both import adapters.
2. Small transparent logos retain odd dimensions and clean edges: task 3/4 tests pixel and alpha results, not just file existence.
3. A revoked Share URI or changed file never silently processes another source: task 2/9 tests staging identity and relink errors.
4. Markup and redaction must survive re-cropping and size-fit retries without leaking thumbnails: task 6/8 tests mapped geometry and final metadata.
5. Stop/process death/stale preview cannot publish an obsolete result: task 5/7/9 tests ownership, revision fencing and recovery.

## File ownership

Paths below are additions unless labeled Modify. Package roots are abbreviated
only in this table; task blocks give full paths.

| Area | Files and responsibility |
| --- | --- |
| Pure contracts | `core/.../image/ImageModels.kt`, `ImageValidation.kt`, `ImageGeometry.kt`, `ImageHistory.kt` |
| Pure planning | `core/.../image/ImagePlanner.kt`, `ImageEffects.kt`, `ImageFitPolicy.kt` |
| Native boundary | `engine-ffmpeg/.../image/ImageProbe.kt`, `ImageVerifier.kt`; Modify `FfmpegBridge.kt` and its concrete/decorating implementations |
| Android adapters | `app/.../image/ImageInputAdapter.kt`, `ImagePreviewRenderer.kt`, `ImageMarkupRenderer.kt`, `ImageDraftRepository.kt` |
| Queue execution | `app/.../data/ImageTranscoder.kt`; Modify `FfmpegTranscoder.kt`, `MediaFiles.kt`, `JobCodec.kt`, `QueueRepository.kt` and service dispatch |
| UI | `app/.../ui/image/ImageEditorPanel.kt`, `ImageCanvas.kt`, `ImageCropPanel.kt`, `ImageAdjustPanel.kt`, `ImageMarkupPanel.kt`, `ImageExportPanel.kt` |
| Tests only | `testing/image/{core,engine,app,android}/`; fixture generator and reports stay there |

## Task 1: Pure document, validation and test-source wiring

**Create:** `core/src/main/kotlin/dev/forma/core/image/ImageModels.kt`,
`ImageValidation.kt`, `ImageHistory.kt`.
**Test:** `testing/image/core/dev/forma/core/image/ImageModelsTest.kt`.
**Modify:** `core/build.gradle.kts` only for conditional test source references.

**Interfaces:** define the spec's document/value types and
`ImageProblem(code: String, field: String?, message: String)`,
`ImageValidation.validate(document: ImageEditDocument): List<ImageProblem>`,
and `ImageHistory.apply/undo/redo` returning immutable history/state.

- [ ] Add failing tests: full crop/neutral values validate; NaN/infinity/wrong numeric types do not; quarterTurns 4 fails; zero-area crop fails; source lists cannot mutate queued snapshots; undo/redo returns the previous revision; the 101st command evicts the oldest; the 8 MiB budget is enforced.
- [ ] Wire `testing/image/core` as a test-only source directory when `imageTests` is true, default true. Run `./gradlew :core:test --tests dev.forma.core.image.ImageModelsTest`; record the expected red failure before implementation.
- [ ] Implement bounded typed state and validation exactly as the spec. No Kotlin type in core imports Android/native APIs. Queue document size, object count and Unicode limits are validated before expensive processing.
- [ ] Rerun the focused suite; require all assertions pass and no skips. Commit `feat(image): add immutable image document and validation`.

## Task 2: Image inspection and URI import

**Create:** `engine-ffmpeg/src/main/kotlin/dev/forma/ffmpeg/image/ImageProbe.kt`;
`app/src/main/kotlin/dev/forma/app/image/ImageInputAdapter.kt`.
**Test:** `testing/image/engine/dev/forma/ffmpeg/image/ImageProbeTest.kt`;
`testing/image/android/dev/forma/app/image/ImageImportTest.kt`.
**Modify:** `app/.../MainActivity.kt`, `TranscodeViewModel.kt`, `data/MediaFiles.kt`.

**Interfaces:** `ImageProbe.inspect(localPath: String): ImageInfo`;
`suspend ImageInputAdapter.stage(sourceUri: String, jobId: String): StagedImage`
where `StagedImage` contains private path, source identity and inspected facts.

- [ ] Add failing cases: misleading extension, JPEG/PNG/WebP headers, PNG animation chunks, animated WebP, GIF, MPO/multiple-image input, truncated bytes, unknown frame count, 40,000,001 pixels, arithmetic overflow, unsupported profile/precision/gain map and changed input hash.
- [ ] Add Android cases for transient Share grants, document-provider access, revoked grants and process restart. Missing grants report `SOURCE_ACCESS`, not an empty successful image.
- [ ] Implement header/metadata inspection plus qualified native decode validation; explicitly reject animation before one-frame limiting. Never trust MIME, extension or decoded preview alone. Preserve EXIF facts and orientation-applied state at adapter boundaries.
- [ ] Run `./gradlew :engine-ffmpeg:testDebugUnitTest`; run the direct-ADB import class after Android wiring exists. Record host and device results separately. Commit `feat(image): inspect and stage still-image inputs`.

## Task 3: Geometry and typed still-image preparation

**Create:** `core/src/main/kotlin/dev/forma/core/image/ImageGeometry.kt`,
`ImagePlanner.kt`.
**Modify:** `engine-ffmpeg/src/main/kotlin/dev/forma/ffmpeg/FfmpegBridge.kt` and
all bridge decorators/concrete implementations, preserving AV overload behavior.
**Test:** `testing/image/core/dev/forma/core/image/ImageGeometryTest.kt`,
`ImagePlannerTest.kt`.

**Interfaces:** `ImageSize(width: Int, height: Int)`;
`ImageGeometry.resolve(info: ImageInfo, document: ImageEditDocument, attempt: ImageAttempt): ImageGeometryResult`
with `outputSize` and forward/inverse coordinate transforms;
`ImagePlanner.plan(info: ImageInfo, spec: ImageJobSpec, attempt: ImageAttempt, capabilities: Capabilities): ImagePlan`;
`suspend FfmpegBridge.prepare(spec: ImageJobSpec, actual: ImageInfo, attempt: ImageAttempt, input: String, output: String): List<String>`.

- [ ] Add failing geometry assertions: the specified 1200×800 crop/turn/resize yields `ImageSize(200,300)`; identity 101×77 remains unchanged; crop bounds floor/ceil correctly; padding anchors are exact; mirrored orientation matrices map known corners correctly.
- [ ] Add argument tests: tokenized distinct paths, one-frame still plan, explicit orientation policy, no `-t`, no forced FPS, no blanket YUV420P, no raw user expressions, missing required capabilities blocked.
- [ ] Implement fixed transform order, inverse hit testing and a still-image prepare overload. Concrete and cached/decorated bridges must preserve the overload; do not leave production dispatch calling an unqualified default.
- [ ] Run `./gradlew :core:test --tests 'dev.forma.core.image.Image*Test'` and existing AV planner regressions. Commit `feat(image): compile still-image geometry through the native bridge`.

## Task 4: Defined RGB corrections and detail pipeline

**Create:** `core/src/main/kotlin/dev/forma/core/image/ImageEffects.kt`.
**Test:** `testing/image/core/dev/forma/core/image/ImageEffectsTest.kt`;
`testing/image/android/dev/forma/app/image/ImagePixelTest.kt`.

**Interfaces:** `ImageEffects.compile(adjustments: ImageAdjustments, geometry: ImageGeometryResult): ImageEffectGraph`.
The graph identifies RGB/alpha handling, required filters and effect parameter units.

- [ ] Add red tests for neutral graph bypass, saturation-zero grayscale, numeric RGB golden vectors, alpha unchanged by color correction, zero-radius bypass and bounded radius/amount. Check the formulas in the spec with independently calculated expected pixels.
- [ ] Add native RGBA edge fixtures for blur/resize/sharpen and a pixel-art fixture to expose chroma subsampling. A geometry-only PNG must be pixel-exact; non-neutral RGB rounding tolerance is at most one code value before lossy encoding.
- [ ] Implement qualified RGB operations without inheriting video color/subsampling rules. Pin radius units and scale proxy parameters explicitly; block unavailable active effects rather than drop them.
- [ ] Run focused core and native pixel tests. Inspect actual output pixels/alpha, not only FFmpeg exit. Commit `feat(image): render RGB adjustments with alpha-safe detail effects`.

## Task 5: One-frame export, verification and queue integration

**Create:** `app/src/main/kotlin/dev/forma/app/data/ImageTranscoder.kt`;
`engine-ffmpeg/src/main/kotlin/dev/forma/ffmpeg/image/ImageVerifier.kt`.
**Modify:** existing queue/service/model/persistence dispatch and `MediaFiles.kt`.
**Test:** `testing/image/app/dev/forma/app/image/ImageExportTest.kt`.

**Interfaces:** `suspend ImageTranscoder.run(spec: ImageJobSpec, onState: suspend (JobState) -> Unit, onProgress: (ImageStageProgress) -> Unit)`;
`suspend ImageVerifier.verify(path: String, plan: ImagePlan): VerifiedImage`.
Add tagged `QueueJobSpec` handling and `ImageStageProgress` without synthetic time.

- [ ] Add failing tests for one-frame output, wrong format/dimensions, JPEG with unapproved alpha loss, missing native codec, empty output, damaged decode, metadata leakage and existing-result collision.
- [ ] Test cancellation during prepare/encode/verify and process death around publication. Output must not be marked complete before verified publication; native cleanup finishes before file deletion and worker release.
- [ ] Implement PNG first, then JPEG/qualified WebP; output metadata finalization and full decode precede publication. Preserve image/AV queue payloads with integration-base-specific migration fixtures. Active annotations remain blocked until task 8 supplies their renderer.
- [ ] Run app/engine JVM suites and real native PNG roundtrip through direct ADB. Record actual artifact identities. Commit `feat(image): export and verify still images in the shared queue`.

## Task 6: Strict target-byte fitting

**Create:** `core/src/main/kotlin/dev/forma/core/image/ImageFitPolicy.kt`.
**Test:** `testing/image/core/dev/forma/core/image/ImageFitPolicyTest.kt`;
`testing/image/android/dev/forma/app/image/ImageFitTest.kt`.
**Modify:** image execution loop and result diagnostics, not global defaults.

**Interface:** `ImageFitPolicy.candidates(spec: ImageJobSpec, info: ImageInfo): List<ImageAttempt>`.
Generate the spec's bounded deduplicated ladder; the executor chooses the first
verified fitting candidate and retains requested versus effective values.

- [ ] Add red assertions: `cap-1` succeeds, `cap` fails; cap bounds/types are exact; no more than seven attempts; lossless/no-resize deduplicates; exact geometry/alpha/format remains fixed without consent.
- [ ] Inject one valid oversized candidate to exercise retry deterministically. Verify each attempt calls prepare and reads the original hash; errors/cancellation do not become size retries.
- [ ] Implement the ladder and final-byte measurement after metadata writes. Exhaustion publishes nothing. An accepted output never mutates editor settings or already-queued jobs.
- [ ] Run focused host and native fit tests including incompressible transparent images and annotated output after task 8. Commit `feat(image): add bounded strict-byte image fitting`.

## Task 7: Native canvas, controls and honest edited preview

**Create:** `app/src/main/kotlin/dev/forma/app/image/ImagePreviewRenderer.kt`;
`app/src/main/kotlin/dev/forma/app/ui/image/{ImageEditorPanel,ImageCanvas,ImageCropPanel,ImageAdjustPanel,ImageExportPanel}.kt`.
**Modify:** existing `FormaScreen.kt`/`TranscodeViewModel.kt` routing only.
**Test:** `testing/image/android/dev/forma/app/image/ImageEditorUiTest.kt`.

**Interface:** `suspend ImagePreviewRenderer.render(document: ImageEditDocument, request: ImagePreviewRequest): ImagePreviewResult`;
request records proxy versus actual-pixel region, revision and cancellation owner.

- [ ] Add red UI tests for basic image routing, absent video-only controls, numeric crop, 52 dp actions, 200% text scroll reachability, keyboard/TalkBack semantics and tool switches preserving draft state.
- [ ] Add stale-result and cache-key tests: revision N completing after N+1 cannot replace it; Original is labeled; 100% never means an enlarged low-resolution proxy; preview cannot cancel an export session.
- [ ] Implement the spec's phone/wide layout, one inspector scroll owner, rendered preview, history/reset and capability-bound controls. Mark transient display changes Updating. Enforce preview memory/work priority.
- [ ] Run emulator Compose tests and physical touch/display checks separately. Commit `feat(image): add native image controls and revision-safe preview`.

## Task 8: Editable markup and final solid redaction

**Create:** `app/src/main/kotlin/dev/forma/app/image/ImageMarkupRenderer.kt`;
`app/src/main/kotlin/dev/forma/app/ui/image/ImageMarkupPanel.kt`.
**Test:** `testing/image/android/dev/forma/app/image/ImageMarkupTest.kt`.

**Interface:** `suspend ImageMarkupRenderer.render(document: ImageEditDocument, geometry: ImageGeometryResult): MarkupPlane`;
result supplies a private transparent plane and layout identity to image prepare.

- [ ] Add red cases for multiline/RTL/combining text, quoted punctuation, long text bounds, arrow geometry, object reorder/duplicate/delete, alpha edges and missing fonts.
- [ ] Add crop/rotate/fit cases proving source-relative objects map identically in preview and output. Redaction must cover the intended region at full resolution and remove original thumbnails/edit metadata.
- [ ] Implement Canvas/StaticLayout rasterization and FFmpeg composition; never use Compose screenshots or interpolate arbitrary text into filter syntax. Enable only implemented tools. Final redaction is opaque and above other content.
- [ ] Run native pixel/markup and UI tests, plus task 6 retry regressions. Commit `feat(image): add editable markup and flattened solid redaction`.

## Task 9: Autosave, limits and interruption recovery

**Create:** `app/src/main/kotlin/dev/forma/app/image/ImageDraftRepository.kt`.
**Test:** `testing/image/app/dev/forma/app/image/ImageDraftTest.kt`;
`testing/image/android/dev/forma/app/image/ImageLifecycleTest.kt`.
**Modify:** existing ViewModel and queue startup recovery.

**Interfaces:** `suspend ImageDraftRepository.load(sourceId: String): ImageDraftLoadResult`;
`suspend save(document: ImageEditDocument, expectedRevision: Long): ImageDraftSaveResult`.
Return explicit missing/corrupt/unsupported/conflict results.

- [ ] Add red tests for 1 MiB/object/text/history limits, stale save completion, corrupt/unknown schema preservation, rotation/process recreation, missing URI grants and changed source identity.
- [ ] Add memory/disk-budget tests before full allocation; low-memory rejection is not silent downscaling or a fabricated success. Recovery may restore a draft but never auto-resume a partial image.
- [ ] Implement atomic revision-aware persistence, bounded caches and shared worker controls. Integrate battery/thermal settings only once their real producer exists; B controls remain Planned beforehand.
- [ ] Run JVM and real Android storage/recreation tests. Commit `feat(image): persist image drafts and enforce resource budgets`.

## Task 10: Direct-ADB runner and release exclusion proof

**Create:** `testing/image/android/dev/forma/app/image/ImageEditorScenarioTest.kt`,
`ImageFixtures.kt`, `ImageReport.kt`; test-only fixtures/resources as needed.
**Modify:** core/engine/app Gradle test-source references, each removable with
`-PimageTests=false`; extend an existing runner rather than ship a competing lab.

**Contract:** implement the case IDs and fresh-run report behavior in
[testing guide](../../../testing/image/README.md). Keep every scenario's production
edit/export call identical to the app's path. Expected-error cases pass only when
the exact declared error/no-publication behavior is observed.

- [ ] Add red contract checks for unknown case, reused UUID, missing native build, mismatched APK/report identity, zero executed tests and stale output. These must not report a passing native suite.
- [ ] Implement fixtures on device and case selection/report emission inside the separate instrumentation APK. No Python wrapper, broadcast receiver, production command interpreter, network listener or arbitrary FFmpeg command argument.
- [ ] Run build/unit/lint and API-only gates, then native packaging checks and documented direct-ADB cases on actual phones. Attach evidence to this PR, not source commits; retain failures and unrun gates.
- [ ] In a disposable checkout remove `testing/image/`, build with `imageTests=false`, and inspect release APK/dependencies for absent image-test classes, commands, fixtures and endpoints. Do not silently disable other workstreams' test switches.
- [ ] Update all A statuses individually with evidence and run a full combined-branch regression. Keep Draft until the A acceptance gates pass. Commit `test(image): qualify native image workflows through direct ADB`.

## Completion gate

Do not infer completion from documents, successful planning tests or an API-only
AAR. Require the isolated native roundtrip, geometry/alpha/metadata checks,
size-cap boundaries/retries, cancellation/recovery, UI accessibility and release
exclusion evidence. Record exact source revision, APK/native hashes, device/API/
ABI/page size and observed results. B/C remain planned follow-on slices, each with
its own focused design and tests rather than empty scaffolding in this PR.
