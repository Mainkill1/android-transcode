# Runtime codec trials: implementation and handoff

This implements the 2026-09-29 request to **try actual Android encoders instead of
letting capability reports decide everything**. It supersedes the advertised-only
selection rules in the older acceleration handoff for the application export path.
The old `AccelerationPolicy.choose` remains an advertised-only helper for existing
callers/tests; production exports now use `CodecTrials.plan` and `ExportRetry.run`.

## What is now implemented

| Area | Implementation |
| --- | --- |
| Real application exports | `FfmpegTranscoder` calls `prepareAttempts`, executes bounded trials, decodes/checks each successful output, then publishes only the accepted artifact. |
| Automatic mode | H.264/H.265 Automatic choices are available in the existing contextual encoder selector. CPU fallback retains format, trim, bitrate, filters, dimensions, audio and requested fps. |
| Required-device mode | H.264, HEVC, VP9 and AV1 device choices; no fallback to a software FFmpeg encoder. A compiled wrapper and an enumerated encoder component are necessary. |
| Capability reports | Size/rate/bitrate/buffer support are ordering hints, not vetoes. A reported NO or query failure can still lead to an actual trial. |
| Trial configurations | Up to 3 distinct components, each with NV12/YUV420P and VBR/CBR. Breadth-first order tries other components before additional variants. At most 12 device attempts plus 1 Auto software attempt. |
| Error handling | Typed try/catch at the executor. Recognized component-local initialization failures and explicit output rejection can retry. Unknown errors, I/O, cancellation and programming exceptions do not. |
| Evidence | Private per-job `files/outputs/<job-id>.acceleration.json`; separate instrumentation reports with device identity, media hashes and actual selected route. |
| Testing | External engine JVM tests, app queue tests, Android instrumentation, and a fail-closed ADB collector; no production test receiver or network server. |

The actual pipeline remains **CPU decode -> CPU filters -> MediaCodec encode ->
FFmpeg mux**. No GPU, NPU, hardware-decode, NDK-async, zero-copy, or speedup claim is
made by this change. FFmpeg remains the in-process export owner; source pins and
licensing policy are unchanged.

## Exact attempt contract

1. Stage and inspect the original. Preserve the immutable queued settings.
2. Resolve Automatic / software-only / required-device intent. Source-rate/VFR,
   constant-quality, and display-matrix jobs use software in Automatic mode rather
   than silently changing their semantics. Required-device jobs reject unsupported
   media semantics. HDR/high-bit-depth remains a separate color-pipeline feature.
3. Enumerate encoder components for the requested MIME. A decoder is never used as
   an encoder; known software-only MediaCodec components are excluded. API 26–28
   and ambiguous newer flags can be tried as **hardware UNKNOWN**, never relabeled
   as proven hardware. Required-device means no software FFmpeg fallback, not a
   fabricated guarantee about unknown OEM internals.
4. Order using advertised configuration, provisional same-device manufacturer
   performance hints and platform preference. These are not measured speed rankings.
5. Execute each entire trial through the normal FFmpeg adapter. There is no fake
   configure-only success, silent resolution downgrade, dropped audio, `-fs`,
   `cbr_fd`, `-hwaccel auto`, or assumption that an NPU is an encoder.
6. FFmpeg return codes do not become arbitrary Java MediaCodec exceptions. The
   adapter accumulates session-local error evidence and recognizes specific encoder
   create/configure/start messages. A known storage/input/resource/filter error
   vetoes codec-init fallback. Unknown/new log forms remain fatal until deliberately
   recognized and tested. Errors after encoded-frame progress are not reclassified
   as initialization errors. Java exceptions are still propagated unchanged.
7. Wait for native completion/cancellation cleanup before removing an unsuccessful
   private output. Never feed that output into the next attempt. Keep one hardware
   export active under the existing app coordinator and bridge mutex.
8. A zero return code must be followed by a nonempty output, full decode, track,
   geometry, SDR color and duration checks. Known invalid-output errors may try
   another device route; unknown verification/I/O failures stop. Duration tolerance
   is max(250 ms, two requested frame periods), not the old 5% of the whole film.
9. Publish only after those checks. Per-attempt verification occurs within RUNNING;
   the existing VERIFYING state is retained for final publication. Reports say
   publication is tracked by the durable queue, rather than asserting it early.

A passing output check is **not** perceptual-quality, per-frame completeness,
A/V-sync, HDR, thermal, or whole-device qualification. No successful/failed route is
persistently cached or globally blacklisted yet. Each job can retry a component
that was temporarily busy earlier; a later bounded positive-preference cache must
remain an ordering hint keyed to exact configuration and runtime/device identity.

## User-visible and persistence behavior

Existing software and explicit-device choices retain their meanings. Default
`Settings()` is still X264: opening this version does not rewrite older queues to
Automatic. Selecting an Automatic choice in the UI explicitly selects bitrate
mode and supplies 30 fps when the prior value was source-rate, just as the existing
device selector did. Users can choose source-rate or constant-quality afterward;
Automatic then preserves that intent using software.

