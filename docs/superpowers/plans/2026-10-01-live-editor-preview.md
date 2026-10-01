# Live Editor Preview Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give image and video editing a focused screen with immediate crop/rotation feedback and an in-app playable rendered preview.

**Architecture:** Route Edit into a native Compose editor shell. Reuse `ImageCanvas` and its native preview, add a bounded Android frame controller for video, and map preview geometry through the same pure transform used by export. Render short immutable preview snapshots through existing FFmpeg ownership, then play them with Media3.

**Tech Stack:** Kotlin, Compose gestures, `MediaMetadataRetriever`, Media3, FFmpeg bridge, JUnit/Android instrumentation.

**Spec:** `docs/superpowers/specs/2026-10-01-live-editor-preview-design.md`

## Global Constraints

- Quick frames are nearest-decodable navigation feedback; label approximate effects/color honestly.
- A rendered preview is five seconds around the playhead within the kept range, private, revision-scoped, and never a Finished job.
- One completed gesture is one undo command; an in-progress gesture never changes a queued job.
- Keep minSdk 26, original files, native-run serialization, and existing strict final-export verification.

## Review Focus

- Rotated metadata plus crop: on-screen bounds and FFmpeg crop must agree; Task 1 test.
- Rapid source switch while frame extraction is pending: stale bitmap must not appear; Task 2 test.
- Process recreation during a pointer gesture: restore only the last committed draft; Task 3 test.
- Native conversion running when Play preview is tapped: wait for ownership without overlap; Task 4 test.
- HDR quick frame differs from rendered color: show the limitation and preserve rendered-preview access; Task 2 UI test.

---

### Task 1: Shared edit geometry

**Files:** Create `core/src/main/kotlin/dev/forma/core/PreviewGeometry.kt`; modify `core/src/main/kotlin/dev/forma/core/ClipEffects.kt`; test `testing/core/unit/dev/forma/core/EditorTest.kt`.

**Interfaces:** `PreviewGeometry.mapCrop(sourceWidth,sourceHeight,orientation,effects,displayRect)` returns validated source-pixel bounds or a typed unsupported-orientation result; export planning consumes the same mapping.

- [ ] Write failing tests for 0/90/180/270-degree metadata, flips, odd sizes, edge crops, and unsafe display matrices.
- [ ] Run `./gradlew :core:test`; confirm the new cases fail.
- [ ] Implement the pure mapper and use it in export planning; preserve even-pixel constraints and existing error text intent.
- [ ] Rerun `:core:test`; commit the geometry slice.

### Task 2: Bounded quick-frame controller

**Files:** Create `app/src/main/kotlin/dev/forma/app/video/VideoFrameController.kt`; modify `app/src/main/kotlin/dev/forma/app/TranscodeViewModel.kt`; test `testing/app/device/dev/forma/app/VideoFrameControllerTest.kt`.

**Interfaces:** `VideoFrameController.request(source,timeMs,revision)` exposes `Loading`, `Ready(bitmap,requestedMs,sourceKey,revision)`, or `Error`; `close()` releases retriever/bitmaps. API 27+ extracts scaled frames; API 26 enforces the bitmap budget before publishing a downsized result.

- [ ] Write failing tests for source/revision invalidation, 26/27 paths, decode errors, memory cap, and HDR/nearest-frame status text.
- [ ] Run targeted Android tests and confirm failure.
- [ ] Implement a single active retriever, bounded cache, IO decode, cancellation/invalidation, and visible quick-frame status.
- [ ] Rerun tests and commit the frame-controller slice.

### Task 3: Focused editor route and durable draft

**Files:** Create `app/src/main/kotlin/dev/forma/app/ui/EditorWorkspace.kt`, `app/src/main/kotlin/dev/forma/app/video/VideoDraftRepository.kt`; modify `app/src/main/kotlin/dev/forma/app/ui/FormaScreen.kt`, `app/src/main/kotlin/dev/forma/app/ui/EditControls.kt`, `app/src/main/kotlin/dev/forma/app/ui/image/ImageEditorPanel.kt`, `app/src/main/kotlin/dev/forma/app/ui/image/ImageCanvas.kt`, `app/src/main/kotlin/dev/forma/app/MainActivity.kt`; test `testing/app/device/dev/forma/app/EditorWorkspaceTest.kt`.

**Interfaces:** `VideoDraftRepository.save(sourceKey,selectedTool,commands,revision)` atomically stores committed edits; `EditorWorkspace` receives draft/preview state and emits typed undoable actions. The image canvas opens directly; video crop gestures commit once on release.

- [ ] Write failing Compose/repository tests for visible preview, direct image canvas, crop/rotate feedback, one undo per gesture, Back choices, rotation/process recreation, expired grant, TalkBack/large text, and no queued snapshot mutation.
- [ ] Run targeted tests; confirm failure.
- [ ] Implement the editor route, versioned draft, overlay/handles and clear controls; use an explicit unavailable-crop reason when geometry cannot match export.
- [ ] Rerun tests and commit the editor slice.

### Task 4: Rendered five-second preview and qualification

**Files:** Create `app/src/main/kotlin/dev/forma/app/video/RenderedPreviewController.kt`, `app/src/main/kotlin/dev/forma/app/ui/PreviewPlayer.kt`; modify `app/src/main/kotlin/dev/forma/app/work/RunCoordinator.kt`, `app/src/main/kotlin/dev/forma/app/TranscodeViewModel.kt`; test `testing/app/device/dev/forma/app/RenderedPreviewDeviceTest.kt`.

**Interfaces:** `RenderedPreviewController.render(snapshot,playheadMs)` waits for native ownership, returns a private revision-tagged media URI, and removes stale/superseded files. `PreviewPlayer` owns a Media3 player for the screen lifecycle.

- [ ] Add failing tests for five-second clipping, stale-result rejection, cancellation cleanup, native-job contention, and in-app playback.
- [ ] Run targeted tests; confirm failure, implement the controller/player, then rerun.
- [ ] Run core/readiness, `./gradlew :core:test :engine-ffmpeg:testDebugUnitTest :app:testLabUnitTest :app:assembleLab :app:lintLab`, emulator Compose checks, native-payload validation, and physical image/video edit/preview/export checks on a native-enabled build. Measure cached-frame gesture p95 against 150 ms; compare crop/rotation geometry with the verified export.
- [ ] Commit verification notes and code, open the live-editor PR as Draft against the save-destinations branch, and mark Ready only after all required gates pass.
