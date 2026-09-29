# Isolated Android acceleration lab

**Draft; Android compilation and physical-device qualification have not been run
by the authoring session.** This directory contains test code, not an app service,
receiver or production fallback. The shipping app still uses its checked FFmpeg
buffer-input hardware path. See [research and investment order](../../docs/android-hardware-acceleration-research.md).

## What runs

| `--mode` | Experiment |
| --- | --- |
| `JAVA` | Explicit Java MediaCodec buffer encode; compare with `--baseline SOFTWARE` |
| `NDK` | NDK synchronous buffer encode against Java |
| `NDK_ASYNC` | NDK async H.264/HEVC buffer encode; extract extradata for the muxer |
| `DECODE_BUFFER` | H.264 NDK hardware decode into software frames, CPU filter, Java hardware encode |
| `SURFACE` | H.264 NDK decode into a persistent surface shared with NDK encode; identity fixture only |

`--format` accepts H264, HEVC, VP8, VP9 and AV1. Compiled wrapper, actual hardware
encoder and exact configuration must exist. AV1/VP9 decode support does not pass
an encode test. VP8 has no app software baseline; use JAVA. An unavailable route
is an explicit failed experiment, not an invisible software fallback.

Each invocation generates one real H.264/AAC SDR source and runs **A, B, B, A**
sequentially. It probes each result, decodes both audio/video, checks exact decoded
video frame count, monotonic PTS and expected frame intervals, then records timings,
bytes and identities. Sources are 640x360, 1280x720, 1920x1080 or 3840x2160;
24/30/60/120 fps; 2–30 seconds. Defaults are 720p30, 3 seconds, 4000 kb/s.

## Build from the source-built native bundle

Use the repository's pinned FFmpeg build; do not download an unrelated binary AAR.
From a full checkout with Android SDK, JDK 17 and the supported native build tools:

```bash
./tools/build-ffmpeg.sh
NATIVE_REPO="$PWD/vendor/ffmpeg-kit-next/prebuilt/bundle-android-aar-24-maven"
./gradlew -PffmpegEnabled=true -PffmpegRepo="$NATIVE_REPO" \
  :core:test :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug
python3 tools/verify_android_native.py app/build/outputs/apk/debug/app-debug.apk \
  --json native-apk-report.json
adb -s DEVICE_SERIAL install -r app/build/outputs/apk/debug/app-debug.apk
adb -s DEVICE_SERIAL install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
```

Also run the native AAR verifier and official `zipalign`/16 KB checks described in
`docs/android-handoff.md`. A successful host check or Kotlin API compilation does
not establish a usable native payload. These install commands replace the debug
app/test APK on the **explicitly selected** device; do not use a production phone
with valuable app data as an unattended build target.

## Run over ADB, including from Windows

The Python host runner needs only Python 3.10+ and ADB. It does not use a shell
command string. Supply the complete source commit used to build the installed
APK; the report labels that as caller-declared and independently hashes the actual
installed base APK. Do not substitute the current checkout SHA for an older APK.

```bash
python3 testing/acceleration/host/run_lab.py --serial DEVICE_SERIAL \
  --app-commit FULL_40_CHARACTER_LOWERCASE_COMMIT_SHA --mode NDK --format H264
```

Windows uses the same command with `python` instead of `python3` and a single line.
To compare hardware versus software, use `--mode JAVA --baseline SOFTWARE`.
Use `--mode NDK_ASYNC`, `--mode DECODE_BUFFER` or `--mode SURFACE` for the other
experiments. Add `--height 1080 --fps 60 --seconds 10` for a larger supported case.
An optional `--operating-rate 240` requests an encoder resource hint for B only;
it does **not** change output frame rate. It is rejected if absent from the loaded
wrapper. This is not a performance guarantee or permission to drop frames.

The runner invokes the separate instrumentation APK with these key arguments:

