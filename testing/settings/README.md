# Removable advanced-settings tests

This directory is outside production source sets. `core/build.gradle.kts` and `app/build.gradle.kts` explicitly add only the relevant files to test source sets. Removing `testing/settings/` and those references leaves production sources and release dependencies intact.

## Coverage

- `core/`: 48 Android-free assertions covering all 64 IDs, category counts, typed defaults, persistence/migration, provenance, media mappings, power-policy hysteresis, runtime worker instructions and edge cases.
- `junit/`: parameterized JVM wrappers for the pure contracts.
- `android/`: native Compose navigation/editing tests, AtomicFile replacement/corruption tests, battery-intent mapping, runtime-policy integration, service transition rules and the direct-ADB scenario runner.
- `host/check.sh`: optional Kotlin-CLI execution of all 48 pure checks when `kotlinc` is available.

The instrumentation suite does not package a native FFmpeg engine and does not claim physical battery, thermal or encoder qualification. Synthetic `PowerSample` values prove policy/state transitions only. Android battery/thermal behavior still requires real-device unplug, charging, screen-off, process-death and sustained-load evidence.

## Direct ADB scenario runner

Build and install the regular test APKs, then call the instrumentation component directly. No Python wrapper, localhost server, production receiver or debug HTTP endpoint is required.

```bash
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell pm list instrumentation

RUN_ID="$(uuidgen | tr '[:upper:]' '[:lower:]')"
adb shell am instrument -w \
  -e class dev.forma.app.settings.SettingsScenarioTest \
  -e formaSettingsCase all \
  -e formaSettingsRunId "$RUN_ID" \
  -e formaAppRevision "$(git rev-parse HEAD)" \
  dev.forma.transcode.test/androidx.test.runner.AndroidJUnitRunner
adb exec-out run-as dev.forma.transcode \
  cat "files/settings-tests/$RUN_ID.json" > "settings-$RUN_ID.json"
```

`all` selects 52 assertions: 48 pure catalog/mapping/power/runtime checks plus four real Android AtomicFile checks. Other allowlisted values are `catalog`, `overrides`, `battery_low`, `charging_exception`, `thermal_wait`, `power_runtime`, `storage_memory`, and `storage_roundtrip`. Unknown names fail instead of running arbitrary code.

The JSON report records its schema, run UUID, requested scenario, device model, OS fingerprint, ABI, selected/passed checks, caller-reported app revision, whether Android storage ran, and explicitly reports `nativeExecution: false` and `powerInputs: synthetic`. The caller supplies the app revision because the current app does not embed its Git SHA; it is not independently attested. Reports use one UUID-named AtomicFile in the target app sandbox, reject reused IDs, and are read back before PASS is returned.

## Current provenance/queue integration

The counts above are the upstream foundation snapshot. Current `all`
adds `ProvenanceChecks` and `ConsumerChecks`; require the fresh report's dynamic
selected/passed counts. New allowlisted pure cases are `provenance` and
`queue_privacy`. `SettingsProvenanceUiTest` and `SettingsQueueStorageTest` are
separate device classes; the latter uses disposable directories and verifies real
queue persistence, interrupted recovery, retention and active-file preservation.
They do not replace actual service/native reader qualification.

Host settings tests also reference `app/` and `fixtures/` from test-only source and
resource sets. Fixed queue schema-1/schema-2 files include legacy planner values,
required hardware, all source facts, rational rate and opaque future effect data.
The tests verify migration to schema 3 and strict separation from app preferences.
`settingsTests=false` disables these additional references as well.

Current implemented consumers and Planned boundaries are listed in
[implementation status](../../docs/advanced-settings-implementation.md). No ADB
or install action is performed by a host check.
