# First usable audio editor: implementation and validation

PR3 contains the first usable native single-clip editor. The current reviewed product head is `41c30b5`; its host/build and matching phone checks are recorded below. Earlier downloaded-media/UI results belong to `8dfab26` and remain historical evidence. The remaining B/C roadmap is not implemented.

## Using the editor

Share audio/video from Files or Gallery to Forma, or use Add files. Tap **Edit audio** after inspection. Gain, mute, fades, parametric EQ, compressor and limiter are optional ordered nodes. Numeric fields use **Set** or the keyboard's Done action. Nodes can be bypassed, moved or removed; EQ bands can be added/removed. **Undo/Redo** covers audio effects and output policy, with continuous gain changes grouped into one undo entry. Existing source trim has its own controls and is outside that history.

**Render preview** creates original and edited float-PCM previews using the export graph. The waveform, peak/RMS and playback cover the first ten seconds of the selected range. **Play original**, **Play edited**, pause/resume, seeking and optional listening-level matching use Media3 ExoPlayer 1.11.1. Matching changes monitoring volume only. Changing the source, range, track, graph or relevant output routing invalidates the preview. Export waits for native preview cancellation to finish. Cached PCM is private, replaced between previews and cleared on startup; only 512 waveform peaks are retained in UI state.

Expand output controls for M4A/AAC, WAV/PCM16 or float PCM, FLAC, channel routing, sample rate, peak/loudness normalization and a decimal-MB cap. Available choices depend on the packaged native profile. This profile does not provide `libmp3lame` or `libopus`; their presence is not inferred from unrelated muxers or the experimental native Opus encoder. Normalization is off by default. EQ graphics show the band positions, not a measured combined frequency-response curve.

**Convert** snapshots the current edit into the persistent queue. Source files are never overwritten. Completed jobs expose the existing Play output / Share output / Save copy actions. Progress retains percentage, conversion rate, ETA and optional battery watts. This phone's battery-current sign cannot provide a trustworthy discharge reading, so watts remain unavailable here.

## Correctness and persistence

- One typed immutable audio graph is compiled in `core` and used by export, analysis and rendered preview. Processing uses sample-index trim and explicit rate anchors, rational speed and nearest-frame rounding; channel routing precedes normalization.
- Queue schema 2 reads schema 1 with neutral audio defaults. Current saved snapshots require exact integer/boolean/text types, all writer fields (including explicitly nullable trim/output fields), and complete known effect parameters and EQ bands. Missing or unknown queue fields fail without rewriting the original file. Sparse external recipes keep their separate defaulting behavior. Future effects and unknown effect fields remain opaque; active unsupported data blocks export until explicit bypass/removal.
- Edited video audio is padded to the selected video duration. Creative processing with a nonzero linked audio timestamp offset is rejected until that synchronization path is qualified.
- Peak measurement remains valid for short clips whose integrated loudness is not measurable. Silent/nonfinite loudness is a typed failure. Preserve-dynamics loudness rejects unattainable targets; zero measured loudness range uses constant measured gain to avoid FFmpeg's dynamic fallback.
- Outputs undergo strict full decode, independent retained-stream start/end checks and decoded video frame counting before publication. Explicit CFR jobs require the expected count, allowing floor/ceiling only for fractional frame windows; a container duration alone cannot establish completeness. Clockless WAV inputs use observed packet timestamps rather than an assumed zero origin. The audio DSP verifier also checks rate/channels and exact PCM/lossless sample count where available, then measures normalization. Loudness tolerance is 0.5 LU; true-peak ceiling tolerance is 0.1 dB. A capped artifact must be strictly smaller than the requested bytes. Cap exhaustion fails with a lower-bitrate/larger-limit action; this slice does not implement automatic fit retries.
- Publication renames only a verified new artifact and waits for durable Completed state. A failed or cancelled completion callback rolls back that new file; Stop after durable completion retains it. Native cancellation joins before working-file cleanup.
- Save copy accepts only a destination that can be opened for reading and observed to be empty, before opening it for writing or deleting it on failure. Existing originals, aliases and destinations whose emptiness cannot be verified are rejected, including after Share has copied the input into private storage.

## Current reviewed checkpoint, 2026-09-29