The queue continues to serialize encoder enum **names**, not ordinals. App tests
cover all encoder-name round trips and unchanged legacy defaults. No new queue
field or schema rewrite is required. Older app versions cannot read the newly
introduced enum values; their existing unknown/corrupt-data path preserves the
queue rather than silently resetting it.

Diagnostics contain chosen backend/component, hardware YES/UNKNOWN, buffer, rate
mode, attempts and outcomes. They omit source URIs, paths and raw native logs.
Software-only exports also pass through the common verifier. Decoder/filter/NPU
stages must not be labeled accelerated based on encoder selection.

## Reproducible commands

Host checks (no phone or Android SDK implied):

```bash
bash tools/check-acceleration.sh
bash tools/check-runtime-acceleration.sh
```

On a full checkout with JDK 17, SDK 36 and the existing source-built native Maven
repository, keep the repository's native payload checks and run:

```bash
./gradlew -PffmpegEnabled=true -PffmpegRepo="$NATIVE_REPO" \
  :core:test :engine-ffmpeg:testDebugUnitTest :app:testDebugUnitTest \
  :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug
adb -s "$SERIAL" install -r app/build/outputs/apk/debug/app-debug.apk
adb -s "$SERIAL" install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
python3 tools/collect-android-acceleration.py --serial "$SERIAL" --output inventory.json
python3 tools/collect-android-acceleration.py --serial "$SERIAL" --encoder H264_AUTO --timeout 600 --output auto-h264.json
python3 tools/collect-android-acceleration.py --serial "$SERIAL" --encoder H264_HW --timeout 600 --output device-h264.json
```

Additional encoder arguments: H265_AUTO, H265_HW, VP9_HW, AV1_HW. Each native case
creates a real 3-second 640x360/30 SDR H.264/AAC source, executes the same production
preparer/retry/verifier, and records the actual selected route. The source generator
requires compiled x264/AAC/lavfi. A missing native bundle or required codec fails
an explicitly requested native test. Automatic CPU success is reported as CPU,
not hardware success. MKV is used for the VP9/AV1 test cases, not an implicit claim
of every upload destination's support.

The collector checks instrumentation completion, a fresh UUID, requested/actual
encoder agreement, hashes, nonempty output and a final VERIFIED event. It refuses
to replace evidence files. An ADB timeout does not prove that a native job stopped.
The fixture is a runtime smoke test, **not an equal-quality encoder benchmark**.

## Test isolation

- `testing/acceleration/jvm`: referenced only by engine-ffmpeg's `test` source set.
- `testing/acceleration/appTest`: referenced only by app's `test` source set.
- `testing/acceleration/androidTest`: referenced only by app's `androidTest` source set.
- `tests/acceleration` and `tools/check-*` / collector: host-only utilities.

No main source set includes these paths. The production retry planner and executor
are not test machinery and must remain when deleting the optional harness.
Remove the three test-source references and engine testImplementation when removing
this harness. No new scheduled automation or GitHub workflow is introduced.

## Remaining work before merge / device handoff

- Run Android compilation, lint, JUnit, real native payload/alignment checks and
  physical exports. Local host checks do not establish those results.
- Verify current FFmpegKitNext callback APIs and actual native log prefixes against
  the pinned, built artifact. Keep unfamiliar errors fatal; do not broaden to
  `catch (Exception) { useSoftware() }`.
- Qualify full frame counts, timestamps, rotation/VFR, A/V sync, unusual sources,
  thermal behavior, repeated jobs/cancellation, and foreground-service lifecycle.
- Wire the existing planned native upload-byte-cap controller through the same
  attempt/verifier path. This baseline has no native targetBytes executor; the PR
  does not claim that it adds size-goal parity. Never treat oversize as codec failure.
- A Java catch cannot contain SIGSEGV, process death, or a stuck native driver.
  The current cancellation ownership is retained; process/watchdog containment is
  separate work. Attempt count is bounded, but native-call wall time is not.
- Reconcile overlapping files with editor draft #2 and future settings draft #4.
  This branch intentionally targets main and does not modify those branches.
- Next performance work remains independent hardware decoding, GPU surfaces and
  synchronization, then NDK async/mux-header qualification and optional inference.

## Primary API references

- Android MediaCodec lifecycle and error semantics:
  https://developer.android.com/reference/android/media/MediaCodec
- FFmpeg MediaCodec formats and options:
  https://ffmpeg.org/ffmpeg-codecs.html#MediaCodec
- FFmpeg encoder-local create/configure/start error messages (source reference;
  confirm against the pinned build rather than treating master as its version):
  https://github.com/FFmpeg/FFmpeg/blob/master/libavcodec/mediacodecenc.c
- Manufacturer performance hints are not cross-device benchmarks:
  https://developer.android.com/reference/android/media/MediaCodecInfo.VideoCapabilities
