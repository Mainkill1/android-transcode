# Forma testing: host checks and Android commands

**Device commands are implemented but not yet physically qualified by this PR.** A host pass does not prove Android compilation, native APK packaging, device performance, visual quality or audio synchronization on a phone. See the PR verification record before promotion.

## Where the tests live

| Path | Role | Shipped in the release app? |
| --- | --- | --- |
| `core/unit/` | Existing core tests plus edit/timeline assertions | No |
| `app/unit/` | Existing runtime/queue tests plus edit persistence tests | No |
| `app/device/` | Existing Compose/native checks and `EditorCommandTest` | No; separate test APK |
| `shared/` | Deterministic media-case definitions shared by test runners | No |
| `host/`, `run_host.py` | Kotlin entry points and desktop verification | No |
| `adb/` | Python ADB controller and its own contract tests | No |
| `recipes/` | Editable input recipes | No |
| `results/` | Generated logs, reports and optional media; gitignored | No |

Gradle references these external source roots. `app` and `core` production source sets do not import this tree. The existing `tools/tests` and `studio/tests` remain separate tooling/reference-app tests, not Android production dependencies. Existing `tools/check-*.sh` runners point to the relocated native tests.

## Build without test machinery

```sh
./gradlew -PformaTests=false :app:assembleDebug
```

With that property, test source references and test-only dependencies are removed and the lab variant is not created. `testing/` can be removed from a product-only checkout. Do not set `formaTestBuildType=lab` in that checkout. For release builds, the existing native FFmpeg build/payload requirements still apply; disabling tests does not bypass them. Test-only Compose manifest support is limited to debug/lab builds.

## Host verification

Requirements: Python 3.10+, Kotlin CLI and a compatible JDK; the compiler targets Java 17. For `--exports`, install desktop FFmpeg/FFprobe with libx264, AAC and the requested filters.

```sh
python testing/run_host.py
python testing/run_host.py --exports
python -m unittest discover -s testing/adb -p "test_*.py" -v
```

The host runner executes the existing core assertions, new editor/timeline checks and Python CLI-contract tests. `--exports` generates its own inputs and executes **production Planner arguments**, checking output duration, transformed geometry, track layout, successful decode and source preservation. Two delayed-audio cases guard against losing relative A/V offsets during trim and speed changes. Full output/logs stay under `testing/results/host/<runId>`.

For the complete Gradle JVM suite, including JSON queue persistence and existing runtime checks:

```sh
./gradlew :core:test :app:testDebugUnitTest
```

The dependency-free host runner is not a substitute for that Gradle suite.

## Build and install the isolated Android lab

The command target is **`dev.forma.transcode.lab`**, not the normal `dev.forma.transcode` installation. Instrumentation may restart its target process. Keep the lab idle, run one command at a time per device, and never point this harness at the everyday app. Neither root nor a production command receiver/server is needed.

Use JDK 17, the repository's Android SDK requirements, an authorized ADB device, and the source-built FFmpeg Maven repository documented in `docs/android-handoff.md`. Preserve its native version/licensing pins. From the repository root:

```sh
./gradlew -PformaTestBuildType=lab -PffmpegEnabled=true -PffmpegRepo=/absolute/path/to/native-maven :app:assembleLab :app:assembleLabAndroidTest
adb -s SERIAL install -r app/build/outputs/apk/lab/app-lab.apk
adb -s SERIAL install -r app/build/outputs/apk/androidTest/lab/app-lab-androidTest.apk
```

On Windows, use `gradlew.bat` and the corresponding native-repository path. Those are Gradle's standard output paths; confirm the build's actual outputs before installation. Both APKs must be built together with matching signatures. Native payload verification remains mandatory. A no-native lab can inspect missing capabilities; it **cannot** pass an explicitly requested export or smoke test.

## Run from a computer over ADB

```sh
python testing/adb/forma_device.py capabilities --serial SERIAL
python testing/adb/forma_device.py smoke --serial SERIAL --pull-media
python testing/adb/forma_device.py export --serial SERIAL --input sample.mp4 --recipe testing/recipes/basic-edit.json --pull-media
```

