# Audio Editor Implementation Plan

> **For agentic workers:** Use `superpowers:subagent-driven-development` or `superpowers:executing-plans` to implement this plan task-by-task. PR3 implements the user-selected first usable slice. Checkboxes retain the broader original gates; see the implementation status below and the validation record for completed and unrun portions.

**Goal:** Add a native, non-destructive audio editor that shares Forma's conversion, preview, queue and device-test contracts.

**Architecture:** Keep typed editing and graph planning in `core`, native execution in `engine-ffmpeg`, and Compose/state/work ownership in `app`. Stage A uses FFmpeg-rendered preview audio played through ExoPlayer; richer live processing is a later qualified adapter.

**Tech Stack:** Existing Kotlin/Compose/coroutines/FFmpegKitNext; Media3 ExoPlayer 1.11.1 for preview; optional native/AI extensions only in later slices.

**Spec:** [Audio architecture](../../audio-editor.md) and [UI layout](../../audio-editor-ui.md). Read both before implementing.

## Implemented slice and remaining gates

[Validation record](../../audio-editor-validation.md) identifies the code, APKs, observed phone results and limitations. Tasks 1–4 have shipped typed edits/migration, DSP/export, analysis/verification, native controls and bounded A/B preview. Task 6 supplies real test-only scenarios and release isolation. Task 5 is explicitly deferred. Original checklist items include broader acceptance requirements that have not all run; partial gates below are left open rather than claiming exhaustive Stage A qualification.

## Global constraints