```text
adb -s DEVICE_SERIAL shell am instrument -w -r \
  -e class dev.forma.app.HardwareAccelerationLabTest#benchmark \
  -e formaAccelerationLab true -e runId FRESH_32_HEX_ID \
  -e appCommit FULL_40_HEX_SHA -e mode NDK -e baseline JAVA \
  -e format H264 -e width 1280 -e height 720 -e fps 30 \
  -e seconds 3 -e videoKbps 4000 -e operatingRate 0 \
  dev.forma.transcode.test/androidx.test.runner.AndroidJUnitRunner
```

Prefer the Python wrapper: it generates safe fresh IDs, pulls the exact report,
requires **one passing instrumentation test** and validates all four results.
`adb` returning zero, a skipped test, a missing test class or a stale JSON file is
not success. Reports/logs go under ignored `testing/acceleration/results/<runId>/`.
The native test writes `files/acceleration-lab/<runId>/report.json` atomically.

## Evidence and limits

Evidence includes OS fingerprint/model/SoC, API/ABI/page size, installed base APK
hash, native version, fixture/output hashes, codec inventory, actual bound encoder,
observed hardware decoder for decode experiments, argument tokens, output bytes,
decoded frame count, timestamps, prepare/encode-mux/verification times, thermal
state and process PSS snapshots. It never changes `deviceQualified` to true.
For split-APK distributions, additionally hash every split/native payload; the
current build instructions produce a single debug APK.

Run on an **idle app and physical phone**, not concurrently with an export. The
test directly uses native session APIs to inspect full diagnostics; it is not a
production scheduler test. API 29+ is required for hardware identity. API 26–28
remain UNKNOWN rather than guessed from an OMX/c2 name. A no-native build has no
lab class, and the explicit host runner fails when it is requested.

The test waits up to 60 seconds for thermal status below MODERATE before each
sample. Short ABBA measurements, snapshots and debug logging do not prove sustained
throughput or energy consumption. Generated patterns are not perceptual quality
benchmarks. Matched bitrate is not necessarily matched quality; no upload byte cap
is enforced by this lab. Real-media, noisy-motion, size-fit, VFR, rotations, HDR,
long-run heat, A/V sync, edits and cancellation tests remain promotion gates.

SURFACE accepts only the generated same-size, unedited fixture and an encoder that
also supports the buffer baseline. It does not test surface-only encoders or GPU
editing. It requires a non-null surface log and checks output frames; that is not
proof of driver-internal physical zero-copy. Missing decoder-identity diagnostics
fail the test rather than fabricating a hardware badge.
An NDK_ASYNC request also fails if the selected encoder logs a fallback to
synchronous operation. A successful synchronous encode is not an async sample.

The native timeout is 20 minutes; host default is 25 minutes to allow reporting.
Cancellation waits for native cleanup. A hung vendor/native call may outlive the
host timeout: inspect the phone before starting another run. The host never
force-stops a possibly unrelated app session. `--keep-media` retains generated
inputs/results in the run directory for inspection; otherwise media is deleted,
while diagnostics stay. No originals or user-selected files are touched.

## Host checks and removal

```bash
bash tools/check-acceleration.sh
```

This executes Kotlin policy/command/PTS checks, Python report validation tests,
and the real production transcoder's decode-before-publication and cancellation
checks with host-only storage/native fixtures. The last checks need Kotlin's
coroutines library. Gradle `:core:test` also references the policy tests. Test sources
are external to `src/main`; the native diagnostic dependency is test-only and the
same pinned version as the app engine.

`-PaccelerationTests=false` removes these Gradle test source/dependency references.
It does not remove production capability negotiation, nor unrelated existing
editor/native tests. To delete the lab, remove this directory, the small guarded
blocks in `app/build.gradle.kts` and `core/build.gradle.kts`, and
`tools/check-acceleration.sh`. Do not put runtime evidence into source commits;
attach it to the relevant code PR with exact build identities.
