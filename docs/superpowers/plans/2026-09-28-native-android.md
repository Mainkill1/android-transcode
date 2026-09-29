# Native Android implementation plan

> **For agentic workers:** Use superpowers:executing-plans, or subagent-driven-development where available, to implement and review these tasks in order. This is the next-agent plan, not a claim that these milestones already passed.

**Goal:** Ship an Android upload-first transcoder with actual FFmpeg, checked device acceleration, strict byte targets and native bracket editing.

**Architecture:** Preserve `app -> engine-ffmpeg -> core` and `app -> core`. Compose owns interaction, Android owns documents/lifecycle, pure Kotlin owns job intent/policy, FFmpeg owns export. Studio remains a reference oracle.

**Tech Stack:** Kotlin/Compose, API 26+, SDK 36, JDK 17, pinned source-built FFmpegKitNext 9.0.0; optional NPU provider later.

**Spec:** `docs/android-handoff.md`; acceleration design: `docs/android-acceleration.md`.

## Global constraints

10,000,000-byte default target; final output strictly below the target; 4 total video/audio attempts or 7 image attempts. Never overwrite originals. Preserve full selected duration, sound, transparency and edits. No Python server/WebView/native-engine substitution. No unqualified HDR conversion, hardware CRF, NPU codec claim or invented progress. User settings/queue snapshots survive advanced-view navigation.

## Review focus

- Rotated/VFR/cover-art media must not become wrong-orientation exports or mistaken video tracks.
- Provider permission revocation, low storage and process death must not publish partial outputs.
- Hardware rate control can overshoot; verify bytes and preserve all selected content.
- Cancellation may race native completion; release resources only after the native session stops.
- Model delegation/codec wrappers are not measured device support or end-to-end acceleration.

## Task 1: Real native bundle and explicit device smoke

**Files:** `tools/build-ffmpeg.sh`, `tools/verify_android_native.py`, `docs/ffmpeg.md`, `app/src/androidTest/kotlin/dev/forma/app/NativeAccelerationSmokeTest.kt`.

**Consumes:** pinned native source and required MediaCodec build flag.
**Produces:** hash-addressed arm64 AAR/APK, configuration listing and `h264-smoke.json` from a physical phone.

- [ ] Run host readiness checks, then confirm an API-only artifact fails the package gate.
- [ ] Build the real native bundle and validate every packaged 64-bit shared object. Resolve dependency alignment failures deliberately, with source/pin changes documented separately.
- [ ] Run native enabled Gradle assemble/unit/lint/instrumentation compilation; install on the initial phone.
- [ ] Run `NativeAccelerationSmokeTest` with `formaNative=true`. Assert actual encoder component, dimensions, tracks, duration and full decode success; preserve failure report.
- [ ] Add test media for phone rotation/display matrices, cover art, VFR and 4 KB/16 KB installations. Do not broaden the device path until these cases are supported or explicitly rejected.
- [ ] Record baseline package/device identity and commit the source/build fixes, not generated binaries.

## Task 2: Kotlin size controller and versioned job model

**Create:** `core/src/main/kotlin/dev/forma/core/UploadFit.kt`, `core/src/test/kotlin/dev/forma/core/UploadFitTest.kt`.
**Modify:** `core/src/main/kotlin/dev/forma/core/Models.kt`, `Planner.kt`, `app/src/main/kotlin/dev/forma/app/data/JobCodec.kt`, `JobCodecTest.kt`.
**Reference:** `studio/upload_limits.py`, `studio/tests/test_upload_limits.py`, `docs/upload-limits.md`.

**Interfaces:** `UploadGoal(targetBytes: Long, maxAttempts: Int)`; `UploadFit.plan(source, edits, goal, previousAttempts): PlannedAttempt`; `FitAttempt(number, effectiveSettings, actualBytes, failureKind)` with immutable requested settings separate from effective settings. Use integer microseconds for native edit times; do not add parallel time scales silently.

- [ ] Write failing tests for 10 MB byte boundary (9,999,999 passes; 10,000,000 fails), duration+speed budgets, reserved audio/mux space, portrait/no-upscale, unsupported encoders and impossible rates.
- [ ] Port the policy without changing the tested Studio math incidentally. Add recorded cross-language test vectors; distinguish math parity from device output parity.
- [ ] Write failing versioned-serialization tests; old jobs deserialize to no target and preserve their previous encoder semantics. New goals and acceleration preference survive restart.
- [ ] Implement immutable intent/effective-attempt separation, strict limits and source-based retry bookkeeping.
- [ ] Run `./gradlew :core:test :app:testDebugUnitTest`; commit the policy/model migration independently.

## Task 3: Native automatic acceleration and bounded export retries

**Modify:** `app/src/main/kotlin/dev/forma/app/data/FfmpegTranscoder.kt`, `engine-ffmpeg/src/native/kotlin/dev/forma/ffmpeg/KitNextBridge.kt`, `core/src/main/kotlin/dev/forma/core/Acceleration.kt`, `Planner.kt` queue transitions; add executor tests.

**Consumes:** `PlannedAttempt`, `EncodeRequest`, `AndroidCodecCatalog.candidates(request)`, `AccelerationPolicy.choose`, `FfmpegBridge.prepare`.
**Produces:** `VerifiedOutput` only after structure/full-decode and byte checks, plus attempt history/effective route.

