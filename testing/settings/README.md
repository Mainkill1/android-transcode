# Settings tests: direct ADB, no Python driver

These files are test-only. `core/build.gradle.kts` references `core/` and `junit/` from its test source set; `app/build.gradle.kts` references `core/` and `android/` from androidTest. No production command receiver, intent endpoint, host server, test API or new workflow is required. Remove this directory and those test-source references to remove the harness.

## Host checks

```bash
bash testing/settings/host/check.sh
./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug
```

The Kotlin CLI runs the existing 39 settings assertions plus four power-transition regressions. Android instrumented tests also exercise the real `AtomicSettingsStorage` adapter in generated private temporary directories, without replacing the user's `settings-v1.txt`.

## Install and run on the connected device

Build/install the APK and its matching test APK from the same revision. This branch's existing debug application ID is `dev.forma.transcode`; confirm the instrumentation component instead of assuming the `.lab` ID used on another draft branch.

```text
adb devices
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell pm list instrumentation
```

Direct PowerShell example (no script wrapper):

```powershell
$runId = [guid]::NewGuid().ToString()
$revision = (git rev-parse HEAD).Trim()
adb shell am instrument -w -r -e class dev.forma.app.settings.SettingsScenarioTest -e formaSettingsCase all -e formaSettingsRunId $runId -e formaAppRevision $revision dev.forma.transcode.test/androidx.test.runner.AndroidJUnitRunner
adb exec-out run-as dev.forma.transcode cat "files/settings-tests/$runId.json"
```

For Bash, set `runId="$(uuidgen | tr '[:upper:]' '[:lower:]')"` and `revision="$(git rev-parse HEAD)"`, then run the same ADB commands with quoted `"$runId"` and `"$revision"`. When multiple devices are connected add `-s SERIAL` to every ADB invocation.

**Require both successful instrumentation output (`OK`, with no assertion failure/crash) and this run's JSON `result=PASS`, matching runId/case and selectedCount=passedCount > 0.** ADB transport success alone does not prove a test passed. Every run needs a fresh UUID; reused IDs are rejected. The report uses that UUID as its filename, not a shared `last-result.json`. Malformed IDs fail before a report is written. Unknown scenarios produce a failed report and failing instrumentation. `callerReportedAppRevision` is caller-supplied metadata, not proof of the installed APK's identity; retain hashes of the installed APK/test APK alongside the report.

Read the report before optionally removing only that report with `adb shell run-as dev.forma.transcode rm "files/settings-tests/$runId.json"`. Do not clear app data or delete real preferences to clean test output.

## Implemented scenario names

| Case | Assertions | Evidence type |
| --- | --- | --- |
| `all` (default) | All 43 pure settings/power assertions and four real storage checks | Kotlin behavior plus Android storage |
| `catalog` | 64-key/eight-category inventory | Catalog contract |
| `overrides` | Explicit Auto, inheritance/reset, preset precedence | Pure resolver |
| `battery_low` | Inclusive low threshold and recovery margin/timer | Synthetic power samples |
| `charging_exception` | Charging exemption plus unknown/disconnect transitions | Synthetic power samples |
| `thermal_wait` | Severity, critical latch, unknown telemetry and recovery | Synthetic power samples |
| `storage_memory` | Existing in-memory store/corruption/conflict assertions | Pure store contract |
| `storage_roundtrip` | Fresh-store disk round trip, malformed UTF-8, read/write size bounds, aborted atomic replacement | Real Android storage adapter |

UI checks run separately:

```text
adb shell am instrument -w -r -e class dev.forma.app.settings.SettingsScreenTest,dev.forma.app.settings.SettingsCompletionTest dev.forma.transcode.test/androidx.test.runner.AndroidJUnitRunner
```

The scenario runner does **not** encode media or enforce battery/thermal actions on the running service. Its JSON says `nativeExecution=false` and `powerInputs=synthetic`. The storage tests use the actual Android adapter but an aborted `AtomicFile` write is not a power-loss certification. Real unplug/replug, screen-off execution, native output qualification, all-font-scale/accessibility/rotation testing and release-artifact exclusion remain separate gates. No test-selection argument can activate a Planned control in production.

Primary command reference: https://developer.android.com/studio/test/command-line
