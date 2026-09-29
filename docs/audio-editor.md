# Audio editor: design and framework decisions

**Status: first usable single-clip editor implemented and tested on the target LE2125 phone.** Read the [implementation and validation record](audio-editor-validation.md) for shipped behavior, artifact identities and unrun gates. The broader inventory below remains a roadmap.

Read [UI layouts](audio-editor-ui.md), then the [implementation plan](superpowers/plans/2026-09-29-audio-editor.md). This proposal turns the approved audio-feature inventory into native Android work; it does not add another Studio/WebView editor.

## Purpose and integration baseline

Make a source sound right and fit its destination without requiring a desktop DAW. Preserve Forma's source-first home, decimal-byte upload goals, visible direct-media URL input, expandable left shelf, bracket trimming and immutable queued settings. Editing is optional; selecting media must not automatically denoise, equalize or normalize it.

Inspected `main`: [`89ceb3b362872f193a54feacc99225c80e77f176`](https://github.com/Mainkill1/android-transcode/tree/89ceb3b362872f193a54feacc99225c80e77f176). The following are facts about that revision, not claims of phone qualification:

| Existing integration | What the proposal must extend |
| --- | --- |
| `core/src/main/kotlin/dev/forma/core/Models.kt` | `Settings`, `Source`, `Trim`, `JobSpec`, codec/container enums and observed `Capabilities`; no general audio graph yet |
| `core/src/main/kotlin/dev/forma/core/Planner.kt` | Typed validation and token-array command generation; track selection/bitrate/stereo, not a complete DSP editor |
| `engine-ffmpeg/src/main/kotlin/dev/forma/ffmpeg/FfmpegBridge.kt` | `capabilities`, `probe`, `prepare`, `execute`; every export still goes through preparation |
| `app/src/main/kotlin/dev/forma/app/data/JobCodec.kt` | Schema 1 JSON queue persistence at the inspected baseline |
| `app/src/main/kotlin/dev/forma/app/ui/FormaScreen.kt` and `AdvancedControls.kt` | Source-first Compose workspace, shelf, advanced settings and persistent queue actions |
| Existing service/run coordinator | Native session serialization, cancellation ownership and bounded progress |

[PR #2](https://github.com/Mainkill1/android-transcode/pull/2) was documentation-only at the implementation's inspected baseline `a362442b37c84da6654b56e044daff0d11684678`. This implementation retains existing `Settings.audioTrack` as the stream authority and adds one typed audio graph and queue schema migration. PR2 has since acquired separate clip/timeline code; integration of the two drafts needs explicit reconciliation before both merge. Preserve [AGENT-START](AGENT-START.md), [native UX](native-ux.md), [upload](upload-limits.md) and [FFmpeg](ffmpeg.md) contracts.

## Delivery slices and complete feature coverage

Stages are sequencing decisions, not availability badges. A feature becomes available only when its model, graph compilation, packaged dependency and actual execution pass their gates.

| Stage | Feature family | Included behavior |
| --- | --- | --- |
| A: useful single-clip editor | Editing | Existing bracket trim; gain; mute; fade in/out; peak normalization; optional measured loudness normalization; source audio-track selection |
| A | EQ | Simple bass/mid/treble backed by the same parametric model; bell, low/high shelf, high/low-pass, band-pass and notch; frequency/gain/Q; editable presets |
| A | Dynamics | Compressor, look-ahead limiter, threshold/ratio/knee/attack/release; honest peak-protection status |
| A | Channels and export | Mono/stereo, extract/swap channels, explicit downmix; AAC/M4A and MP4, MP3, Opus/Ogg, FLAC, WAV PCM when the packaged profile supports them; compatible stream copy |
| A | Feedback | Waveform, selection duration, peak/RMS, input/output LUFS and true peak when measured, A/B, undo/redo, estimated bytes and verified final bytes |
| B: fuller editing | Arrangement and time | Split, join, crossfade, insert silence, reverse, constant speed, independent tempo/pitch, markers, silence detection and reviewed silence shortening |
| B | Dynamics and voice | Expander, gate, de-esser, soft clipping, explicit auto-level preset, multiband compression, speech EQ and breath-region attenuation |
| B | Cleanup | Hiss/noise reduction, 50/60 Hz hum/harmonics, rumble, clicks/pops, de-crackle, DC-offset correction and clipping detection; adjustable strength and before/after |
| B | Creative effects | Reverb, convolution/impulse responses, delay/echo, chorus, flanger, phaser, distortion/overdrive/saturation, bit crusher, tremolo, vibrato, telephone/radio and robot-style presets |
| B | Mixing and analysis | Multitrack music/voiceover, recording, pan/balance, per-channel gain, width, mid/side, polarity inversion, phase correlation, 5.1/7.1 routing, ducking; spectrum, spectrogram, silence and loudness-range analysis |
| B | Automation and formats | Parameter envelopes; batch presets; metadata/artwork; ALAC, AIFF, Vorbis, AMR and AC-3/E-AC-3 only after encoder/container/profile qualification |
| C: optional extensions | Restoration and AI | Voice isolation, stem separation, dereverb, advanced wind reduction, clipped-waveform reconstruction, formant shifting and Doppler-style effects |

Do not label hum filtering as source separation, a noise gate as denoising, polarity inversion as phase alignment, or compressed-to-FLAC conversion as quality restoration. Label clipping reconstruction and AI cleanup as estimates. Advanced spatial effects require mono-compatibility listening tests. Silence shortening and pitch/tempo changes must expose their timing consequences.

## Framework decision

**Choose the existing FFmpeg engine for authoritative DSP/export, Compose for controls, and Media3 ExoPlayer for preview playback.** A new general-purpose DAW engine is not required for Stage A.

| Layer | Required/recommended implementation | Boundary |
| --- | --- | --- |
| UI/state | Existing Kotlin, Compose Material 3/Foundation/Canvas, ViewModel, coroutines/StateFlow | Draw waveform/EQ in Canvas; reuse 52 dp controls; keep processing out of composition |
| Domain | Existing Android-free `core` | Immutable edits, effect descriptors, automation validation, duration math and graph plans; no Android/FFmpegKit objects |
| DSP/export | Existing source-built FFmpegKitNext + FFmpeg libavfilter/libswresample | Retain pin `5e51b2da4c3593c0f2f9b49f53eeb497d93e39d3` and `com.arthenica:ffmpeg-kit-next:9.0.0`; inspect actual filters/encoders, not a marketing feature list |
| Playback | Add `androidx.media3:media3-exoplayer` when preview is implemented | Candidate pin **1.11.1**, listed stable September 10, 2026; dependency/API compatibility still needs a build against this repository [1][2] |
| Persistence/work | Existing `JobCodec`, queue repository, service and run coordinator | Version deliberately; no mandatory Room, Hilt, WorkManager or new service framework |
| Tests | Existing JUnit/AndroidX/Compose testing plus #2's isolated harness | Test-only sources/dependencies; no production CLI endpoint |
| Later low-latency audio | Optional Oboe/AAudio adapter and shared native DSP | Only for measured interactive monitoring/recording needs; Oboe is audio I/O, not a library of all effects [3] |
| Later stretch quality | Optional Rubber Band native build feature | Build/profile and licensing review first; not a default binary dependency [4] |
| Later AI | Optional model-specific ONNX Runtime Android module | Pin both runtime and licensed model/hash; CPU baseline first, benchmark accelerators per model/device; no NPU promise [5] |

Keep API 26 minimum, compile/target SDK 36 and JDK 17. Keep current Kotlin 2.2.21, AGP 8.13.2 and Compose BOM 2025.10.01 unless a separately reviewed compatibility change is necessary. New dependencies belong in `gradle/libs.versions.toml`; use exact versions, not `+`. ExoPlayer alone is sufficient for a custom Compose transport; add Media3 UI/session artifacts only when a concrete feature requires them. Do not add Transformer merely to replace the required FFmpeg export path. Its `AudioProcessor` integration is a possible future interactive-preview adapter, not proof of identical FFmpeg processing [2].

Rejected alternatives: platform session EQ/effects as the export engine (wrong ownership/parity contract); parallel Media3-only exports (conflicts with repo requirements); a heavyweight plugin host or mandatory ML runtime (unnecessary for the first useful editor). Do not add retired FFmpegKit binaries or unverified binary mirrors. The repository already uses a GPL/x264 build profile; this PR changes no licensing or native pin. Any added native library, IR or model needs provenance and distribution review [4][6].

## Effect registry and compilation

An `AudioEffectDescriptor` owns stable effect/parameter IDs, schema version, defaults, units, ranges, channel restrictions, preview support, required filters/model assets, latency/tail rules and automation eligibility. The native UI, presets, planner and instrumentation read this one registry. Nodes contain typed parameters, never user-authored FFmpeg strings. Unknown effects are preserved as unsupported and block export until explicitly removed or bypassed; never silently drop them.

Candidate backend mappings, subject to the pinned build's capabilities and native tests [7]:

| Family | Candidate libavfilter building blocks |
| --- | --- |
| Trim/gain/fades/joins | `atrim`, `asetpts`, `volume`, `afade`, `acrossfade`, `concat` |
| EQ | `equalizer`, `bass`, `treble`, `highpass`, `lowpass`, `bandpass`, `bandreject` |
| Dynamics/mix | `acompressor`, `alimiter`, `agate`, `deesser`, `acrossover`, `amix`, `sidechaincompress` |
| Cleanup | `afftdn`, `anlmdn`, `adeclick`, `adeclip`; `arnndn` additionally needs a compatible model |
| Analysis/levels | `astats`, `ebur128`, `loudnorm`, `silencedetect` |
| Time/creative | `atempo`, `areverse`, `aecho`, `chorus`, `flanger`, `aphaser`, `tremolo`, `vibrato`, `acrusher`, `asoftclip`, `afir` |
| Routing | `pan`, `channelmap`, `channelsplit`, `stereotools`, `aresample`, `aformat` |

This is not a claim that every listed filter is present. Detect decoder, encoder, muxer, filter options and external assets separately. Parameter IDs such as `thresholdDb` convert deliberately to the backend's units. Finite numeric validation, locale-independent formatting and filtergraph escaping remain necessary even when arguments are token arrays.

## Non-destructive model and signal order

Proposed core contracts (names are new, not existing APIs):

```text
SourceAudioFacts(streamIndex, sampleRateHz, channelLayout, sampleFormat, durationUs, encoderDelay, padding)
AudioEdit(schemaVersion, selectedStreamIndex, clipChain, outputPolicy)
AudioEffectNode(id, effectType, version, enabled, typedParameters, automation)
AudioOutputPolicy(channelLayout, sampleRateHz, encoding, normalization, peakProtection, tailPolicy)
AutomationLane(effectId, parameterId, timeDomain, points)
AutomationPoint(timeUs, value, interpolation)
TimeRangeUs(startInclusive, endExclusive)
AudioGraphPlan(typedOperations, requiredCapabilities, outputDurationUs, analysisRequirement, identity)
```

Probe `SourceAudioFacts` from the staged source; unknown layout/delay/padding stays unknown rather than being invented from channel count. Preserve stream language/title metadata separately for selection.

`clipChain` is an ordered list of nodes. Stage A supports one selected stream from one clip; queued files remain separate outputs. Later audio tracks attach to #2's timeline/project identities, not a new unrelated timeline. Track chains and the master chain then become explicit scopes.

```text
original staged sources -> selected streams -> source trims / timing map
  -> clip effect chains / envelopes -> arrangement and crossfades
  -> track chains / pan / bus mix -> master creative chain
  -> final channel layout -> loudness / output protection
  -> final sample format/rate -> encoder -> muxer
  -> decode/probe/measure final artifact -> byte-cap check -> publish
```

Creative nodes may be reordered within their scope. Final normalization/protection stays in a clearly separate Output section so moving a reverb cannot secretly place it after the final safety stage. Final resampling/encoding can still change measured peaks; verify the delivered artifact.

Use 32-bit floating-point processing as the initial working policy where supported, without silently reducing a higher-precision source before the qualified processing boundary. Audio-only export defaults to a supported source rate; video preset defaults to 48 kHz. Never resample repeatedly between effects without a documented requirement. Dither is an explicit integer-PCM bit-depth-reduction decision, not a switch automatically applied before AAC/Opus.

Audio-local editing uses integer microseconds and rational speed maps; derive sample positions with one documented rounding rule, not accumulated floating-point milliseconds. Source trim occurs before speed. Fade/envelope points reference the resulting clip timeline and are explicitly rebased on split. V1 automation supports step/linear gain and pan only; unsupported animated parameters must be rejected. Add others only with a tested processing path; FFmpeg commands do not imply sample-accurate automation for every filter.

Video-linked audio keeps duration and synchronization by default. Silence removal produces a reviewed edit-decision list applied to both tracks, or is disabled while linked; it must not simply shorten the sound. Likewise, reverse/speed must change the linked video or require explicit unlinking. Default tail policy preserves project duration. Audio-only users can explicitly include reverb/delay tails, which changes duration and the size budget. Compensate processing latency, encoder delay and padding deliberately.

## Loudness and output correctness

Normalization defaults to **Off**; provide distinct Peak and Loudness choices. Product starting presets such as Voice `-16 LUFS / -1.5 dBTP` are editable Forma choices, not universal platform mandates.

Measured loudness export is a two-pass job over the exact edited program. Analyze the final creative mix and channel layout, then apply its measurements to the matching render. Store integrated loudness, true peak, loudness range and gating threshold with the graph/source/native-build identity. A trim, EQ, channel, speed, model or automation change invalidates the measurements. Silence/non-finite measurements are structured `not measurable` results, not JSON infinities or huge gain corrections.

FFmpeg's linear normalization can fall back to dynamic processing when its constraints are unmet [8]. Record the effective mode; a preserve-dynamics policy must instead decline an unattainable target. A sample-peak limiter must not be described as a guaranteed true-peak limiter. Qualify its auto-level and latency options, and measure again after lossy encoding. Do not automatically add an unmeasured extra limiter after normalization.

One job owns analysis, render and verification, with separate phase labels and cancellation across all phases. Cache only identity-matched results. Every changed retry is prepared again through `FfmpegBridge.prepare`. Size estimation includes **edited duration, tails, audio bitrate, video bitrate and container overhead**. Lossless encodings cannot promise a target byte size; offer a lossy preset when needed, with explicit user choice. Never remove audio or shorten the selected duration to meet the cap. Only an existing, valid output strictly below the decimal-byte limit may be published.

Stream copy is eligible only with compatible container/timing and no active audio processing. Exact arbitrary cuts generally need decode/re-encode; distinguish packet-boundary copy from sample-accurate editing. Changing audio alone may leave video copy eligible, but only through the separately validated video route. Hardware video encoding does not accelerate audio filters automatically.

## Preview, resource use and delivery evidence

Stage A previews use a **bounded FFmpeg-rendered PCM window played by ExoPlayer**, using the same compiled creative graph as export. This avoids pretending that a different playback EQ is export-equivalent. For stateful effects, process required history; exact mode may need rendering from the clip start. Whole-program loudness analysis cannot be replaced by measuring ten seconds around the playhead.

Expose `Original`, `Rendered preview`, `Updating`, `Stale`, `Unavailable` and later `Live approximation`. Stale work cannot overwrite a newer graph revision. Level-matched A/B changes monitoring only, never saved output settings. Rendering a selection can be accurate for that graph without proving the lossy output is identical. A final-output audition plays the verified encoded artifact.

Keep one active native task under existing coordinator ownership; lower-priority preview/analysis yields safely to exports. Do not release a slot before native cancellation completes. Tile waveform/spectrum caches on disk, render only the visible range, bound PCM windows/cache bytes and reject long reverse operations that exceed the temporary-storage budget. No full-file PCM buffers in Compose/ViewModel. Optional real-time callbacks allocate no memory and do no disk I/O or blocking; this requirement belongs to the later Oboe slice, not a claimed current capability.

Report modeled, compiled, packaged, device-tested and listening-qualified separately. No-native builds may edit project settings but must show render/export unavailable. A requested native test without native libraries fails, not skips. Tests and generated fixtures stay outside release runtime; see the implementation plan for ADB and artifact gates.

## Primary references

References checked September 29, 2026. Current upstream documentation describes possible APIs, not the capabilities of this repository's pinned native binary.

1. [Media3 release notes and dependency coordinates](https://developer.android.com/jetpack/androidx/releases/media3)
2. [ExoPlayer setup](https://developer.android.com/media/media3/exoplayer/hello-world) and [Media3 audio-processor integration](https://developer.android.com/media/media3/transformer/transformations)
3. [Google Oboe project](https://github.com/google/oboe)
4. [Rubber Band licensing](https://breakfastquay.com/rubberband/license.html)
5. [ONNX Runtime mobile deployment](https://onnxruntime.ai/docs/tutorials/mobile/) and [XNNPACK provider](https://onnxruntime.ai/docs/execution-providers/Xnnpack-ExecutionProvider.html)
6. [FFmpeg licensing](https://ffmpeg.org/legal.html)
7. [FFmpeg filter reference](https://ffmpeg.org/ffmpeg-filters.html)
8. [FFmpeg loudnorm linear-mode clarification](https://ffmpeg.org/pipermail/ffmpeg-cvslog/2020-January/120713.html)