- [ ] Start with failing tests using a fake bridge and filesystem: valid oversize -> stricter attempt; equal-to-cap rejection; cancellation during native work; no share URL on failure; original settings unchanged.
- [ ] Wire Auto/CPU/required-hardware preference into versioned job intent. Auto uses only the exact current request; explicit hardware stays explicit.
- [ ] Recompute and prepare every retry after size/rate changes. Keep the same staged original and fresh candidate paths. Add RUNNING/VERIFYING/retry transitions deliberately; current QueueRules do not permit VERIFYING -> RUNNING.
- [ ] Introduce structured native failure classification. Only a positively identified hardware initialization failure may trigger one software fallback in Auto; do not parse all failures as hardware trouble. Persist fallback reason and reset per-attempt progress honestly.
- [ ] Bind any hardware quarantine/qualification to native build, OS/device fingerprint, component, format, dimensions/rate/profile and buffer route. Do not globally disable a codec because the user ran out of disk.
- [ ] Verify full selected duration/frame progression, tracks, size and supported color. Current foundation duration tolerance (max 1 second or 5%) is not adequate final trim qualification; replace with a frame/audio-packet-aware validation contract and tests, not an arbitrary larger tolerance.
- [ ] Run actual native software vs MediaCodec jobs under the same strict 10 MB target, including forced overshoot; commit the executor independently of UI polish.

## Task 4: Upload-first Compose screen and direct URL ingestion

**Modify:** `app/src/main/kotlin/dev/forma/app/ui/FormaScreen.kt`, `TranscodeViewModel.kt`, `data/MediaFiles.kt`, Android manifest/network configuration, instrumentation tests.
**Create:** small `ui/UploadHome.kt` and `data/MediaUrlImporter.kt` components as needed instead of growing one monolithic screen.

**Interfaces:** ViewModel exposes immutable `UploadGoal`, source inspection state and job plan. `MediaUrlImporter.download(uri, destination, onBytes): staged local source`, cancellable and bounded; do not let networking own codec policy.

- [ ] Write failing Compose tests: empty home contains Select media, visible URL input and size goals; no codec wall. Selecting a goal alone creates no job.
- [ ] Port 10/20/25/50/100/500 MB and Custom plus one primary Convert action. Goal changes keep edits; expanding the shelf/settings never resets values. Display effective device/CPU route truthfully.
- [ ] Implement SAF input handling and direct HTTPS download with progress, redirects/size bounds, cancellation and actual probing; decide explicit HTTP handling rather than enabling global cleartext.
- [ ] Cover provider grants, revoked access, missing duration, unsupported sources, cancellation and keyboard/font/inset behavior. Unknown metadata stays unknown.
- [ ] Run Compose/device tests and a real native URL -> 10 MB job; commit the native workflow.

## Task 5: Native images and bracket timeline

**Create:** `core/EditTimeline.kt` and related tests (under existing Kotlin package paths), `app/.../ui/editor/BracketTimeline.kt`, bounded thumbnail/waveform reader.
**Modify:** native job models/serialization and `Planner.kt`; refer to Studio's timeline and image tests.

**Interfaces:** canonical ordered kept ranges; one undo transaction per completed drag; immutable edit snapshot in queued jobs. Thumbnails and preview state never define export timing.

- [ ] Write failing tests for bracket crossing, gesture cancellation, numeric sync, cut masks across multiple kept clips, clip ordering and undo history.
- [ ] Port filmstrip/waveform, hatched removed regions, playhead, zoom/Fit, tap nudges and no-drag alternatives into Compose with at least 48 dp targets.
- [ ] Implement accurate selected/all-kept preview and native exports using the same time model. Test frame/audio continuity and display rotation.
- [ ] Add still PNG/JPEG/WebP probe/encode, alpha preservation, 40 MP decode bound, strict image byte verification and up to seven source-based retries. Explicitly reject unsupported animations.
- [ ] Test physical-device memory bounds for thumbnails and images; no full-video frame cache. Commit editor and image stages separately when independently reviewable.

## Task 6: Lifecycle, thermal, packaging and evidence gate

**Modify:** `TranscodeService.kt`, `QueueRepository.kt`, native cancellation/diagnostic interfaces and Android tests.

- [ ] Write failing tests for process death, screen-off operation, queue restart, low disk, revoked URI, notification cancellation, timeout callback and partially produced output.
- [ ] Respect API 35+ mediaProcessing budgets and existing user-started service semantics. Finish native cancellation before deleting staging resources; design a bounded watchdog/process boundary for a hung native worker.
- [ ] Implement thermal-aware scheduling (one hardware encode at a time; no optional neural work by default). Report waiting/restart states, not fictitious pause/resume.
- [ ] Record comparisons with app/native/source/job identities, actual component, total and per-stage timing, bytes, memory, quality and thermal conditions.
- [ ] Re-run AAR/APK payload/alignment gates and test 4 KB/16 KB devices. Full native acceptance requires real artifacts and recorded exports, not just API-contract CI.

## Task 7: Optional NPU experiment, after a usable native release candidate

**Create only when justified:** isolated `engine-ml` module and one licensed model's benchmark/tests. Follow `docs/android-acceleration.md`.

- [ ] Define an explicit denoising or sparse-analysis benefit hypothesis; do not propose replacing H.264/HEVC/AV1 entropy coding with an NPU.
- [ ] Pin LiteRT/vendor/model artifacts and verify API/ABI availability without raising the whole application's minimum version.
- [ ] Measure cold/warm init, tensor/frame copies, real delegation, output quality, bytes, total latency and thermal load versus no-ML baseline.
- [ ] Add optional UI only after successful inference and visible effect are proven. A requested model stage must not silently disappear on CPU fallback or missing runtime.
- [ ] Stop or leave the provider optional if it increases total time/size without the intended quality benefit. NPU completion does not gate the required Android FFmpeg app.
