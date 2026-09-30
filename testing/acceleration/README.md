# Isolated Android acceleration lab

**Blocked Draft.** Native Android build and selected OnePlus 9 Pro routes are
now exercised; the required independent-vendor/OnePlus 15 matrix and working
surface adapter remain blocked. See [observed qualification and exact artifacts](../../docs/acceleration-device-validation.md).
This directory contains test code, not an app service, receiver or production fallback. The shipping app still uses its checked FFmpeg
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
From a full checkout with Android SDK, JDK 17, the source-rebuilt graphics-path
Maven artifact described in [device validation](../../docs/device-validation.md),
and the supported native build tools:

```bash
set -euo pipefail
test -z "$(git status --porcelain)" # Commit source changes before capturing the build identity.
APP_COMMIT="$(git rev-parse HEAD)" # Build from this clean, exact source revision.
./tools/build-ffmpeg.sh
NATIVE_REPO="$PWD/vendor/ffmpeg-kit-next/prebuilt/bundle-android-aar-24-maven"
GRAPHICS_REPO="$PWD/vendor/graphics-path/maven" # Source-rebuilt 16 KB aligned graphics-path AAR.
./gradlew -PffmpegEnabled=true -PffmpegRepo="$NATIVE_REPO" \
  -PgraphicsPathRepo="$GRAPHICS_REPO" -PformaLab=true \
  :core:test :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug
python3 tools/verify_android_native.py app/build/outputs/apk/debug/app-debug.apk \
  --json native-apk-report.json
SERIAL=DEVICE_SERIAL # Replace explicitly; never infer a phone from adb's device list.
APP=dev.forma.transcode.lab
test "$SERIAL" != DEVICE_SERIAL
test "$(adb -s "$SERIAL" get-state)" = device
adb -s "$SERIAL" install -r app/build/outputs/apk/debug/app-debug.apk
adb -s "$SERIAL" install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
```

Also run the native AAR verifier and official `zipalign`/16 KB checks described in
`docs/android-handoff.md`. A successful host check or Kotlin API compilation does
not establish a usable native payload. These install commands replace the debug
lab app/test APK on the **explicitly selected** device; do not use a production phone
with valuable app data as an unattended build target.

## Run directly over ADB

Run in the same Bash session and use the `SERIAL`, `APP` and `APP_COMMIT` from the
build above. If the APK was built elsewhere, set `APP_COMMIT` to that build's
complete 40-character lowercase source
commit, not the current checkout. Confirm the installed package is the selected
lab package with `adb -s "$SERIAL" shell pm path "$APP"`. Its test APK must also
be installed. A supplied source SHA is caller-declared; the report independently
hashes the installed base APK. The following Bash workflow uses OpenSSL for a fresh
run ID and `jq` to reject mismatched or incomplete evidence:

```bash
set -euo pipefail
test "$APP" = dev.forma.transcode.lab
[[ "$APP_COMMIT" =~ ^[a-f0-9]{40}$ ]]
RUN_ID="$(openssl rand -hex 16)"
[[ "$RUN_ID" =~ ^[a-f0-9]{32}$ ]]
EVIDENCE="testing/acceleration/results/$RUN_ID"
mkdir -p "$EVIDENCE"
APP_APK_SHA="$(sha256sum app/build/outputs/apk/debug/app-debug.apk | cut -d ' ' -f1)"
adb -s "$SERIAL" shell am instrument -w -r \
  -e class 'dev.forma.app.HardwareAccelerationLabTest#benchmark' \
  -e formaAccelerationLab true -e runId "$RUN_ID" -e appCommit "$APP_COMMIT" \
  -e mode NDK -e baseline JAVA -e format H264 \
  -e width 1280 -e height 720 -e fps 30 -e seconds 3 \
  -e videoKbps 4000 -e operatingRate 0 \
  "$APP.test/androidx.test.runner.AndroidJUnitRunner" | tee "$EVIDENCE/instrumentation.log"
grep -Eq '^OK \(1 test\)[[:space:]]*$' "$EVIDENCE/instrumentation.log"
grep -Eq '^INSTRUMENTATION_CODE: -1[[:space:]]*$' "$EVIDENCE/instrumentation.log"
! grep -Eq 'FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed' "$EVIDENCE/instrumentation.log"
adb -s "$SERIAL" exec-out run-as "$APP" cat \
  "files/acceleration-lab/$RUN_ID/report.json" > "$EVIDENCE/report.json"
jq -e --arg run "$RUN_ID" --arg commit "$APP_COMMIT" \
  --arg app "$APP" --arg apk "$APP_APK_SHA" '
  (.fps * .seconds) as $frames |
  (.runId == $run and .appCommit == $commit and .targetPackage == $app
   and .appApkSha256 == $apk and .schemaVersion == 1 and .status == "passed"
   and .deviceQualified == false and .mode == "NDK" and .baseline == "JAVA"
   and .format == "H264" and .width == 1280 and .height == 720
   and .fps == 30 and .seconds == 3 and .videoKbps == 4000 and .operatingRate == 0)
  and (.nativeBuild | type == "string" and . != "Not loaded" and length > 0)
  and (.fixtureSha256 | test("^[a-f0-9]{64}$"))
  and (.samples | length == 4)
    and (.samples | map(.index) == [0,1,2,3])
    and (.samples | map(.route) == ["JAVA","NDK","NDK","JAVA"])
    and (.samples | all(.[]; .passed == true and .frames == $frames
                           and .bytes > 0 and .encodeAndMuxMs > 0
                           and (.outputSha256 | test("^[a-f0-9]{64}$"))))
' "$EVIDENCE/report.json"
```

The shell must stop on any failed command, including `adb` in the pipe. Inspect
`instrumentation.log` on failure; an ADB exit code alone, zero tests, a missing
test class, or an old report is not a pass. The lab writes its report atomically to
`files/acceleration-lab/<runId>/report.json`. The selected package is recorded by
the Android **target context**, not copied from an instrumentation argument.
Keep the log, report, local APK hash and exact source commit with the result.

Windows PowerShell can use the same `adb -s $SERIAL` instrumentation and `exec-out
run-as $APP` commands; generate a 32-character ID with
`[guid]::NewGuid().ToString('N')`, save both outputs, and check the same result
fields with `ConvertFrom-Json`. The Python runner below is also portable to Windows.

## Optional Python runner and analysis

The Python 3.10+ host runner automates the direct commands and validates additional
timing, decoder and sample details. It does not use a shell command string. Its
default package is the isolated lab build; `--package` also accepts the ordinary
`dev.forma.transcode` debug app for explicitly selected experiments, but never
falls back based on what happens to be installed. The selected ID is used for
instrumentation, `run-as`, and report validation.

```bash
python3 testing/acceleration/host/run_lab.py --serial "$SERIAL" \
  --app-commit "$APP_COMMIT" --package "$APP" --mode NDK --format H264
```

Windows uses the same runner command with `python` instead of `python3`. To compare
hardware versus software, use `--mode JAVA --baseline SOFTWARE`. Use `--mode
NDK_ASYNC`, `--mode DECODE_BUFFER` or `--mode SURFACE` for the other experiments.
Add `--height 1080 --fps 60 --seconds 10` for a larger supported case. An optional
`--operating-rate 240` requests an encoder resource hint for B only; it does
**not** change output frame rate. It is rejected if absent from the loaded wrapper.
This is not a performance guarantee or permission to drop frames. Runner reports
and logs go under ignored `testing/acceleration/results/<runId>/`.

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
