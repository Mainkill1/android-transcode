# Runtime codec trials: implementation and direct ADB testing

This implementation tries real Android encoder configurations instead of treating
advertised capability reports as an absolute veto. Production exports use
`CodecTrials.plan`, `FfmpegBridge.prepareAttempts`, `ExportRetry.run`, and
`verifyEncodedOutput`; the older `AccelerationPolicy.choose` remains an
advertised-only helper for existing callers and policy tests.

The device test interface is **direct ADB**. No Python collector, host command
wrapper, production receiver, or test server is required. The separate
instrumentation APK accepts arguments through `adb shell am instrument`, and the
result is copied with `adb exec-out run-as`.

## Implemented behavior

| Area | Implementation |
| --- | --- |
| Application exports | `FfmpegTranscoder` stages the original, prepares bounded routes, executes and verifies each attempt, and publishes only the accepted output. |
| Automatic mode | H.264 and H.265 Automatic try device encoders and then the same-format software encoder for eligible initialization/output failures; capped jobs can also use software when a verified hardware result cannot fit at its minimum bitrate. |
| Required-device mode | H.264, HEVC, VP9, and AV1 device choices never fall back to a software FFmpeg encoder. |
| Capability handling | Advertised size/rate/bitrate/buffer support orders trials but does not veto a real attempt. Known software-only components and decoders remain excluded. |
| Trial matrix | Uncapped jobs: up to three components with NV12/YUV420P and VBR/CBR, at most twelve device attempts plus one Automatic software attempt. Capped jobs share four total attempts across codec and size retries and reserve an Automatic software fallback. |
| Size intent | Default 10 MB before import; decimal-byte presets/custom limit or No size limit. Each queued job freezes its own limit. Fully decoded candidates must be strictly smaller; oversized files are deleted and retried from the original at lower rates. |
| Error handling | Only recognized encoder-local create/configure/start failures and explicitly rejected encoded outputs can advance to another route. Cancellation, I/O, invalid input, unknown native errors, and programming exceptions stop. |
| Validation | A zero FFmpeg return code is followed by a nonempty-file check, full decode, per-stream timing/frame-count, track, geometry, and SDR validation. |
| Evidence | Normal jobs write private acceleration sidecars. Device tests write run-ID-specific JSON files under the target app's private `files/acceleration` directory. |
| Test isolation | JVM, app, and Android instrumentation tests live under `testing/acceleration`; none are included in a release main source set. |

Queue schema 2 writes the optional byte limit and reads legacy schema 1 as uncapped.
Unknown nested fields, fractional/coerced integers, missing required trim/limit intent and future schemas are rejected with the original queue preserved. Schema 2 emits an explicit null for a manual limit; a missing field is corrupt. Schema 1 accepts only its original uncapped payload.
Other editor/settings draft branches also extend their queues: their version numbers
must be unified when those branches are combined; an equal schema number does not
make their payloads interchangeable.

The current accelerated pipeline remains:

```text
CPU decode -> CPU filters/scale -> MediaCodec encode -> FFmpeg mux -> full decode check
```

This change does not claim hardware decoding, a GPU surface pipeline, NPU encoding,
HDR support, zero-copy operation, or measured speedup.

## Attempt and fallback contract

1. Every attempt rereads the original staged input and retains the queued codec
   family, trim, dimensions, frame rate, audio, and filters. Uncapped jobs also retain
   the exact requested bitrate/quality. A size limit explicitly uses bitrate budgeting;
   retries lower that budget, never truncate the file or omit tracks.
2. Automatic source-rate/VFR, constant-quality, or display-matrix jobs use software
   instead of silently changing their semantics. Required-device mode rejects those
   unimplemented semantics.
3. Android encoder enumeration is advisory. API 26-28 components and ambiguous
   newer components may be attempted as hardware `UNKNOWN`; they are never relabeled
   as confirmed hardware based on their names.
4. Device attempts are count-bounded and breadth-first across components before
   less-preferred buffer/rate-mode variants.
5. The executor waits for native completion and cancellation cleanup before deleting
   a failed private output or starting the next route.
6. A partial or failed output is never used as the next input. `-fs`, `cbr_fd`,
   hidden resolution changes, dropped audio, and removed edits are not fallback tools.
