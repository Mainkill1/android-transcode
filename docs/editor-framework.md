# Editor framework and feature roadmap

## Goal

Keep Forma source-first: **Select media or enter a URL → inspect → edit only when needed → choose output → convert → share.** Advanced features must not crowd Home or reset edits. Build reusable native editing logic, not another Studio UI or a test-only encoder.

This is a framework delivery, not a claim that every professional-editor feature is finished. A model, a compiled filter, an exposed control and a verified phone export are four different milestones.

## Read the feature set by workflow

| Workflow | Capabilities to provide | Delivery boundary |
| --- | --- | --- |
| Import and organize | Video, audio, stills, animated media, document picker, camera/share sheet, direct-media URLs, metadata, relink, proxies, multiple sources | Existing native document import; URL/image/proxy extensions remain separate |
| Cut and arrange | Bracket IN/OUT, exact times, split, remove, ripple, reorder, duplicate, group, markers, undo/redo, autosave | Immutable ordered-clip model and bounded history; full native timeline UI/sequence rendering is a later integration |
| Preview | Scrub, frame stepping, loop/range playback, thumbnails/waveforms, fullscreen, quality selector, before/after, dropped-frame reporting | Preserve current preview; frame-accurate edited preview is a separate render consumer |
| Transform | Crop, scale, fit/fill, rotate, mirror, position, anchor, opacity, aspect ratios, background color/blur | Typed clip effects first; layers/canvas compositing later |
| Time | Speed, pitch-preserving audio, ramps, reverse, freeze, frame blending/interpolation | Constant-speed synchronized video/audio first; memory-heavy reverse/interpolation deferred |
| Audio | Track selection, extraction, mute, gain, fades, normalization, music/voiceover, mixing, pan, EQ, compression, limiting, denoise, delay, channel/sample-rate controls | Per-clip processing first; multitrack mixer later |
| Color | Brightness, contrast, saturation, gamma, exposure, temperature/tint, highlights/shadows, curves, LUTs, scopes, color management | Basic SDR corrections first; HDR/LUT/color-managed pipeline must be independently qualified |
| Effects | Blur, sharpen, denoise, deinterlace, grain, vignette, pixelate, stabilization, lens correction, deflicker | Capability-checked built-ins; no arbitrary FFmpeg strings in ordinary job data |
| Titles and captions | Text, fonts, outlines, shadows, logos, lower thirds, subtitles, SRT/VTT/ASS, burn-in or tracks, transcript generation | Later typed assets and text/caption renderer; not a fake available control |
| Animation and composition | Keyframes/easing, masks/feathering, tracking, chroma/luma key, alpha, blend modes, picture-in-picture, transitions | Future timeline/render extensions; do not silently flatten unsupported projects |
| Compression | Size/quality/bitrate goals, edited-duration budget, audio reserve, bounded retries, no silent duration/audio loss | Preserve upload contract; native byte-fit remains a distinct acceptance gate |
| Encoding | Container/codec/profile, resolution/FPS, bitrate/quality, hardware/software selection, metadata, stream mapping/remux | Reuse native Planner → FfmpegBridge.prepare → executor; do not bypass capability preparation |
| Export and batch | Whole project, range, individual clips, audio/still/GIF, queue, presets, output destination, direct sharing | Existing durable native queue for single-source jobs; compound export requires later integration |
| Reliability | Cancel, recovery, immutable job snapshots, source preservation, diagnostics, storage checks, progress, thermal limits | Test production behavior; never turn missing FFmpeg into simulated success |
| Professional extensions | Multicam, timecode, nested sequences, project interchange, review/collaboration, plugin effects, broadcast delivery | Optional scope, not prerequisites for a useful mobile editor |

## Implementation slices

1. **Production clip effects.** Add Android-free typed edits and a deterministic filter compiler. Integrate with Settings, Planner, queue serialization, native export verification and advanced controls. Crop, right-angle rotation, mirroring, constant speed, basic color, blur/sharpen, gain, audio/video fades and optional audio normalization use the existing FFmpeg path. Validate filter availability and reject unqualified routes before execution.
2. **Non-destructive timeline foundation.** Ordered clips with identity, source range, settings, split/reorder/remove/duplicate operations and bounded undo/redo. Preserve sources. This slice does not pretend that multiple queued outputs are one rendered composition.
3. **Separated tests.** Keep Android/JVM test sources and the new host/ADB harness under `testing/`, referenced by test source sets only. No production dependency may point back to test code. A build property must allow removing the test references/dependencies without changing production Kotlin.
4. **Device automation.** A separately installed instrumentation APK accepts ADB commands for capability inspection and real native editing/export checks. A host Python CLI handles device selection, test invocation, isolated inputs, JSON reports and failure exit codes. No root, exported app receiver, embedded HTTP server, arbitrary shell endpoint or scheduled automation.

## Native contracts

- Preserve API 26 minimum, SDK 36 and JDK 17. Do not change the FFmpeg source/licensing pins.
- UI, queued jobs and instrumentation consume the same production types/planner/executor.
- Arguments are token lists, not shell strings. Filter text is generated from validated typed values, never supplied verbatim in a job.
- Source trims happen before speed changes; audio and video timestamps start together. Verify the edited duration, not the original duration.
- Hardware capability checks must match the actual output geometry and filter route. Until qualified, explicitly reject edited hardware requests rather than silently choosing CPU or configuring the wrong dimensions.
- Extend the queue schema deliberately. Read old jobs with neutral edits; preserve corrupt/unknown schemas as errors. New edits survive encode/decode and immutable queue snapshots.
- Missing codecs/filters/native libraries are actionable failures. Capability inspection alone is not an export qualification.
- Only non-empty, structurally checked outputs may be published. Originals and unrelated queue entries must remain unchanged.
- Normalization is an optional processing effect, not a promise of two-pass broadcast loudness compliance.

## Test and evidence boundaries

| Level | What it proves | What it does not prove |
| --- | --- | --- |
| Host Kotlin checks | Edit validation, filter tokens/order, timeline/history behavior, duration math | Android packaging, native execution or image correctness |
| JVM/serialization checks | Queue round trips and legacy behavior | Device storage/lifecycle correctness |
| Android API build/lint | Source/API compatibility | Real FFmpeg payload or phone execution |
| Instrumentation capability command | Observed packaged engine capabilities | Codec correctness/performance |
| Instrumentation native export | Production planner/executor, probe/decode and output assertions on that device | Universal device support, visual quality or long-run thermal behavior |
| Manual/visual qualification | Brackets, touch ergonomics, A/V sync, visible transforms and preview/export agreement | Other devices or codecs not exercised |

Reports must identify the device/build, run ID, requested command, native build/capabilities, inputs/settings, output facts and errors. Host and device evidence must never be conflated. Generated media, APKs and reports are not committed. A requested native test fails when native FFmpeg is absent; it must not quietly skip.

## Definition of done for this draft

- [ ] Typed effects execute through the real production planner and survive queue persistence.
- [ ] Basic controls are reachable without changing the source-first home.
- [ ] Timeline reducer/history has host regression coverage.
- [ ] Tests are referenced externally and can be disabled for a test-free source checkout.
- [ ] ADB CLI and standalone instrumentation commands are documented, including JSON output and failure handling.
- [ ] Host checks are executed and exact unrun Android/device gates are recorded.
- [ ] Physical Android build/export/visual qualification is completed before marking the PR ready.