- Native Android, not Studio/Python/WebView runtime; API 26 minimum, SDK 36, JDK 17.
- Preserve FFmpeg source pin, current licensing and existing Gradle pins unless a separate change is justified.
- Preserve source-first home, decimal-byte upload limits, URL entry, left shelf and bracket trim.
- No default destructive audio enhancement; no source overwrite; queued jobs own immutable snapshots.
- Every export/retry uses `FfmpegBridge.prepare`; one active native task, cancellation cleanup before slot release.
- Reconcile [PR #2](https://github.com/Mainkill1/android-transcode/pull/2) before code changes. At the inspected implementation baseline it was documentation-only. Its subsequently added clip/timeline code requires explicit integration before both drafts merge.
- Test code, media generators, CLI and evidence are separate from release runtime. No new scheduled automation or workflow is part of this plan.

## Review focus

Five easy-to-miss cases are assigned below: all-silent audio/non-finite loudness (Task 3); changing settings during analysis (Tasks 3-4); short clips with limiter/codec delay (Tasks 2-3); video-linked silence shortening (Task 5); unavailable optional filters in restored jobs (Tasks 1 and 6).

## File ownership

Existing roots are `core/src/main/kotlin/dev/forma/core/`, `engine-ffmpeg/src/main/kotlin/dev/forma/ffmpeg/`, and `app/src/main/kotlin/dev/forma/app/`. Paths below relative to these roots are identified by `core:`, `engine:` and `app:`. All added filenames are **proposed**, not claims that those APIs exist today.

| Owner | Create | Modify/integrate |
| --- | --- | --- |
| Domain | `core:audio/AudioEdit.kt`, `AudioEffectRegistry.kt`, `AudioGraphPlanner.kt`, `AudioAutomation.kt` | `core:Models.kt`, `Planner.kt`; #2's eventual clip/timeline types |
| Native | `engine:audio/AudioAnalysis.kt`, `AudioPreviewRenderer.kt` | `engine:FfmpegBridge.kt` and its actual native implementation; reuse session/capability handling |
| State/storage | `app:audio/AudioEditorState.kt`, `AudioPreviewController.kt` | `app:TranscodeViewModel.kt`, `data/JobCodec.kt`, `data/FfmpegTranscoder.kt`, service/coordinator |
| UI | `app:ui/audio/AudioEditorPanel.kt`, `AudioWaveform.kt`, `AudioEqEditor.kt`, `AudioEffectChain.kt`, `AudioOutputPanel.kt` | `app:ui/FormaScreen.kt`, `AdvancedControls.kt`, existing touch wrappers |
| Dependency | Media3 version-catalog entry only when Task 4 needs it | `gradle/libs.versions.toml`, `app/build.gradle.kts` |
| Tests | Audio suites under `testing/core/`, `testing/app-unit/`, `testing/android/`, generated fixture definitions under `testing/fixtures/audio/` | #2's test source-set references and ADB host CLI; reconcile actual names first |

Proposed production interfaces: `AudioGraphPlanner.plan(edit, sourceFacts): AudioGraphPlan`; `AudioEffectRegistry.validate(edit, capabilities): List<AudioProblem>`; `AudioAnalyzer.analyze(request): AudioAnalysisResult`; `AudioPreviewRenderer.render(request): AudioPreviewResult`. Define their request/result types alongside the interfaces in the files above, using the exact identities, ranges, duration/analysis fields and states in the spec. Analysis/preview requests carry graph revision, staged-source fingerprint, selected range and native-build identity; results echo that identity. Preview results additionally identify the cache artifact and valid time range. These are typed boundaries, not new raw-command APIs.

## Task 1: typed edits, registry and persisted migration

**Interfaces:** Produce the core types and validation above. Consume existing `Settings`, `Source`, `Capabilities` and #2's eventual edit model. Store one audio edit value in the shared settings/project model; migrate legacy gain/fade/normalization fields exactly once.

- [ ] Write `AudioEditTest` for neutral defaults, ordered stable IDs, duplicate ID rejection, finite/range validation, frequency below Nyquist, bypass and immutable copies. Write `AudioJobCodecTest` for baseline schema-1 jobs, #2's eventual schema and the new schema; test unknown effects/version preservation and no silent processing loss.
- [ ] Run the new suites and record their expected failures before implementation.
- [ ] Implement the registry/model and explicit migration in the owned files. Registry includes units, capability/asset requirements, preview support and automation whitelist. Do not reuse video `Settings.denoise` for audio noise reduction.
- [ ] Run tests: neutral old job retains prior behavior; changing editor gain after queueing cannot change its queued graph; unavailable restored effects block export with a named reason. Commit this slice independently.

## Task 2: first DSP graph and actual export

**Interfaces:** Produce `AudioGraphPlan`; integrate it through `Planner` and `FfmpegBridge.prepare`. Add audio output-container/encoder modeling rather than treating every non-M4A container as video.

- [ ] Write `AudioGraphPlannerTest`: gain/EQ/compressor/fade order; 30 s source trimmed 5-15 s then played at 2x yields 5 s; empty chain has no extra processing; active EQ prohibits audio stream copy; audio-only WAV is not routed through a video encoder. Test channel matrices, output-rate bounds, filter availability and invariant decimal formatting under a non-English locale.
- [ ] Run those tests to observe failures, then implement gain/fades/EQ/compressor/channel conversion and encoding plans. Keep codec-specific bitrate/quality controls and observed decoder/encoder/muxer support explicit. Add Stage A formats only when their native profile is available; no default native-profile upgrade.
- [ ] Test generated mono/stereo tones and impulses through the actual packaged engine: -6 dB gain within 0.1 dB; neutral PCM null comparison within declared numerical tolerance; EQ response within 0.5 dB at tested frequencies; exact PCM output frame count under the defined rounding rule; no lost final impulse after look-ahead flush.
- [ ] Confirm short clips, fade lengths, limiter auto-level/latency, Unicode paths, selected stream identity and output decode. Lossy delay/padding is evaluated separately from sample-exact PCM. Re-run host suites, record native evidence/unrun gates and commit.

## Task 3: analysis, loudness and byte-cap verification

**Interfaces:** Implement `AudioAnalyzer` in `engine:audio/AudioAnalysis.kt`; integrate immutable analysis identity and job phases with the existing executor/coordinator. Analyzer returns structured measurements or `NotMeasurable(reason)`, not unvalidated native log text.

- [ ] Write `AudioAnalysisIdentityTest`: source/trim/EQ/layout/rate/automation/model changes invalidate measurements; stale completion cannot update current state; silent input never serializes NaN/infinity. Write job tests for cancel during analysis/render/verification and retry preparation.
- [ ] Run failures, implement measurement of the edited program, two-pass application, effective normalization-mode reporting and final encoded-artifact measurement. Retain one job/run lease throughout all phases; no nested coordinator acquisition. Preserve-dynamics mode rejects unattainable targets instead of silently compressing.
- [ ] Run native voiced fixtures long enough for valid measurements. Acceptance: requested integrated loudness within 0.5 LU, measured final true peak no higher than the selected ceiling plus 0.1 dB measurement tolerance. Declare the analyzer/build and fixture; short/silent programs get explicit non-measurable results. These are proposed product gates, not previously measured results.
- [ ] Verify full duration, decoder success, channels/rate, tails and strict final bytes. Test oversized/retry exhaustion, storage full, failed document copy and cancellation cleanup: no false Completed or shareable partial output. Re-run suites and commit.

## Task 4: native editor, rendered preview and A/B

**Interfaces:** Produce `AudioEditorState`, `AudioPreviewController` and the proposed composables. State owns selection and graph revision; native tasks remain service/coordinator-owned. `AudioPreviewRenderer` consumes the same creative graph, not a second EQ implementation.

- [ ] Add failing Compose tests for source-first Home, audio-only entry, 52 dp actions, EQ numeric entry, node bypass/reorder, advanced-state preservation, undo gesture grouping, queued snapshot isolation and exact missing-engine messaging. Add controller tests for rapid edits, cancellation and stale results.
- [ ] Introduce the pinned ExoPlayer dependency, checking current Gradle/API compatibility. Implement waveform tiles and a bounded rendered-PCM selection preview; reuse native session serialization. Render from the required history for stateful filters and label approximation where exact state is unavailable.
- [ ] Implement portrait and wide inspector layouts from the UI spec. Separate Original, Rendered, Updating and Stale playback. Level-matched A/B is monitoring-only. Final-output audition reads the verified artifact.
- [ ] Run Compose/device tests at compact and wide sizes, enlarged text, TalkBack and keyboard navigation. Verify audio focus, unplug/headset/Bluetooth route changes, pause/resume and rotation. Measure preview cancellation/scrub responsiveness and memory on actual devices without inventing universal latency claims. Re-run suites and commit.

## Task 5: later editor-grade slices

Complete A before these; each row becomes its own reviewed implementation slice with failing tests, implementation, passing native evidence and a commit. Do not preinstall every optional dependency now.

| Slice | Integration and non-negotiable tests |
| --- | --- |
| Cleanup and dynamics | Extend registry/native compiler/UI: noise profile strength, hum harmonics, clicks, gate/de-esser and multiband crossover; no new noise on silence, no missing speech transients; matched listening fixtures |
| Time and arrangement | Reuse #2 timeline for split/join/crossfade/reverse/speed and reviewed silence-edit decisions; source preservation, exact edit boundaries and linked A/V sync; bounded reverse storage |
| Multitrack and automation | Extend project tracks, mixer graph, pan/gain envelopes, sidechain ducking and optional recording; mixed-rate/channel inputs, timestamps, headroom, envelope rebasing, solo/mute and permission denial |
| Creative effects/analysis | Registry entries for delays/modulation/convolution plus bounded spectrum/spectrogram; impulse/tail tests, dry/wet behavior, cache identity and mono compatibility |
| AI/restoration/quality stretch | Separate optional module/native feature for the specific approved model/library; license/hash/ABI/16 KB packaging, source separation quality, CPU baseline, accelerator fallback, memory/thermal/cancellation and uninstall/debloat tests |

Record VST/plugin hosting, cloud enhancement and a full DAW as out of this plan's required scope. Advanced AI effects may remain unavailable until a licensed model and tested implementation are selected; never substitute a simulated effect.

## Task 6: isolated Android/ADB automation and release gates

**Implemented protocol:** `python3 testing/audio_device.py all --serial "$SERIAL"` installs the matching debug/test APK pair and runs `capabilities`, `dsp`, `analysis`, `preview`, `jobs` and `ui` scenarios sequentially. Scenarios invoke the production bridge/planner/executor; the `jobs` case includes deterministic cap failure and verification cancellation. Each native test is explicitly enabled with `-e formaNative true`; no release command endpoint is installed.

```bash
python3 -m unittest discover -s testing/host -v
python3 testing/audio_device.py all --serial "$SERIAL"
# Equivalent single native scenario:
adb -s "$SERIAL" shell am instrument -w -r \
  -e formaNative true -e class dev.forma.app.audio.AudioJobDeviceTest \
  dev.forma.transcode.test/androidx.test.runner.AndroidJUnitRunner
```

- [x] Reject ADB-zero instrumentation failure, crash, missing results and connection failure; record timeout/parse/native failures as a nonzero host result. Native engine/filter absence fails the requested test.
- [x] Record run ID, repo/diff identity, matching APK hashes, device/API/ABI/page size, scenario timings and native measurements; remove stale reports before each scenario. No optional model is bundled. General staged JSON/path commands and memory telemetry remain outside this protocol.
- [x] Generate deterministic silence, impulse, tones, channel IDs and voiced-like fixtures in test-only code; independently evaluate PCM arithmetic/frame counts. Broader hum/noise/sweep/listening fixtures remain later qualification.
- [x] Include audio instrumentation only through `androidTest` sources; `-PaudioTests=false` disables their references. Release builds with `testing/` physically absent and release dex excludes instrumentation classes, fixtures and test commands.
- [x] Run existing host checks, Android build/unit/lint, all 24 phone instrumentation cases and packaged audio scenarios. Do not commit media, reports, APKs or native binaries.

Existing verification commands (require their documented toolchains):

```bash
./tools/check-core.sh
./tools/check-android-readiness.sh
./tools/check-ux.sh
./gradlew -PffmpegEnabled=true -PffmpegRepo="$NATIVE_REPO" \
  :core:test :app:testDebugUnitTest :app:assembleDebug \
  :app:assembleDebugAndroidTest :app:lintDebug
python3 tools/verify_android_native.py app/build/outputs/apk/debug/app-debug.apk
```

Physical-device qualification must cover at least a mainstream arm64 device and a recent high-end arm64 device, 44.1/48 kHz sources, supported mono/stereo/surround routes, API-26 behavior, 4 KB/16 KB page-size coverage and one sustained large-file run. Record unsupported combinations rather than implying exhaustive Android coverage. Headphone listening and A/V sync review remain separate from passing automated signal checks.

## Definition of done and current evidence

**Current PR:** first usable native audio editor and isolated device tests, with the original architecture/UI/roadmap retained as references. Media3 is added; the FFmpeg pin/profile is preserved. See the validation record for measured results, release isolation and integrations still required.

**Full Stage A acceptance:** real packaged exports, persistence, preview/export contract, strict byte cap, missing-engine failures, release test isolation, device signal checks and native accessibility review must all pass with artifact identities. The selected slice passes target-phone functional/signal gates; broader accessibility/device gates remain open. Mark later B/C features honestly unavailable until their independent gates pass. A feature list, host-only green test or declared filter name is not device qualification.