7. Unknown error text is fatal. The implementation does not use a blanket
   `catch (Exception) { useSoftware() }` policy.
8. Software-only jobs still pass through the common output verifier.
9. Final publication remains tied to the durable queue transition. An attempt report
   does not itself claim that the file was published or that the device is qualified.

A passing short test is not proof of frame-for-frame completeness, A/V sync,
perceptual quality, sustained thermal behavior, or every profile/resolution on that
phone.

## Build and install

Use the existing source-built native Maven repository and packaging checks:

```bash
./gradlew -PffmpegEnabled=true -PffmpegRepo="$NATIVE_REPO" \
  :core:test :engine-ffmpeg:testDebugUnitTest :app:testDebugUnitTest \
  :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug

adb -s "$SERIAL" install -r app/build/outputs/apk/debug/app-debug.apk
adb -s "$SERIAL" install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
```

The commands below assume those matching native debug and test APKs are installed.
A no-native/UI-only APK must fail an explicitly requested native test.

## Direct ADB: advertised inventory

Generate a new lowercase UUID for every run. The test refuses to overwrite a report
with the same run ID.

```bash
RUN_ID="$(uuidgen | tr '[:upper:]' '[:lower:]')"

adb -s "$SERIAL" shell am instrument -w -r \
  -e class dev.forma.app.HardwareAccelerationInventoryTest \
  -e formaAccelerationInventory true \
  -e formaRunId "$RUN_ID" \
  dev.forma.transcode.test/androidx.test.runner.AndroidJUnitRunner

adb -s "$SERIAL" exec-out run-as dev.forma.transcode \
  cat "files/acceleration/inventory-$RUN_ID.json" \
  > "inventory-$RUN_ID.json"
```

Accept the report only when instrumentation shows exactly one completed test with
`OK (1 test)` and no `FAILURES!!!`, process-crash, or instrumentation-aborted line.
The JSON must contain the same `runId`, `inventoryComplete: true`,
`nativeExecutionTested: false`, and `deviceQualified: false`.

## Direct ADB: real runtime export

Set one of `H264_AUTO`, `H265_AUTO`, `H264_HW`, `H265_HW`, `VP9_HW`, or `AV1_HW`:

```bash
ENCODER="H264_AUTO"
RUN_ID="$(uuidgen | tr '[:upper:]' '[:lower:]')"

adb -s "$SERIAL" shell am instrument -w -r \
  -e class dev.forma.app.RuntimeAccelerationTest \
  -e formaNative true \
  -e formaEncoder "$ENCODER" \
  -e formaRunId "$RUN_ID" \
  dev.forma.transcode.test/androidx.test.runner.AndroidJUnitRunner

adb -s "$SERIAL" exec-out run-as dev.forma.transcode \
  cat "files/acceleration/runtime-$RUN_ID.json" \
  > "runtime-$ENCODER-$RUN_ID.json"
```

The test creates a real three-second 640x360/30 SDR H.264/AAC input, runs the same
preparer/retry/verifier used by the app, and records the selected backend,
component, hardware identity, buffer layout, rate mode, hashes, output bytes, and
attempt events. VP9/AV1 use MKV for this smoke fixture. The source generator requires
compiled x264, AAC, and lavfi support.

For a valid successful runtime report, verify:

- instrumentation completed one test without failure;
- `runId` and `requestedEncoder` match the command;
- `success` and `nativeExecutionTested` are `true`;
- `deviceQualified` remains `false`;
- `outputBytes` is positive and both SHA-256 fields contain 64 lowercase hex digits;
- the final event is `VERIFIED`;
- an explicit `*_HW` request selected `MEDIACODEC`, never `SOFTWARE`;
- an Automatic software result is reported as software rather than hardware success.

After copying a report, it may be removed directly:

```bash
adb -s "$SERIAL" shell run-as dev.forma.transcode \
  rm -f "files/acceleration/runtime-$RUN_ID.json"
```

### PowerShell run-ID setup

The ADB arguments are identical on Windows. Generate the required canonical UUID
with:

```powershell
$serial = "DEVICE_SERIAL"
$runId = [guid]::NewGuid().ToString()
$encoder = "H264_AUTO"
```