At `41c30b5`, **89 JVM tests passed**: core 36, engine 20, app 33. The test-only `9fe947a` follow-up passed **90** (36/20/34), adds deterministic completion-publication coverage and waits for published coordinator state; all qualified APK hashes are unchanged. Native-enabled debug app/test assembly and lint passed, including compilation against the pinned native API. Fourteen desktop media checks cover truncated retained tracks, wrong CFR counts, offsets, trims, Matroska and real WAV-to-M4A/FLAC exports; source hashes remain unchanged. Queue, saved-node intent, publication and Save policy regressions passed. Both debug APKs passed native payload and 16 KB ELF/ZIP alignment checks. These host and packaging checks do not establish phone runtime qualification.

The parent also built the unsigned minified release with `testing/` physically absent and `audioTests=false`. Native payload/alignment and structural DEX exclusion checks passed: no test runner, audio/Share/Save fixtures or test commands remain. This release was statically checked, not installed.

```text
current debug APK  da335dc6321cd766a9b61cc62f2c546ec797407e2f61fe5ba81628bb067a5f29
current test APK   d2eba074a2084f4a3c666c3af8fc93b69d8f2aee2d920b76dc62e11f477b368f
unsigned release   6baee2f97f71c5d0b142e4cb1d9ce3002a815c44bf264555230ea7e2bfde670c
```

The matching `.lab.audio` app/test pair passed **25 physical-phone tests**, with `formaNative=true`, zero failures and no assumption skips. This includes native DSP/analysis/preview/jobs, real Share-to-foreground-worker execution, imported-original/alias/unreadable Save rejection and successful new-empty Save, Compose/touch checks and native smoke. Target: OnePlus 9 Pro LE2125, reported API 36, arm64, 4096-byte pages. The parent log is `source/vendor/pr-readiness/pr3-final-native-instrumentation.txt`; this run does not repeat the historical downloaded-media/UI measurement below.

## Historical phone checkpoint: `8dfab26`, 2026-09-29

Target: OnePlus 9 Pro **LE2125**, reported API **36**, arm64, **4096-byte** pages. Native build: FFmpegKitNext **9.0.0**, FFmpeg **n9.0.1**, original source/licensing pin unchanged. Artifact checks are separate from runtime qualification.

| Gate | Observed result |
| --- | --- |
| Host JUnit | **68 passed**: core 36, engine 11, app 21; graph timing/routing, validation, migration, analysis, preview identity, undo and controller cancellation |
| Build / lint | Debug app, separate instrumentation APK and unsigned minified release built; lint has zero errors |
| Existing standalone checks | Core, UX/concurrency and Android-readiness suites passed; native verifier Python checks passed |
| Physical instrumentation | **24 passed**, including existing source-first, Share imports, touch targets and native smoke plus audio DSP/analysis/preview/jobs/UI |
| Packaged PCM DSP | 44.1/48 kHz exact frame counts; neutral null peak 0; requested −6 dB measured −6.0000005 dB; +6 dB EQ measured about +6.000002 dB; fades, sustained compression, limiter final impulse, mono/swap, Unicode paths and full decode passed |
| Packaged normalization | AAC fixture delivered **−16.02 LUFS** against −16 target, **−11.3 dBTP** below −1.5 ceiling; short 200 ms peak-normalized stereo delivered **−0.9999997 dB**, 9600 frames; silent results and cancellation join tested |
| Preview | Ten seconds, 512 waveform tiles, −6.0000005 dB gain; actual float-PCM playback/seek/pause passed. One fixture rendered in **331 ms**; this is not a universal latency bound |
| Production job runner | Default-stereo loudness AAC preserved ten seconds; 100-byte cap failed without publication; cancellation at verification did not publish |
| Downloaded media through UI | Files → Share → Forma, `sample-15s.wav` (actual 19.173878 s), gain −6 dB, render/A-B/pause, Convert: selected 13.591 s exported as **13.590998 s**, AAC 44.1 kHz stereo, **272860 bytes**, independently decoded and measured **−6.034 dB** |
| Release isolation | Release built with `testing/` physically absent and `audioTests=false`; release dex excludes test runner, audio device classes, Share fixture provider and test commands |
| Native packaging | Debug/release native payload and 16 KB ELF/ZIP alignment checks passed; 16 KB runtime remains untested |

Historical artifact SHA-256:

