# Precision Video Timeline Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make video trimming precise and visual with a pinch-zoom filmstrip, independent playhead, bracket handles, exact times, and bounded movie-sequence preview.

**Architecture:** A pure Long-millisecond/Double-viewport model owns time-to-pixel mapping and gesture transactions. Compose renders a visible-window filmstrip from the bounded frame controller. Existing `EditTimeline`/`MovieProject` commands own committed edits; a sequence-window planner builds short movie previews through the existing renderer.

**Tech Stack:** Kotlin, Compose multi-pointer input, `MediaMetadataRetriever`, existing FFmpeg/Media3 preview path, JUnit/Android instrumentation.

**Spec:** `docs/superpowers/specs/2026-10-01-precision-video-timeline-design.md`

## Global Constraints

- Persist time as `Long` milliseconds; use `Double` only for viewport mapping, never whole-source `Float` state.
- Fit through one-second (or shorter-source) windows, minimum kept duration 50 ms, and exact 1 ms requests where valid.
- A completed drag is one undo step; canceled gestures leave the committed trim unchanged.
- Do not claim frame-accurate source seeking for variable-frame-rate media; final export remains strictly verified.

## Review Focus

- Three-hour source zoom: no precision collapse at 1 ms entry; Task 1 test.
- Two close brackets: each remains 48 dp and independently accessible; Task 2 test.
- Rapid switch between movie clips: a stale trim/frame cannot attach to the new clip; Task 2 test.
- Split near transition: reject unsupported mapping without changing project; Task 3 test.
- Preview across a transition: output order/duration must match the full sequence segment; Task 3 device test.

---

### Task 1: Pure viewport and trim transactions

**Files:** Create `core/src/main/kotlin/dev/forma/core/TimelineViewport.kt`; modify `core/src/main/kotlin/dev/forma/core/EditTimeline.kt`; test `testing/core/unit/dev/forma/core/EditorTest.kt`, `testing/core/unit/dev/forma/core/SequenceTest.kt`.

**Interfaces:** `TimelineViewport(durationMs,visibleStartMs,visibleDurationMs,playheadMs,trim)` provides `timeAt(x,width)`, `xAt(time,width)`, `pinch(anchorX,scale,width)`, `pan(deltaPx,width)`, and `moveEdge(edge,timeMs)`; `TrimGesture.commit()` emits one existing edit command.

- [ ] Write failing tests for three hours, anchored pinch, pan clamps, Fit/one-second zoom, one-ms exact entry, 50-ms minimum, edge auto-scroll, canceled gesture, and one undo command.
- [ ] Run `./gradlew :core:test`; confirm the new cases fail.
- [ ] Implement pure mapping with clamping before rounding and transactional trim commands.
- [ ] Rerun `:core:test`; commit the viewport slice.

### Task 2: Filmstrip and accessible video controls

**Files:** Create `app/src/main/kotlin/dev/forma/app/ui/VideoTimeline.kt`; modify `app/src/main/kotlin/dev/forma/app/ui/EditorWorkspace.kt`, `app/src/main/kotlin/dev/forma/app/video/VideoFrameController.kt`, `app/src/main/kotlin/dev/forma/app/ui/EditControls.kt`; test `testing/app/device/dev/forma/app/VideoTimelineTest.kt`.

**Interfaces:** `VideoTimeline` emits independent `Seek`, `TrimStart`, `TrimEnd`, `Split`, and zoom/pan actions; frame controller requests a fixed number of real thumbnails for only the visible window.

- [ ] Add failing Compose tests for pinch anchoring, pan, bracket drag, independent scrub, 48-dp targets, exact fields, TalkBack actions, failed thumbnail tile, clip switch, rotation, and 200% text.
- [ ] Run targeted Compose tests; confirm failure.
- [ ] Implement filmstrip/ruler, shaded discard range, labels, viewport gestures, visible-window thumbnail budget, and exact-time dialog.
- [ ] Rerun tests and commit the timeline UI slice.

### Task 3: Movie sequence preview and qualification

**Files:** Create `core/src/main/kotlin/dev/forma/core/SequenceWindowPlanner.kt`; modify `core/src/main/kotlin/dev/forma/core/MovieProject.kt`, `app/src/main/kotlin/dev/forma/app/ui/MovieControls.kt`, `app/src/main/kotlin/dev/forma/app/video/RenderedPreviewController.kt`; test `testing/core/unit/dev/forma/core/SequenceTest.kt`, `testing/app/device/dev/forma/app/VideoTimelineTest.kt`.

**Interfaces:** `SequenceWindowPlanner.window(project,playheadMs,limitMs=5_000)` returns a typed immutable sequence segment spanning clip/transition boundaries; unsupported split returns a reason without changing `MovieProject`.

- [ ] Write failing tests for selected-clip source time versus movie time, order changes, split validation, speed/transition duration, and a five-second window crossing a transition.
- [ ] Run targeted tests; confirm failure.
- [ ] Implement the sequence-window mapper and connect the selected clip filmstrip plus rendered preview to the editor; keep prior movie actions available.
- [ ] Rerun tests and compare a rendered preview with the corresponding full-movie segment on device.
- [ ] Run core/readiness, `./gradlew :core:test :engine-ffmpeg:testDebugUnitTest :app:testLabUnitTest :app:assembleLab :app:lintLab`, emulator UI, native-payload validation, and physical short/long video trim/export checks on a native-enabled build; report requested milliseconds and observed frame boundaries, source hash, and queued snapshot. Commit evidence/code, open the timeline PR as Draft against the live-editor branch, and mark Ready only after all gates pass.