Then substitute `$serial`, `$runId`, and `$encoder` in the direct `adb` invocation.
To capture JSON as UTF-8 text in PowerShell:

```powershell
$lines = adb -s $serial exec-out run-as dev.forma.transcode cat "files/acceleration/runtime-$runId.json"
[IO.File]::WriteAllLines("runtime-$encoder-$runId.json", $lines, [Text.UTF8Encoding]::new($false))
```

## Host checks

These checks exercise Kotlin policy and retry contracts only; they do not require or
simulate a phone:

```bash
bash tools/check-acceleration.sh
bash tools/check-runtime-acceleration.sh
```

Python is not part of the device workflow or these acceleration-specific checks.

## Self-review corrections in the direct-ADB follow-up

The first implementation used fixed device report names and depended on a Python
collector to delete/read/validate them. That was unnecessary when direct ADB is
available and created a stale-evidence hazard if instrumentation failed before
replacing the fixed file. The follow-up changes therefore:

- remove the Python collector and its Python-only tests;
- require an explicit canonical `formaRunId` for inventory and runtime tests;
- require an explicit `formaEncoder` for runtime tests;
- write `inventory-<runId>.json` and `runtime-<runId>.json`;
- refuse to replace an existing report for the same run ID;
- include attempt totals in runtime evidence;
- make raw `adb shell am instrument` plus `adb exec-out run-as` the documented and
  supported device interface.

The production retry code was also reviewed for cancellation ownership, original
input reuse, attempt bounds, software fallback rules, output cleanup, verification,
and unknown-error handling. No blanket fallback was introduced. The remaining
high-risk gap is native crash/hang containment: a Java catch cannot contain SIGSEGV,
process death, or a codec call that never returns.

## Qualification checkpoint

Final review qualification (2026-09-29) uses the pinned source-built FFmpeg n9.0.1 bundle. **41 JVM tests**, native app/test builds and lint pass. Fourteen real desktop stream-completeness cases pass, including rejection of decodable truncated video hidden behind full-length audio, shortened audio, shifted streams and wrong CFR counts. WAV/FLAC inputs lacking declared start clocks use measured packet/frame timestamps; unknown clocks remain unknown.

On the attached **OnePlus 9 Pro LE2125**, API 36, arm64, 4096-byte pages:

- All **15 physical-device instrumentation tests pass with no skips**, using UUID `b710ebff-f1c4-42d6-84e9-6ec52bc15d0c`. This includes actual video and clockless WAV foreground-service jobs, byte-exact queue preservation, inventory, runtime encoding, cancellation, failed durable completion and Compose controls.
- The H264_AUTO runtime job used `c2.qti.avc.encoder`, YUV420P/VBR, reported as actual MediaCodec hardware. Earlier separate H264_HW/H265_AUTO/H265_HW cases also passed; VP9_HW/AV1_HW explicitly failed unavailable. These are short phone-specific results.
- Downloaded Sintel SHA-256 remained `b670602fa00934ca27c4351bb0efe7ea7a07fae57284e44226025eeed7c51254`. Its five-second capped export is **76,894 bytes < 250,000**, with **150 frames at 640×360/30** and 48 kHz stereo AAC. Independent strict full decode passed. The first real native MP4 receives inert trailing padding to force a size retry; reports separate padding from native encoded bytes. Hardware measured 434,581/414,068 bytes at 261/100 kb/s requests; the third same-codec libx264 attempt fit. Every attempt starts from the original.
- Stop before publication and a failing durable Completed callback both leave no published output. Stop after successful durable completion retains the completed file.
- `-PformaLab=true` uses `dev.forma.transcode.lab.codec`, with an empty isolated queue for service tests. Production release retains its normal ID.

The minified native release built with `testing/acceleration` physically absent and `accelerationTests=false`. DEX excludes qualification classes, runner and opt-in arguments. Debug/release native payload, 16 KB ELF and APK ZIP alignment checks pass; the attached phone has 4 KB pages.

Final artifact SHA-256:

```text
debug    acd55fd2b5ea90a9ed780f1c4af420552a4aa9fd19a9f39e604bbe5305be91d1
test     a9af3e9d7dab11f423b1d0c4974f76833608b5017f64c4f172ab9bd424558809
release  1cb66286d8b07527fb2e617868c5057fe307774c737e99916db5941a82a77e23
```

Private reports carry fresh run IDs and installed-artifact identities. These deterministic cases do not certify broad-device support, every VFR timeline, perceptual quality, A/V sync or sustained thermal performance. Unknown native errors remain fatal; SIGSEGV and indefinitely hung codec calls are not contained by Java. Required-device mode never switches to software, and Automatic mode retains the codec family.

## Direct ADB: production byte-cap qualification

Build matching lab APKs using `-PformaLab=true`. Copy an expendable downloaded source
into the lab package (not the user's app) before running this opt-in test:

```bash
adb -s "$SERIAL" shell run-as dev.forma.transcode.lab.codec mkdir -p files/outputs
adb -s "$SERIAL" shell run-as dev.forma.transcode.lab.codec sh -c \
  "'cat > files/outputs/cap-original.mp4'" < sample.mp4
adb -s "$SERIAL" shell am instrument -w -r \
  -e class dev.forma.app.NativeByteCapTest \
  -e formaNative true -e formaByteCapTests true -e formaRunId "$RUN_ID" \
  dev.forma.transcode.lab.codec.test/androidx.test.runner.AndroidJUnitRunner
adb -s "$SERIAL" exec-out run-as dev.forma.transcode.lab.codec \
  cat "files/acceleration/cap-$RUN_ID.json" > "cap-$RUN_ID.json"
```

Require `OK (1 test)`, a matching fresh canonical UUID, `passed: true`, strict
`outputBytes < targetBytes`, original hash matches on every preparation, successful
full output decode and `cancellationLeftNoOutput: true` and `publicationFailureLeftNoOutput: true`. The fixture contract is a
video/audio source long enough for 5–10 seconds. It intentionally forces one valid
candidate oversize; its report exposes native bytes separately from added padding.
It uses the actual production staging/exporter/verifier and contains no release
receiver/server. `-PaccelerationTests=false` removes external test source references.

## Direct ADB: foreground service and queue preservation

After installing matching `-PformaLab=true` native APKs, run:

```bash
adb -s "$SERIAL" shell am instrument -w -r \
  -e class dev.forma.app.CodecServiceTest -e formaCodecService true \
  dev.forma.transcode.lab.codec.test/androidx.test.runner.AndroidJUnitRunner
adb -s "$SERIAL" shell am instrument -w -r \
  -e class dev.forma.app.QueuePreservationTest \
  dev.forma.transcode.lab.codec.test/androidx.test.runner.AndroidJUnitRunner
```

Service tests refuse nonempty or active queues, generate their own video/WAV sources, start the real foreground service, check durable Completed outputs, wait for idle native ownership and restore their empty queue snapshot. Queue-preservation tests use a separate context directory and compare exact bytes after rejected loads. Default emulator CI intentionally skips opt-in native tests.

## Remaining qualification and integration limits

- Additional physical Qualcomm, MediaTek, Tensor and Exynos devices are needed for
  broader support/performance claims; only the attached Qualcomm phone is qualified
  for the specific short cases above.
- Wide real-source quality, A/V sync, VFR/rotation and sustained thermal behavior
  require larger fixtures and runs before corresponding claims are made.
- Shared job/editor/settings wire schemas must be reconciled when PR2/PR3/PR4/PR8
  are combined. This branch rejects unsupported editor data rather than erasing it.
- Hardware decode, GPU surface processing and native crash containment are separate
  architecture work, not features claimed by this buffer-encode PR.

## Primary references

- Android MediaCodec lifecycle and errors:
  https://developer.android.com/reference/android/media/MediaCodec
- FFmpeg MediaCodec encoders and options:
  https://ffmpeg.org/ffmpeg-codecs.html#MediaCodec
- FFmpeg MediaCodec encoder source; confirm messages against the pinned build:
  https://github.com/FFmpeg/FFmpeg/blob/master/libavcodec/mediacodecenc.c
- Android manufacturer performance hints:
  https://developer.android.com/reference/android/media/MediaCodecInfo.VideoCapabilities