```text
debug APK      1af8f3e31fc13b9b769efd58a3e9527871e90bbac53cb83da7e3e3c360404cfc
test APK       3f7367213621bc727ba05a9322a731c9e8d71250068ff62dee190010f43671c1
unsigned release
               726f013b0617381bd036e1c0fa657e4e4d2615dcc07f0186699365ccfcaa0075
UI AAC output  2c170a0e54bd91da05f8c3814a809a2b24797a07af5b9fad5c0e6bd6cb671d54
source WAV     33e2e7b2ffa021275a90a26704d923fe902d3600e4ffecf06253c57778a2a986
```

Generated evidence stays untracked under `testing/results/` and workspace build/review directories. No media, native binaries, APKs or signing material is committed. The signed debug build was exercised on the phone; the unsigned release was statically checked, not installed.

## Repeat the checks

Supply your Android SDK/JDK 17 and source-built native Maven repositories as described in [device validation](device-validation.md).

```bash
./tools/check-core.sh
./tools/check-ux.sh
./tools/check-android-readiness.sh
./gradlew -PformaLab=true -PffmpegEnabled=true -PffmpegRepo="$NATIVE_REPO" \
  -PgraphicsPathRepo="$GRAPHICS_REPO" \
  :core:test :engine-ffmpeg:testDebugUnitTest :app:testDebugUnitTest \
  :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug
python3 -m unittest discover -s testing/host -v
adb -s "$SERIAL" install -r app/build/outputs/apk/debug/app-debug.apk
adb -s "$SERIAL" install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s "$SERIAL" shell am instrument -w -r -e formaNative true \
  dev.forma.transcode.lab.audio.test/androidx.test.runner.AndroidJUnitRunner
python3 tools/verify_android_native.py app/build/outputs/apk/debug/app-debug.apk
./gradlew -PaudioTests=false -PffmpegEnabled=true \
  -PffmpegRepo="$NATIVE_REPO" -PgraphicsPathRepo="$GRAPHICS_REPO" :app:assembleRelease
```

The optional lab build uses `dev.forma.transcode.lab.audio` and its matching `.test` package; derive report paths and instrumentation targets from that package. The normal release ID is unchanged. For normal-ID builds, `python3 testing/audio_device.py all --serial "$SERIAL"` runs the host harness (`capabilities`, `dsp`, `analysis`, `preview`, `jobs`, `ui` and `all`); that script currently uses the normal package ID. It installs the matching APK pair, removes stale native reports, runs one instrumentation session at a time and rejects failures/crashes even if ADB exits zero. Each generated report includes run ID, repo/diff identity, APK hashes, device/API/ABI/page size, scenario time and scenario-specific native facts. Fixed test scenarios generate their own inputs in the debug sandbox and clean up. There is no raw project JSON command endpoint, staged-path protocol, exported receiver, root requirement or production server. Memory telemetry/model hashes are not reported; no optional model is bundled.

## Remaining qualification and coordination

The target phone covers portrait interaction and native signal correctness, not exhaustive Android qualification. API 26 runtime, a second device, real 16 KB pages, surround inputs, wide layout, enlarged text/TalkBack/keyboard/rotation, sustained large media, headphones/Bluetooth/focus route events and listening/A-V sync review remain unrun. Focus/noisy-route/lifecycle handling is implemented but those physical route events are not certified. Cancellation joins correctly; the rate-limited test took roughly 30 seconds and establishes no responsiveness guarantee.

Stage B/C cleanup, AI, multitrack, automation, full waveform/loop/zoom, EQ presets/response plot and automatic upload fitting are outside this selected first usable slice. PR2's newer independent clip/timeline implementation and PR4's settings migration must reconcile with this graph/queue before their branches merge; this PR does not claim those integrations already passed.

## Review-readiness CI correction

The original no-native emulator workflow ran four native-only audio cases without opt-in and failed for the missing encoder. Those cases now require `-e formaNative true`, matching the existing native smoke contract. Without that argument the four cases were observed as assumption skips; with it the historical matching native app/test APK pair passed **24 physical-phone tests** with no failures or skips. That result does not qualify `41c30b5`. Native availability checks remain inside each explicit test, so a requested native run cannot pass by skipping a missing encoder. This changes instrumentation setup only, not audio processing.