`--serial` is optional with exactly one authorized device. Other options: `--adb PATH`, `--timeout 300` (30–3600 seconds), `--output DIRECTORY`, `--keep-device-files`. The sample recipe requires an audio/video source at least five seconds long and keeps source seconds 1–5; edit the recipe for other inputs.

| Command | Behavior |
| --- | --- |
| `capabilities` | Records actual installed APK identity, native availability, build, encoders, muxers and filters. PASS means inspection succeeded, not that encoding is available. |
| `smoke` | Generates a six-second fixture and runs neutral trim, speed, crop/rotation/color, fades/normalization and audio-only cases through the production exporter. Missing native support is a failure, not a skip. |
| `export` | Uploads an input and typed recipe, then runs one production export with structural and decode verification. |

The test calls `FfmpegTranscoder` with the normal managed bridge and media-files implementation. A forwarding recorder captures `prepare` arguments; it is not a second encoder. Each export uses its own UUID, obeys queue transition rules, hashes the input, probes/decodes the result and deletes only its own temporary job output. It does not append test jobs to the normal app's queue.

Recipes use enum names from production `Settings` and fields from `ClipEffectsCodec`. Unknown keys and malformed effect values fail. There is no raw FFmpeg filter/shell field. Hardware requests still use production capability preparation; edited hardware routes are explicitly held pending qualification. Crop on display-matrix sources is also held rather than applying coded-pixel coordinates incorrectly.

## Direct instrumentation command

The Python controller wraps this command and report protocol; it is not required for capability/synthetic smoke invocation. Replace the sample run ID with a **fresh 32-character lowercase hexadecimal ID** every time:

```sh
adb -s SERIAL shell am instrument -w -r -e class dev.forma.app.EditorCommandTest -e formaCommand smoke -e formaRunId 0123456789abcdef0123456789abcdef -e formaTimeoutMs 290000 dev.forma.transcode.lab.test/androidx.test.runner.AndroidJUnitRunner
adb -s SERIAL exec-out run-as dev.forma.transcode.lab cat files/forma-tests/0123456789abcdef0123456789abcdef/result.json
```

For a direct custom export, stage `input.media` and `recipe.json` in that lab-private run directory first; the Python controller handles binary streaming and shell quoting. `run-as` works because lab is debuggable. A release-only APK is intentionally not a CLI target.

## Reports and failures

Each host run writes `instrumentation.txt`, `device.json` when available, and `host.json`. `--pull-media` copies verified output files alongside them. Reports identify the run/command, actual APK SHA-256, device/API/ABI/page size, native capabilities, serialized job/effects, prepared argument tokens, transitions, input/output hashes, output facts, elapsed time and errors. The host checkout revision is recorded as **unverified against the installed APK**, not falsely presented as build provenance.

Exit codes: **0** verified command pass; **1** failure; **2** timeout or invalid command-line usage. Success requires both exactly one passing instrumentation command and its matching non-stale JSON report. ADB's own zero exit code is not sufficient. A cleanup timeout cannot produce a host PASS.

Success removes only that run's private device directory unless `--keep-device-files` is supplied. Failure/timeout retains it for investigation. A host timeout may expire before native cleanup finishes: do not start another instrumentation session or remove its files until the lab has stopped. There is no automatic force-stop, queue wipe or cross-host locking. For ordinary Compose tests, omit `formaCommand`; the command-only test then skips intentionally while the existing UI tests run.

## Qualification still required before merge

Build/lint normal debug, lab and their test APKs; run JVM persistence tests; inspect a release dependency/APK report with `formaTests=false`; verify the actual native bundle; run capabilities/smoke/custom exports on an Android device; inspect crop/color/fades and listen for A/V sync; exercise timeout/cancellation and source preservation. Also verify the touchscreen controls and queued edits across app restarts. Keep desktop evidence distinct from those results.
