# Forma native Android handoff

Preparation date: 2026-09-28 (America/Los_Angeles).

## Product, not prototype

Forma is an Android application written in Kotlin/Jetpack Compose. FFmpeg runs
inside the Android application through the existing native binding, not through
a desktop service. The HTML Studio is a design reference and independent export
oracle. Do not ship Python, localhost HTTP, browser-only encoding, a privileged
WebView, or Media3 Transformer as a replacement for required FFmpeg exports.
Media3 may be used for preview playback if it does not change export ownership.

Retain API 26 minimum, SDK 36 compile/target and JDK 17. Prioritize arm64-v8a for
modern phones; x86_64 is for emulator coverage and does not prove phone hardware
acceleration. Root access is not required, including on the user's OnePlus 15.

## What is implemented and what is not

| Area | Current native status | Required work |
| --- | --- | --- |
| Compose app, settings, document selection | Existing foundation | Port the current upload-first Studio UX |
| Durable queue, foreground service, cancellation, private staging, output sharing | Existing foundation | Version job model for size goals/edits and test recovery end-to-end |
| Source-built FFmpegKitNext adapter | Existing API-integrated path; actual bundle built separately | Package and qualify real Android .so files |
| H.264/H.265 device encoder preparation | Added here: request checks, concrete MediaCodec component binding, VBR and buffer format selection | Physical-device exports, failure classification, per-attempt telemetry |
| Automatic/CPU/required-hardware policy | Added as pure Kotlin policy with tests | Wire user preference into versioned native size-goal jobs and expose effective route |
| Hardware decode | Not enabled by this preparation | Qualify independently after buffer encode is correct |
| GPU transforms / surface pipeline | Not implemented | Separate bounded surface-ownership project |
| NPU | Research/design only; no runtime/model bundled and no UI switch pretending to work | Optional measured analysis/filter experiment |
| Size fitting, image conversion, URL import, bracket editor | Working in Studio only | Port into native Kotlin and the Android executor |

`FfmpegBridge.prepare` is now called by `FfmpegTranscoder` after staging/probing.
For existing explicit H.264/H.265 device selections, the native implementation
checks exact output configuration using `AndroidCodecCatalog`, then binds
`-codec_name:v` and VBR. It does not silently enable hardware on existing quality
presets or change old saved job semantics. Current device exports require explicit
output fps, SDR/8-bit input and no display-matrix transform. CPU filters/scale
remain CPU filters; input decode remains software. A hardware preparation failure
is a real error for explicit device selection, not an invisible software retry.

The `AUTO` policy is usable by the next native upload planner, but its UI,
serialization and executor fallback loop are NOT integrated in this preparation.
It returns advertised-compatible, not device-qualified, decisions. A capability
query is not an output correctness or speed test. The failure classification hook
only allows software retry for positively identified codec initialization failure.
Do not infer that classification from any nonzero FFmpeg exit code.

## Non-negotiable user workflow

Home: 10 MB default; 20, 25, 50, 100, 500 MB and Custom. Select media and a visible
HTTP(S) media-URL field. No wall of codec controls or preset dashboard. After a
source is inspected, show the chosen output, the size limit and one Convert
button. More settings and the expandable left shelf reveal detailed controls
without discarding edits. Controls reserve at least 48 dp; primary actions use
52 dp. Accommodate font scaling, screen insets, keyboard, portrait and landscape.

Native editor: source thumbnails (waveform for audio), two visible trim brackets,
kept region, hatched removed regions, independent playhead, exact time readouts,
zoom/Fit, tap nudges, undo/redo, split and kept-clip ordering. One drag is one undo
operation. Cancellation restores the pre-drag selection. Other kept clips must
not be shown as discarded. Export exactly the selected cuts and their order.
Start with single-source non-destructive editing; unrelated-file compositing is
not a prerequisite for the useful transcoder.

Video/audio/image goals must port `studio/upload_limits.py` and its tests into
Android-free Kotlin. `targetBytes` belongs to each immutable job, not a global
preference consulted midway through execution. 10 MB means 10,000,000 bytes.
Success requires `0 < actualBytes < targetBytes`, after structural/decode checks.
Budget for the finished edited duration, audio and mux overhead. Four total
video/audio attempts and seven image attempts, bounded and cancellable. Each
retry uses the original staged source; never the previous lossy output.

An oversized valid file reduces bitrate/quality/dimensions as appropriate. It
must not silently lose duration, frames due to an artificial file-size stop,
sound, transparency, or edits. Never use `-fs` to pretend a target was met.
Cancellation, corruption, storage errors and unsupported encoders are not size
overshoot. Preserve failing attempt logs without exposing invalid/oversized
outputs through share/download actions. Strict byte verification applies equally
to software and MediaCodec output; hardware bitrate requests do not guarantee
file size.

Initial image coverage: PNG/JPEG/WebP stills, alpha-preserving automatic choice,
explicit rejection of JPEG with alpha without an agreed flatten operation. No
silent GIF/APNG/animated-WebP flattening. HEIC/AVIF and color-managed/HDR image
support remain separate extensions.

## Native package and execution gates

Release preparation now rejects a missing `ffmpegEnabled=true` flag. This is a
build-intent guard, not a substitute for inspecting the actual native payload.

The pinned wrapper is `arthenica/ffmpeg-kit-next` commit
`5e51b2da4c3593c0f2f9b49f53eeb497d93e39d3`, artifact
`com.arthenica:ffmpeg-kit-next:9.0.0`. Preserve the existing GPL/x264 build policy;
application licensing is still an explicit distribution decision. The maintained
source wrapper is a binding, not a binary download service. [1]

The build helper explicitly enables MediaCodec with the upstream option
`--enable-lib-android-media-codec`. That option defaults off. Upstream also enables
JNI in its Android FFmpeg configure script. Do not send raw FFmpeg configure
options to the wrapper's build frontend. [2]

Build on an upstream-supported Nix host (or follow its documented non-Nix route):

```bash
./tools/build-ffmpeg.sh
NATIVE_REPO="$PWD/vendor/ffmpeg-kit-next/prebuilt/bundle-android-aar-24-maven"
python3 tools/verify_android_native.py \
  "$NATIVE_REPO/com/arthenica/ffmpeg-kit-next/9.0.0/ffmpeg-kit-next-9.0.0.aar" \
  --json native-aar-report.json
./gradlew -PffmpegEnabled=true -PffmpegRepo="$NATIVE_REPO" \
  :core:test :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug
python3 tools/verify_android_native.py app/build/outputs/apk/debug/app-debug.apk \
  --json native-apk-report.json
```

The verifier requires actual FFmpeg and wrapper libraries per requested ABI,
checks all packaged 64-bit ELF libraries, 16 KB PT_LOAD alignment, RELRO end
alignment and uncompressed APK ZIP offsets. It records hashes, not a runtime
qualification claim. Run the official `zipalign -c -P 16 -v 4` check too. Android
requires compatible native dependencies, not just a compatible app module. NDK
r28+ defaults to 16 KB; pinned r27d builds need suitable linker flags. [3]

The supplied older no-native preview APK fails the new gate: it has no complete
FFmpeg payload and its arm64 AndroidX path library has a non-16-KB RELRO end.
This is a packaging/static-check finding, not an observed crash on a phone.
Do not change the upstream pin or patch vendor output silently to hide the gate.
Update/rebuild affected native dependencies deliberately, then retest both 4 KB
and 16 KB devices. A passed Kotlin/API-only CI artifact cannot substitute for this.

Opt-in native smoke test (a missing native engine is a failure once requested):

```bash
./gradlew -PffmpegEnabled=true -PffmpegRepo="$NATIVE_REPO" \
  -Pandroid.testInstrumentationRunnerArguments.class=dev.forma.app.NativeAccelerationSmokeTest \
  -Pandroid.testInstrumentationRunnerArguments.formaNative=true \
  :app:connectedDebugAndroidTest
adb exec-out run-as dev.forma.transcode cat files/native-readiness/h264-smoke.json > h264-smoke.json
```

It generates a real short H.264/AAC source, prepares a checked MediaCodec encoder,
encodes, probes dimensions/tracks/duration, and decodes the result with FFmpeg.
It writes actual device/component/page-size/build/timing metadata. It is NOT a
speed comparison, perceptual quality test, long-run thermal test or complete
device certification. The agent must add those cases before promoting a route.

## Android-specific ownership and lifecycle

Use SAF `content://` URIs and persistable grants when offered. Do not invent
filesystem paths for document providers. Stage seekable private files for probing,
faststart and retries; check free storage and show preparation/download phases.
Share only verified output via FileProvider/MediaStore/SAF with intended grants.
Keep originals unchanged and omit source URLs, tokens and private paths from
exported diagnostics unless explicitly requested.

Fetch direct HTTPS media in a bounded Android networking layer with cancellation,
redirect/size limits, progress and validation. HTTP requires a deliberate network
security policy; do not enable cleartext globally as an incidental workaround.
Do not invent URL duration/dimensions, add DRM extraction, or imply website URL
resolution. Plan jobs from fully inspected local media, not remote guesses.

Retain user-started foreground media processing. API 35+ has `mediaProcessing`
foreground-service time budgets; handle `onTimeout`, stop promptly and preserve
interrupted job state. Native cancellation owns native completion: never delete
staging buffers/files while the native worker is still reading them. A hung native
session needs a designed watchdog/process boundary, not unsafe forced cleanup.
Do not use WorkManager as a way around Android foreground-service rules. [4]

Thermal/power policy is still an implementation milestone: bounded worker count,
one video hardware encode at a time, no parallel AI inference by default, and
no new attempt while thermally constrained. Do not promise pause/resume of a
half-written FFmpeg output; show waiting/cancelling/restarting honestly.

## Acceptance evidence

Record exact app commit, native package hash/configuration, OS build fingerprint,
ABI and page size, Android codec component, source hash, job JSON, output bytes,
output probe/decode result, elapsed time, stage timing, retries and thermal state.
Compare software and hardware on the same job, with equal size/compatibility
requirements; faster output at much worse quality is not a comparable win.
Use the user's OnePlus 15 as an initial candidate, then independent Qualcomm,
MediaTek, Tensor and Exynos devices as available. Query capabilities; no capability
is inferred from a marketing chip name or NPU TOPS figure.

Current host tests do not establish real AAR packaging, Android execution,
physical-device support, full upload-native parity, GPU filtering or NPU speedup.
The agent must report unrun gates instead of inheriting Studio's green status.

## Primary references

[1] https://github.com/arthenica/ffmpeg-kit-next/blob/5e51b2da4c3593c0f2f9b49f53eeb497d93e39d3/android/README.md

[2] https://github.com/arthenica/ffmpeg-kit-next/blob/5e51b2da4c3593c0f2f9b49f53eeb497d93e39d3/scripts/help-android.sh and https://github.com/arthenica/ffmpeg-kit-next/blob/5e51b2da4c3593c0f2f9b49f53eeb497d93e39d3/scripts/android/ffmpeg.sh

[3] https://developer.android.com/guide/practices/page-sizes

[4] https://developer.android.com/develop/background-work/services/fgs/timeout
